package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.OutfitSuggestion
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
 * into period windows, evaluates clothes rules, composes the evening-event tie-in
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
 * morning)` wrapping past midnight. The morning insight's evening tie-in is
 * derived by running this same renderer against the night slice — i.e. the
 * tie-in's clothes + rain mention is whatever the 7pm night notification would
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

        val periodView = buildPeriodView(
            bundle = bundle,
            prefs = prefs,
            period = period,
            morningStart = morningStart,
            tonightStart = tonightStart,
            events = snapshot.events,
        )

        val eveningEventTieIn = if (period == ForecastPeriod.TODAY && prefs.dailyMentionEveningEvents) {
            buildEveningEventTieIn(
                bundle = bundle,
                prefs = prefs,
                morningStart = morningStart,
                tonightStart = tonightStart,
                allEvents = snapshot.events,
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
            perModelHourly = periodView.perModelForRender,
            eveningEventTieIn = eveningEventTieIn,
            deltaThresholdC = prefs.deltaThresholdC,
            deltaFormat = prefs.deltaFormat,
            clothesMentionMode = prefs.clothesMentionMode,
            yesterdayTriggeredItems = periodView.yesterdayTriggeredItems,
            todayRuleItems = Garment.layerReduce(periodView.triggeredOutfit.rules).map { it.item.itemKey },
            diagLog = diagLog,
        )

        val insight = Insight(
            summary = summary,
            recommendedItems = periodView.triggeredOutfit.items,
            generatedAt = snapshot.generatedAt,
            forDate = bundle.today.date,
            location = snapshot.location,
            hourly = periodView.forecast.hourly,
            confidence = if (bundle.perModelHourly == null) {
                bundle.confidence
            } else {
                periodView.perModelForRender?.let { ConfidenceInfo.computeFrom(it) }
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
        val nextForecast = when (period) {
            ForecastPeriod.TODAY -> tonightForecast.takeIf { it.hourly.isNotEmpty() }
            ForecastPeriod.TONIGHT -> bundle.tomorrow?.slicedForToday(
                morningStart = morningStart,
                eveningEnd = tonightStart,
            )
        }
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
        val perModelForRender = bundle.perModelHourly?.slicedTo(
            when (period) {
                ForecastPeriod.TODAY -> todayWindow(periodForecast.hourly, bundle.today.date)
                ForecastPeriod.TONIGHT -> tonightWindow(periodForecast.hourly, bundle.today.date, tonightStart)
            },
        )
        return PeriodView(
            forecast = periodForecast,
            nextForecast = nextForecast,
            triggeredOutfit = triggeredOutfit,
            events = filterEventsForPeriod(events, period, tonightStart),
            perModelForRender = perModelForRender,
            deltaToday = deltaToday,
            deltaYesterday = deltaYesterday,
            yesterdayTriggeredItems = yesterdayTriggeredItems,
        )
    }

    private fun buildEveningEventTieIn(
        bundle: ForecastBundle,
        prefs: UserPreferences,
        morningStart: LocalTime,
        tonightStart: LocalTime,
        allEvents: List<CalendarEvent>,
        todayItems: List<String>,
    ): EveningEventTieInClause? {
        val nightEvents = filterEventsForPeriod(allEvents, ForecastPeriod.TONIGHT, tonightStart)
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
            todayItems = nightView.triggeredOutfit.items,
            events = nightView.events,
            period = ForecastPeriod.TONIGHT,
            todayForDelta = nightView.deltaToday,
            perModelHourly = nightView.perModelForRender,
            eveningEventTieIn = null,
            deltaThresholdC = prefs.deltaThresholdC,
            deltaFormat = prefs.deltaFormat,
            todayRuleItems = Garment.layerReduce(nightView.triggeredOutfit.rules).map { it.item.itemKey },
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

        return EveningEventTieInClause(
            items = clothesDelta + eveningAccessories,
            rainTime = precip?.time,
            likelihood = precip?.likelihood ?: PrecipLikelihood.LIKELY,
            precipCondition = precip?.condition,
        )
    }

    /**
     * The garments the evening outfit adds beyond what the morning already
     * announced. Both sides are the full, layer-reduced [TriggeredOutfit.items]
     * — threshold matches *and* the per-slot default (a default is just the rule
     * that fires when nothing else covers the slot, so it belongs in the outfit
     * on both sides of the comparison).
     *
     * Tops are warmth-aware: an evening top is "extra" only when it's warmer than
     * the day's warmest top — wearing a jacket by day already implies the lighter
     * layers under it, so a lighter evening top isn't worth mentioning. Warmth is
     * [Garment.warmth], not the [Garment.Layer] band, so same-band upgrades still
     * surface: a day `jacket` doesn't suppress a warmer evening `puffer`. Bottoms
     * substitute rather than stack, so an evening bottom is extra when it's a
     * different garment than the day's. Items we can't place in a slot (legacy
     * free-form rules, accessories like an umbrella) are left to the prose / rain
     * paths rather than the clothes delta.
     */
    private fun eveningClothesDelta(
        eveningItems: List<String>,
        dayItems: List<String>,
    ): List<String> {
        val dayGarments = dayItems.mapNotNull { Garment.fromKey(it) }
        // Warmest top worn by day, by [Garment.warmth] (== layer position now
        // that puffer is an honest shell), so an evening top is "extra" only
        // when it adds a layer the day didn't have.
        val dayTopWarmth = dayGarments
            .filter { it.slot == Garment.Slot.TOP }
            .maxOfOrNull { it.warmth } ?: 0
        // Non-top slots substitute, so an evening garment is "extra" when the
        // day didn't already include that exact garment — a different bottom,
        // or gloves the warmer day didn't need.
        val daySubstituteKeys = dayGarments
            .filter { it.slot != Garment.Slot.TOP }
            .groupBy({ it.slot }, { it.itemKey })
        return eveningItems.filter { item ->
            val g = Garment.fromKey(item) ?: return@filter false
            when (g.slot) {
                Garment.Slot.TOP -> g.warmth > dayTopWarmth
                // Carried accessories (umbrella) are handled separately in
                // buildEveningEventTieIn so they survive the day-dedup; keep
                // them out of the worn-garment delta to avoid a double mention.
                Garment.Slot.CARRIED -> false
                else -> g.itemKey !in daySubstituteKeys[g.slot].orEmpty()
            }
        }
    }

    private fun filterEventsForPeriod(
        events: List<CalendarEvent>,
        period: ForecastPeriod,
        tonightStart: LocalTime,
    ): List<CalendarEvent> = when (period) {
        ForecastPeriod.TODAY -> events
        ForecastPeriod.TONIGHT -> events.filter { it.allDay || !it.start.isBefore(tonightStart) }
    }

    private data class PeriodView(
        val forecast: DailyForecast,
        val nextForecast: DailyForecast?,
        val triggeredOutfit: TriggeredOutfit,
        val events: List<CalendarEvent>,
        val perModelForRender: PerModelHourly?,
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

internal fun PerModelHourly.slicedTo(window: List<LocalDateTime>): PerModelHourly? {
    if (window.isEmpty()) return null
    val filtered = byModel.mapNotNull { (model, entries) ->
        val byTime = entries.associateBy { it.time }
        val sliced = window.map { byTime[it] ?: return@mapNotNull null }
        model to sliced
    }.toMap()
    return if (filtered.isEmpty()) null else PerModelHourly(filtered)
}

internal fun todayWindow(windowHourly: List<HourlyForecast>, todayDate: LocalDate): List<LocalDateTime> =
    windowHourly.map { LocalDateTime.of(todayDate, it.time) }

internal fun tonightWindow(
    windowHourly: List<HourlyForecast>,
    todayDate: LocalDate,
    tonightStart: LocalTime,
): List<LocalDateTime> = windowHourly.map { entry ->
    val date = if (!entry.time.isBefore(tonightStart)) todayDate else todayDate.plusDays(1)
    LocalDateTime.of(date, entry.time)
}

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
