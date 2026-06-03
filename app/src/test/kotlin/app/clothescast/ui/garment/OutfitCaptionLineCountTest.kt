package app.clothescast.ui.garment

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the wrapped-line estimate that frames the widget caption. The estimate
 * only sets the widget's `maxLines` cap and icon-room reserve, so it must:
 *  - keep the established single-column wrapping (a long base outfit on one
 *    line, an accessory run on two) so existing widget snapshots don't move, and
 *  - grow to three lines once a side-by-side column halves the width, so the
 *    final accessory ("Umbrella") isn't clipped — the regression this fixes.
 *
 * Widths / font sizes mirror what the widget passes: ~160 dp / 11 sp for a
 * single column, ~120 dp / 10 sp for a halved side-by-side column.
 */
class OutfitCaptionLineCountTest {

    private val accessoryRun = "Thick coat · Long pants · Gloves · Umbrella"

    @Test
    fun `accessory run wraps to two lines in a single-column cell`() {
        outfitGarmentCaptionLineCount(accessoryRun, availableWidthDp = 160f, fontSizeSp = 11f) shouldBe 2
    }

    @Test
    fun `accessory run wraps to three lines in a halved side-by-side column`() {
        // The previous constant cap of 2 clipped "Umbrella" here.
        outfitGarmentCaptionLineCount(accessoryRun, availableWidthDp = 120f, fontSizeSp = 10f) shouldBe 3
    }

    @Test
    fun `a long base outfit still fits one line in a single-column cell`() {
        outfitGarmentCaptionLineCount("Thick jacket · Long pants", availableWidthDp = 160f, fontSizeSp = 11f) shouldBe 1
    }

    @Test
    fun `a plain outfit is one line`() {
        outfitGarmentCaptionLineCount("T-shirt · Shorts", availableWidthDp = 160f, fontSizeSp = 11f) shouldBe 1
    }

    @Test
    fun `the line count is capped so a tiny cell can't shrink the icons away`() {
        outfitGarmentCaptionLineCount(accessoryRun, availableWidthDp = 80f, fontSizeSp = 10f) shouldBe 3
    }
}
