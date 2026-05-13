package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.core.domain.repository.WeatherRepository
import java.time.Clock
import java.time.LocalTime

/**
 * The product. Fetches the forecast, evaluates clothes rules, renders the
 * deterministic summary string in [renderInsightSummary], and packages the result.
 *
 * Severe-weather alerts piggy-back on the same fetch and are returned alongside the
 * insight in [DailyInsightResult]; the worker uses them to drive a separate
 * high-priority notification while still feeding them into the daily summary.
 */
class GenerateDailyInsight(
    private val weatherRepository: WeatherRepository,
    private val evaluateClothesRules: EvaluateClothesRules = EvaluateClothesRules(),
    private val renderInsightSummary: RenderInsightSummary = RenderInsightSummary(),
    private val calendarEventReader: CalendarEventReader? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(
        location: Location,
        prefs: UserPreferences,
        period: ForecastPeriod = ForecastPeriod.TODAY,
    ): DailyInsightResult {
        val bundle = weatherRepository.fetchForecast(location)
        val activeAlerts = bundle.alerts.filter { it.expires.isAfter(clock.instant()) }
        // Each period is narrowed to the user-configured window so the insight
        // talks about hours the user will actually walk into:
        //  - TODAY = morning time → tonight time (e.g. 07:00–19:00)
        //  - TONIGHT = tonight time → next morning time (e.g. 19:00–07:00, wrapping
        //    past midnight via tomorrow's hourly)
        // Both reads pull from `prefs.schedule.time` and `prefs.tonightSchedule.time`
        // (not fixed constants) so a user who moved either dial sees an insight
        // that matches the alarm that just fired.
        val morningStart = prefs.schedule.time
        val tonightStart = prefs.tonightSchedule.time
        val todayForecast = bundle.today.slicedForToday(
            morningStart = morningStart,
            eveningEnd = tonightStart,
        )
        val tonightForecast = bundle.today.slicedForTonight(
            tonightStart = tonightStart,
            morningEnd = morningStart,
            tomorrowHourly = bundle.tomorrowHourly,
        )
        val periodForecast = when (period) {
            ForecastPeriod.TODAY -> todayForecast
            ForecastPeriod.TONIGHT -> tonightForecast
        }
        // Slice yesterday to the same daytime window as today so the delta clause
        // compares apples to apples — daytime feels-like high/low on both sides
        // rather than 24h aggregates that fold in overnight extremes the listener
        // doesn't care about. Falls back to 24h-vs-24h when either side lacks
        // daytime hourly entries (legacy cached payloads, sparse fixtures), to
        // keep the comparison symmetric in the absence of hourly data.
        val yesterdayDaytime = bundle.yesterday.slicedForToday(
            morningStart = morningStart,
            eveningEnd = tonightStart,
        )
        val (deltaToday, deltaYesterday) =
            if (todayForecast.hourly.isNotEmpty() && yesterdayDaytime.hourly.isNotEmpty()) {
                todayForecast to yesterdayDaytime
            } else {
                bundle.today to bundle.yesterday
            }
        // The home screen wants a side-by-side preview pair: "Today + Tonight"
        // on a morning insight, "Tonight + Tomorrow" on an evening one. We
        // compute the second outfit from the same forecast bundle so showing
        // both costs no extra API call. Null when the underlying data isn't
        // there (e.g. evening insight on a legacy bundle without tomorrow's
        // daily aggregates) — the screen falls back to a single card.
        val nextForecast = when (period) {
            ForecastPeriod.TODAY -> tonightForecast.takeIf { it.hourly.isNotEmpty() }
            ForecastPeriod.TONIGHT -> bundle.tomorrow
        }
        val rules = prefs.clothesRules
        val nextOutfit = nextForecast?.let { OutfitSuggestion.fromForecast(it, rules) }
        val nextOutfitRationale = nextForecast?.let {
            OutfitSuggestion.explainFromForecast(it, rules)
        }
        val todayTriggered = evaluateClothesRules(periodForecast, prefs.clothesRules)
        // Calendar events are gated on both the opt-in pref AND a configured reader.
        // Failures (missing permission, provider crash) degrade to no events so a
        // misbehaving reader can never fail the insight pipeline; the rest of the
        // summary still renders. Capture the property as a local so the smart-cast
        // survives across the runCatching lambda boundary.
        val reader = calendarEventReader
        val allEvents = if (prefs.useCalendarEvents && reader != null) {
            runCatching {
                reader.eventsForDay(bundle.today.date, prefs.schedule.zoneId)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val periodEvents = filterEventsForPeriod(allEvents, period, tonightStart)
        // Morning-only: when the user opted in to "Mention evening events", we
        // also evaluate clothes rules against the evening slice and pass any
        // evening events through so the renderer can attach a tie-in clause
        // ("Bring a jacket tonight." — or "Bring an umbrella tonight, rain at
        // 9pm." when the evening slice has rain). Event titles never appear
        // in the rendered prose; see the AGENTS.md privacy note. Cheap to
        // compute — both inputs already live in this scope.
        val eveningEvents = if (period == ForecastPeriod.TODAY && prefs.dailyMentionEveningEvents) {
            allEvents.filter { !it.allDay && !it.start.isBefore(tonightStart) }
        } else {
            emptyList()
        }
        val eveningTriggered = if (eveningEvents.isNotEmpty()) {
            evaluateClothesRules(tonightForecast, prefs.clothesRules)
        } else {
            emptyList()
        }
        // Per-model hourly only covers `forecast_days=1`, so only the TODAY
        // pass can pair the precip clause with cross-model agreement. The
        // tonight pass falls through to base-only behaviour in the renderer.
        // Sliced to the period window so the renderer's per-hour matches line
        // up with [periodForecast.hourly].
        val perModelForPeriod = if (period == ForecastPeriod.TODAY) {
            bundle.perModelHourly?.slicedTo(periodForecast.hourly)
        } else {
            null
        }
        val summary = renderInsightSummary(
            today = periodForecast,
            yesterday = deltaYesterday,
            todayTriggeredRules = todayTriggered,
            alerts = activeAlerts,
            events = periodEvents,
            period = period,
            eveningEvents = eveningEvents,
            eveningTriggeredRules = eveningTriggered,
            eveningForecast = if (period == ForecastPeriod.TODAY) tonightForecast else null,
            todayForDelta = deltaToday,
            perModelHourly = perModelForPeriod,
        )
        val insight = Insight(
            summary = summary,
            recommendedItems = todayTriggered.map { it.item },
            generatedAt = clock.instant(),
            forDate = bundle.today.date,
            hourly = periodForecast.hourly,
            // Cross-model confidence is derived from today's apparent-max and
            // peak precipitation probability (forecast_days=1 daily), so it
            // describes how much the major models disagree about *today*. Don't
            // attach it to a TONIGHT insight — the chip/callout copy literally
            // says "Forecasts disagree today," which is wrong-tense when the
            // user is reading the evening card.
            confidence = if (period == ForecastPeriod.TODAY) bundle.confidence else null,
            // Same reasoning for the per-model hourly overlay data: today's
            // hourly per-model series doesn't span the tonight window
            // (19:00 today → 07:00 tomorrow), and Open-Meteo's per-model
            // hourly only covers `forecast_days=1`. Strip on tonight, and
            // narrow today's series to the same daytime window we sliced
            // `periodForecast.hourly` to — otherwise the chart plots the
            // overlays by index and the midnight model value lands on the
            // first visible hour of the daytime window. Same series that
            // fed the precip-clause tier selection above.
            perModelHourly = perModelForPeriod,
            outfit = OutfitSuggestion.fromForecast(periodForecast, rules),
            nextOutfit = nextOutfit,
            outfitRationale = OutfitSuggestion.explainFromForecast(periodForecast, rules),
            nextOutfitRationale = nextOutfitRationale,
            period = period,
            hasEvents = periodEvents.isNotEmpty(),
        )
        return DailyInsightResult(insight = insight, alerts = activeAlerts)
    }

    private fun filterEventsForPeriod(
        events: List<CalendarEvent>,
        period: ForecastPeriod,
        tonightStart: LocalTime,
    ): List<CalendarEvent> = when (period) {
        ForecastPeriod.TODAY -> events
        // Tonight = anything starting at or after the tonight time, OR an all-day
        // event that bleeds across the evening (treated as relevant context).
        ForecastPeriod.TONIGHT -> events.filter { it.allDay || !it.start.isBefore(tonightStart) }
    }
}

/**
 * Slices [DailyForecast] to the daytime window (today at [morningStart] through
 * today at [eveningEnd]). The TODAY counterpart of [slicedForTonight]: keeps the
 * rendered insight, clothes evaluation and outfit suggestion focused on the
 * hours the user is awake and out, not on past pre-dawn hours or a late-evening
 * peak the user has already gone to bed for.
 *
 * Behaves like [slicedForTonight]:
 *  - filters today's hourly to entries in `[morningStart, eveningEnd)`,
 *  - recomputes daily-level aggregates ([feelsLikeMinC] etc.) from the slice so
 *    the band sentence and clothes rules talk about the daytime range, not the
 *    raw 24h day-level fields,
 *  - rewrites [DailyForecast.condition] to the wettest in-window hour (preventing
 *    the precip-peak fallback from naming a midday rain on a sunny afternoon),
 *  - falls back to the day-level aggregates with an empty hourly list when the
 *    slice would be empty for an otherwise valid window (sparse fixtures or
 *    legacy day-level-only payloads), so the pipeline always emits something
 *    even without in-window hourly data,
 *  - returns the original [DailyForecast] unchanged on a degenerate
 *    `morningStart >= eveningEnd` window — the tonight pass covers overnight
 *    users via its own wrap, so blanking out the hourly here would just lose
 *    information the tonight slice still needs.
 */
/**
 * Filters each model's per-hour entries to just the hours covered by
 * [windowHourly] (typically today's daytime slice), preserving the window's
 * order so each index in the resulting series lines up with the same index
 * in `windowHourly` — the chart plots overlays by index.
 *
 * Drops a model entirely from the overlay if it's missing any in-window hour
 * (the upstream `parseHourly` skips per-hour entries with null temp or precip
 * values for a model whose run hasn't fully landed). Carrying a partial series
 * would compact the surviving points left, misaligning the model line with the
 * blended line for every hour after the gap — better to omit the model than
 * draw a curve that's silently shifted. An empty result returns null so the
 * caller can null the whole field.
 */
private fun PerModelHourly.slicedTo(windowHourly: List<HourlyForecast>): PerModelHourly? {
    if (windowHourly.isEmpty()) return null
    val order = windowHourly.map { it.time }
    val filtered = byModel.mapNotNull { (model, entries) ->
        val byTime = entries.associateBy { it.time }
        val sliced = order.map { byTime[it] ?: return@mapNotNull null }
        model to sliced
    }.toMap()
    return if (filtered.isEmpty()) null else PerModelHourly(filtered)
}

private fun DailyForecast.slicedForToday(
    morningStart: LocalTime,
    eveningEnd: LocalTime,
): DailyForecast {
    // Degenerate window (user's day end is at or before its start). Bail out
    // rather than produce an empty or wrapped slice; the tonight pass covers
    // overnight users via its own wrap.
    if (!morningStart.isBefore(eveningEnd)) return this
    val sliced = hourly.filter { it.time >= morningStart && it.time < eveningEnd }
    if (sliced.isEmpty()) return copy(hourly = sliced)
    return copy(
        hourly = sliced,
        temperatureMinC = sliced.minOf { it.temperatureC },
        temperatureMaxC = sliced.maxOf { it.temperatureC },
        feelsLikeMinC = sliced.minOf { it.feelsLikeC },
        feelsLikeMaxC = sliced.maxOf { it.feelsLikeC },
        precipitationProbabilityMaxPct = sliced.maxOf { it.precipitationProbabilityPct },
        condition = sliced.maxByOrNull { it.precipitationProbabilityPct }
            ?.condition
            ?.takeIf { it != WeatherCondition.UNKNOWN }
            ?: condition,
    )
}

/**
 * Slices [DailyForecast] to the overnight tonight window (today at [tonightStart]
 * through tomorrow at [morningEnd]). Used by TONIGHT to keep the rendered insight
 * focused on what the user will walk into between bedtime and the morning.
 *
 * Concatenates today's hourly entries at or after [tonightStart] with tomorrow's
 * hourly entries strictly before [morningEnd]. Tomorrow entries keep their wall
 * time (e.g. 06:00) — [HourlyForecast] doesn't carry a date, but the precip-peak
 * sentence is fine with that since it talks about wall-clock times.
 *
 * Beyond just filtering, this also recomputes the daily-level aggregates
 * ([DailyForecast.feelsLikeMinC] etc.) from the combined hourly so that:
 *
 *  - [RenderInsightSummary]'s band sentence ("Tonight will be cold to mild.") talks
 *    about the actual overnight low — typically the pre-dawn hours from
 *    [tomorrowHourly], which the day-level fields don't capture.
 *  - The clothes rules evaluate against the overnight conditions (the user
 *    pulling a thicker sweater because it'll be 4°C at 05:00, not the day's 18°C
 *    midday high).
 *  - [OutfitSuggestion.fromForecast] picks a top + bottom appropriate for what
 *    the user will leave in tonight and arrive in tomorrow morning.
 *  - The precip-peak fallback (when the hourly peak < 30% and it falls back to
 *    the daily fields) doesn't surface "Rain at 12:00" on a tonight insight
 *    after the morning rain has already passed.
 *
 * If [tonightStart] IS strictly before [morningEnd] (e.g. the user set their
 * tonight time to 03:00, which is before the morning's 07:00) we treat tonight
 * as today-only, no wrap — anything else would either double-count hours or
 * invert the window.
 *
 * Falls back to the day-level aggregates with an empty hourly list when the
 * combined hourly is empty (older cached payloads, sparse fixtures, or a tonight
 * time after the last available hour) so we still emit *something* rather than a
 * forecast with all-zero aggregates.
 */
private fun DailyForecast.slicedForTonight(
    tonightStart: LocalTime,
    morningEnd: LocalTime,
    tomorrowHourly: List<HourlyForecast>,
): DailyForecast {
    val tonightHours = hourly.filter { it.time >= tonightStart }
    val tomorrowMorning = if (tonightStart.isBefore(morningEnd)) {
        // Same-day window (no real "evening"); skip the overnight wrap to avoid
        // double-counting hours with today.
        emptyList()
    } else {
        tomorrowHourly.filter { it.time < morningEnd }
    }
    val sliced = tonightHours + tomorrowMorning
    if (sliced.isEmpty()) return copy(hourly = sliced)
    return copy(
        hourly = sliced,
        temperatureMinC = sliced.minOf { it.temperatureC },
        temperatureMaxC = sliced.maxOf { it.temperatureC },
        feelsLikeMinC = sliced.minOf { it.feelsLikeC },
        feelsLikeMaxC = sliced.maxOf { it.feelsLikeC },
        precipitationProbabilityMaxPct = sliced.maxOf { it.precipitationProbabilityPct },
        // The day's overall condition (e.g. RAIN) may have applied to morning rain
        // that's now over. Use the condition from the wettest hour in the slice as
        // a better proxy for "what's it doing tonight"; fall back to the day-level
        // condition only if every overnight hour is UNKNOWN.
        condition = sliced.maxByOrNull { it.precipitationProbabilityPct }
            ?.condition
            ?.takeIf { it != WeatherCondition.UNKNOWN }
            ?: condition,
    )
}

/**
 * Bundles the daily insight with the active alerts that informed it. Alerts are kept
 * separate from [Insight] because they have a different lifecycle: the insight is
 * cached for the day and redelivered on demand, while alerts drive a one-shot
 * high-priority notification at fetch time.
 */
data class DailyInsightResult(
    val insight: Insight,
    val alerts: List<WeatherAlert>,
)
