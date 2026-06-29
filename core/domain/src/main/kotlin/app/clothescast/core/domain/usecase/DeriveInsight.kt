package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.EveningEventExtrasClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.TriggeredOutfit
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.repository.ForecastBundle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Pure-function builder for [DailyInsightResult] from a captured [ForecastSnapshot]
 * + the user's current [UserPreferences]. This is the part of the daily pipeline
 * that doesn't touch the network or the calendar provider — it slices the bundle
 * into period windows, evaluates clothes rules, composes the evening-event extras
 * from a sub-render of the night slice, and runs [RenderInsightSummary].
 *
 * Splitting this out of [GenerateDailyInsight] lets the cache layer store the
 * snapshot rather than the derived insight, so a settings change re-runs the
 * derivation against the latest prefs for free — no preservation / re-gating
 * logic, no waiting for the next worker run. [GenerateDailyInsight] composes the
 * fetch + events read on top of this same function.
 *
 * Day / night windows are derived entirely from the user's notification times
 * (`prefs.schedule.time` / `prefs.tonightSchedule.time`, defaulting to 07:00 and
 * 19:00). TODAY covers `[morning, tonight)`; TONIGHT covers `[tonight, next
 * morning)` wrapping past midnight. The morning insight's evening extras is
 * derived by running this same renderer against the night slice — i.e. the
 * extras's clothes + rain mention is whatever the 7pm night notification would
 * itself say — and only emits when the user has at least one non-all-day
 * calendar event with a location in the night window (an event "away from
 * home"). That away-from-home gate is the only behavioural asymmetry between
 * the two passes; everything else falls out of the period each pass is
 * computing.
 */
class DeriveInsight(
    private val evaluateClothesRules: EvaluateClothesRules = EvaluateClothesRules(),
    private val renderInsightSummary: RenderInsightSummary = RenderInsightSummary(),
) {
    operator fun invoke(
        snapshot: ForecastSnapshot,
        prefs: UserPreferences,
        // Diagnostic hook forwarded to [RenderInsightSummary] for the
        // delta-clause one-liner. Only the worker delivery path passes a
        // DiagLog adapter; cache re-derives (Today screen, widget, format
        // preview) leave it null so prefs-flip log churn stays nil.
        diagLog: (String) -> Unit = {},
    ): DailyInsightResult {
        val bundle = snapshot.bundle
        val morningStart = prefs.schedule.time
        val tonightStart = prefs.tonightSchedule.time
        val period = snapshot.period
        // The ongoing overnight slices its night off *yesterday* (yesterday
        // 19:00 → today 07:00) and dates itself to yesterday, while the calendar
        // anchor (currentDay / upcomingDays / week pages) stays on the real
        // today. Only ever set with TONIGHT.
        val overnight = snapshot.overnight

        // Calendar events feed the evening extras and [Insight.hasEvents] only
        // while the calendar extras is currently active. The fetch path already
        // drops events when it's off (GenerateDailyInsight gates the reader on
        // calendarEventMentionsActive), but a cache hit / replay re-derives from
        // snapshot.events, which can still hold events captured before the user
        // turned the extras off. Gate here too, so a stale event can't resurface
        // in the prose, hasEvents, the delivery gates, or the MQTT has_events
        // flag after the setting was disabled.
        val activeEvents = if (prefs.calendarEventMentionsActive) snapshot.events else emptyList()

        val periodView = buildPeriodView(
            bundle = bundle,
            prefs = prefs,
            period = period,
            morningStart = morningStart,
            tonightStart = tonightStart,
            events = activeEvents,
            overnight = overnight,
        )

        val eveningEventExtras = if (period == ForecastPeriod.TODAY && prefs.dailyMentionEveningEvents) {
            buildEveningEventExtras(
                bundle = bundle,
                prefs = prefs,
                morningStart = morningStart,
                tonightStart = tonightStart,
                allEvents = activeEvents,
                todayItems = periodView.triggeredOutfit.items,
            )
        } else {
            null
        }

        val rules = prefs.clothesRules
        val defaultBottom = prefs.defaultBottom
        val defaultTop = prefs.defaultTop

        // Prefer the day we actually delivered to the user yesterday over the
        // upstream past-days hindcast (see [DailyHistoryEntry] for why). Only
        // accepted when the recorded date is exactly the day before the
        // snapshot's today — a stale record from two days ago is more
        // misleading than no clause at all.
        val deltaYesterdayForRender = snapshot.historicYesterday
            ?.takeIf { it.date == bundle.today.date.minusDays(1) }
            ?.let { entry ->
                diagLog(
                    "delta-source: using historic yesterday (date=${entry.date}, " +
                        "feels-like min/max=${entry.feelsLikeMinC}/${entry.feelsLikeMaxC}) " +
                        "in place of bundle.yesterday min/max=" +
                        "${periodView.deltaYesterday.feelsLikeMinC}/" +
                        "${periodView.deltaYesterday.feelsLikeMaxC}",
                )
                periodView.deltaYesterday.copy(
                    feelsLikeMinC = entry.feelsLikeMinC,
                    feelsLikeMaxC = entry.feelsLikeMaxC,
                )
            }
            ?: periodView.deltaYesterday

        val summary = renderInsightSummary(
            today = periodView.forecast,
            yesterday = deltaYesterdayForRender,
            todayItems = periodView.triggeredOutfit.items,
            events = periodView.events,
            period = period,
            todayForDelta = periodView.deltaToday,
            eveningEventExtras = eveningEventExtras,
            deltaThresholdC = prefs.deltaThresholdC,
            deltaFormat = prefs.deltaFormat,
            clothesMentionMode = prefs.clothesMentionMode,
            yesterdayTriggeredItems = periodView.yesterdayTriggeredItems,
            todayRuleItems = Garment.layerReduce(periodView.triggeredOutfit.rules).map { it.item.itemKey },
            tonightStart = tonightStart,
            diagLog = diagLog,
            overnight = overnight,
        )

        val insight = Insight(
            summary = summary,
            recommendedItems = periodView.triggeredOutfit.items,
            generatedAt = snapshot.generatedAt,
            // The overnight is the night that began yesterday evening, so it
            // dates to yesterday even though the calendar anchor stays today.
            forDate = if (overnight) bundle.yesterday.date else bundle.today.date,
            location = snapshot.location,
            hourly = periodView.forecast.hourly,
            confidence = if (bundle.perModelHourly == null) {
                bundle.confidence
            } else {
                periodView.confidencePerModel?.let { ConfidenceInfo.computeFrom(it) }
            },
            perModelHourly = periodView.perModelForRender,
            // Derive the displayed icon from the *same* TriggeredOutfit that
            // drives the prose / recommendations, so the two can't drift apart.
            // nextForecast has no pre-evaluated outfit, so it still goes through
            // the forecast-driven path (same tier logic underneath).
            outfit = OutfitSuggestion.fromTriggeredOutfit(periodView.triggeredOutfit, defaultBottom, defaultTop),
            nextOutfit = periodView.nextForecast?.let {
                OutfitSuggestion.fromForecast(it, rules, defaultBottom, defaultTop)
            },
            outfitRationale = OutfitSuggestion.explainFromForecast(periodView.forecast, rules),
            nextOutfitRationale = periodView.nextForecast?.let {
                OutfitSuggestion.explainFromForecast(it, rules)
            },
            period = period,
            hasEvents = periodView.events.any { !it.allDay && !it.location.isNullOrBlank() },
            forecastZone = bundle.forecastZone,
            currentDay = bundle.today,
            upcomingDays = bundle.upcomingDays,
            weekPerModelHourly = bundle.perModelHourly,
        )
        return DailyInsightResult(insight = insight)
    }

    private fun buildPeriodView(
        bundle: ForecastBundle,
        prefs: UserPreferences,
        period: ForecastPeriod,
        morningStart: LocalTime,
        tonightStart: LocalTime,
        events: List<CalendarEvent>,
        overnight: Boolean,
    ): PeriodView {
        val todayForecast = bundle.today.slicedForToday(
            morningStart = morningStart,
            eveningEnd = tonightStart,
        )
        // The night base: the ongoing overnight began *yesterday* evening
        // (yesterday 19:00 → today 07:00), so it slices off yesterday with
        // today's hourly as the pre-dawn wrap. The coming night slices off today
        // with tomorrow's hourly. The calendar anchor (currentDay / week) is
        // unaffected either way — it always stays on `bundle.today`.
        val nightBase = if (overnight) bundle.yesterday else bundle.today
        val nightWrapHourly = if (overnight) bundle.today.hourly else bundle.tomorrowHourly
        val tonightForecast = nightBase.slicedForTonight(
            tonightStart = tonightStart,
            morningEnd = morningStart,
            tomorrowHourly = nightWrapHourly,
        )
        val periodForecast = when (period) {
            ForecastPeriod.TODAY -> todayForecast
            ForecastPeriod.TONIGHT -> tonightForecast
        }
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
        val rawNextForecast = when (period) {
            ForecastPeriod.TODAY -> tonightForecast.takeIf { it.hourly.isNotEmpty() }
            // The coming night leads into tomorrow's daytime; the ongoing
            // overnight leads into *today's* daytime (the day you're walking
            // into), so its "next" card is today, not tomorrow.
            ForecastPeriod.TONIGHT ->
                if (overnight) {
                    todayForecast.takeIf { it.hourly.isNotEmpty() }
                } else {
                    bundle.tomorrow?.slicedForToday(
                        morningStart = morningStart,
                        eveningEnd = tonightStart,
                    )
                }
        }
        // The next period's forecast drives the Today card / widget next-outfit
        // icon (via OutfitSuggestion.fromForecast). Its rain gear keys off the
        // blended-consensus probability already on the sliced forecast, so no
        // per-model enrichment is needed.
        val nextForecast = rawNextForecast
        val window = when (period) {
            ForecastPeriod.TODAY -> todayWindow(periodForecast.hourly, bundle.today.date)
            // The night window is dated off its base day (yesterday for the
            // ongoing overnight, today for the coming night).
            ForecastPeriod.TONIGHT -> tonightWindow(periodForecast.hourly, nightBase.date, tonightStart)
        }
        val perModelForRender = bundle.perModelHourly?.slicedTo(window)
        // The day-level confidence compares each model's min/max over the
        // window, so a model that only covers part of it skews the spread.
        // Google's forecast starts at the current hour, so a midday refresh
        // leaves its sliced series front-truncated (missing the morning) — drop
        // it from the confidence input unless it actually covers the window
        // start. It still draws on the chart ([perModelForRender]) and still
        // votes in the per-hour consensus blend.
        val confidencePerModel = perModelForRender?.confidenceInput(window)
        // The rule engine reads the blended-consensus chance of rain already on
        // the sliced forecast (precipitationProbabilityMaxPct), so the umbrella /
        // rain-jacket rules fire off the same number the prose and strip use — no
        // per-model enrichment needed.
        val triggeredOutfit = evaluateClothesRules(
            periodForecast,
            prefs.clothesRules,
            prefs.defaultTop,
            prefs.defaultBottom,
        )
        val yesterdayTriggeredItems = evaluateClothesRules(
            deltaYesterday,
            prefs.clothesRules,
            prefs.defaultTop,
            prefs.defaultBottom,
        ).items
        return PeriodView(
            forecast = periodForecast,
            nextForecast = nextForecast,
            triggeredOutfit = triggeredOutfit,
            events = filterEventsForPeriod(events, period, nightBase.date, morningStart, tonightStart),
            perModelForRender = perModelForRender,
            confidencePerModel = confidencePerModel,
            deltaToday = deltaToday,
            deltaYesterday = deltaYesterday,
            yesterdayTriggeredItems = yesterdayTriggeredItems,
        )
    }

    private fun buildEveningEventExtras(
        bundle: ForecastBundle,
        prefs: UserPreferences,
        morningStart: LocalTime,
        tonightStart: LocalTime,
        allEvents: List<CalendarEvent>,
        todayItems: List<String>,
    ): EveningEventExtrasClause? {
        val nightEvents = filterEventsForPeriod(
            events = allEvents,
            period = ForecastPeriod.TONIGHT,
            todayDate = bundle.today.date,
            morningStart = morningStart,
            tonightStart = tonightStart,
        )
        val awayFromHome = nightEvents.any { !it.allDay && !it.location.isNullOrBlank() }
        if (!awayFromHome) return null

        val nightView = buildPeriodView(
            bundle = bundle,
            prefs = prefs,
            period = ForecastPeriod.TONIGHT,
            morningStart = morningStart,
            tonightStart = tonightStart,
            events = allEvents,
            // The morning insight's evening extras is about *tonight* (the coming
            // night of today), never the ongoing overnight.
            overnight = false,
        )
        val nightSummary: InsightSummary = renderInsightSummary(
            today = nightView.forecast,
            yesterday = nightView.deltaYesterday,
            todayItems = nightView.triggeredOutfit.items,
            events = nightView.events,
            period = ForecastPeriod.TONIGHT,
            todayForDelta = nightView.deltaToday,
            eveningEventExtras = null,
            deltaThresholdC = prefs.deltaThresholdC,
            deltaFormat = prefs.deltaFormat,
            todayRuleItems = Garment.layerReduce(nightView.triggeredOutfit.rules).map { it.item.itemKey },
            tonightStart = tonightStart,
        )

        // Two emission paths, either of which fires the clause: extra clothing
        // the evening needs beyond the morning outfit (clothesDelta), or rain to
        // flag for the event (precip). Both are computed independently below.
        val clothesDelta = eveningClothesDelta(
            eveningItems = nightView.triggeredOutfit.items,
            dayItems = todayItems,
        )
        // Carried accessories (umbrella) ride with the evening rain warning, not
        // the clothes delta: an umbrella that fired both today and tonight would
        // be deduped out of the delta, silently dropping "bring an umbrella
        // tonight" — so source it straight from the evening outfit. The
        // formatter still gates it on a rain/drizzle precip clause.
        val eveningAccessories = nightView.triggeredOutfit.items.filter { Garment.isAccessoryKey(it) }
        val precip = nightSummary.precip
        if (clothesDelta.isEmpty() && precip == null) return null

        return EveningEventExtrasClause(
            items = clothesDelta + eveningAccessories,
            rainTime = precip?.time,
            likelihood = precip?.likelihood ?: PrecipLikelihood.LIKELY,
            precipCondition = precip?.condition,
            allDay = precip?.allDay ?: false,
        )
    }

    /**
     * The garments the evening outfit adds beyond what the morning already
     * announced. Both sides are the full, layer-reduced [TriggeredOutfit.items]
     * — threshold matches *and* the per-slot default (a default is just the rule
     * that fires when nothing else covers the slot, so it belongs in the outfit
     * on both sides of the comparison).
     *
     * Insulating tops are warmth-aware: an evening top is "extra" only when it's
     * warmer than the day's warmest top — wearing a jacket by day already implies
     * the lighter layers under it, so a lighter (or equally warm) evening top
     * isn't worth mentioning. Warmth is [Garment.warmth], which equals the
     * garment's layer count, so the heavyweight shells (`jacket`, `coat`,
     * `puffer` — all layer 3) tie: a day `jacket` keeps an evening `puffer`
     * quiet, deliberately — "swap one shell for another" isn't an actionable
     * evening tip. The [Garment.Layer.OUTER] rain shell is the exception:
     * it's keyed on rain, not warmth, so it's never gated by the warmth
     * comparison — a warm daytime top must not swallow the evening's required
     * rain jacket. It's additive instead, surfacing whenever the day's outfit
     * didn't already include it (like the substitute slots). Bottoms substitute
     * rather than stack, so an evening bottom is extra when it's a different
     * garment than the day's. Items we can't place in a slot (legacy free-form
     * rules, accessories like an umbrella) are left to the prose / rain paths
     * rather than the clothes delta.
     */
    private fun eveningClothesDelta(
        eveningItems: List<String>,
        dayItems: List<String>,
    ): List<String> {
        val dayGarments = dayItems.mapNotNull { Garment.fromKey(it) }
        // Warmest *insulating* top worn by day, by [Garment.warmth] (== layer
        // position now that puffer is an honest shell), so an evening top is
        // "extra" only when it adds a layer the day didn't have. The OUTER rain
        // shell is excluded — it's handled additively below, not by warmth.
        val dayTopWarmth = dayGarments
            .filter { it.slot == Garment.Slot.TOP && it.layer != Garment.Layer.OUTER }
            .maxOfOrNull { it.warmth } ?: 0
        // The OUTER shells (rain jacket) the day already included — an evening
        // rain jacket is "extra" only when the day didn't have one, regardless
        // of how warm the day's tops were.
        val dayOuterKeys = dayGarments
            .filter { it.slot == Garment.Slot.TOP && it.layer == Garment.Layer.OUTER }
            .mapTo(mutableSetOf()) { it.itemKey }
        // Non-top slots substitute, so an evening garment is "extra" when the
        // day didn't already include that exact garment — a different bottom,
        // or gloves the warmer day didn't need.
        val daySubstituteKeys = dayGarments
            .filter { it.slot != Garment.Slot.TOP }
            .groupBy({ it.slot }, { it.itemKey })
        return eveningItems.filter { item ->
            val g = Garment.fromKey(item) ?: return@filter false
            when {
                // Carried accessories (umbrella) are handled separately in
                // buildEveningEventExtras so they survive the day-dedup; keep
                // them out of the worn-garment delta to avoid a double mention.
                g.slot == Garment.Slot.CARRIED -> false
                // OUTER rain shell: additive and rain-keyed, never warmth-gated.
                g.slot == Garment.Slot.TOP && g.layer == Garment.Layer.OUTER ->
                    g.itemKey !in dayOuterKeys
                g.slot == Garment.Slot.TOP -> g.warmth > dayTopWarmth
                else -> g.itemKey !in daySubstituteKeys[g.slot].orEmpty()
            }
        }
    }

    private fun filterEventsForPeriod(
        events: List<CalendarEvent>,
        period: ForecastPeriod,
        todayDate: LocalDate,
        morningStart: LocalTime,
        tonightStart: LocalTime,
    ): List<CalendarEvent> {
        // Real interval overlap against the period window, now that events
        // carry dates. Keep any event that *overlaps* the window, not just
        // those that start inside it: an 18:30–21:30 dinner straddles a 19:00
        // tonight start, and excluding it made the 7pm notification silent
        // (hasEvents false) and hid the away-from-home extras even though the
        // user is out for most of the evening. The upper bound matters too —
        // the two-day fetch now surfaces tomorrow's events, and a
        // tomorrow-19:30 dinner must not count for tonight, while a
        // tomorrow-00:30 gig must.
        val windowStart: LocalDateTime
        val windowEnd: LocalDateTime
        when (period) {
            // Whole calendar day, preserving the previous "TODAY sees every
            // event read for the day" semantics.
            ForecastPeriod.TODAY -> {
                windowStart = todayDate.atStartOfDay()
                windowEnd = todayDate.plusDays(1).atStartOfDay()
            }
            ForecastPeriod.TONIGHT -> {
                windowStart = LocalDateTime.of(todayDate, tonightStart)
                windowEnd = LocalDateTime.of(todayDate.plusDays(1), morningStart)
            }
        }
        return events.filter { event ->
            if (event.allDay) {
                // All-day events are presence-only markers (overlaps() never
                // matches them, and hasEvents / the away-from-home gate both
                // exclude them): keep today's passing through both periods as
                // before, and drop tomorrow's, which the two-day fetch now
                // surfaces too.
                event.start.toLocalDate() == todayDate
            } else {
                // Half-open interval overlap: [start, end) against
                // [windowStart, windowEnd).
                event.start.isBefore(windowEnd) && event.end.isAfter(windowStart)
            }
        }
    }

    private data class PeriodView(
        val forecast: DailyForecast,
        val nextForecast: DailyForecast?,
        val triggeredOutfit: TriggeredOutfit,
        val events: List<CalendarEvent>,
        val perModelForRender: PerModelHourly?,
        val confidencePerModel: PerModelHourly?,
        val deltaToday: DailyForecast,
        val deltaYesterday: DailyForecast,
        val yesterdayTriggeredItems: List<String>,
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
internal fun ForecastBundle.shiftedToTomorrow(): ForecastBundle? {
    val tmrw = tomorrow ?: return null
    return copy(
        today = tmrw,
        yesterday = today,
        tomorrow = null,
        tomorrowHourly = emptyList(),
    )
}

/**
 * Restricts each model's series to the hours in [window], keeping whatever
 * subset of the window a model reported. A model with gaps stays in — every
 * consumer is sparse-safe: the charts position points by timestamp lookup
 * (not list position), and the confidence computation tolerates per-hour
 * gaps by design. The previous behavior evicted a model
 * missing even one window hour, which starved the confidence computation
 * of exactly the warming-up models its sparse handling exists for — and
 * when *every* model had a gap, collapsed the overlay to null, dropping the
 * confidence chip. Models with nothing in the window drop out entirely (ICON past
 * day 7); null when none remain.
 */
internal fun PerModelHourly.slicedTo(window: List<LocalDateTime>): PerModelHourly? {
    if (window.isEmpty()) return null
    val windowSet = window.toSet()
    val filtered = byModel
        .mapValues { (_, entries) -> entries.filter { it.time in windowSet } }
        .filterValues { it.isNotEmpty() }
    return if (filtered.isEmpty()) null else PerModelHourly(filtered)
}

/**
 * The subset of [this] to feed day-level confidence ([ConfidenceInfo.computeFrom]).
 *
 * Confidence compares each model's daily high/low over [window], so a model
 * that only covers part of the window biases the spread. Google's forecast
 * starts at the current hour, so on a midday refresh its sliced series is
 * front-truncated relative to the Open-Meteo models that span the whole day —
 * its partial min/max would inject a coverage-driven (not real) disagreement
 * and wrongly downgrade the chip. So drop Google from the confidence input
 * unless it actually covers the window's first hour. Google still draws on the
 * chart (the full [slicedTo] series) and still votes in the per-hour consensus
 * blend; only the day-extreme comparison excludes it when its coverage doesn't
 * reach the window start. Open-Meteo models (which carry the whole day via
 * `past_days`) are unaffected.
 *
 * Guard: Google is only dropped when at least two *other* consulted models
 * remain. For a Google + one-Open-Meteo selection (which the picker allows),
 * removing front-truncated Google would leave a single consulted model and
 * [ConfidenceInfo.computeFrom] — which excludes `best_match` and needs two —
 * would return null, hiding the chip entirely. Google's slightly partial
 * contribution is better than no chip, so in that case it's kept.
 */
internal fun PerModelHourly.confidenceInput(window: List<LocalDateTime>): PerModelHourly {
    val googleId = ForecastModel.GOOGLE_WEATHER.openMeteoId
    if (byModel[googleId] == null) return this
    val start = window.minOrNull() ?: return this
    if (byModel.getValue(googleId).any { it.time == start }) return this
    val otherConsulted = byModel.keys.count {
        it != googleId && it != PerModelHourly.BEST_MATCH_MODEL_ID
    }
    return if (otherConsulted >= 2) PerModelHourly(byModel - googleId) else this
}

internal fun todayWindow(windowHourly: List<HourlyForecast>, todayDate: LocalDate): List<LocalDateTime> =
    windowHourly.map { LocalDateTime.of(todayDate, it.time) }

internal fun tonightWindow(
    windowHourly: List<HourlyForecast>,
    todayDate: LocalDate,
    tonightStart: LocalTime,
): List<LocalDateTime> = windowHourly.map { tonightDateTime(todayDate, tonightStart, it.time) }

/**
 * Dates a wall-clock [time] inside the tonight window: times at/after
 * [tonightStart] belong to [todayDate], earlier times to the next day. The
 * single source of truth for the tonight wrap convention — [tonightWindow]
 * (per-model slicing) and [RenderInsightSummary] (dating the precip peak
 * against calendar events) both go through it, so they can't drift apart.
 */
internal fun tonightDateTime(
    todayDate: LocalDate,
    tonightStart: LocalTime,
    time: LocalTime,
): LocalDateTime = LocalDateTime.of(
    if (!time.isBefore(tonightStart)) todayDate else todayDate.plusDays(1),
    time,
)

internal fun DailyForecast.slicedForToday(
    morningStart: LocalTime,
    eveningEnd: LocalTime,
): DailyForecast {
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

internal fun DailyForecast.slicedForTonight(
    tonightStart: LocalTime,
    morningEnd: LocalTime,
    tomorrowHourly: List<HourlyForecast>,
): DailyForecast {
    val tonightHours = hourly.filter { it.time >= tonightStart }
    val tomorrowMorning = if (tonightStart.isBefore(morningEnd)) {
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
        condition = sliced.maxByOrNull { it.precipitationProbabilityPct }
            ?.condition
            ?.takeIf { it != WeatherCondition.UNKNOWN }
            ?: condition,
    )
}

/**
 * Wraps the derived daily insight. A thin holder kept distinct from [Insight]
 * so the derivation entry point has a stable return type as the pipeline grows.
 */
data class DailyInsightResult(
    val insight: Insight,
)
