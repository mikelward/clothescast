package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.PrecipProbability.LIKELY_THRESHOLD
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.isPrecipitation
import app.clothescast.core.domain.model.WeekAheadClause
import app.clothescast.core.domain.model.WeekAheadInsight

/**
 * Pure-function builder for [WeekAheadInsight] from today's forecast and the
 * upcoming-days slice the 7-day page already plots. Returns null when nothing
 * worth surfacing fires — a calm week ahead doesn't need a headline.
 *
 * Each clause slot fires independently and the formatter sorts the surviving
 * clauses chronologically so a noisy week shows several stories at once
 * ("Warmer tomorrow, cooler Sunday.") rather than collapsing to one. The
 * slots:
 *  1. **Rain** — first upcoming day whose day-level precip probability
 *     clears [LIKELY_THRESHOLD] (50%, i.e. a majority of consulted models
 *     are calling rain). Condition is the day's reported condition when
 *     it's a precipitating type, falling back to [WeatherCondition.RAIN]
 *     so the prose stays honest when the base under-called the type. We
 *     deliberately don't hedge on a sub-50% "chance of rain" tier here —
 *     the today / tonight insights are where that fine-grained call lives.
 *  2. **First warmer / first cooler** — first upcoming day whose
 *     feels-like high diverges from today's by [deltaThresholdC] (defaults
 *     to 3°C, matching the today-page [RenderInsightSummary] delta clause).
 *     Both slots can fire on the same week. `null` disables the slots
 *     entirely — matches the same `null` semantic [RenderInsightSummary]
 *     uses for the user's "Temperature change: Off" setting.
 *  3. **Stays hot / cold** — today and every upcoming day land in the
 *     extreme band ([TemperatureBand.HOT] for hot; COLD or FREEZING for
 *     cold). Suppressed when either temperature-shift slot fires;
 *     mathematically they can't both be true (a flat band leaves no room
 *     for a 3°C+ swing), but the explicit gate keeps a future threshold
 *     change from leaking "Hot all week, warmer Sunday" through.
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
        val warmer = firstWarmerHeadline(today, upcomingDays, deltaThresholdC)
        val cooler = firstCoolerHeadline(today, upcomingDays, deltaThresholdC)
        // Persistence and a real temperature shift can't both fire — the
        // persistence rule requires every day to land in the same extreme
        // band, which mathematically rules out a 3°C+ feels-like-high swing
        // from today. Belt-and-braces: prefer the more specific shift when
        // either one somehow appears (e.g. if the delta threshold is lowered).
        val persistence = if (warmer == null && cooler == null) {
            persistenceHeadline(today, upcomingDays)
        } else {
            null
        }

        val bundle = WeekAheadInsight(
            firstWarmer = warmer,
            firstCooler = cooler,
            rain = rain,
            persistence = persistence,
        )
        return bundle.takeUnless { it.isEmpty }
    }

    private fun rainHeadline(today: DailyForecast, upcomingDays: List<DailyForecast>): WeekAheadClause.Rain? {
        val wet = upcomingDays.firstOrNull { it.precipitationProbabilityMaxPct >= LIKELY_THRESHOLD }
            ?: return null
        val condition = if (wet.condition.isPrecipitation()) wet.condition else WeatherCondition.RAIN
        return WeekAheadClause.Rain(
            date = wet.date,
            isTomorrow = wet.date == today.date.plusDays(1),
            condition = condition,
        )
    }

    private fun firstWarmerHeadline(
        today: DailyForecast,
        upcomingDays: List<DailyForecast>,
        deltaThresholdC: Double?,
    ): WeekAheadClause.Warmer? {
        if (deltaThresholdC == null) return null
        val pick = upcomingDays.firstOrNull { it.feelsLikeMaxC - today.feelsLikeMaxC >= deltaThresholdC }
            ?: return null
        return WeekAheadClause.Warmer(
            date = pick.date,
            isTomorrow = pick.date == today.date.plusDays(1),
        )
    }

    private fun firstCoolerHeadline(
        today: DailyForecast,
        upcomingDays: List<DailyForecast>,
        deltaThresholdC: Double?,
    ): WeekAheadClause.Cooler? {
        if (deltaThresholdC == null) return null
        val pick = upcomingDays.firstOrNull { today.feelsLikeMaxC - it.feelsLikeMaxC >= deltaThresholdC }
            ?: return null
        return WeekAheadClause.Cooler(
            date = pick.date,
            isTomorrow = pick.date == today.date.plusDays(1),
        )
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

}
