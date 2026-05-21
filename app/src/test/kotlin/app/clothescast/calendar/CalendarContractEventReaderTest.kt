package app.clothescast.calendar

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.usecase.CalendarEventClassifier
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Robolectric coverage for [CalendarContractEventReader]'s classification path.
 * The classifier itself has its own pure-Kotlin tests; here we verify that the
 * reader correctly projects `CALENDAR_ID`, joins on `OWNER_ACCOUNT` from the
 * Calendars table, and passes both signals through to the classifier so the
 * resulting [app.clothescast.core.domain.model.CalendarEvent.kind] is right.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CalendarContractEventReaderTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val context: Context = application
    private val zone: ZoneId = ZoneOffset.UTC
    private val date: LocalDate = LocalDate.of(2026, 10, 24) // arbitrary; Diwali 2025-era
    private lateinit var provider: FakeCalendarProvider

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.READ_CALENDAR)
        provider = Robolectric.setupContentProvider(
            FakeCalendarProvider::class.java,
            CalendarContract.AUTHORITY,
        ) as FakeCalendarProvider
    }

    private fun allDayInstance(
        calendarId: Long,
        title: String,
        forDate: LocalDate = date,
        availability: Int = CalendarContract.Instances.AVAILABILITY_BUSY,
        eventType: Int? = null,
    ): FakeInstance = FakeInstance(
        calendarId = calendarId,
        title = title,
        allDay = true,
        beginMillis = forDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        endMillis = forDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        availability = availability,
        eventType = eventType,
    )

    @Test
    fun `classifies birthday-shaped title as BIRTHDAY`(): Unit = runBlocking {
        provider.calendars[10L] = "user@gmail.com"
        provider.instances += allDayInstance(
            calendarId = 10L,
            title = "Alice's birthday",
        )

        val events = CalendarContractEventReader(context).eventsForDay(date, zone)

        events shouldHaveSize 1
        events[0].title shouldBe "Alice's birthday"
        events[0].kind shouldBe EventKind.BIRTHDAY
        events[0].ownerAccount shouldBe "user@gmail.com"
    }

    @Test
    fun `classifies google holiday-calendar event as PUBLIC_HOLIDAY`(): Unit = runBlocking {
        provider.calendars[20L] = "en.indian#holiday@group.v.calendar.google.com"
        provider.instances += allDayInstance(
            calendarId = 20L,
            title = "Diwali",
        )

        val events = CalendarContractEventReader(context).eventsForDay(date, zone)

        events shouldHaveSize 1
        events[0].kind shouldBe EventKind.PUBLIC_HOLIDAY
        events[0].ownerAccount shouldBe "en.indian#holiday@group.v.calendar.google.com"
    }

    @Test
    fun `regular event with personal owner stays NORMAL`(): Unit = runBlocking {
        provider.calendars[30L] = "user@gmail.com"
        provider.instances += FakeInstance(
            calendarId = 30L,
            title = "Lunch with Sam",
            allDay = false,
            beginMillis = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            endMillis = date.atTime(13, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        )

        val events = CalendarContractEventReader(context).eventsForDay(date, zone)

        events shouldHaveSize 1
        events[0].kind shouldBe EventKind.NORMAL
    }

    @Test
    fun `mixed-source rows classify independently`(): Unit = runBlocking {
        provider.calendars[40L] = "user@gmail.com"
        provider.calendars[41L] = "en.usa#holiday@group.v.calendar.google.com"
        provider.instances += allDayInstance(40L, "Alice's birthday")
        provider.instances += allDayInstance(41L, "Thanksgiving")
        provider.instances += FakeInstance(
            40L,
            "Project review",
            allDay = false,
            beginMillis = date.atTime(15, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            endMillis = date.atTime(16, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        )

        val events = CalendarContractEventReader(context).eventsForDay(date, zone)

        events shouldHaveSize 3
        events.first { it.title == "Alice's birthday" }.kind shouldBe EventKind.BIRTHDAY
        events.first { it.title == "Thanksgiving" }.kind shouldBe EventKind.PUBLIC_HOLIDAY
        events.first { it.title == "Project review" }.kind shouldBe EventKind.NORMAL
    }

    @Test
    fun `FREE public-holiday row is preserved`(): Unit = runBlocking {
        // Google's "Holidays in <country>" calendars sync their events as
        // AVAILABILITY_FREE — holidays are informational, not actual busy time.
        // The classifier exists to recognise these, so the FREE filter must run
        // after classification, not before.
        provider.calendars[70L] = "en.uk#holiday@group.v.calendar.google.com"
        provider.instances += allDayInstance(
            calendarId = 70L,
            title = "Christmas Day",
            availability = CalendarContract.Instances.AVAILABILITY_FREE,
        )

        val events = CalendarContractEventReader(context).eventsForDay(date, zone)

        events shouldHaveSize 1
        events[0].kind shouldBe EventKind.PUBLIC_HOLIDAY
    }

    @Test
    fun `FREE birthday row is preserved`(): Unit = runBlocking {
        // Google's auto-Birthdays calendar (and the new Birthday-event-type
        // entries on the primary calendar) sync as FREE for the same reason.
        provider.calendars[71L] = "user@gmail.com"
        provider.instances += allDayInstance(
            calendarId = 71L,
            title = "Alice's birthday",
            availability = CalendarContract.Instances.AVAILABILITY_FREE,
        )

        val events = CalendarContractEventReader(context).eventsForDay(date, zone)

        events shouldHaveSize 1
        events[0].kind shouldBe EventKind.BIRTHDAY
    }

    @Test
    fun `FREE normal row is still dropped`(): Unit = runBlocking {
        // Preserves the existing behaviour for actual user-held FREE blocks
        // (lunch holds, focus time markers, etc.) that the insight pipeline
        // doesn't want surfaced as real meetings.
        provider.calendars[72L] = "user@gmail.com"
        provider.instances += FakeInstance(
            calendarId = 72L,
            title = "Focus time",
            allDay = false,
            beginMillis = date.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            endMillis = date.atTime(11, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            availability = CalendarContract.Instances.AVAILABILITY_FREE,
        )

        val events = CalendarContractEventReader(context).eventsForDay(date, zone)

        events shouldHaveSize 0
    }

    @Test
    fun `non-english title with eventType BIRTHDAY classifies as BIRTHDAY`(): Unit = runBlocking {
        // Closes the locale gap on devices that expose the eventType column:
        // the title's not English-shaped but the row carries the explicit type.
        provider.calendars[50L] = "user@gmail.com"
        provider.instances += allDayInstance(
            calendarId = 50L,
            title = "Anniversaire de Marie",
            eventType = CalendarEventClassifier.EVENT_TYPE_BIRTHDAY,
        )

        val events = CalendarContractEventReader(context).eventsForDay(date, zone)

        events shouldHaveSize 1
        events[0].kind shouldBe EventKind.BIRTHDAY
    }

    @Test
    fun `provider rejecting eventType column retries with stable projection`(): Unit = runBlocking {
        // Strict providers (older Android, non-Google) throw IllegalArgumentException
        // when asked to project a column they don't know. The reader must catch
        // that and retry without `eventType` — otherwise the day's events vanish
        // entirely instead of degrading to title-regex-only birthday detection.
        provider.exposeEventTypeColumn = false
        provider.calendars[60L] = "user@gmail.com"
        provider.instances += allDayInstance(
            calendarId = 60L,
            title = "Alice's birthday",
        )
        provider.instances += FakeInstance(
            calendarId = 60L,
            title = "Lunch with Sam",
            allDay = false,
            beginMillis = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            endMillis = date.atTime(13, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        )

        val events = CalendarContractEventReader(context).eventsForDay(date, zone)

        events shouldHaveSize 2
        events.first { it.title == "Alice's birthday" }.kind shouldBe EventKind.BIRTHDAY
        events.first { it.title == "Lunch with Sam" }.kind shouldBe EventKind.NORMAL
    }

    @Test
    fun `yesterday's all-day birthday does not leak into today's query in a UTC-east zone`(): Unit = runBlocking {
        // CalendarContract.Instances returns events whose [begin, end] overlap the
        // query window. All-day rows are stored as UTC midnight-to-midnight, so for
        // a London user (BST = UTC+1) yesterday's all-day event (end = today UTC
        // midnight) overlaps the start of today's local-day query window by 1 hour.
        // Without filtering by UTC date, yesterday's birthday wins the BEGIN-ASC sort
        // and themes today's outfit instead of an actual today birthday.
        val london = ZoneId.of("Europe/London")
        val today = LocalDate.of(2026, 5, 18)
        provider.calendars[80L] = "user@gmail.com"
        provider.instances += allDayInstance(80L, "Yesterday's birthday", forDate = today.minusDays(1))
        provider.instances += allDayInstance(80L, "Test's birthday", forDate = today)

        val events = CalendarContractEventReader(context).eventsForDay(today, london)

        events shouldHaveSize 1
        events[0].title shouldBe "Test's birthday"
        events[0].kind shouldBe EventKind.BIRTHDAY
    }

    @Test
    fun `upcomingCelebrations lists all-day birthdays and holidays in date order, excluding timed and out-of-window events`(): Unit = runBlocking {
        // The Celebrations settings listing reads a forward window and surfaces
        // only birthdays + public holidays, chronologically. It narrows to
        // all-day rows at the provider level, so a timed birthday entry is
        // intentionally excluded here (it still themes the day via eventsForDay —
        // see the TODO in upcomingCelebrations). The fake provider ignores the
        // URI's begin/end range, so the reader's own date filter is what keeps
        // the far-future holiday out of the one-year window.
        provider.calendars[10L] = "user@gmail.com"
        provider.calendars[20L] = "en.usa#holiday@group.v.calendar.google.com"
        val start = LocalDate.of(2026, 5, 21)
        provider.instances += allDayInstance(20L, "Independence Day", forDate = LocalDate.of(2026, 7, 4))
        provider.instances += allDayInstance(10L, "Alex's birthday", forDate = LocalDate.of(2026, 6, 3))
        provider.instances += allDayInstance(20L, "Out of range holiday", forDate = LocalDate.of(2030, 1, 1))
        provider.instances += FakeInstance(
            calendarId = 10L,
            title = "Dana's birthday", // timed — dropped by the all-day narrowing
            allDay = false,
            beginMillis = LocalDate.of(2026, 5, 25).atTime(18, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            endMillis = LocalDate.of(2026, 5, 25).atTime(19, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        provider.instances += FakeInstance(
            calendarId = 10L,
            title = "Project review",
            allDay = false,
            beginMillis = LocalDate.of(2026, 6, 10).atTime(15, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            endMillis = LocalDate.of(2026, 6, 10).atTime(16, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        )

        val events = CalendarContractEventReader(context).upcomingCelebrations(start, start.plusYears(1), zone)

        events shouldHaveSize 2
        events[0].title shouldBe "Alex's birthday"
        events[0].kind shouldBe EventKind.BIRTHDAY
        events[1].title shouldBe "Independence Day"
        events[1].kind shouldBe EventKind.PUBLIC_HOLIDAY
    }

    @Test
    fun `missing owner account falls through to NORMAL`(): Unit = runBlocking {
        // No row in the Calendars table for this id — owner lookup returns null,
        // classifier falls back to NORMAL since title isn't birthday-shaped.
        provider.instances += FakeInstance(
            calendarId = 99L,
            title = "Team standup",
            allDay = false,
            beginMillis = date.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            endMillis = date.atTime(9, 30).toInstant(ZoneOffset.UTC).toEpochMilli(),
        )

        val events = CalendarContractEventReader(context).eventsForDay(date, zone)

        events shouldHaveSize 1
        events[0].kind shouldBe EventKind.NORMAL
        events[0].ownerAccount shouldBe null
    }
}

data class FakeInstance(
    val calendarId: Long,
    val title: String,
    val allDay: Boolean = false,
    val beginMillis: Long = 0L,
    val endMillis: Long = 0L,
    val location: String? = null,
    val status: Int = CalendarContract.Instances.STATUS_CONFIRMED,
    val availability: Int = CalendarContract.Instances.AVAILABILITY_BUSY,
    val eventType: Int? = null,
)

/**
 * Minimal ContentProvider stand-in for [CalendarContract]. The reader makes
 * exactly two query types: an Instances range query (path starts with
 * `instances/when/<begin>/<end>`) and a Calendars selection query (path is
 * `calendars`). Both are routed by inspecting the path; everything else is
 * unimplemented because the production reader doesn't call it.
 */
class FakeCalendarProvider : ContentProvider() {
    val instances: MutableList<FakeInstance> = mutableListOf()
    val calendars: MutableMap<Long, String?> = mutableMapOf()

    /**
     * When false, the fake instance provider rejects the `eventType` column
     * with `IllegalArgumentException` — exactly how the real strict Android
     * provider behaves on devices that don't know the column. The reader is
     * expected to catch this, retry with the stable projection, and still
     * surface events (just without the locale-independent birthday signal).
     */
    var exposeEventTypeColumn: Boolean = true

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val cols = projection ?: return null
        return when {
            uri.pathSegments.firstOrNull() == "instances" -> {
                if (!exposeEventTypeColumn && cols.any { it == "eventType" }) {
                    throw IllegalArgumentException("Invalid column eventType")
                }
                // Honour the `allDay = 1` selection the celebrations read passes,
                // so tests can verify the all-day narrowing actually drops timed
                // rows at the provider level (the daily read passes no selection).
                val allDayOnly = selection?.contains(CalendarContract.Instances.ALL_DAY) == true
                val visible = if (allDayOnly) instances.filter { it.allDay } else instances
                val cursor = MatrixCursor(cols)
                visible.forEach { inst ->
                    cursor.addRow(cols.map { col -> instanceValue(inst, col) }.toTypedArray())
                }
                cursor
            }
            uri.pathSegments.firstOrNull() == "calendars" -> {
                val cursor = MatrixCursor(cols)
                val ids = parseIdSelection(selection, selectionArgs)
                calendars.entries
                    .filter { ids == null || it.key in ids }
                    .forEach { (id, owner) ->
                        cursor.addRow(cols.map { col -> calendarValue(id, owner, col) }.toTypedArray())
                    }
                cursor
            }
            else -> null
        }
    }

    private fun instanceValue(inst: FakeInstance, col: String): Any? = when (col) {
        CalendarContract.Instances.TITLE -> inst.title
        CalendarContract.Instances.BEGIN -> inst.beginMillis
        CalendarContract.Instances.END -> inst.endMillis
        CalendarContract.Instances.EVENT_LOCATION -> inst.location
        CalendarContract.Instances.ALL_DAY -> if (inst.allDay) 1 else 0
        CalendarContract.Instances.STATUS -> inst.status
        CalendarContract.Instances.AVAILABILITY -> inst.availability
        CalendarContract.Instances.CALENDAR_ID -> inst.calendarId
        "eventType" -> inst.eventType
        else -> null
    }

    private fun calendarValue(id: Long, owner: String?, col: String): Any? = when (col) {
        CalendarContract.Calendars._ID -> id
        CalendarContract.Calendars.OWNER_ACCOUNT -> owner
        else -> null
    }

    private fun parseIdSelection(selection: String?, args: Array<out String>?): Set<Long>? {
        if (selection == null || args == null) return null
        return args.mapNotNull { it.toLongOrNull() }.toSet()
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
