package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.BannerSegment
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.FestiveThemes
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.core.domain.model.isFunny
import java.time.LocalDate

/**
 * Resolves the Today screen's "is today themeable, and how?" question by
 * combining the curated [HolidayCatalog] (via [HolidayResolver]) with
 * calendar-sourced holiday and birthday events.
 *
 * Unlike a single-winner lookup, this collects *every* celebration that
 * fires today and composes them into one theme:
 *  - **Banner** — each celebration's copy joined with "and". A lone
 *    celebration uses its standalone banner; when several share a day,
 *    Funny themes switch to their lower-case join clause ("Happy bank
 *    holiday and don't forget your towel").
 *  - **Colours** — taken from the Funny theme when one is present (the
 *    fun palette is the point), otherwise the first primary holiday.
 *  - **Solemn suppression** — when a remembrance day fires (Anzac,
 *    Memorial Day, Remembrance Day, Korean Memorial Day), every Funny
 *    clause is dropped so the day stays solemn.
 *
 * Ordering of contributors (which is also banner order): curated primaries
 * in calendar order, then a calendar public holiday, then a birthday, then
 * Funny clauses last.
 *
 * Calendar contributors only count when the matching toggle is on. A
 * calendar public holiday is ignored when the curated catalog already has
 * an eligible entry for the date (dedupe — a Google "Christmas Day" event
 * shouldn't double up clothescast's curated Christmas). Birthdays always
 * join.
 */
class ThemeForToday(
    private val holidayResolver: HolidayResolver = HolidayResolver(),
) {
    fun resolve(
        date: LocalDate,
        overrides: Map<HolidayId, HolidayOverride>,
        enabledCountries: Set<String>,
        events: List<CalendarEvent>,
        themeFromCalendarHolidays: Boolean,
        themeFromCalendarBirthdays: Boolean,
    ): HolidayTheme? {
        val catalogMatches = holidayResolver.resolveAll(date, overrides, enabledCountries)
        val primaries = catalogMatches.filterNot { it.isFunny }
        // A remembrance day mutes every playful clause for the day.
        val funnies = if (primaries.any { it.solemn }) emptyList() else catalogMatches.filter { it.isFunny }

        val calendarHolidays = if (themeFromCalendarHolidays &&
            !holidayResolver.hasCatalogMatch(date, enabledCountries)
        ) {
            events.firstOrNull { it.kind == EventKind.PUBLIC_HOLIDAY }
                ?.let { listOf(FestiveThemes.publicHoliday(it.title)) }
                .orEmpty()
        } else {
            emptyList()
        }
        val birthdays = if (themeFromCalendarBirthdays) {
            events.firstOrNull { it.kind == EventKind.BIRTHDAY }
                ?.let { listOf(FestiveThemes.birthday(it.title)) }
                .orEmpty()
        } else {
            emptyList()
        }

        // Banner / contributor order: primaries → calendar holiday →
        // birthday → Funny clause last.
        val contributors = primaries + calendarHolidays + birthdays + funnies
        if (contributors.isEmpty()) return null

        // Colours come from the Funny theme when present (suppressed on
        // solemn days), else the first primary, else the synthetic.
        val colourSource = funnies.firstOrNull()
            ?: primaries.firstOrNull()
            ?: contributors.first()

        // A single celebration renders its own standalone banner — no join.
        if (contributors.size == 1) return contributors.first()

        val segments = contributors.map { theme ->
            when {
                theme.displayTitleOverride != null ->
                    BannerSegment(literalText = theme.displayTitleOverride)
                theme.isFunny ->
                    BannerSegment(textKey = theme.bannerJoinKey ?: theme.bannerTextKey)
                else ->
                    BannerSegment(
                        textKey = theme.bannerTextKey,
                        textKeyByCountry = theme.bannerTextKeyByCountry,
                    )
            }
        }
        return colourSource.copy(bannerSegments = segments)
    }
}
