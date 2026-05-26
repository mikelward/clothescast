package app.clothescast.core.domain.model

import java.time.LocalDate

/**
 * Structured payload for the 7-day page headline — up to three independent
 * clauses worth telling the user about the week ahead. Mirrors the
 * structured-clause pattern used by [InsightSummary]: this type carries the
 * *what* (which clauses fire and their fields), and the Android-side
 * formatter (`InsightFormatter.formatWeekAhead`) turns it into prose via
 * string resources so vocab can localize per region.
 *
 * The renderer ([app.clothescast.core.domain.usecase.DeriveWeekAheadInsight])
 * fills each slot independently so a noisy week shows both stories at once
 * — "5° cooler tomorrow, chance of rain Monday." — instead of forcing one
 * to win. The slots:
 *  - [temperatureShift] — [WeekAheadClause.Cooler] / [WeekAheadClause.Warmer]
 *    for the biggest feels-like-high delta from today, when it clears the
 *    same 3°C threshold as the today-page delta clause.
 *  - [rain] — the nearest upcoming day with precip probability ≥ 30%.
 *  - [persistence] — [WeekAheadClause.StaysHot] / [WeekAheadClause.StaysCold]
 *    when today and every upcoming day sit in an extreme band. Cannot
 *    coexist with [temperatureShift] (a flat band leaves no room for a
 *    3°C+ swing), but does coexist with [rain].
 *
 * The renderer returns `null` when no clauses fire — a calm week ahead
 * doesn't need a headline at all, so the UI suppresses the card.
 *
 * `isTomorrow` on each clause is precomputed by the renderer (it has today's
 * date) so the formatter doesn't need to. The formatter renders "tomorrow"
 * when true and the day-of-week name ("Thursday") otherwise — by request, no
 * fancier vocabulary ("this weekend", "next Friday") is used.
 */
data class WeekAheadInsight(
    val temperatureShift: WeekAheadClause? = null,
    val rain: WeekAheadClause.Rain? = null,
    val persistence: WeekAheadClause? = null,
) {
    val isEmpty: Boolean
        get() = temperatureShift == null && rain == null && persistence == null
}

/**
 * One clause that can appear in a [WeekAheadInsight]. The container in
 * [WeekAheadInsight] enforces which slot each variant lands in — the
 * sealed type itself just carries the structured fields the formatter
 * needs.
 */
sealed interface WeekAheadClause {
    data class Rain(
        val date: LocalDate,
        val isTomorrow: Boolean,
        val condition: WeatherCondition,
        val likelihood: PrecipLikelihood,
    ) : WeekAheadClause

    data class Cooler(
        val date: LocalDate,
        val isTomorrow: Boolean,
        val degrees: Int,
    ) : WeekAheadClause

    data class Warmer(
        val date: LocalDate,
        val isTomorrow: Boolean,
        val degrees: Int,
    ) : WeekAheadClause

    /** Today plus every upcoming day classify as [TemperatureBand.HOT]. */
    object StaysHot : WeekAheadClause

    /** Today plus every upcoming day classify as [TemperatureBand.COLD] or [TemperatureBand.FREEZING]. */
    object StaysCold : WeekAheadClause
}
