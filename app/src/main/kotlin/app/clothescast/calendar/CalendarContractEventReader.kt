package app.clothescast.calendar

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import app.clothescast.diag.DiagLog
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.CalendarInfo
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.UpcomingCalendarEvent
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.core.domain.usecase.CalendarEventClassifier
import app.clothescast.core.domain.util.coRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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
class CalendarContractEventReader(
    private val context: Context,
    /**
     * Supplies the user's current per-calendar overrides (see
     * [app.clothescast.core.domain.model.UserPreferences.calendarOverrides]).
     * Read fresh on each query so a toggle takes effect on the next refresh
     * without rebuilding the reader. Defaulted to "no overrides" so tests and
     * any call site that doesn't care still get the visibility-default
     * behaviour.
     */
    private val calendarOverridesProvider: suspend () -> Map<String, Boolean> = { emptyMap() },
) : CalendarEventReader {

    @Volatile
    private var eventTypeProjectionRejected: Boolean = false

    override suspend fun eventsForDay(date: LocalDate, zoneId: ZoneId): List<CalendarEvent> {
        if (!CalendarPermission.isGranted(context)) {
            DiagLog.i(TAG, "READ_CALENDAR not granted; skipping calendar read.")
            return emptyList()
        }
        // Resolve per-calendar overrides off the blocking cursor path (the
        // provider reads DataStore) before entering the query.
        val overrides = calendarOverridesProvider()
        return withContext(Dispatchers.IO) {
            coRunCatching {
                // Drop all-day rows whose UTC span doesn't cover today: in zones
                // east of UTC, yesterday's all-day event overlaps the start of
                // today's local-day window and would otherwise bleed in. Checked
                // against the row's full [date, endDateExclusive) span — not just
                // its first day — so a multi-day all-day event still counts on
                // days two onward. Timed rows are kept as-is — the Instances
                // window already scoped them.
                query(date, date.plusDays(1), zoneId, overrides = overrides)
                    .filterNot { it.event.allDay && (date < it.date || date >= it.endDateExclusive) }
                    .map { it.event }
            }
                .onFailure { DiagLog.w(TAG, it, "Calendar query failed; degrading to no events.") }
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
        val overrides = calendarOverridesProvider()
        return withContext(Dispatchers.IO) {
            coRunCatching {
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
                query(startInclusive, endExclusive, zoneId, allDayOnly = true, overrides = overrides)
                    .filter { it.event.kind != EventKind.NORMAL }
                    .filter { it.date >= startInclusive && it.date < endExclusive }
                    .map { UpcomingCalendarEvent(it.date, it.event.title, it.event.kind, it.event.ownerAccount) }
                    .sortedWith(compareBy({ it.date }, { it.title }))
            }
                .onFailure { DiagLog.w(TAG, it, "Calendar query failed; degrading to no events.") }
                .getOrDefault(emptyList())
        }
    }

    /**
     * A classified event paired with the local date it falls on (its first
     * day, for a multi-day all-day event) and the exclusive end of its
     * date span. Timed rows always span a single date; all-day rows carry
     * their real UTC span so [eventsForDay] can match days after the first.
     */
    private data class DatedEvent(
        val date: LocalDate,
        val event: CalendarEvent,
        val endDateExclusive: LocalDate = date.plusDays(1),
    )

    @SuppressLint("MissingPermission")
    private fun query(
        startInclusive: LocalDate,
        endExclusive: LocalDate,
        zoneId: ZoneId,
        allDayOnly: Boolean = false,
        overrides: Map<String, Boolean> = emptyMap(),
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
            val endDateExclusive: LocalDate,
            val title: String,
            val start: LocalDateTime,
            val end: LocalDateTime,
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
                    DiagLog.w(TAG, e, "Provider rejected `eventType` column; future queries will skip it.")
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
                // pure all-day markers (anchored at their UTC date's midnight) and project the
                // rest into full local date-times in the user zone (dated by the begin instant
                // in that zone), so a midnight-crossing instance keeps its end on the next date.
                // Callers apply their own window filter on [date]: a single-day read drops
                // adjacent-day all-day bleed (an east-of-UTC zone overlaps the previous UTC
                // day), a forward-window read keeps every row inside its range.
                val (date, start, finish) = if (allDay) {
                    val eventDate = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                    Triple(eventDate, eventDate.atStartOfDay(), eventDate.atStartOfDay())
                } else {
                    val zonedBegin = Instant.ofEpochMilli(begin).atZone(zoneId).toLocalDateTime()
                    val zonedEnd = Instant.ofEpochMilli(end).atZone(zoneId).toLocalDateTime()
                    Triple(zonedBegin.toLocalDate(), zonedBegin, zonedEnd)
                }
                // A multi-day all-day event is one Instances row whose END is
                // the UTC midnight after its last day; carry that span so the
                // single-day read can match days after the first. Clamped to
                // at least one day against a malformed END at-or-before BEGIN.
                val endDateExclusive = if (allDay) {
                    Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                        .coerceAtLeast(date.plusDays(1))
                } else {
                    date.plusDays(1)
                }

                rows += Row(date, endDateExclusive, title, start, finish, location, allDay, calendarId, eventType, availability)
                if (calendarId >= 0) calendarIds += calendarId
            }
        }

        val metaByCalendarId = calendarMetaFor(calendarIds)

        // Tally classifications across the query so we can log one summary line
        // instead of one per row — a year-long upcomingCelebrations() over a
        // dense Google account otherwise floods the 300-line diag buffer with
        // dozens of repeated "Classified calendarId=X as Y" lines that all carry
        // the same per-calendar verdict. Title and owner stay out of the log
        // (both are PII); the summary names only the kind+reason counts.
        val classificationTally = mutableMapOf<Pair<EventKind, String>, Int>()
        val events = rows.mapNotNull { row ->
            val meta = metaByCalendarId[row.calendarId]
            // Per-calendar gate: an explicit override wins; otherwise follow the
            // calendar's visibility in the host calendar app. Unknown calendars
            // (metadata lookup failed) default to included so a lookup glitch
            // doesn't silently drop every event.
            val enabled = meta?.let { overrides[calendarKey(it)] ?: it.visible } ?: true
            if (!enabled) return@mapNotNull null
            val owner = meta?.ownerAccount
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
                endDateExclusive = row.endDateExclusive,
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
            DiagLog.i(TAG, "Classified %s events: %s", events.size, summary)
        }
        return events
    }

    /** Per-calendar metadata used for classification and the visibility gate. */
    private data class CalendarMeta(
        val ownerAccount: String?,
        val syncId: String?,
        val displayName: String?,
        val accountName: String?,
        val accountType: String?,
        val visible: Boolean,
    )

    /**
     * The per-calendar override key: the calendar's account scoped to its
     * stable provider id — `account/syncId`, where syncId is the sync adapter's
     * server-side id — falling back to `account/name` for local calendars with
     * no sync id. Account-scoped because the same holiday calendar synced into
     * two accounts shares one `_SYNC_ID` (e.g. `en.uk#holiday@…`); without the
     * account prefix the two would collapse to a single toggle. Never the local
     * row index, which is regenerated when an account is removed and re-added.
     */
    private fun calendarKey(meta: CalendarMeta): String {
        val account = meta.accountName.orEmpty()
        val tail = meta.syncId?.takeIf { it.isNotBlank() } ?: meta.displayName.orEmpty()
        val type = meta.accountType?.takeIf { it.isNotBlank() }
        // Android identifies an account by (name, type), so include the type:
        // the same email added under two providers (e.g. Google + Exchange) can
        // share an account name and even a sync id / display name, and without
        // the type the two calendars would collapse to one key.
        return if (type != null) "$type/$account/$tail" else "$account/$tail"
    }

    /**
     * Reads metadata for the given calendar [ids] — or every calendar on the
     * device when [ids] is null (used by [availableCalendars]).
     * `CalendarContract.Instances` doesn't expose these columns — they live on
     * the Calendars table — so this runs one secondary query. Returns an empty
     * map on permission/cursor failure; callers degrade gracefully
     * (classification falls back to NORMAL, the visibility gate to "included").
     */
    @SuppressLint("MissingPermission")
    private fun calendarMetaFor(ids: Set<Long>?): Map<Long, CalendarMeta> {
        if (ids != null && ids.isEmpty()) return emptyMap()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars._SYNC_ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.VISIBLE,
        )
        val selection = ids?.let { "${CalendarContract.Calendars._ID} IN (${it.joinToString(",") { "?" }})" }
        val selectionArgs = ids?.map { it.toString() }?.toTypedArray()
        val result = mutableMapOf<Long, CalendarMeta>()
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
                val syncIdx = cursor.getColumnIndex(CalendarContract.Calendars._SYNC_ID)
                val nameIdx = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIdx = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                val accountTypeIdx = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
                val visibleIdx = cursor.getColumnIndex(CalendarContract.Calendars.VISIBLE)
                while (cursor.moveToNext()) {
                    if (idIdx < 0) continue
                    result[cursor.getLong(idIdx)] = CalendarMeta(
                        ownerAccount = if (ownerIdx >= 0) cursor.getString(ownerIdx) else null,
                        syncId = if (syncIdx >= 0) cursor.getString(syncIdx) else null,
                        displayName = if (nameIdx >= 0) cursor.getString(nameIdx) else null,
                        accountName = if (accountIdx >= 0) cursor.getString(accountIdx) else null,
                        accountType = if (accountTypeIdx >= 0) cursor.getString(accountTypeIdx) else null,
                        // Column missing → assume visible so the gate fails open.
                        visible = visibleIdx < 0 || cursor.getInt(visibleIdx) != 0,
                    )
                }
            }
        }.onFailure { DiagLog.w(
            TAG,
            it,
            "Calendar metadata lookup failed; classification/visibility degrade gracefully.",
        ) }
        return result
    }

    override suspend fun availableCalendars(): List<CalendarInfo> {
        if (!CalendarPermission.isGranted(context)) {
            DiagLog.i(TAG, "READ_CALENDAR not granted; skipping calendar enumeration.")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            coRunCatching {
                calendarMetaFor(ids = null).values
                    .map { meta ->
                        CalendarInfo(
                            id = calendarKey(meta),
                            displayName = meta.displayName?.takeIf { it.isNotBlank() }
                                ?: meta.accountName?.takeIf { it.isNotBlank() }
                                ?: calendarKey(meta),
                            accountName = meta.accountName.orEmpty(),
                            accountType = meta.accountType,
                            visible = meta.visible,
                        )
                    }
                    .distinctBy { it.id }
                    .sortedWith(compareBy({ it.accountName.lowercase() }, { it.displayName.lowercase() }))
            }
                .onFailure { DiagLog.w(TAG, it, "Calendar enumeration failed; returning none.") }
                .getOrDefault(emptyList())
        }
    }

    private companion object {
        private const val TAG = "CalendarReader"

        /** Literal column name for `Events.EVENT_TYPE`; constant not in compileSdk 35. */
        private const val EVENT_TYPE_COLUMN = "eventType"
    }
}
