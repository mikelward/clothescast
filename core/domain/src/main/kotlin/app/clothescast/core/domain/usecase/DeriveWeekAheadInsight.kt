package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.WeekAheadClause
import app.clothescast.core.domain.model.WeekAheadInsight
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-function builder for [WeekAheadInsight] from today's forecast and the
 * upcoming-days slice the 7-day page already plots. Returns null when nothing
 * worth surfacing fires — a calm week ahead doesn't need a headline.
 *
 * Each clause slot fires independently so a noisy week shows both stories at
 * once ("5° cooler tomorrow, chance of rain Monday.") rather than collapsing
 * to one. The slots:
 *  1. **Rain** — nearest upcoming day whose day-level precip probability
 *     clears [POSSIBLE_THRESHOLD]. [PrecipLikelihood.LIKELY] when it also
 *     clears [LIKELY_THRESHOLD]; otherwise POSSIBLE ("Chance of rain on …").
 *     Condition is the day's reported condition when it's a precipitating
 *     type, falling back to [WeatherCondition.RAIN] so the prose stays
 *     honest when the base under-called the type.
 *  2. **Cooler / Warmer** — upcoming day with the biggest feels-like-high
 *     delta from today. Only emitted when the unrounded delta clears
 *     [deltaThresholdC] (defaults to 3°C, matching the today-page
 *     [RenderInsightSummary] delta clause). Ties take the nearest day.
 *     `null` disables the slot entirely — matches the same `null` semantic
 *     [RenderInsightSummary] uses for the user's "Temperature change: Off"
 *     setting.
 *  3. **Stays hot / cold** — today and every upcoming day land in the
 *     extreme band ([TemperatureBand.HOT] for hot; COLD or FREEZING for
 *     cold). Suppressed when the temperature-shift slot fires; mathematically
 *     they can't both be true (a flat band leaves no room for a 3°C+ swing),
 *     but the explicit gate keeps a future threshold change from leaking
 *     "Hot all week, 4° cooler Sunday" through.
 *
 * The renderer doesn't peek at per-model data on purpose. The 7-day page
 * already shows model spread on its primary charts; a single-headline
 * domain layer that depended on per-model agreement would need every
 * caller to plumb the wider series through, and the day-level rollup from
 * Open-Meteo is reliable enough at the "is there rain this week" coarseness
 * we need here. Per-model precip agreement still gates the today / tonight
 * insights, which is where the umbrella-worthy decision actually lives.
 */
class DeriveWeekAheadInsight {
    operator fun invoke(
        today: DailyForecast,
        upcomingDays: List<DailyForecast>,
        deltaThresholdC: Double? = 3.0,
    ): WeekAheadInsight? {
        if (upcomingDays.isEmpty()) return null

        val rain = rainHeadline(today, upcomingDays)
        val shift = temperatureShiftHeadline(today, upcomingDays, deltaThresholdC)
        // Persistence and a real temperature shift can't both fire — the
        // persistence rule requires every day to land in the same extreme
        // band, which mathematically rules out a 3°C+ feels-like-high swing
        // from today. Belt-and-braces: prefer the more specific shift when
        // both somehow appear (e.g. if the delta threshold is lowered).
        val persistence = if (shift == null) persistenceHeadline(today, upcomingDays) else null

        val bundle = WeekAheadInsight(
            temperatureShift = shift,
            rain = rain,
            persistence = persistence,
        )
        return bundle.takeUnless { it.isEmpty }
    }

    private fun rainHeadline(today: DailyForecast, upcomingDays: List<DailyForecast>): WeekAheadClause.Rain? {
        val wet = upcomingDays.firstOrNull { it.precipitationProbabilityMaxPct >= POSSIBLE_THRESHOLD }
            ?: return null
        val likelihood = if (wet.precipitationProbabilityMaxPct >= LIKELY_THRESHOLD) {
            PrecipLikelihood.LIKELY
        } else {
            PrecipLikelihood.POSSIBLE
        }
        val condition = if (wet.condition.isPrecipitation()) wet.condition else WeatherCondition.RAIN
        return WeekAheadClause.Rain(
            date = wet.date,
            isTomorrow = wet.date == today.date.plusDays(1),
            condition = condition,
            likelihood = likelihood,
        )
    }

    private fun temperatureShiftHeadline(
        today: DailyForecast,
        upcomingDays: List<DailyForecast>,
        deltaThresholdC: Double?,
    ): WeekAheadClause? {
        if (deltaThresholdC == null) return null
        // maxByOrNull picks the first entry on ties, which is the nearest day —
        // exactly what we want when two days share the same swing.
        val pick = upcomingDays.maxByOrNull { abs(it.feelsLikeMaxC - today.feelsLikeMaxC) }
            ?: return null
        val delta = pick.feelsLikeMaxC - today.feelsLikeMaxC
        if (abs(delta) < deltaThresholdC) return null
        val degrees = abs(delta).roundToInt()
        val isTomorrow = pick.date == today.date.plusDays(1)
        return if (delta > 0) {
            WeekAheadClause.Warmer(date = pick.date, isTomorrow = isTomorrow, degrees = degrees)
        } else {
            WeekAheadClause.Cooler(date = pick.date, isTomorrow = isTomorrow, degrees = degrees)
        }
    }

    private fun persistenceHeadline(today: DailyForecast, upcomingDays: List<DailyForecast>): WeekAheadClause? {
        val window = listOf(today) + upcomingDays
        if (window.all { TemperatureBand.forCelsius(it.feelsLikeMaxC) == TemperatureBand.HOT }) {
            return WeekAheadClause.StaysHot
        }
        val coldBands = setOf(TemperatureBand.COLD, TemperatureBand.FREEZING)
        if (window.all { TemperatureBand.forCelsius(it.feelsLikeMaxC) in coldBands }) {
            return WeekAheadClause.StaysCold
        }
        return null
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

    companion object {
        // Matches RenderInsightSummary's thresholds so the today / tonight
        // and weekly insights agree on "is this rainy enough to mention."
        internal const val POSSIBLE_THRESHOLD: Double = 30.0
        internal const val LIKELY_THRESHOLD: Double = 50.0
    }
}
