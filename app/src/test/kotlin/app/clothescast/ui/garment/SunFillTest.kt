package app.clothescast.ui.garment

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SunFillTest {

    @Test
    fun `an empty sun fills nothing and a full one fills every ray`() {
        sunLiquidTopY(0f) shouldBe 24f
        sunLiquidTopY(1f) shouldBe 0f
    }

    @Test
    fun `half the sunshine fills to the middle of the disc`() {
        sunLiquidTopY(0.5f) shouldBe (11.5f plusOrMinus 0.01f)
    }

    @Test
    fun `the fill line tracks the disc's area, not its height`() {
        // A quarter of a disc's area lies below about 30% of its height, so a
        // quarter-sunny day reads clearly less than a third of the way up.
        val top = sunLiquidTopY(0.25f)
        val heightFilled = (17.5f - top) / 12f
        heightFilled shouldBe (0.298f plusOrMinus 0.01f)
    }

    @Test
    fun `the fill rises as the sunshine does`() {
        listOf(0.1f, 0.3f, 0.6f, 0.9f).zipWithNext().forEach { (lower, higher) ->
            (sunLiquidTopY(higher) < sunLiquidTopY(lower)) shouldBe true
        }
    }
}
