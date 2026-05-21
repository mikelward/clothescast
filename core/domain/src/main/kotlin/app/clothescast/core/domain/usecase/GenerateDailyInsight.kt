package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.core.domain.repository.ForecastBundle
import app.clothescast.core.domain.repository.WeatherRepository
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * The product. Fetches the forecast, evaluates clothes rules, renders the
 * deterministic summary string in [renderInsightSummary], and packages the result.
 *
 * Day / night windows are derived entirely from the user's notification times
 * (`prefs.schedule.time` / `prefs.tonightSchedule.time`, defaulting to 07:00 and
 * 19:00). TODAY covers `[morning, tonight)`; TONIGHT covers `[tonight, next
 * morning)` wrapping past midnight. The morning insight's evening tie-in is
 * derived by running this same renderer against the night slice — i.e. the
 * tie-in's clothes + rain mention is whatever the 7pm night notification would
 * itself say — and only emits when the user has at least one non-all-day
 * calendar event with a location in the night window (an event "away from
 * home"). That away-from-home gate is the only behavioural asymmetry between
 * the two passes; everything else falls out of the period each pass is
 * computing.
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
        // Which day's window the caller wants generated. 0 == today (the
        // default; the user-facing alarm path), 1 == tomorrow. The evening
        // alarm uses `dayOffset = 1` with `period = TODAY` to pre-render
        // tomorrow's daytime insight for the Today screen's "next" card —
        // Open-Meteo's `forecast_days=2` response already carries tomorrow's
        // daily aggregates and hourly, so no extra fetch is needed. Only
        // `(TODAY, 1)` is supported — there's no day-after-tomorrow data in
        // the bundle to wrap a tomorrow-tonight window around.
        dayOffset: Int = 0,
    ): DailyInsightResult {
        require(dayOffset == 0 || (dayOffset == 1 && period == ForecastPeriod.TODAY)) {
            "Only same-day (dayOffset=0) and tomorrow-daytime (dayOffset=1, period=TODAY) generation are supported."
        }
        val fetched = weatherRepository.fetchForecast(location)
        val bundle = if (dayOffset == 1) {
            requireNotNull(fetched.shiftedToTomorrow()) {
                "Bundle has no tomorrow forecast; cannot generate next-day insight (was the fetch a forecast_days=1 response?)."
            }
        } else {
            fetched
        }
        val activeAlerts = bundle.alerts.filter { it.expires.isAfter(clock.instant()) }
        val morningStart = prefs.schedule.time
        val tonightStart = prefs.tonightSchedule.time

        val allEvents = readEventsForDay(bundle.today.date, prefs)

        val periodView = buildPeriodView(
            bundle = bundle,
            prefs = prefs,
            period = period,
            morningStart = morningStart,
            tonightStart = tonightStart,
            events = allEvents,
        )

        // Morning insight's tie-in: re-run the same machinery against the night
        // slice and fold its clothes + precip clauses into an
        // EveningEventTieInClause, but only when the user has an event away
        // from home that night (location-set, non-all-day). When TONIGHT is
        // the primary, no tie-in (it's already what the user is hearing).
        val eveningEventTieIn = if (period == ForecastPeriod.TODAY && prefs.dailyMentionEveningEvents) {
            buildEveningEventTieIn(
                bundle = bundle,
                prefs = prefs,
                morningStart = morningStart,
                tonightStart = tonightStart,
                allEvents = allEvents,
                alerts = activeAlerts,
                todayItems = periodView.triggeredRules.map { it.item },
            )
        } else {
            null
        }

        val summary = renderInsightSummary(
            today = periodView.forecast,
            yesterday = periodView.deltaYesterday,
            todayTriggeredRules = periodView.triggeredRules,
            alerts = activeAlerts,
            events = periodView.events,
            period = period,
            todayForDelta = periodView.deltaToday,
            perModelHourly = periodView.perModelForRender,
            eveningEventTieIn = eveningEventTieIn,
            deltaThresholdC = prefs.deltaThresholdC,
        )

        val rules = prefs.clothesRules
        val defaultBottom = prefs.defaultBottom
        val insight = Insight(
            summary = summary,
            recommendedItems = periodView.triggeredRules.map { it.item },
            generatedAt = clock.instant(),
            forDate = bundle.today.date,
            hourly = periodView.forecast.hourly,
            // Recompute over the rendered window so the chip's tier title +
            // precip-spread number describe the same hours the divergence
            // hint picks its peak from. `bundle.confidence` is the full-
            // calendar-day aggregate; falling back to it is only safe when
            // the *bundle itself* has no perModelHourly (truly legacy
            // payloads), because in that case the chip has no detail line
            // either and the window mismatch is invisible.
            //
            // When the bundle does carry perModelHourly but the rendered
            // window slice ends up null or sparse — `slicedTo` returns
            // null on an empty window or when every model is missing an
            // in-window hour; `ConfidenceInfo.computeFrom` returns null
            // on < 2 consulted models with non-empty hours — prefer null
            // over `bundle.confidence`. Surfacing the daily aggregate
            // there would silently reintroduce the window mismatch this
            // change is fixing. The chip treats null as "unknown" and
            // hides itself, which is the honest answer.
            confidence = if (bundle.perModelHourly == null) {
                bundle.confidence
            } else {
                periodView.perModelForRender?.let { ConfidenceInfo.computeFrom(it) }
            },
            perModelHourly = periodView.perModelForRender,
            outfit = OutfitSuggestion.fromForecast(periodView.forecast, rules, defaultBottom),
            nextOutfit = periodView.nextForecast?.let { OutfitSuggestion.fromForecast(it, rules, defaultBottom) },
            outfitRationale = OutfitSuggestion.explainFromForecast(periodView.forecast, rules),
            nextOutfitRationale = periodView.nextForecast?.let {
                OutfitSuggestion.explainFromForecast(it, rules)
            },
            period = period,
            // Mirrors the "away from home" gate used by `buildEveningEventTieIn`
            // below: a tonight notification fires loud / through the "only on
            // events" pref only when the user has somewhere to be, i.e. a
            // non-all-day event with a location. Holiday and birthday rows
            // (all-day, no location — and now preserved by the reader for
            // classification) naturally fall out.
            hasEvents = periodView.events.any { !it.allDay && !it.location.isNullOrBlank() },
        )
        return DailyInsightResult(insight = insight, alerts = activeAlerts)
    }

    private suspend fun readEventsForDay(date: LocalDate, prefs: UserPreferences): List<CalendarEvent> {
        // Failures (missing permission, provider crash) degrade to no events so
        // a misbehaving reader can never fail the insight pipeline. Capture the
        // property as a local so the smart-cast survives across the runCatching
        // lambda boundary.
        val reader = calendarEventReader
        if (!prefs.calendarEventMentionsActive || reader == null) return emptyList()
        return runCatching {
            reader.eventsForDay(date, prefs.schedule.zoneId)
        }.getOrDefault(emptyList())
    }

    private fun buildPeriodView(
        bundle: ForecastBundle,
        prefs: UserPreferences,
        period: ForecastPeriod,
        morningStart: LocalTime,
        tonightStart: LocalTime,
        events: List<CalendarEvent>,
    ): PeriodView {
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
        // Yesterday paired against today's daytime window for the delta
        // comparison — apples-to-apples daytime feels-like, falling back to 24h
        // if either side has no hourly data. TONIGHT skips the delta clause
        // entirely so this pair only matters for TODAY, but compute it
        // unconditionally for symmetry.
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
        // on a morning insight, "Tonight + Tomorrow" on an evening one. The
        // second outfit comes from the same bundle so showing both costs no
        // extra API call. Null when the underlying data isn't there (e.g.
        // evening insight on a legacy bundle without tomorrow's daily
        // aggregates) — the screen falls back to a single card.
        //
        // Slice tomorrow to the user's daytime window so the "Why this
        // outfit?" rationale (and the outfit picker) cite an in-window
        // hour — otherwise a pre-dawn 06:00 trough drives "Feels-like low
        // 5.8°C at 06:00" on the Tomorrow card for a user who won't be
        // outside until 07:00.
        val nextForecast = when (period) {
            ForecastPeriod.TODAY -> tonightForecast.takeIf { it.hourly.isNotEmpty() }
            ForecastPeriod.TONIGHT -> bundle.tomorrow?.slicedForToday(
                morningStart = morningStart,
                eveningEnd = tonightStart,
            )
        }
        val triggeredRules = evaluateClothesRules(periodForecast, prefs.clothesRules)
        val perModelForRender = bundle.perModelHourly?.slicedTo(
            when (period) {
                ForecastPeriod.TODAY -> todayWindow(periodForecast.hourly, bundle.today.date)
                ForecastPeriod.TONIGHT -> tonightWindow(periodForecast.hourly, bundle.today.date, tonightStart)
            },
        )
        return PeriodView(
            forecast = periodForecast,
            nextForecast = nextForecast,
            triggeredRules = triggeredRules,
            events = filterEventsForPeriod(events, period, tonightStart),
            perModelForRender = perModelForRender,
            deltaToday = deltaToday,
            deltaYesterday = deltaYesterday,
        )
    }

    /**
     * Composes the morning's evening-event tie-in by running the renderer
     * against the night slice and folding off its clothes and precip clauses.
     * Gated on the user having at least one non-all-day calendar event with a
     * location set in the night window — the "away from home" rule that
     * motivates the heads-up in the first place.
     *
     * The clothing item carried in the clause is the *delta* between night
     * and day — items the night needs that the day didn't already announce
     * — so a tie-in only adds vocabulary the morning insight hasn't already
     * delivered. If the day already says "Bring a jacket", an identical
     * "Bring a jacket tonight" repeats; if the night additionally needs a
     * coat, the tie-in carries "coat". When the night and day rules trigger
     * the same items but the night insight has a precip clause, the tie-in
     * falls through to a bare rain mention (item=null, rainTime set), since
     * the rain itself is new information the morning slice didn't surface.
     *
     * Returns null when there's no away-from-home event, when there's
     * neither a delta nor rain to surface, or both.
     */
    private fun buildEveningEventTieIn(
        bundle: ForecastBundle,
        prefs: UserPreferences,
        morningStart: LocalTime,
        tonightStart: LocalTime,
        allEvents: List<CalendarEvent>,
        alerts: List<WeatherAlert>,
        todayItems: List<String>,
    ): EveningEventTieInClause? {
        val nightEvents = filterEventsForPeriod(allEvents, ForecastPeriod.TONIGHT, tonightStart)
        // "Away from home" = any non-all-day event with a non-blank location.
        // A user-configured home pin now exists (UserPreferences.homeLocation,
        // used by FetchAndNotifyWorker for the at-home TTS gate) but we
        // deliberately don't fold it in here: the home displayName is a
        // specific reverse-geocoded label that almost never byte-matches a
        // calendar event's free-form location string, and lat/lon-based event
        // matching isn't reachable — CalendarContract.Instances.EVENT_LOCATION
        // is text only, off-device geocoding of event data is blocked by
        // PRIVACY.md, and on-device Android Geocoder is unreliable. If we ever
        // gain structured event lat/lon (e.g. via Google Calendar extended
        // properties), revisit then; for now the blank-location assumption
        // does the heavy lifting and is correct for the common case.
        val awayFromHome = nightEvents.any { !it.allDay && !it.location.isNullOrBlank() }
        if (!awayFromHome) return null

        val nightView = buildPeriodView(
            bundle = bundle,
            prefs = prefs,
            period = ForecastPeriod.TONIGHT,
            morningStart = morningStart,
            tonightStart = tonightStart,
            events = allEvents,
        )
        val nightSummary: InsightSummary = renderInsightSummary(
            today = nightView.forecast,
            yesterday = nightView.deltaYesterday,
            todayTriggeredRules = nightView.triggeredRules,
            alerts = alerts,
            events = nightView.events,
            period = ForecastPeriod.TONIGHT,
            todayForDelta = nightView.deltaToday,
            perModelHourly = nightView.perModelForRender,
            eveningEventTieIn = null,
            deltaThresholdC = prefs.deltaThresholdC,
        )
        val nightItems = nightSummary.clothes?.items.orEmpty()
        val precip = nightSummary.precip
        // Case- and whitespace-insensitive set comparison, matching the
        // formatter's umbrella check and avoiding "Jacket" / "jacket"
        // mismatches from legacy free-form ClothesRule.item values.
        val normalize: (String) -> String = { it.trim().lowercase(Locale.ROOT) }
        val todayKey = todayItems.map(normalize).toSet()
        val deltaItems = nightItems.filter { normalize(it) !in todayKey }

        if (deltaItems.isEmpty() && precip == null) return null

        return EveningEventTieInClause(
            items = deltaItems,
            rainTime = precip?.time,
            likelihood = precip?.likelihood ?: PrecipLikelihood.LIKELY,
        )
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

    private data class PeriodView(
        val forecast: DailyForecast,
        val nextForecast: DailyForecast?,
        val triggeredRules: List<ClothesRule>,
        val events: List<CalendarEvent>,
        val perModelForRender: PerModelHourly?,
        val deltaToday: DailyForecast,
        val deltaYesterday: DailyForecast,
    )
}

