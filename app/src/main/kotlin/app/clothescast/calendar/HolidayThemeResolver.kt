package app.clothescast.calendar

import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.core.domain.usecase.ThemeForToday
import app.clothescast.core.domain.util.coRunCatching
import app.clothescast.tts.toJavaLocale
import java.time.LocalDate
import java.util.Locale

/**
 * Resolves today's holiday / birthday theme for off-screen renderers (the
 * notification large icon, the Home-Assistant outfit card published over
 * MQTT, the widget). Mirrors the same merge [app.clothescast.ui.today.TodayViewModel]
 * performs at composition time so background and manual publishes share
 * the Today screen's palette — without it, themed days silently fall back
 * to the user's default outfit colours.
 *
 * Calendar events are only read when the user has opted into
 * calendar-sourced theming; the read is wrapped in [coRunCatching] so a
 * missing READ_CALENDAR permission silently falls through to "no events"
 * while a caller cancellation still unwinds instead of being swallowed.
 *
 * [today] defaults to the user's wall-clock date but is injectable so
 * tests can pin a specific calendar date (avoids flake when the test
 * date happens to land on a real holiday like Pentecost or Easter).
 */
suspend fun resolveHolidayTheme(
    prefs: UserPreferences,
    calendarEventReader: CalendarEventReader,
    today: LocalDate = LocalDate.now(prefs.schedule.zoneId),
): HolidayTheme? {
    val needEvents = prefs.calendarHolidayThemingActive || prefs.calendarBirthdayThemingActive
    val events = if (needEvents) {
        coRunCatching {
            calendarEventReader.eventsForDay(today, prefs.schedule.zoneId)
        }.getOrDefault(emptyList())
    } else {
        emptyList()
    }
    val localeCountry = prefs.region.toJavaLocale()?.country
        ?: Locale.getDefault().country
    val effectiveCountries = prefs.holidayCountrySelection.resolveEnabledCountries(
        localeCountry = localeCountry,
        weatherLocationCountry = prefs.location?.countryCode,
        allCountries = HolidayCatalog.allCountries,
    )
    return ThemeForToday().resolve(
        date = today,
        overrides = prefs.holidayOverrides,
        enabledCountries = effectiveCountries,
        events = events,
        themeFromCalendarHolidays = prefs.calendarHolidayThemingActive,
        themeFromCalendarBirthdays = prefs.calendarBirthdayThemingActive,
    )
}
