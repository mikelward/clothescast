package app.clothescast.core.domain.model

import java.time.LocalDate

/**
 * A single celebration (birthday or public holiday) detected in the user's
 * synced calendars over a forward date window, used by the Celebrations
 * settings screen to list what will theme upcoming outfits.
 *
 * Unlike [CalendarEvent] — which is always projected into the one local day an
 * insight is generated for — this carries its [date] so the listing can show
 * each event chronologically across the window. The title, date, reader-set
 * [kind], and source-calendar [ownerAccount] are surfaced so the settings
 * listing can show which calendar a detected celebration came from (tap a row
 * to reveal it). Like [CalendarEvent], every field here is device-local and
 * must never appear in insight prose, TTS payloads, or Firebase — [ownerAccount]
 * especially is account/email-shaped PII and is shown on screen only.
 */
data class UpcomingCalendarEvent(
    val date: LocalDate,
    val title: String,
    val kind: EventKind,
    val ownerAccount: String? = null,
)
