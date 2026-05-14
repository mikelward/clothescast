package app.clothescast.ui.today

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for [niceStep] + [alignToStep] — the helpers that pin the
 * Today screen charts' y-axis to round-number ticks and snap bounds to the
 * nearest enclosing step multiples. These are what fix the "0, 13, 26" wind
 * axis and the phantom "14" sitting a step above a 10.3°C temperature peak.
 */
class ChartAxisTest {

    @Test
    fun `niceStep picks 1 for narrow spans`() {
        niceStep(1.0) shouldBe 1.0
        niceStep(4.0) shouldBe 1.0
    }

    @Test
    fun `niceStep picks 2 for typical temperature swings`() {
        niceStep(5.0) shouldBe 2.0
        niceStep(9.0) shouldBe 2.0
        niceStep(12.0) shouldBe 2.0
    }

    @Test
    fun `niceStep picks 5 for typical wind ranges`() {
        niceStep(15.0) shouldBe 5.0
        niceStep(26.0) shouldBe 5.0
        niceStep(30.0) shouldBe 5.0
    }

    @Test
    fun `niceStep picks 10 for very wide spans`() {
        niceStep(45.0) shouldBe 10.0
        niceStep(60.0) shouldBe 10.0
    }

    @Test
    fun `niceStep picks 25 for the 60-to-150 bucket`() {
        // Bucket added when the solar-radiation card landed — a 120 W/m²
        // overcast peak should read "0, 25, 50, 75, 100, 125" rather than
        // the old 20-step "0, 20, 40, 60, 80, 100, 120" which crowded labels.
        niceStep(80.0) shouldBe 25.0
        niceStep(120.0) shouldBe 25.0
        niceStep(150.0) shouldBe 25.0
    }

    @Test
    fun `niceStep picks larger buckets for solar radiation ranges`() {
        // Sunny summer peaks around 900 W/m²; the bucket layout should keep
        // tick counts to ~4-7 across the typical surface-irradiance range.
        niceStep(200.0) shouldBe 50.0
        niceStep(450.0) shouldBe 100.0
        niceStep(900.0) shouldBe 250.0
        niceStep(2000.0) shouldBe 500.0
    }

    @Test
    fun `alignToStep snaps bounds to step multiples`() {
        // Temp peak at 10.3°C — the case the user called out. Span ~5.3 falls
        // in the step-2 bucket; top tick lands at 12, not Vico's default 14.
        val bounds = alignToStep(rawMin = 5.0, rawMax = 10.3, step = 2.0)
        bounds.min shouldBe 4.0
        bounds.max shouldBe 12.0
        bounds.step shouldBe 2.0
    }

    @Test
    fun `alignToStep leaves exact multiples alone`() {
        val bounds = alignToStep(rawMin = 6.0, rawMax = 18.0, step = 2.0)
        bounds.min shouldBe 6.0
        bounds.max shouldBe 18.0
    }

    @Test
    fun `alignToStep handles wind 0 to 26 with step 5`() {
        // The user's wind chart: 26 km/h peak. Should yield 0..30 with step 5
        // so Vico renders ticks at 0, 5, 10, 15, 20, 25, 30 instead of 0, 13, 26.
        val bounds = alignToStep(rawMin = 0.0, rawMax = 26.0, step = 5.0)
        bounds.min shouldBe 0.0
        bounds.max shouldBe 30.0
    }

    @Test
    fun `alignToStep with negative min snaps below zero`() {
        // Cold morning at -3.5°C, afternoon at 6°C. Span ~10, step 2.
        val bounds = alignToStep(rawMin = -3.5, rawMax = 6.0, step = 2.0)
        bounds.min shouldBe -4.0
        bounds.max shouldBe 6.0
    }
}
