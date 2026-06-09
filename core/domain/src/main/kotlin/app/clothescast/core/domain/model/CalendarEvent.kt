package app.clothescast.core.domain.model

import java.time.LocalDateTime

/**
 * Classification applied to each [CalendarEvent] by the reader, so downstream code
 * can branch on it without inspecting the title. Always derived on-device and never
 * carried into any off-device payload — see the privacy note on [CalendarEvent].
 */
enum class EventKind {
    NORMAL,
    BIRTHDAY,
    PUBLIC_HOLIDAY,
}

/**
 * One scheduled event read from the user's device calendar. [start]/[end] are
 * full local date-times — wall-clock in the user's zone; the reader is
 * responsible for applying timezone conversion before constructing the model
 * so downstream code never has to think about Instants. Carrying the date
 * means an event that crosses midnight is just an interval whose end falls on
 * the next date, and a tomorrow-pre-dawn event inside tonight's window is
 * distinguishable from today's — no wrapped-interval special casing anywhere
 * downstream.
 *
 * Privacy posture: only the [title], [start]/[end], and an optional [location] line
 * are carried. Descriptions, attendees, organizers, and account info are not
 * surfaced. The current consumer [app.clothescast.core.domain.usecase.RenderInsightSummary]
 * never includes the location in the rendered string, but the field is kept so a
 * future "what to bring + where" sentence can use it without a model migration.
 *
 * [kind] is a reader-set classification (birthday / public holiday / normal) so
 * UI code can pick a themed palette without scraping the title at the use-case
 * layer. [ownerAccount] carries the source-calendar account so the reader can
 * recognise Google's "Holidays in <country>" calendar; both fields stay on
 * device and must never appear in insight prose, TTS payloads, or Firebase.
 */
data class CalendarEvent(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val location: String? = null,
    val allDay: Boolean = false,
    val kind: EventKind = EventKind.NORMAL,
    val ownerAccount: String? = null,
) {
    /**
     * True when [time] falls within `[start, end)`. All-day events never
     * match, so the precip-peak overlap check never accidentally pairs an
     * umbrella with "your all-day Public holiday".
     */
    fun overlaps(time: LocalDateTime): Boolean {
        if (allDay) return false
        if (start == end) return time == start
        // A defensive end-before-start interval (reader bug) never matches.
        return !time.isBefore(start) && time.isBefore(end)
    }
}
