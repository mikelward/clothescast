package app.clothescast.ui.garment

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.ForecastPeriod.TODAY
import app.clothescast.core.domain.model.ForecastPeriod.TONIGHT
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * The forecast-window line on the outfit card: the span is the insight's own
 * hourly slice, ending an hour after its last hour and rolling past midnight
 * for the overnight window.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "en-rGB")
class OutfitCardWindowTest {

    // A Monday.
    private val monday = LocalDate.of(2026, 10, 5)
    private val tonightStart = LocalTime.of(19, 0)

    private fun range(period: ForecastPeriod, hourly: List<HourlyForecast>) =
        outfitCardWindowRange(monday, period, tonightStart, hourly)

    private fun window(
        period: ForecastPeriod,
        hourly: List<HourlyForecast>,
        format: TimeFormat,
        locale: Locale = Locale.UK,
    ) = outfitCardWindow(locale, monday, period, tonightStart, hourly, format)

    private fun hours(vararg hours: Int) = hours.map {
        HourlyForecast(
            time = LocalTime.of(it, 0),
            temperatureC = 15.0,
            feelsLikeC = 15.0,
            precipitationProbabilityPct = 0.0,
            condition = WeatherCondition.CLEAR,
        )
    }

    @Test
    fun `daytime window ends an hour after its last hour, same day`() {
        assertEquals(
            LocalDateTime.of(monday, LocalTime.of(7, 0)) to LocalDateTime.of(monday, LocalTime.of(19, 0)),
            range(TODAY, hours(*(7..18).toList().toIntArray())),
        )
    }

    @Test
    fun `overnight window rolls into the next day`() {
        val overnight = hours(*((19..23) + (0..6)).toIntArray())
        assertEquals(
            LocalDateTime.of(monday, LocalTime.of(19, 0)) to
                LocalDateTime.of(monday.plusDays(1), LocalTime.of(7, 0)),
            range(TONIGHT, overnight),
        )
    }

    @Test
    fun `sparse overnight with only pre-dawn hours stays on the next morning`() {
        // The mapper drops malformed or temperature-less hours, so a night can
        // lose everything before midnight; its remaining hours are still Tuesday's.
        assertEquals(
            LocalDateTime.of(monday.plusDays(1), LocalTime.of(3, 0)) to
                LocalDateTime.of(monday.plusDays(1), LocalTime.of(7, 0)),
            range(TONIGHT, hours(3, 4, 5, 6)),
        )
    }

    @Test
    fun `after-midnight tonight start keeps its hours on the period's own date`() {
        // A night-owl tonight time of 00:30 slices [00:30, 07:00) off the same
        // day, so tonightDateTime leaves every hour on forDate.
        assertEquals(
            LocalDateTime.of(monday, LocalTime.of(1, 0)) to LocalDateTime.of(monday, LocalTime.of(7, 0)),
            outfitCardWindowRange(monday, TONIGHT, LocalTime.of(0, 30), hours(1, 2, 3, 4, 5, 6)),
        )
    }

    @Test
    fun `window whose last hour is 23 ends at midnight the next day`() {
        assertEquals(
            LocalDateTime.of(monday.plusDays(1), LocalTime.MIDNIGHT),
            range(TONIGHT, hours(19, 20, 21, 22, 23))?.second,
        )
    }

    @Test
    fun `no hours means no window`() {
        assertNull(range(TONIGHT, emptyList()))
        assertNull(window(TONIGHT, emptyList(), TimeFormat.TWENTY_FOUR_HOUR))
    }

    @Test
    fun `formats both ends with the full date in 24h`() {
        assertEquals(
            "Mon 5 Oct 07:00 – Mon 5 Oct 19:00",
            window(TODAY, hours(*(7..18).toList().toIntArray()), TimeFormat.TWENTY_FOUR_HOUR),
        )
    }

    @Test
    fun `overnight end carries the next day's date`() {
        val overnight = hours(*((19..23) + (0..6)).toIntArray())
        assertEquals(
            "Mon 5 Oct 19:00 – Tue 6 Oct 07:00",
            window(TONIGHT, overnight, TimeFormat.TWENTY_FOUR_HOUR),
        )
    }

    @Test
    fun `follows the 12h setting`() {
        assertEquals(
            "Mon 5 Oct 7am – Mon 5 Oct 7pm",
            window(TODAY, hours(*(7..18).toList().toIntArray()), TimeFormat.TWELVE_HOUR),
        )
    }

    @Test
    fun `strips the weekday comma in a month-first locale too`() {
        // CLDR's US skeleton is "EEE, MMM d"; the card drops the comma there too.
        assertEquals(
            "Mon Oct 5 7am – Mon Oct 5 7pm",
            window(TODAY, hours(*(7..18).toList().toIntArray()), TimeFormat.TWELVE_HOUR, Locale.US),
        )
    }

    @Test
    fun `follows the locale it is given, not the app's`() {
        // The card's prose follows the Region setting, which can differ from
        // the phone's language; the window takes that region's locale. The
        // test context stays en-GB throughout.
        assertEquals(
            "Mo. 5. Okt. 07:00 – Mo. 5. Okt. 19:00",
            window(TODAY, hours(*(7..18).toList().toIntArray()), TimeFormat.TWENTY_FOUR_HOUR, Locale.GERMANY),
        )
    }

    @Test
    fun `uses the locale's own digits for the date as well as the time`() {
        // Persian writes ۰-۹; the day number must match the times rather than
        // fall back to Latin digits.
        val line = window(
            TODAY,
            hours(*(7..18).toList().toIntArray()),
            TimeFormat.TWENTY_FOUR_HOUR,
            Locale.forLanguageTag("fa-IR"),
        )!!
        assertTrue("expected Persian digits in \"$line\"", line.any { it in '۰'..'۹' })
        assertFalse("found Latin digits in \"$line\"", line.any { it in '0'..'9' })
    }
}
