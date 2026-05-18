package app.clothescast.calendar

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import app.clothescast.diag.DiagLog
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.core.domain.usecase.CalendarEventClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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

    override suspend fun eventsForDay(date: LocalDate, zoneId: ZoneId): List<CalendarEvent> {
        if (!CalendarPermission.isGranted(context)) {
            DiagLog.i(TAG, "READ_CALENDAR not granted; skipping calendar read.")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            runCatching { query(date, zoneId) }
                .onFailure { DiagLog.w(TAG, "Calendar query failed; degrading to no events.", it) }
                .getOrDefault(emptyList())
        }
    }

    @SuppressLint("MissingPermission")
    private fun query(date: LocalDate, zoneId: ZoneId): List<CalendarEvent> {
        val startOfDay = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endOfDay = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, startOfDay)
        ContentUris.appendId(uriBuilder, endOfDay)
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

        // First pass: read every row into a tuple, accumulating the set of calendar
        // ids we touched. The owner-account lookup happens once afterwards instead
        // of per-row to keep the heavy join out of the cursor loop. `availability`
        // is carried through because Google's holiday and birthday calendars sync
        // their rows as AVAILABILITY_FREE — we have to know the row's classified
        // kind before deciding whether the FREE filter applies.
        data class Row(
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
        val rawCursor = try {
            context.contentResolver.query(uri, withEventType, null, null, sortOrder)
        } catch (e: IllegalArgumentException) {
            DiagLog.i(TAG, "Provider rejected `eventType` column; retrying without it.", e)
            context.contentResolver.query(uri, baseProjection, null, null, sortOrder)
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
                // pure all-day markers and project the rest into wall-clock in the user zone.
                val (start, finish) = if (allDay) {
                    LocalTime.MIDNIGHT to LocalTime.MIDNIGHT
                } else {
                    val s = Instant.ofEpochMilli(begin).atZone(zoneId).toLocalTime()
                    val e = Instant.ofEpochMilli(end).atZone(zoneId).toLocalTime()
                    s to e
                }

                rows += Row(title, start, finish, location, allDay, calendarId, eventType, availability)
                if (calendarId >= 0) calendarIds += calendarId
            }
        }

        val ownerByCalendarId = ownerAccountsFor(calendarIds)

        return rows.mapNotNull { row ->
            val owner = ownerByCalendarId[row.calendarId]
            val result = CalendarEventClassifier.classify(row.title, owner, row.eventType)
            // FREE rows are typically calendar holds the user doesn't want surfaced
            // as meetings — but holiday and birthday calendars sync as FREE too, and
            // those are exactly the rows the classifier exists to identify. Skip a
            // FREE row only when it didn't classify as anything special.
            if (result.kind == EventKind.NORMAL && row.availability == CalendarContract.Instances.AVAILABILITY_FREE) {
                return@mapNotNull null
            }
            // Log the deduction path (kind + reason) without the title or owner —
            // both are PII. calendarId is a row-local integer, safe.
            DiagLog.i(
                TAG,
                "Classified calendarId=${row.calendarId} as ${result.kind} via ${result.reason}" +
                    (row.eventType?.let { " (eventType=$it)" } ?: " (eventType column unavailable)"),
            )
            CalendarEvent(
                title = row.title,
                start = row.start,
                end = row.end,
                location = row.location,
                allDay = row.allDay,
                kind = result.kind,
                ownerAccount = owner,
            )
        }
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
        }.onFailure { DiagLog.w(TAG, "Owner-account lookup failed; classification falls back to NORMAL.", it) }
        return result
    }

    private companion object {
        private const val TAG = "CalendarReader"

        /** Literal column name for `Events.EVENT_TYPE`; constant not in compileSdk 35. */
        private const val EVENT_TYPE_COLUMN = "eventType"
    }
}
