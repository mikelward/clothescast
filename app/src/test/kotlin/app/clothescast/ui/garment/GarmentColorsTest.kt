package app.clothescast.ui.garment

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure-JVM unit tests for [deriveStrokeArgb]. Avoid the
 * `androidx.core.graphics.ColorUtils` round-trip in assertions because the
 * project's `unitTests.isReturnDefaultValues = true` config stubs every
 * `android.graphics.Color.*` static to return 0. Components are extracted
 * by bit math.
 */
class GarmentColorsTest {

    @Test
    fun `deriveStrokeArgb darkens the fill colour`() {
        // tshirt's baked colours: #4FC3F7 fill → #0288D1 stroke. The derived
        // stroke should be visibly darker than the fill while staying
        // recognisably the same hue family — sanity-checked here by:
        //  - mean RGB drops (darker overall),
        //  - blue channel still dominates (hue family preserved).
        val fill = 0xFF4FC3F7L
        val derived = deriveStrokeArgb(fill)
        val (fr, fg, fb) = derived.rgb()
        val (orR, orG, orB) = fill.rgb()
        meanOf(fr, fg, fb) shouldBeLessThan meanOf(orR, orG, orB)
        fb shouldBeGreaterThan fr  // blue channel still dominates red
        fb shouldBeGreaterThan fg  // blue channel still dominates green
    }

    @Test
    fun `deriveStrokeArgb is roughly 60 percent of fill lightness for mid-saturated colours`() {
        // Spot-check across the wheel — the project's preset palette is mostly
        // saturated mid-lightness colours, so the L scale is what determines
        // whether derived strokes look like the originals.
        val pairs = listOf(
            0xFFFF7043L to 0.55f,  // orange
            0xFF1E88E5L to 0.55f,  // blue
            0xFF43A047L to 0.55f,  // green
            0xFFEC407AL to 0.55f,  // pink
        )
        for ((fill, ratio) in pairs) {
            val derived = deriveStrokeArgb(fill)
            val (fr, fg, fb) = fill.rgb()
            val (dr, dg, db) = derived.rgb()
            val fillMean = meanOf(fr, fg, fb)
            val derivedMean = meanOf(dr, dg, db)
            val actualRatio = derivedMean.toFloat() / fillMean.toFloat()
            // Loose tolerance — RGB mean isn't a perfect proxy for HSL L,
            // but it tracks well enough on saturated mid-lightness colours.
            (actualRatio - ratio).let { delta ->
                check(delta in -0.20f..0.20f) {
                    "Derived/fill mean ratio for ${fill.toString(16)} = $actualRatio, expected ~$ratio (Δ=$delta)"
                }
            }
        }
    }

    @Test
    fun `deriveStrokeArgb preserves alpha`() {
        val fullyOpaque = deriveStrokeArgb(0xFFFF0000L)
        ((fullyOpaque ushr 24) and 0xFFL) shouldBe 0xFFL

        // A non-opaque fill keeps its alpha through the derivation. We don't
        // emit alpha-blended garments today, but the contract holds.
        val translucent = deriveStrokeArgb(0x80FF0000L)
        ((translucent ushr 24) and 0xFFL) shouldBe 0x80L
    }

    @Test
    fun `deriveStrokeArgb clamps near-black fills above the floor`() {
        // A near-black input would scale L to ~0; the clamp keeps the stroke
        // visibly distinguishable from a very dark fill.
        val nearBlack = 0xFF050505L
        val derived = deriveStrokeArgb(nearBlack)
        val (r, g, b) = derived.rgb()
        // L=0.08 → mid-grey at ~20/255 per channel, allow +/- a few from the
        // round-trip rounding.
        meanOf(r, g, b) shouldBeGreaterThanOrEqual 16
        meanOf(r, g, b) shouldBeLessThanOrEqual 25
    }

    @Test
    fun `deriveStrokeArgb returns a Long that fits in 32 bits unsigned`() {
        val derived = deriveStrokeArgb(0xFFFF0000L)
        // No bits above the alpha byte — i.e. the Long is in the unsigned-Int range.
        (derived ushr 32) shouldBe 0L
    }
}

private fun Long.rgb(): Triple<Int, Int, Int> = Triple(
    ((this ushr 16) and 0xFFL).toInt(),
    ((this ushr 8) and 0xFFL).toInt(),
    (this and 0xFFL).toInt(),
)

private fun meanOf(a: Int, b: Int, c: Int): Int = (a + b + c) / 3