/**
 * Returns a view of this bundle with tomorrow promoted to today. Used by the
 * evening worker's "next" pre-render path: the same fetch covers both the
 * tonight window the alarm is delivering *and* the tomorrow-daytime window
 * the Today screen's pager will show on page 2. Returns null when the bundle
 * has no tomorrow data (legacy `forecast_days=1` fixtures); the caller drops
 * the next-card pre-render rather than fabricate data.
 *
 * Yesterday becomes today's daily forecast so the delta clause still has an
 * apples-to-apples comparison (tomorrow vs. today). The `tomorrow` and
 * `tomorrowHourly` fields drop to empty — we don't have day-after-tomorrow
 * data, so a hypothetical tonight-period generation off the shifted bundle
 * would lose its overnight wrap; that's why the public `dayOffset=1` path is
 * gated to TODAY-period only.
 */
private fun ForecastBundle.shiftedToTomorrow(): ForecastBundle? {
    val tmrw = tomorrow ?: return null
    return copy(
        today = tmrw,
        yesterday = today,
        tomorrow = null,
        tomorrowHourly = emptyList(),
    )
}

/**
 * Filters each model's per-hour entries to just the hours covered by [window]
 * (LocalDateTimes the caller computed from the period's hourly slice), preserving
 * the window's order so each index in the resulting series lines up with the same
 * index in the matching base hourly — the chart plots overlays by index.
 *
 * Drops a model entirely from the overlay if it's missing any in-window hour
 * (the upstream `parseHourly` skips per-hour entries with null temp or precip
 * values for a model whose run hasn't fully landed). Carrying a partial series
 * would compact the surviving points left, misaligning the model line with the
 * blended line for every hour after the gap. An empty result returns null so
 * the caller can null the whole field.
 */
