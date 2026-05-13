package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.AlertClause
import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.CalendarTieInClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.model.WeatherCondition
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds the structured [InsightSummary] for a daily generation pass: up to seven
 * independent clauses, each driven by an independent rule. The presentation layer
 * (an Android-side formatter) turns this into the final prose, so anything
 * region- or locale-specific (clothes vocab, sentence templates, time formatting)
 * is resolved there rather than here.
 *
 * Rules (each yields 0 or 1 clause):
 * 1. [AlertClause] — highest-severity SEVERE/EXTREME alert. Extreme outranks Severe;
 *    ties take the first listed.
 * 2. [BandClause] — classify feels-like low and high into bands. Always emitted.
 * 3. [DeltaClause] — yesterday vs today; only emitted when the larger absolute
 *    feels-like delta is ≥ 3°C, and only for [ForecastPeriod.TODAY] (the morning
 *    pass already mentioned this comparison; the tonight pass shouldn't repeat it).
 * 4. [ClothesClause] — items triggered by the user's rule list, in rule order.
 * 5. [PrecipClause] — fires in two tiers driven by cross-model agreement:
 *    [PrecipLikelihood.LIKELY] when a majority of consulted models hit ≥ 50%
 *    at the same hour ("Rain at 3pm."), [PrecipLikelihood.POSSIBLE] when at
 *    least one model hits ≥ 30% but the majority bar isn't cleared ("Chance
 *    of rain at 3pm."). Falls back to the base hourly series — and ultimately
 *    a noon synthesis from the day-level field — when per-model data isn't
 *    available; both fallbacks render as LIKELY (the existing behaviour).
 * 6. [CalendarTieInClause] — when clothes + precip both fired AND a calendar
 *    event overlaps the precip peak hour. Picks "umbrella" when on the clothes
 *    list, otherwise the first triggered item, mirroring rule 4's ordering.
 *    **Only emitted on [ForecastPeriod.TONIGHT].** On TODAY the bare precip
 *    clause ("Rain at 3pm.") is enough — the listener already knows about
 *    their morning event, so chaining a tie-in just repeats what they heard.
 * 7. [EveningEventTieInClause] — the morning's heads-up about a cold/rainy
 *    evening event, paired with a clothes item drawn from the *evening*
 *    forecast slice. Only emits on [ForecastPeriod.TODAY], gated on the
 *    caller passing non-empty [eveningEvents] + [eveningTriggeredRules]
 *    (which the use case in turn gates on the user opting in via
 *    "Mention evening events").
 *
 * All temperature comparisons use feels-like values, matching the clothes rules.
 */
class RenderInsightSummary {
    operator fun invoke(
        today: DailyForecast,
        yesterday: DailyForecast,
        todayTriggeredRules: List<ClothesRule>,
        alerts: List<WeatherAlert> = emptyList(),
        events: List<CalendarEvent> = emptyList(),
        period: ForecastPeriod = ForecastPeriod.TODAY,
        eveningEvents: List<CalendarEvent> = emptyList(),
        eveningTriggeredRules: List<ClothesRule> = emptyList(),
        eveningForecast: DailyForecast? = null,
        // The today side of the delta comparison, paired with [yesterday]. The
        // caller picks the pair so today and yesterday cover the same time range
        // — typically daytime-vs-daytime when both have hourly entries in the
        // window, falling back to 24h-vs-24h when either side lacks them.
        // Defaults to [today], which is correct when the caller hasn't sliced
        // the forecast (e.g. in unit tests that pass raw 24h fields on both sides).
        todayForDelta: DailyForecast = today,
        // Per-model hourly series for the same period [today] covers. When
        // present, the precip clause uses cross-model agreement to pick its
        // tier (see [PrecipLikelihood]); when null we fall back to the base
        // hourly + day-level field. Caller is responsible for slicing the
        // per-model series to the same window as [today.hourly] so peak hours
        // line up.
        perModelHourly: PerModelHourly? = null,
        // Evening per-model hourly, paired with [eveningForecast]. Open-Meteo's
        // per-model hourly currently only covers `forecast_days=1`, so the
        // tonight pass (which wraps past midnight) usually can't supply this —
        // null falls back to the base series exactly like the today path.
        eveningPerModelHourly: PerModelHourly? = null,
    ): InsightSummary {
        val items = todayTriggeredRules.map { it.item }
        val peak = peakPrecip(today, perModelHourly)
        // Compute the evening peak only when the evening tie-in is going to
        // emit (i.e. caller passed events + triggered rules + an evening
        // forecast). Avoids spending the search on every TODAY pass, and
        // keeps the result strictly scoped to the tie-in clause.
        val eveningPeak = if (
            period == ForecastPeriod.TODAY &&
            eveningEvents.isNotEmpty() &&
            eveningTriggeredRules.isNotEmpty()
        ) {
            eveningForecast?.let { peakPrecip(it, eveningPerModelHourly) }
        } else {
            null
        }
        return InsightSummary(
            period = period,
            alert = alertClause(alerts),
            band = bandClause(today),
            delta = if (period == ForecastPeriod.TODAY) deltaClause(todayForDelta, yesterday) else null,
            clothes = clothesClause(items),
            precip = peak?.let { PrecipClause(it.condition, it.time, it.likelihood) },
            // Calendar tie-in only fires on TONIGHT — pairing the precip peak
            // with an event the listener hasn't started yet ("Bring an umbrella
            // for your 8pm dinner") is the case where it adds value. On TODAY
            // the listener already knows about the event their morning is
            // built around, so the bare precip clause ("Rain at 3pm.") is
            // enough; chaining "Bring an umbrella for your 3pm standup." after
            // it just repeats what the user already heard.
            calendarTieIn = if (period == ForecastPeriod.TONIGHT) calendarTieInClause(items, peak, events) else null,
            eveningEventTieIn = eveningEventTieInClause(period, eveningEvents, eveningTriggeredRules, eveningPeak, items),
        )
    }

    private fun alertClause(alerts: List<WeatherAlert>): AlertClause? {
        val top = alerts.firstOrNull { it.severity == AlertSeverity.EXTREME }
            ?: alerts.firstOrNull { it.severity == AlertSeverity.SEVERE }
            ?: return null
        return AlertClause(top.event)
    }

    private fun bandClause(today: DailyForecast): BandClause = BandClause(
        low = TemperatureBand.forCelsius(today.feelsLikeMinC),
        high = TemperatureBand.forCelsius(today.feelsLikeMaxC),
    )

    private fun deltaClause(today: DailyForecast, yesterday: DailyForecast): DeltaClause? {
        val highDelta = today.feelsLikeMaxC - yesterday.feelsLikeMaxC
        val lowDelta = today.feelsLikeMinC - yesterday.feelsLikeMinC
        val biggest = if (abs(highDelta) >= abs(lowDelta)) highDelta else lowDelta
        // Apply the threshold against the *unrounded* delta. Otherwise 2.6°C rounds
        // to 3 and would emit a clause even though the actual delta is under the
        // 3° rule.
        if (abs(biggest) < 3.0) return null
        val rounded = biggest.roundToInt()
        val direction = if (rounded > 0) DeltaClause.Direction.WARMER else DeltaClause.Direction.COOLER
        return DeltaClause(degrees = abs(rounded), direction = direction)
    }

    private fun clothesClause(items: List<String>): ClothesClause? =
        if (items.isEmpty()) null else ClothesClause(items)

    /**
     * Resolves the precipitation peak hour the way the precip rule needs it. Lifted
     * out of the precip clause assembly so the calendar-tie-in rule can pair an
     * event window against the same time without re-running the logic and getting
     * out of sync.
     *
     * Tier selection when [perModelHourly] is supplied:
     *  - [PrecipLikelihood.LIKELY] when a *majority* of consulted models hit
     *    ≥ [LIKELY_THRESHOLD]% probability at the same hour ("majority" = more
     *    than half: 2 of 3, both of 2, 1 of 1). Wettest such hour wins.
     *  - [PrecipLikelihood.POSSIBLE] when at least one model hits ≥
     *    [POSSIBLE_THRESHOLD]% at some hour but the LIKELY bar isn't cleared.
     *    The hour carrying the single biggest per-model reading wins.
     *
     * Falls back to the base hourly series (and finally a noon synthesis from
     * the day-level field) when per-model data isn't available. Both fallback
     * paths emit LIKELY so legacy behaviour and cached payloads keep their
     * original wording. The 30% bar matches the historical fallback threshold.
     *
     * Condition resolution: prefers the base hour's weather code at the peak
     * time when it's a precipitating type; falls back to the day-level
     * condition (after `slicedFor…`, this is already the wettest in-window
     * hour's code) when the base hour is missing, UNKNOWN, or non-precip; and
     * finally defaults to [WeatherCondition.RAIN] when the per-model tier
     * triggered but the base forecast carries no precipitating code at all —
     * which is the exact "base under-called both probability *and* type"
     * scenario the per-model tier exists to catch. The base-only fallback
     * (no per-model data) keeps the original strict "suppress when no
     * precipitating code" behaviour, because there's no extra signal to
     * justify overriding the base condition.
     */
    private fun peakPrecip(today: DailyForecast, perModelHourly: PerModelHourly?): PeakPrecip? {
        val perModelHit = perModelHourly?.let { pickPerModelPeak(today, it) }
        if (perModelHit != null) return perModelHit

        val peak = today.hourly.maxByOrNull { it.precipitationProbabilityPct }
        val time: LocalTime
        val condition: WeatherCondition
        if (peak == null || peak.precipitationProbabilityPct < POSSIBLE_THRESHOLD) {
            if (today.precipitationProbabilityMaxPct < POSSIBLE_THRESHOLD) return null
            time = LocalTime.NOON
            condition = today.condition
        } else {
            time = peak.time
            condition = if (peak.condition == WeatherCondition.UNKNOWN) today.condition else peak.condition
        }
        if (!condition.isPrecipitation()) return null
        return PeakPrecip(time, condition, PrecipLikelihood.LIKELY)
    }

    private fun pickPerModelPeak(today: DailyForecast, perModelHourly: PerModelHourly): PeakPrecip? {
        val models = perModelHourly.byModel.values.toList()
        if (models.isEmpty()) return null
        // Majority = more than half: 2 of 3, both of 2, 1 of 1. Matches how the
        // user described it ("any model says ≥ 30 → chance of rain; majority of
        // models say ≥ 50 → rain"); generalises naturally to whatever subset of
        // models reported usable hourly values today.
        val majorityNeeded = models.size / 2 + 1
        // Walk every hour any model reported, scoring by "models hitting LIKELY
        // at this hour" first and the single biggest per-model reading second
        // (tie-break, also doubles as the POSSIBLE fallback pivot).
        val hours = models.flatMap { entries -> entries.map { it.time } }.toSortedSet()
        val likelyHour = hours
            .mapNotNull { hour ->
                val readings = models.mapNotNull { entries -> entries.firstOrNull { it.time == hour } }
                val likelyCount = readings.count { it.precipitationProbabilityPct >= LIKELY_THRESHOLD }
                if (likelyCount >= majorityNeeded) hour to readings else null
            }
            .maxByOrNull { (_, readings) -> readings.maxOf { it.precipitationProbabilityPct } }
            ?.first
        if (likelyHour != null) {
            return PeakPrecip(likelyHour, perModelConditionAt(likelyHour, today), PrecipLikelihood.LIKELY)
        }

        val possibleHour = models.asSequence()
            .flatten()
            .filter { it.precipitationProbabilityPct >= POSSIBLE_THRESHOLD }
            .maxByOrNull { it.precipitationProbabilityPct }
            ?.time
        if (possibleHour != null) {
            return PeakPrecip(possibleHour, perModelConditionAt(possibleHour, today), PrecipLikelihood.POSSIBLE)
        }
        return null
    }

    private fun perModelConditionAt(time: LocalTime, today: DailyForecast): WeatherCondition {
        val baseHourCondition = today.hourly.firstOrNull { it.time == time }
            ?.condition
            ?.takeIf { it != WeatherCondition.UNKNOWN }
        if (baseHourCondition != null && baseHourCondition.isPrecipitation()) return baseHourCondition
        if (today.condition.isPrecipitation()) return today.condition
        // The per-model tier triggered on probability alone but no part of the
        // base forecast carries a precipitating code. Default to RAIN — the
        // model is the source of truth here and "Chance of rain" / "Rain" reads
        // honestly even when the base under-called the precipitation type.
        return WeatherCondition.RAIN
    }

    private fun WeatherCondition.isPrecipitation(): Boolean = when (this) {
        WeatherCondition.DRIZZLE,
        WeatherCondition.RAIN,
        WeatherCondition.SNOW,
        WeatherCondition.THUNDERSTORM -> true
        WeatherCondition.CLEAR,
        WeatherCondition.PARTLY_CLOUDY,
        WeatherCondition.CLOUDY,
        WeatherCondition.FOG,
        WeatherCondition.UNKNOWN -> false
    }

    private fun calendarTieInClause(
        items: List<String>,
        peak: PeakPrecip?,
        events: List<CalendarEvent>,
    ): CalendarTieInClause? {
        if (items.isEmpty() || peak == null || events.isEmpty()) return null
        // Need an overlapping event to motivate the clause, but we don't capture
        // the event's title or time — neither is in the rendered prose, and we
        // never want a calendar event title flowing through to off-device TTS
        // (the prose is fed to Gemini over the BYOK key).
        events.firstOrNull { it.overlaps(peak.time) } ?: return null
        // Prefer "umbrella" when the user has it on their list — that's the clothes
        // item the precip-peak overlap was actually motivated by. Otherwise just
        // take the first triggered item, mirroring rule 4's ordering.
        val item = items.firstOrNull { it.equals("umbrella", ignoreCase = true) } ?: items.first()
        return CalendarTieInClause(item = item)
    }

    /**
     * Evening-event tie-in for the morning insight. Only emits on [ForecastPeriod.TODAY]
     * — the tonight pass already covers evening events via [calendarTieInClause].
     * Pairs the evening forecast slice with the first triggered clothes item (umbrella
     * first if present) and the evening precip-peak time, gated on the existence of at
     * least one located evening event. Caller is responsible for filtering [eveningEvents]
     * to actually-evening events and for evaluating clothes rules against the evening
     * forecast. Calendar event titles, locations, and start times are never captured
     * into the returned clause.
     *
     * Suppressed when:
     *  - No evening event has a location (location-less events don't imply outdoor
     *    exposure where the weather matters).
     *  - The evening clothes items are a subset of (or equal to) [todayItems] — the
     *    morning insight already told the user every item; repeating a subset of them
     *    for the evening adds no new information.
     */
    private fun eveningEventTieInClause(
        period: ForecastPeriod,
        eveningEvents: List<CalendarEvent>,
        eveningTriggeredRules: List<ClothesRule>,
        eveningPeak: PeakPrecip?,
        todayItems: List<String>,
    ): EveningEventTieInClause? {
        if (period != ForecastPeriod.TODAY) return null
        if (eveningEvents.isEmpty() || eveningTriggeredRules.isEmpty()) return null
        // Gate on at least one non-all-day evening event that has a location.
        // Events without a location don't imply outdoor exposure, so the
        // weather-specific clothing tip isn't warranted. Calendar event titles
        // never flow to off-device TTS.
        eveningEvents.firstOrNull { !it.allDay && !it.location.isNullOrBlank() } ?: return null
        val items = eveningTriggeredRules.map { it.item }
        // If the evening clothes are a subset of (or equal to) today's clothes,
        // the morning insight already covered every item — no new information to add.
        // Compare normalized (trim + lowercase) so legacy free-form ClothesRule.item
        // values don't fail to suppress on a casing/whitespace mismatch ("Jacket" vs
        // "jacket"); matches the case-insensitive umbrella check below.
        val normalize: (String) -> String = { it.trim().lowercase(Locale.ROOT) }
        if (todayItems.map(normalize).toSet().containsAll(items.map(normalize).toSet())) return null
        val item = items.firstOrNull { it.equals("umbrella", ignoreCase = true) } ?: items.first()
        return EveningEventTieInClause(item = item, rainTime = eveningPeak?.time)
    }

    private data class PeakPrecip(
        val time: LocalTime,
        val condition: WeatherCondition,
        val likelihood: PrecipLikelihood,
    )

    companion object {
        // Per-model agreement thresholds. The user's mental model is "1 model
        // says rain → hedge it as a chance; majority of models say a lot of
        // rain → just say rain". 30% is the historical base-only trigger
        // threshold; 50% is the per-model bar for a *confident* announcement.
        internal const val POSSIBLE_THRESHOLD: Double = 30.0
        internal const val LIKELY_THRESHOLD: Double = 50.0
    }
}
