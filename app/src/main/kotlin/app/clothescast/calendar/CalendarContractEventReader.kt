package app.clothescast.calendar

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import app.clothescast.diag.DiagLog
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.UpcomingCalendarEvent
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.core.domain.usecase.CalendarEventClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Reads device calendar events for a single local day via [CalendarContract.Instances].
 *
 * Why `Instances` and not `Events`: `Events` rows describe recurring patterns; one
 * recurring meeting that fires every weekday is a single Events row but we want each
 * individual occurrence. The `Instances` view materialises occurrences into the
 * `[begin, end]` window we query, so a "9am standup, every weekday" only shows up
 * once per day with the correct start time.
 *
 * Returns an empty list on any failure path (missing permission, missing provider,
 * cursor crash) so the daily insight pipeline degrades gracefully to no events.
 */
class CalendarContractEventReader(private val context: Context) : CalendarEventReader {

    @Volatile
    private var eventTypeProjectionRejected: Boolean = false

    override suspend fun eventsForDay(date: LocalDate, zoneId: ZoneId): List<CalendarEvent> {
        if (!CalendarPermission.isGranted(context)) {
            DiagLog.i(TAG, "READ_CALENDAR not granted; skipping calendar read.")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                // Drop all-day rows whose UTC date isn't today: in zones east of
                // UTC, yesterday's all-day event overlaps the start of today's
                // local-day window and would otherwise bleed in. Timed rows are
                // kept as-is — the Instances window already scoped them.
                query(date, date.plusDays(1), zoneId)
                    .filterNot { it.event.allDay && it.date != date }
                    .map { it.event }
            }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    DiagLog.w(TAG, "Calendar query failed; degrading to no events.", it)
                }
                .getOrDefault(emptyList())
        }
    }

    override suspend fun upcomingCelebrations(
        startInclusive: LocalDate,
        endExclusive: LocalDate,
        zoneId: ZoneId,
    ): List<UpcomingCalendarEvent> {
        if (!CalendarPermission.isGranted(context)) {
            DiagLog.i(TAG, "READ_CALENDAR not granted; skipping calendar read.")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                // Narrow the year-long window to all-day rows at the provider
                // level: birthdays (Contacts / the Birthday event type) and the
                // synced "Holidays in <country>" calendars are all-day, so this
                // keeps dense calendars' timed meeting recurrences from being
                // materialised and classified just to be filtered out. The kind
                // filter below still drops any all-day NORMAL holds that slip
                // through.
                // TODO(celebrations): a user's *timed* birthday entry (e.g. a
                // "Sam's birthday" event the title regex would catch at 7pm)
                // still themes the day via eventsForDay but is excluded from this
                // listing by the all-day narrowing. There's no portable
                // ContentResolver selection for "title looks like a birthday", so
                // surfacing timed celebrations here needs a different approach
                // (e.g. also select eventType = birthday, or widen + cap the
                // scan) without re-materialising every meeting recurrence.
                query(startInclusive, endExclusive, zoneId, allDayOnly = true)
                    .filter { it.event.kind != EventKind.NORMAL }
                    .filter { it.date >= startInclusive && it.date < endExclusive }
                    .map { UpcomingCalendarEvent(it.date, it.event.title, it.event.kind) }
                    .sortedWith(compareBy({ it.date }, { it.title }))
            }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; DiagLog.w(TAG, "Calendar query failed; degrading to no events.", it) }
                .getOrDefault(emptyList())
        }
    }

    /** A classified event paired with the local date it falls on. */
    private data class DatedEvent(val date: LocalDate, val event: CalendarEvent)

    @SuppressLint("MissingPermission")
    private fun query(
        startInclusive: LocalDate,
        endExclusive: LocalDate,
        zoneId: ZoneId,
        allDayOnly: Boolean = false,
    ): List<DatedEvent> {
        val startMillis = startInclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillis = endExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()

        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, startMillis)
        ContentUris.appendId(uriBuilder, endMillis)
        val uri: Uri = uriBuilder.build()

        val baseProjection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.CALENDAR_ID,
        )
        // Try with the `eventType` column appended. Google Calendar writes a
        // non-default integer here when the user explicitly creates a "Birthday"
        // event; the typed constant `Events.EVENT_TYPE` isn't on compileSdk 35
        // yet so we project by literal string. Strict providers (older Android,
        // non-Google) reject unknown columns with IllegalArgumentException at
        // query time — silently swallowing that would lose ALL events for the
        // day, not just the birthday signal — so we retry once with the stable
        // projection and live without the locale-independent birthday hint.
        val withEventType = baseProjection + EVENT_TYPE_COLUMN
        val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"
        val selection = if (allDayOnly) "${CalendarContract.Instances.ALL_DAY} = 1" else null

        // First pass: read every row into a tuple, accumulating the set of calendar
        // ids we touched. The owner-account lookup happens once afterwards instead
        // of per-row to keep the heavy join out of the cursor loop. `availability`
        // is carried through because Google's holiday and birthday calendars sync
        // their rows as AVAILABILITY_FREE — we have to know the row's classified
        // kind before deciding whether the FREE filter applies.
        data class Row(
            val date: LocalDate,
            val title: String,
            val start: LocalTime,
            val end: LocalTime,
            val location: String?,
            val allDay: Boolean,
            val calendarId: Long,
            val eventType: Int?,
            val availability: Int,
        )

        val rows = mutableListOf<Row>()
        val calendarIds = mutableSetOf<Long>()
        // Once a provider has rejected `eventType` we know the rest of this
        // process's queries will too — skip the speculative first attempt
        // (and the stack-trace log it produces) thereafter.
        val rawCursor = if (eventTypeProjectionRejected) {
            context.contentResolver.query(uri, baseProjection, selection, null, sortOrder)
        } else {
            try {
                context.contentResolver.query(uri, withEventType, selection, null, sortOrder)
            } catch (e: IllegalArgumentException) {
                if (!eventTypeProjectionRejected) {
                    eventTypeProjectionRejected = true
                    DiagLog.i(TAG, "Provider rejected `eventType` column; future queries will skip it.", e)
                }
                context.contentResolver.query(uri, baseProjection, selection, null, sortOrder)
            }
        }
        rawCursor?.use { cursor ->
            val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
            val locationIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            val statusIdx = cursor.getColumnIndex(CalendarContract.Instances.STATUS)
            val availabilityIdx = cursor.getColumnIndex(CalendarContract.Instances.AVAILABILITY)
            val calendarIdIdx = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
            val eventTypeIdx = cursor.getColumnIndex(EVENT_TYPE_COLUMN)

            while (cursor.moveToNext()) {
                // Cancelled events are gone for good — never reach the classifier
                // and never reach downstream consumers either. The AVAILABILITY_FREE
                // filter, on the other hand, has to wait until after classification:
                // Google's holiday and birthday calendars sync as FREE and we'd
                // miss exactly the rows the classifier is supposed to recognise.
                if (statusIdx >= 0 && cursor.getInt(statusIdx) == CalendarContract.Instances.STATUS_CANCELED) continue

                val title = cursor.takeIf { titleIdx >= 0 }?.getString(titleIdx)?.takeIf { it.isNotBlank() }
                    ?: continue
                val begin = if (beginIdx >= 0) cursor.getLong(beginIdx) else continue
                val end = if (endIdx >= 0) cursor.getLong(endIdx) else continue
                val location = locationIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }?.takeIf { it.isNotBlank() }
                val allDay = allDayIdx >= 0 && cursor.getInt(allDayIdx) != 0
                val calendarId = if (calendarIdIdx >= 0) cursor.getLong(calendarIdIdx) else -1L
                val eventType = if (eventTypeIdx >= 0 && !cursor.isNull(eventTypeIdx)) cursor.getInt(eventTypeIdx) else null
                val availability = if (availabilityIdx >= 0) cursor.getInt(availabilityIdx) else CalendarContract.Instances.AVAILABILITY_BUSY

                // CalendarContract stores all-day events in UTC midnight-to-midnight; converting
                // those into the user's zone shifts them off the day boundary. Keep them as
                // pure all-day markers (dated by their UTC date) and project the rest into
                // wall-clock in the user zone (dated by the begin instant in that zone).
                // Callers apply their own window filter on [date]: a single-day read drops
                // adjacent-day all-day bleed (an east-of-UTC zone overlaps the previous UTC
                // day), a forward-window read keeps every row inside its range.
                val (date, start, finish) = if (allDay) {
                    val eventDate = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                    Triple(eventDate, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)
                } else {
                    val zoned = Instant.ofEpochMilli(begin).atZone(zoneId)
                    val e = Instant.ofEpochMilli(end).atZone(zoneId).toLocalTime()
                    Triple(zoned.toLocalDate(), zoned.toLocalTime(), e)
                }

                rows += Row(date, title, start, finish, location, allDay, calendarId, eventType, availability)
                if (calendarId >= 0) calendarIds += calendarId
            }
        }

        val ownerByCalendarId = ownerAccountsFor(calendarIds)

        // Tally classifications across the query so we can log one summary line
        // instead of one per row — a year-long upcomingCelebrations() over a
        // dense Google account otherwise floods the 300-line diag buffer with
        // dozens of repeated "Classified calendarId=X as Y" lines that all carry
        // the same per-calendar verdict. Title and owner stay out of the log
        // (both are PII); the summary names only the kind+reason counts.
        val classificationTally = mutableMapOf<Pair<EventKind, String>, Int>()
        val events = rows.mapNotNull { row ->
            val owner = ownerByCalendarId[row.calendarId]
            val result = CalendarEventClassifier.classify(row.title, owner, row.eventType)
            // FREE rows are typically calendar holds the user doesn't want surfaced
            // as meetings — but holiday and birthday calendars sync as FREE too, and
            // those are exactly the rows the classifier exists to identify. Skip a
            // FREE row only when it didn't classify as anything special.
            if (result.kind == EventKind.NORMAL && row.availability == CalendarContract.Instances.AVAILABILITY_FREE) {
                return@mapNotNull null
            }
            val key = result.kind to result.reason.toString()
            classificationTally[key] = (classificationTally[key] ?: 0) + 1
            DatedEvent(
                date = row.date,
                event = CalendarEvent(
                    title = row.title,
                    start = row.start,
                    end = row.end,
                    location = row.location,
                    allDay = row.allDay,
                    kind = result.kind,
                    ownerAccount = owner,
                ),
            )
        }

        if (events.isNotEmpty()) {
            val summary = classificationTally.entries
                .sortedByDescending { it.value }
                .joinToString(", ") { (key, count) -> "$count ${key.first} via ${key.second}" }
            DiagLog.i(TAG, "Classified ${events.size} events: $summary")
        }
        return events
    }

    /**
     * Reads `OWNER_ACCOUNT` for each calendar id touched by the day's instances.
     * `CalendarContract.Instances` doesn't expose the column directly — it lives
     * on the Calendars table — so we run one secondary query keyed on the ids we
     * just saw. Returns an empty map on permission/cursor failure; the classifier
     * just falls back to NORMAL for those rows.
     */
    @SuppressLint("MissingPermission")
    private fun ownerAccountsFor(calendarIds: Set<Long>): Map<Long, String?> {
        if (calendarIds.isEmpty()) return emptyMap()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.OWNER_ACCOUNT,
        )
        val placeholders = calendarIds.joinToString(",") { "?" }
        val selection = "${CalendarContract.Calendars._ID} IN ($placeholders)"
        val selectionArgs = calendarIds.map { it.toString() }.toTypedArray()
        val result = mutableMapOf<Long, String?>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val ownerIdx = cursor.getColumnIndex(CalendarContract.Calendars.OWNER_ACCOUNT)
                while (cursor.moveToNext()) {
                    if (idIdx < 0) continue
                    val id = cursor.getLong(idIdx)
                    val owner = if (ownerIdx >= 0) cursor.getString(ownerIdx) else null
                    result[id] = owner
                }
            }
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            DiagLog.w(TAG, "Owner-account lookup failed; classification falls back to NORMAL.", it)
        }
        return result
    }

    private companion object {
        private const val TAG = "CalendarReader"

        /** Literal column name for `Events.EVENT_TYPE`; constant not in compileSdk 35. */
        private const val EVENT_TYPE_COLUMN = "eventType"
    }
}
