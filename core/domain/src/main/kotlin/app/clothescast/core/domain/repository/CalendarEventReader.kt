package app.clothescast.core.domain.repository

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.UpcomingCalendarEvent
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads the user's calendar events for a given local day. Implementations are
 * expected to be best-effort: missing permission, an unavailable provider, or a
 * query failure should return an empty list rather than throw, so the daily
 * insight pipeline degrades gracefully to its calendar-free behaviour.
 */
interface CalendarEventReader {
    suspend fun eventsForDay(date: LocalDate, zoneId: ZoneId): List<CalendarEvent>

    /**
     * Lists the celebrations — birthdays and public holidays — detected across
     * `[startInclusive, endExclusive)`, ordered by date. Normal meetings are
     * excluded so the result stays small even over a year-long window. Used by
     * the Celebrations settings screen to preview which calendar events will
     * theme upcoming outfits. Best-effort like [eventsForDay]: returns an empty
     * list on missing permission or query failure rather than throwing.
     */
    suspend fun upcomingCelebrations(
        startInclusive: LocalDate,
        endExclusive: LocalDate,
        zoneId: ZoneId,
    ): List<UpcomingCalendarEvent>
}
