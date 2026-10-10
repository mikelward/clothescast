package app.clothescast.widget

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
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

    @Test
    fun `a cell rendered at its own pixels keeps the device density`() {
        // 300x200 dp across 450x300 px is 1.5x, the 240 dpi bucket.
        renderDensityDpi(widthPx = 450, heightPx = 300, widthDp = 300f) shouldBe 240
    }

    @Test
    fun `a cell raised to the minimum render height lays out at its own dp size`() {
        // A 380x150 dp cell at 1x is 380x150 px; the 240 px floor scales the
        // bitmap by 1.6x. The density must scale with it, or the chart lays out
        // on a 608x240 dp canvas and its text shrinks when fitted back.
        val (widthPx, heightPx) = scaleRenderSize(380, 150)
        heightPx shouldBe 240
        val dpi = renderDensityDpi(widthPx, heightPx, widthDp = 380f)
        (widthPx * 160f / dpi) shouldBe (380f plusOrMinus 1f)
        (heightPx * 160f / dpi) shouldBe (150f plusOrMinus 1f)
    }

    @Test
    fun `a cell too short for the chart keeps the minimum layout height`() {
        // 356x55 dp: laying out at 55 dp would leave the chart no room under
        // the subtitle, so the layout keeps the minimum height and the content
        // shrinks with the cell instead.
        val (widthPx, heightPx) = scaleRenderSize(534, 83)
        val dpi = renderDensityDpi(widthPx, heightPx, widthDp = 356f)
        (heightPx * 160f / dpi) shouldBe (minLayoutHeightDp(1f) plusOrMinus 1f)
    }

    @Test
    fun `the minimum layout leaves a plot under a two-line subtitle at any font scale`() {
        // Chrome 32 dp, two 20 dp subtitle lines scaled by the font, 68 dp plot.
        minLayoutHeightDp(1f) shouldBe 140f
        minLayoutHeightDp(2f) shouldBe 180f
        // A font smaller than default never shrinks the reserve.
        minLayoutHeightDp(0.85f) shouldBe 140f
    }

    @Test
    fun `a larger font scale keeps a taller minimum layout`() {
        val (widthPx, heightPx) = scaleRenderSize(534, 83)
        val dpi = renderDensityDpi(widthPx, heightPx, widthDp = 356f, fontScale = 2f)
        (heightPx * 160f / dpi) shouldBe (180f plusOrMinus 1f)
    }

    @Test
    fun `a bitmap is reused only for the cell size it was rendered for`() {
        renderedFor(356f, 120f, 356f, 120f) shouldBe true
        renderedFor(356f, 120f, 356.4f, 119.6f) shouldBe true
        // Moved from full width to half: a new render, not the old one shrunk.
        renderedFor(356f, 120f, 178f, 150f) shouldBe false
        renderedFor(356f, 120f, 356f, 150f) shouldBe false
    }

    @Test
    fun `every new size renders and a size seen again is reused`() {
        val renders = mutableListOf<String>()
        val resized = ResizeRenders(4) { w, h -> "${w.toInt()}x${h.toInt()}".also { renders += it } }
        kotlinx.coroutines.runBlocking {
            // A drag back and forth: B, C, B, then more new sizes than the cache holds.
            listOf(200f, 300f, 200f, 400f, 500f, 600f, 700f).forEach { resized.get(it, 50f) shouldBe "${it.toInt()}x50" }
        }
        renders shouldBe listOf("200x50", "300x50", "400x50", "500x50", "600x50", "700x50")
    }

    @Test
    fun `a failed render is tried again next time`() {
        var renders = 0
        val resized = ResizeRenders<String>(4) { _, _ -> renders++; null }
        kotlinx.coroutines.runBlocking {
            resized.get(100f, 50f) shouldBe null
            resized.get(100f, 50f) shouldBe null
        }
        renders shouldBe 2
    }

    @Test
    fun `a canceled render isn't kept`() {
        var renders = 0
        val resized = ResizeRenders<String>(4) { _, _ ->
            renders++
            kotlinx.coroutines.awaitCancellation()
        }
        kotlinx.coroutines.runBlocking {
            repeat(2) {
                val job = launch { resized.get(100f, 50f) }
                kotlinx.coroutines.yield()
                job.cancel()
                job.join()
            }
        }
        renders shouldBe 2
    }

    @Test
    fun `nothing to draw renders nothing on resize`() {
        kotlinx.coroutines.runBlocking { WidgetCharts.EMPTY.renderResized(100f, 50f) } shouldBe null
    }

    @Test
    fun `only a usable cell size gets its own render on resize`() {
        val cells = distinctCellSizes(List(6) { (100f + it) to 50f })
        isUsableCell(cells, 101f, 50f) shouldBe true
        // Past the cap on listed sizes, a size keeps the nearest bitmap.
        isUsableCell(cells, 105f, 50f) shouldBe false
        isUsableCell(emptyList(), 101f, 50f) shouldBe false
    }
}
