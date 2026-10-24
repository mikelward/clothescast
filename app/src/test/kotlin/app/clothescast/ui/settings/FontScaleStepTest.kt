package app.clothescast.ui.settings

import com.mikelward.androidzoom.MAX_FONT_SCALE
import com.mikelward.androidzoom.MIN_FONT_SCALE
import com.mikelward.androidzoom.fontScalePercent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FontScaleStepTest {

    @Test
    fun `steps from the whole percent shown, not the raw size`() {
        // 1.153 shows as 115%, so one tap up shows 116%, not 116.3%.
        stepFontScale(1.153f, +1) shouldBe 1.16f
        stepFontScale(1.153f, -1) shouldBe 1.14f
        fontScalePercent(stepFontScale(1.153f, +1)) shouldBe 116
        // Rounds rather than truncates: 1.157 shows as 116%.
        stepFontScale(1.157f, +1) shouldBe 1.17f
    }

    @Test
    fun `every step moves the shown percent by exactly one`() {
        for (percent in 80..159) {
            fontScalePercent(stepFontScale(percent / 100f, +1)) shouldBe percent + 1
        }
        for (percent in 81..160) {
            fontScalePercent(stepFontScale(percent / 100f, -1)) shouldBe percent - 1
        }
    }

    @Test
    fun `clamps at both ends`() {
        stepFontScale(MAX_FONT_SCALE, +1) shouldBe MAX_FONT_SCALE
        stepFontScale(MIN_FONT_SCALE, -1) shouldBe MIN_FONT_SCALE
        stepFontScale(1.599f, +1) shouldBe MAX_FONT_SCALE
        stepFontScale(0.801f, -1) shouldBe MIN_FONT_SCALE
    }

    @Test
    fun `a non-finite size steps from the starting size`() {
        stepFontScale(Float.NaN, +1) shouldBe 1.16f
        stepFontScale(Float.POSITIVE_INFINITY, -1) shouldBe 1.14f
    }
}