private fun PerModelHourly.slicedTo(window: List<LocalDateTime>): PerModelHourly? {
    if (window.isEmpty()) return null
    val filtered = byModel.mapNotNull { (model, entries) ->
        val byTime = entries.associateBy { it.time }
        val sliced = window.map { byTime[it] ?: return@mapNotNull null }
        model to sliced
    }.toMap()
    return if (filtered.isEmpty()) null else PerModelHourly(filtered)
}

/**
 * Each daytime hour belongs to [todayDate] — [slicedForToday] never wraps
 * past midnight, so every entry's [HourlyForecast.time] is unambiguously
 * today's calendar day.
 */
private fun todayWindow(windowHourly: List<HourlyForecast>, todayDate: LocalDate): List<LocalDateTime> =
    windowHourly.map { LocalDateTime.of(todayDate, it.time) }

/**
 * Tonight wraps from [tonightStart] today to next morning, so a window hour
 * with `time >= tonightStart` is on [todayDate]; anything before it (the
 * tomorrow-morning portion concatenated by [slicedForTonight]) is on the
 * next day.
 */
private fun tonightWindow(
    windowHourly: List<HourlyForecast>,
    todayDate: LocalDate,
    tonightStart: LocalTime,
): List<LocalDateTime> = windowHourly.map { entry ->
    val date = if (!entry.time.isBefore(tonightStart)) todayDate else todayDate.plusDays(1)
    LocalDateTime.of(date, entry.time)
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
