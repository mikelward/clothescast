package app.clothescast.core.domain.model

import java.time.LocalDate

/**
 * A single celebration (birthday or public holiday) detected in the user's
 * synced calendars over a forward date window, used by the Celebrations
 * settings screen to list what will theme upcoming outfits.
 *
 * Unlike [CalendarEvent] — which is always projected into the one local day an
 * insight is generated for — this carries its [date] so the listing can show
 * each event chronologically across the window. Only the title, date, and the
 * reader-set [kind] are surfaced; like [CalendarEvent], these stay on device
 * and must never appear in insight prose, TTS payloads, or Firebase.
 */
data class UpcomingCalendarEvent(
    val date: LocalDate,
    val title: String,
    val kind: EventKind,
)
