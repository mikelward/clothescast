package app.clothescast.widget

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards [conditionsStripRenderSize]: the conditions strip is a wide, short band,
 * so a tall cell (most visibly a lock-screen host handing it a near-square card)
 * must not render the thermometer/label at the cell's full height. The height is
 * clamped so the bitmap stays at least 3:1, and a cell already wider than that —
 * the 3x1 home-screen default — is left untouched.
 */
class ConditionsStripRenderSizeTest {

    @Test
    fun `a cell wider than the floor renders at its own size`() {
        // The default 3x1 placement is ~4.5:1 — already wider than the floor, so
        // nothing is clamped and the strip fills the cell as before.
        conditionsStripRenderSize(widthPx = 900, heightPx = 200) shouldBe (900 to 200)
    }

    @Test
    fun `a near-square lock-screen cell is clamped to the strip aspect`() {
        // The regression: a large near-square cell rendered at its own aspect
        // ballooned the thermometer. Height is capped to width / 3.
        conditionsStripRenderSize(widthPx = 600, heightPx = 600) shouldBe (600 to 200)
    }

    @Test
    fun `a tall cell is clamped, width preserved`() {
        conditionsStripRenderSize(widthPx = 450, heightPx = 900) shouldBe (450 to 150)
    }

    @Test
    fun `a cell exactly at the floor is unchanged`() {
        conditionsStripRenderSize(widthPx = 600, heightPx = 200) shouldBe (600 to 200)
    }

    @Test
    fun `degenerate sizes stay positive`() {
        val (w, h) = conditionsStripRenderSize(widthPx = 0, heightPx = 0)
        w shouldBe 1
        h shouldBe 1
    }
}
