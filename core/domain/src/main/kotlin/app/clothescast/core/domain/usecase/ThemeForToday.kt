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

        // A curated primary that already fired suppresses the calendar
        // fallback outright — without this, a holiday force-ON'd from
        // outside the enabled countries (which hasCatalogMatch's country
        // gate can't see) would double up with the synced calendar's event
        // for the same day ("Happy Australia Day and Australia Day").
        // hasCatalogMatch still handles the no-primary cases: an
        // OFF-overridden holiday whose country is enabled keeps suppressing
        // the fallback (explicit opt-out beats the calendar event).
        val calendarHolidays = if (themeFromCalendarHolidays &&
            primaries.isEmpty() &&
            !holidayResolver.hasCatalogMatch(date, enabledCountries)
        ) {
            events.firstOrNull { it.kind == EventKind.PUBLIC_HOLIDAY }
                ?.let { listOf(FestiveThemes.publicHoliday(it.title, it.ownerAccount)) }
                .orEmpty()
        } else {
            emptyList()
        }
        val birthdays = if (themeFromCalendarBirthdays) {
            // The same birthday often lands in more than one synced calendar
            // (e.g. a personal "Eva's birthday" and a shared "Eva's birthday!"),
            // and two different people can share a day. Collect every distinct
            // birthday — deduped on a punctuation/case-insensitive key so the
            // same person across calendars merges instead of doubling up — and
            // keep the first-seen title for display.
            events.asSequence()
                .filter { it.kind == EventKind.BIRTHDAY }
                .distinctBy { birthdayDedupeKey(it.title) }
                .map { FestiveThemes.birthday(it.title) }
                .toList()
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
                    BannerSegment(literalText = theme.displayTitleOverride, holidayId = theme.id)
                theme.isFunny ->
                    BannerSegment(
                        textKey = theme.bannerJoinKey ?: theme.bannerTextKey,
                        holidayId = theme.id,
                    )
                else ->
                    BannerSegment(
                        textKey = theme.bannerTextKey,
                        textKeyByCountry = theme.bannerTextKeyByCountry,
                        holidayId = theme.id,
                    )
            }
        }
        return colourSource.copy(bannerSegments = segments)
    }

    // Normalises a birthday title so the same person syncing in from several
    // calendars collapses to one contributor: lower-cased, curly apostrophe
    // folded to straight, trailing whitespace and punctuation stripped.
    private fun birthdayDedupeKey(title: String): String =
        title.lowercase()
            .replace('’', '\'')
            .trim()
            .trimEnd('!', '?', '.', ',', ' ')
}
