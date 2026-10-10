package app.clothescast.widget

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [scaleRenderSize] keeps the widget chart bitmap at the cell's own aspect. It
 * used to clamp to 2:1–4:1, which letterboxed the chart in a squarer or wider
 * cell and left it squashed between empty bands.
 */
class ChartRenderSizeTest {

    @Test
    fun `an in-bounds cell renders at its own size`() {
        scaleRenderSize(900, 500) shouldBe (900 to 500)
    }

    @Test
    fun `a squarer cell keeps its aspect instead of being forced to 2 to 1`() {
        scaleRenderSize(800, 600) shouldBe (800 to 600)
    }

    @Test
    fun `an oversized cell scales down to the cap preserving aspect`() {
        scaleRenderSize(3200, 1200) shouldBe (1600 to 600)
    }

    @Test
    fun `a small cell scales up to the floor preserving aspect`() {
        scaleRenderSize(480, 120) shouldBe (960 to 240)
    }

    @Test
    fun `an extreme aspect never exceeds the cap`() {
        // 30:1 — the 240px floor on the short side would push the long side to
        // 7200px, so the cap wins: 1600 wide, height scaled by the same factor.
        scaleRenderSize(1500, 50) shouldBe (1600 to 53)
    }

    @Test
    fun `each orientation's cell picks the bitmap rendered for it`() {
        // A launcher listing a short-wide landscape cell and a tall portrait
        // one: the portrait composition must get the tall bitmap, not the
        // letterboxed wide one.
        val sizes = listOf(356f to 55f, 300f to 220f)
        closestSizeIndex(sizes, 300f, 220f) shouldBe 1
        closestSizeIndex(sizes, 356f, 55f) shouldBe 0
    }

    @Test
    fun `a cell slightly off a listed size picks the nearest`() {
        val sizes = listOf(400f to 120f, 260f to 300f)
        closestSizeIndex(sizes, 262f, 296f) shouldBe 1
    }

    @Test
    fun `no rendered sizes picks nothing`() {
        closestSizeIndex(emptyList(), 300f, 200f) shouldBe -1
    }

    @Test
    fun `cell sizes drop non-positive and duplicate entries and cap the count`() {
        distinctCellSizes(
            listOf(0f to 100f, 300f to 200f, 300.2f to 199.8f, 100f to -1f, 1f to 1f, 2f to 2f, 3f to 3f, 4f to 4f),
        ) shouldBe listOf(300f to 200f, 1f to 1f, 2f to 2f, 3f to 3f)
    }
}
