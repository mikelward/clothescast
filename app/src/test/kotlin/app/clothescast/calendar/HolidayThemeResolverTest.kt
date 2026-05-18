package app.clothescast.calendar

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.repository.CalendarEventReader
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Covers the shared theme-resolver used by [app.clothescast.work.FetchAndNotifyWorker]
 * (scheduled MQTT image publish) and [app.clothescast.MainActivity] (manual
 * "Publish now" button). Before the helper existed the manual path skipped
 * theme resolution entirely, so a user's birthday calendar event made the
 * notification yellow/magenta but pressing "Publish now" overwrote the
 * retained MQTT image with the unthemed user defaults.
 */
class HolidayThemeResolverTest {
    private val basePrefs = UserPreferences(
        schedule = Schedule(time = LocalTime.of(7, 0), days = Schedule.EVERY_DAY, zoneId = ZoneId.of("UTC")),
        deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        temperatureUnit = TemperatureUnit.CELSIUS,
        distanceUnit = DistanceUnit.KILOMETERS,
        clothesRules = emptyList(),
    )

    @Test
    fun `birthday event resolves to a synthetic birthday theme`() = runBlocking {
        val prefs = basePrefs.copy(themeFromCalendarBirthdays = true)
        val reader = object : CalendarEventReader {
            override suspend fun eventsForDay(date: LocalDate, zoneId: ZoneId): List<CalendarEvent> =
                listOf(
                    CalendarEvent(
                        title = "Alex's birthday",
                        start = LocalTime.MIDNIGHT,
                        end = LocalTime.MIDNIGHT,
                        allDay = true,
                        kind = EventKind.BIRTHDAY,
                    ),
                )
        }

        val theme = resolveHolidayTheme(prefs, reader)

        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.BIRTHDAY
        (theme.topOverrides.isNotEmpty()) shouldBe true
        (theme.bottomOverrides.isNotEmpty()) shouldBe true
    }

    @Test
    fun `calendar reader is skipped when both toggles are off`() = runBlocking {
        val prefs = basePrefs.copy(
            themeFromCalendarBirthdays = false,
            themeFromCalendarHolidays = false,
        )
        val reader = object : CalendarEventReader {
            override suspend fun eventsForDay(date: LocalDate, zoneId: ZoneId): List<CalendarEvent> =
                error("reader must not be queried when both calendar toggles are off")
        }

        resolveHolidayTheme(prefs, reader).shouldBeNull()
    }

    @Test
    fun `reader failure falls through to no theme rather than propagating`() = runBlocking {
        val prefs = basePrefs.copy(themeFromCalendarBirthdays = true)
        val reader = object : CalendarEventReader {
            override suspend fun eventsForDay(date: LocalDate, zoneId: ZoneId): List<CalendarEvent> =
                throw SecurityException("READ_CALENDAR not granted")
        }

        resolveHolidayTheme(prefs, reader).shouldBeNull()
    }
}
