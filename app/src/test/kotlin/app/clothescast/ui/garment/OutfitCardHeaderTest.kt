package app.clothescast.ui.garment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WindSpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * The outfit card's header: the period's scheduled time, prefixed by the
 * Smart Home name when one is set.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class OutfitCardHeaderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("UTC")

    private val prefs = UserPreferences(
        schedule = Schedule(time = LocalTime.of(7, 0), days = Schedule.EVERY_DAY, zoneId = zone),
        tonightSchedule = Schedule(time = LocalTime.of(22, 30), days = Schedule.EVERY_DAY, zoneId = zone),
        deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        temperatureUnit = TemperatureUnit.CELSIUS,
        windSpeedUnit = WindSpeedUnit.KMH,
        clothesRules = emptyList(),
        timeFormat = TimeFormat.TWELVE_HOUR,
    )

    private fun header(period: ForecastPeriod, p: UserPreferences = prefs) =
        outfitCardHeader(context, period, p, Locale.US)

    @Test
    fun `unset name shows only the time`() {
        assertEquals("7am ClothesCast", header(ForecastPeriod.TODAY))
    }

    @Test
    fun `blank name is treated as unset`() {
        assertEquals("7am ClothesCast", header(ForecastPeriod.TODAY, prefs.copy(customName = "  ")))
    }

    @Test
    fun `set name leads the header exactly as typed`() {
        assertEquals(
            "Alex's 7am ClothesCast",
            header(ForecastPeriod.TODAY, prefs.copy(customName = "Alex's")),
        )
    }

    @Test
    fun `tonight uses the tonight schedule time`() {
        assertEquals("10:30pm ClothesCast", header(ForecastPeriod.TONIGHT))
        assertEquals(
            "Sam's 10:30pm ClothesCast",
            header(ForecastPeriod.TONIGHT, prefs.copy(customName = "Sam's")),
        )
    }

    @Test
    fun `24-hour format renders the time as HH-mm`() {
        val p = prefs.copy(timeFormat = TimeFormat.TWENTY_FOUR_HOUR)
        assertEquals("07:00 ClothesCast", header(ForecastPeriod.TODAY, p))
        assertEquals("22:30 ClothesCast", header(ForecastPeriod.TONIGHT, p))
    }
}
