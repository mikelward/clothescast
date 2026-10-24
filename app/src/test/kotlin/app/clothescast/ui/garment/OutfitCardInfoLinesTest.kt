package app.clothescast.ui.garment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.insight.InsightFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalTime

/**
 * Unit coverage for the rain / UV / wind gating in [outfitCardInfoLines] — the
 * logic that decides whether the conditions strip surfaces each cell. The hourly
 * series fed in is already the cross-model consensus blend (wind / UV folded in
 * at fetch time — see blendConsensusHourly), so the strip just takes the peak
 * over it; these tests pin the thresholds, the rain probability gate, and the
 * UV rounding boundary.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class OutfitCardInfoLinesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val formatter = InsightFormatter(ctx.resources)

    private fun hour(uv: Double? = null, wind: Double? = null, gust: Double? = null) = HourlyForecast(
        time = LocalTime.NOON,
        temperatureC = 18.0,
        feelsLikeC = 18.0,
        precipitationProbabilityPct = 0.0,
        condition = WeatherCondition.CLEAR,
        windSpeedKmh = wind,
        windGustKmh = gust,
        uvIndex = uv,
    )

    private fun infoFor(hourly: List<HourlyForecast>) =
        outfitCardInfoLines(ctx, formatter, hourly, TemperatureUnit.CELSIUS)

    private fun uvLabelFor(peakUv: Double?): String? = infoFor(listOf(hour(uv = peakUv))).uvLabel

    private fun windLabelFor(peakWind: Double?, peakGust: Double? = null): String? =
        infoFor(listOf(hour(wind = peakWind, gust = peakGust))).windLabel

    private fun rainHour(pct: Double, condition: WeatherCondition = WeatherCondition.CLEAR) =
        hour().copy(precipitationProbabilityPct = pct, condition = condition)

    private fun rainLineFor(pct: Double, condition: WeatherCondition = WeatherCondition.CLEAR): String? =
        infoFor(listOf(rainHour(pct, condition))).rainLineShort

    // --- Rain (shown at >= 10% blended-consensus peak chance of rain) ---

    @Test
    fun `rain cell shows at or above the chance-of-rain gate`() {
        // Inclusive at the 10% bar — the same chance-of-rain bar the prose and
        // umbrella default key off.
        assertNotNull(rainLineFor(10.0))
        assertNotNull(rainLineFor(60.0))
    }

    @Test
    fun `rain cell hides below the gate`() {
        assertNull(rainLineFor(9.0))
        assertNull(rainLineFor(0.0))
    }

    @Test
    fun `rain cell keys on probability, not the weather code`() {
        // No code arm: a drizzle / rain code below the 10% chance bar doesn't
        // light the cell, and the chance is what's shown.
        assertNull(rainLineFor(5.0, WeatherCondition.DRIZZLE))
        assertNull(rainLineFor(8.0, WeatherCondition.RAIN))
        assertNotNull(rainLineFor(15.0, WeatherCondition.RAIN))
    }

    @Test
    fun `rain cell stays hidden for snow even above the chance gate`() {
        // The probability field is general precipitation; snow maps to SNOW. A
        // rain droplet would mislead, so a snowy hour keeps the cell hidden both
        // below and above the gate.
        assertNull(rainLineFor(9.0, WeatherCondition.SNOW))
        assertNull(rainLineFor(25.0, WeatherCondition.SNOW))
        assertNull(rainLineFor(80.0, WeatherCondition.SNOW))
    }

    // --- UV (notable >= 6, gated on the rounded peak) ---

    @Test
    fun `uv that rounds up to the notable threshold surfaces as UV 6`() {
        // Regression: a 5.5–5.9 peak rounds to "UV 6" but was hidden because the
        // raw value sat below UV_NOTABLE (6.0). The cell must appear here.
        assertEquals("UV 6", uvLabelFor(5.7))
        assertEquals("UV 6", uvLabelFor(5.5))
    }

    @Test
    fun `uv that rounds below the threshold stays hidden`() {
        assertNull(uvLabelFor(5.49))
        assertNull(uvLabelFor(4.0))
    }

    @Test
    fun `uv at and above the threshold surfaces`() {
        assertEquals("UV 6", uvLabelFor(6.0))
        assertEquals("UV 9", uvLabelFor(9.0))
    }

    @Test
    fun `missing uv data surfaces no cell`() {
        assertNull(uvLabelFor(null))
    }

    // --- Wind (notable >= 30 km/h) ---

    @Test
    fun `wind surfaces above the notable threshold`() {
        assertEquals("35 km/h", windLabelFor(35.0))
    }

    @Test
    fun `wind below the threshold stays hidden`() {
        assertNull(windLabelFor(29.0))
        assertNull(windLabelFor(null))
    }

    // --- Gusts (notable >= 50 km/h, or the mean's 30) ---

    @Test
    fun `a notable gust shows wind under a moderate mean, naming the gust`() {
        // A gusty evening: 25 km/h mean, 55 km/h gusts.
        assertEquals("Gusts 55 km/h", windLabelFor(25.0, peakGust = 55.0))
        assertEquals("Gusts 50 km/h", windLabelFor(null, peakGust = 50.0))
        assertNull(windLabelFor(25.0, peakGust = 49.0))
    }

    @Test
    fun `a notable mean shows wind with its gust where there is one`() {
        assertEquals("Gusts 45 km/h", windLabelFor(32.0, peakGust = 45.0))
    }

    @Test
    fun `gusts tint as the mean wind they usually come with`() {
        // 75 km/h gusts on a 25 km/h mean read as a 50 km/h mean: orange.
        val info = infoFor(listOf(hour(wind = 25.0, gust = 75.0)))
        assertEquals(50.0, info.windMaxKmh!!, 0.001)
        assertEquals(0xFFEF6C00.toInt(), windScaleColorArgb(info.windMaxKmh!!))
    }

    // --- A wind alert shows the wind cell whatever the forecast ---

    @Test
    fun `a wind alert shows wind below the thresholds, and no alert leaves it hidden`() {
        val calm = infoFor(listOf(hour(wind = 12.0, gust = 25.0)))
        assertNull(calm.windLabel)
        assertEquals("Gusts 25 km/h", calm.withAlerts(AlertLevel.YELLOW, wind = true).windLabel)
        assertNull(calm.withAlerts(AlertLevel.YELLOW, wind = false).windLabel)
        assertEquals(AlertLevel.YELLOW, calm.withAlerts(AlertLevel.YELLOW, wind = false).alertLevel)
        // No wind in the forecast at all: nothing to show.
        assertNull(infoFor(listOf(hour())).withAlerts(AlertLevel.RED, wind = true).windLabel)
    }
}
