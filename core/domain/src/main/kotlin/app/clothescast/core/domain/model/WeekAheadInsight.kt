package app.clothescast.core.domain.model

import java.time.LocalDate

/**
 * Structured payload for the 7-day page headline — up to four independent
 * clauses worth telling the user about the week ahead. Mirrors the
 * structured-clause pattern used by [InsightSummary]: this type carries the
 * *what* (which clauses fire and their fields), and the Android-side
 * formatter (`InsightFormatter.formatWeekAhead`) turns it into prose via
 * string resources so vocab can localize per region.
 *
 * The renderer ([app.clothescast.core.domain.usecase.DeriveWeekAheadInsight])
 * fills each slot independently and the formatter sorts them chronologically
 * so a noisy week reads as one sentence — "Warmer tomorrow, cooler Sunday."
 * — instead of forcing one story to win. The slots:
 *  - [firstWarmer] — first upcoming day whose feels-like high clears
 *    today's by the configured delta threshold.
 *  - [firstCooler] — first upcoming day whose feels-like high drops below
 *    today's by the same threshold.
 *  - [rain] — first upcoming day whose day-level precip probability clears
 *    50% (a majority of consulted models call rain).
 *  - [persistence] — [WeekAheadClause.StaysHot] / [WeekAheadClause.StaysCold]
 *    when today and every upcoming day sit in an extreme band. Suppressed
 *    when either temperature-shift slot fires.
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
    val firstWarmer: WeekAheadClause.Warmer? = null,
    val firstCooler: WeekAheadClause.Cooler? = null,
    val rain: WeekAheadClause.Rain? = null,
    val persistence: WeekAheadClause? = null,
) {
    val isEmpty: Boolean
        get() = firstWarmer == null && firstCooler == null && rain == null && persistence == null
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
    ) : WeekAheadClause

    data class Cooler(
        val date: LocalDate,
        val isTomorrow: Boolean,
    ) : WeekAheadClause

    data class Warmer(
        val date: LocalDate,
        val isTomorrow: Boolean,
    ) : WeekAheadClause

    /** Today plus every upcoming day classify as [TemperatureBand.HOT]. */
    object StaysHot : WeekAheadClause

    /** Today plus every upcoming day classify as [TemperatureBand.COLD] or [TemperatureBand.FREEZING]. */
    object StaysCold : WeekAheadClause
}
