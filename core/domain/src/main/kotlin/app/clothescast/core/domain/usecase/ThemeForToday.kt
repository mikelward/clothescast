package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.FestiveThemes
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.HolidayTheme
import java.time.LocalDate

/**
 * Resolves the Today screen's "is today themeable, and how?" question by
 * combining the curated [HolidayCatalog] (via [HolidayResolver]) with
 * calendar-sourced holiday and birthday events.
 *
 * Priority (highest to lowest):
 * 1. **Curated holiday match.** [HolidayCatalog] entries always win — a
 *    user with a Google holiday-calendar subscription that also has its
 *    own "Christmas Day" row still sees clothescast's curated Christmas
 *    palette + localised banner.
 * 2. **Calendar-sourced public holiday** (if [themeFromCalendarHolidays]).
 *    The first event in the day's list that classifies as
 *    [EventKind.PUBLIC_HOLIDAY] becomes a [FestiveThemes.publicHoliday].
 *    This fills the curated catalog's known lunisolar gap — Diwali, Eid,
 *    Lunar New Year, Hanukkah, Holi — for users who've subscribed to
 *    "Holidays in <country>" in Google Calendar.
 * 3. **Calendar-sourced birthday** (if [themeFromCalendarBirthdays]).
 *    The first event classified as [EventKind.BIRTHDAY] becomes a
 *    [FestiveThemes.birthday].
 * 4. `null` — no theme today.
 *
 * Both feature toggles default off in [UserPreferences]; the use-case
 * receives the user's current intent each call and ignores any classified
 * events if the matching toggle is off.
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
        holidayResolver.resolve(date, overrides, enabledCountries)?.let { return it }
        // If the curated catalog has an entry for this date but it didn't
        // fire (user OFF'd it, or its country bucket is off), honour the
        // user's choice and skip the calendar-holiday fallback. Without
        // this, turning Christmas off via the per-holiday dropdown would
        // still surface a "Christmas Day" event from a Google holiday
        // calendar as a synthetic theme. Birthdays don't go through the
        // catalog, so they're unaffected.
        val curatedDateOccupied = holidayResolver.hasCatalogMatch(date, enabledCountries)
        if (themeFromCalendarHolidays && !curatedDateOccupied) {
            events.firstOrNull { it.kind == EventKind.PUBLIC_HOLIDAY }
                ?.let { return FestiveThemes.publicHoliday(it.title) }
        }
        if (themeFromCalendarBirthdays) {
            events.firstOrNull { it.kind == EventKind.BIRTHDAY }
                ?.let { return FestiveThemes.birthday(it.title) }
        }
        return null
    }
}
