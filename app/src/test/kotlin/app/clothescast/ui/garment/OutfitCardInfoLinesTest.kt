package app.clothescast.ui.garment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.insight.InsightFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Unit coverage for the UV gating in [outfitCardInfoLines] — the logic that
 * decides whether the conditions strip surfaces a UV cell. Pins two things: the
 * rounding boundary where the gate and the label have to agree, and that the
 * strip reads the cross-model consensus (matching the UV chart) rather than a
 * lone primary-series spike when per-model data is present.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class OutfitCardInfoLinesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val formatter = InsightFormatter(ctx.resources)

    private fun hourWithUv(uv: Double?) = HourlyForecast(
        time = LocalTime.NOON,
        temperatureC = 18.0,
        feelsLikeC = 18.0,
        precipitationProbabilityPct = 0.0,
        condition = WeatherCondition.CLEAR,
        uvIndex = uv,
    )

    private fun perModelHour(uv: Double?) = PerModelHour(
        time = LocalDateTime.of(LocalDate.now(), LocalTime.NOON),
        apparentTemperatureC = 18.0,
        temperatureC = 18.0,
        precipitationProbabilityPct = null,
        uvIndex = uv,
    )

    /** Per-model arrays whose per-hour mean (one hour) is [modelUvs].average(). */
    private fun perModelHourly(vararg modelUvs: Double): PerModelHourly =
        PerModelHourly(
            byModel = modelUvs.mapIndexed { i, uv ->
                "model$i" to listOf(perModelHour(uv))
            }.toMap(),
        )

    private fun uvLabelFor(
        peakUv: Double?,
        perModelHourly: PerModelHourly? = null,
    ): String? =
        outfitCardInfoLines(
            ctx,
            formatter,
            listOf(hourWithUv(peakUv)),
            TemperatureUnit.CELSIUS,
            perModelHourly = perModelHourly,
        ).uvLabel

    // --- Primary-series fallback (no per-model data) ---

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

    // --- Consensus path (per-model data present) ---

    @Test
    fun `consensus peak overrides a lone primary-series spike`() {
        // The primary series spikes to 8 (would read "UV 8"), but the cross-model
        // consensus means out to 5 — below the notable threshold. The strip must
        // follow the consensus (the same blend the UV chart draws) and show
        // nothing, not the spike.
        assertNull(uvLabelFor(8.0, perModelHourly(5.0, 5.0)))
    }

    @Test
    fun `consensus peak surfaces when it clears the threshold`() {
        // Two models averaging 6.0 → "UV 6", even though the primary series here
        // carries no UV at all.
        assertEquals("UV 6", uvLabelFor(null, perModelHourly(5.5, 6.5)))
    }

    @Test
    fun `falls back to primary series when per-model carries no uv`() {
        // Per-model arrays present but none report UV → consensus is null, so the
        // strip falls back to the primary hourly peak.
        assertEquals("UV 7", uvLabelFor(7.0, perModelHourly()))
        assertEquals("UV 7", uvLabelFor(7.0, PerModelHourly(byModel = mapOf("m" to listOf(perModelHour(null))))))
    }
}
