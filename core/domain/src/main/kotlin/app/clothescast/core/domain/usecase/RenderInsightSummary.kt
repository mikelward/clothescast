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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds the structured [InsightSummary] for a single period (TODAY or TONIGHT).
 * Pure function: same forecast slice, same clauses. The renderer no longer
 * special-cases evening data — the morning's heads-up about a cold or rainy
 * evening event is computed by the caller by running this same render against
 * the night slice and folding the resulting clothes + precip clauses into an
 * [EveningEventTieInClause] (see [GenerateDailyInsight]).
 *
 * Rules (each yields 0 or 1 clause):
 * 1. [AlertClause] — highest-severity SEVERE/EXTREME alert. Extreme outranks Severe;
 *    ties take the first listed.
 * 2. [BandClause] — classify feels-like low and high into bands. Always emitted.
 * 3. [DeltaClause] — yesterday vs today; only emitted when the larger absolute
 *    feels-like delta is ≥ 3°C, and only for [ForecastPeriod.TODAY] (yesterday's
 *    overnight comparison isn't useful, and the morning pass already covers it).
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
 * 7. [EveningEventTieInClause] — passes through whatever the caller built. The
 *    renderer doesn't know how to compose one because it requires consulting
 *    the night forecast slice, which is the caller's job.
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
        // per-model series to the same window as [today.hourly].
        perModelHourly: PerModelHourly? = null,
        // Pre-built evening tie-in. The caller (GenerateDailyInsight) constructs
        // it by running render() against the night slice and folding the
        // resulting clothes + precip clauses, gated on the user having an
        // event away from home that night. This renderer is period-local and
        // doesn't compose it.
        eveningEventTieIn: EveningEventTieInClause? = null,
    ): InsightSummary {
        val items = todayTriggeredRules.map { it.item }
        val peak = peakPrecip(today, perModelHourly)
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
            eveningEventTieIn = eveningEventTieIn,
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
        feelsLikeMinC = today.feelsLikeMinC,
        feelsLikeMaxC = today.feelsLikeMaxC,
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
        // Walk every hour any model reported, scoring by "models hitting LIKELY
        // at this hour" first and the single biggest per-model reading second
        // (tie-break, also doubles as the POSSIBLE fallback pivot). Hours are
        // LocalDateTimes (per-model entries carry a date so the tonight wrap
        // doesn't alias); convert to LocalTime at emission, which is all the
        // downstream prose / chart consumers need.
        //
        // Majority is computed PER HOUR over the models that actually reported
        // a reading there, not over the total number of models in [byModel].
        // Floor of 2 readings keeps a single-model agreement honest as a
        // POSSIBLE rather than promoting it to LIKELY: "one model says rain"
        // is the textbook chance-of-rain case the per-model tier exists to
        // express.
        val hours = models.flatMap { entries -> entries.map { it.time } }.toSortedSet()
        val likelyHour = hours
            .mapNotNull { hour ->
                // Readings with usable precip only: a model that reported
                // temperature for this hour but no precipitation_probability
                // (per the parser's null-precip handling) shouldn't count
                // toward the per-hour majority for a rain decision. Filtering
                // here also keeps the rain peak honest in mixed selections
                // (e.g. ECMWF + UKMO) where one model has no precip series
                // at all — without it, the missing-data model would be
                // implicitly counted as "below threshold" and could veto
                // the rain call.
                val readings = models
                    .mapNotNull { entries -> entries.firstOrNull { it.time == hour } }
                    .filter { it.precipitationProbabilityPct != null }
                if (readings.size < 2) return@mapNotNull null
                val majorityOfReporters = readings.size / 2 + 1
                val likelyCount = readings.count { (it.precipitationProbabilityPct ?: 0.0) >= LIKELY_THRESHOLD }
                if (likelyCount >= majorityOfReporters) hour to readings else null
            }
            .maxByOrNull { (_, readings) -> readings.maxOf { it.precipitationProbabilityPct ?: 0.0 } }
            ?.first
            ?.toLocalTime()
        if (likelyHour != null) {
            return PeakPrecip(likelyHour, perModelConditionAt(likelyHour, today), PrecipLikelihood.LIKELY)
        }

        val possibleHour = models.asSequence()
            .flatten()
            .filter { (it.precipitationProbabilityPct ?: 0.0) >= POSSIBLE_THRESHOLD }
            .maxByOrNull { it.precipitationProbabilityPct ?: 0.0 }
            ?.time
            ?.toLocalTime()
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
        // Pick the first triggered item, mirroring rule 4's ordering. The formatter
        // silences accessories (umbrella) before they reach the rendered prose, so
        // there's no point picking a specifically-precip-motivated item here — until
        // the accessory catalog lands, calendar tie-ins are garment-only.
        return CalendarTieInClause(item = items.first())
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
