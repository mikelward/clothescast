package app.clothescast.ui.garment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private fun hour(uv: Double? = null, wind: Double? = null) = HourlyForecast(
        time = LocalTime.NOON,
        temperatureC = 18.0,
        feelsLikeC = 18.0,
        precipitationProbabilityPct = 0.0,
        condition = WeatherCondition.CLEAR,
        windSpeedKmh = wind,
        uvIndex = uv,
    )

    private fun infoFor(hourly: List<HourlyForecast>) =
        outfitCardInfoLines(ctx, formatter, hourly, TemperatureUnit.CELSIUS)

    private fun uvLabelFor(peakUv: Double?): String? = infoFor(listOf(hour(uv = peakUv))).uvLabel

    private fun windLabelFor(peakWind: Double?): String? = infoFor(listOf(hour(wind = peakWind))).windLabel

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
}
