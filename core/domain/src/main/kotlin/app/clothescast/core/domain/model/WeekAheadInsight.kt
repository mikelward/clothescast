package app.clothescast.core.domain.model

import java.time.LocalDate

/**
 * A single glanceable headline for the 7-day page — what's worth telling the
 * user about the week ahead in one short line. Mirrors the structured-clause
 * pattern used by [InsightSummary]: this type carries the *what*, and the
 * Android-side formatter (`InsightFormatter.formatWeekAhead`) turns it into
 * prose via string resources so vocab can localize per region.
 *
 * The renderer ([app.clothescast.core.domain.usecase.DeriveWeekAheadInsight])
 * picks at most one variant per derivation, in this priority order:
 *  1. [Rain] — the nearest upcoming day with precip probability ≥ 30%.
 *     Actionable umbrella-worthy news beats most other stories.
 *  2. [Cooler] / [Warmer] — the upcoming day with the biggest feels-like-high
 *     delta from today, when that delta clears the same 3°C threshold as
 *     the today-page delta clause.
 *  3. [StaysHot] / [StaysCold] — when today and every upcoming day sit in
 *     an extreme band ([TemperatureBand.HOT] for hot; COLD or FREEZING for
 *     cold). "Hot all week" / "Cold all week" — the persistence story.
 *
 * Returns `null` from the renderer when none of the rules fire — a calm,
 * unremarkable week ahead doesn't need a headline.
 *
 * `isTomorrow` is precomputed by the renderer (it has today's date) so the
 * formatter doesn't need to. The formatter renders "tomorrow" when true and
 * the day-of-week name ("Thursday") otherwise — by request, no fancier
 * vocabulary ("this weekend", "next Friday") is used.
 */
sealed interface WeekAheadInsight {
    data class Rain(
        val date: LocalDate,
        val isTomorrow: Boolean,
        val condition: WeatherCondition,
        val likelihood: PrecipLikelihood,
    ) : WeekAheadInsight

    data class Cooler(
        val date: LocalDate,
        val isTomorrow: Boolean,
        val degrees: Int,
    ) : WeekAheadInsight

    data class Warmer(
        val date: LocalDate,
        val isTomorrow: Boolean,
        val degrees: Int,
    ) : WeekAheadInsight

    /** Today plus every upcoming day classify as [TemperatureBand.HOT]. */
    object StaysHot : WeekAheadInsight

    /** Today plus every upcoming day classify as [TemperatureBand.COLD] or [TemperatureBand.FREEZING]. */
    object StaysCold : WeekAheadInsight
}
