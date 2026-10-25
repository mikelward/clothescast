package app.clothescast.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.HomeSection
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.ui.theme.ClothesCastTheme
import com.mikelward.androidzoom.FontSizeSettings
import com.mikelward.androidzoom.MAX_FONT_SCALE
import com.mikelward.androidzoom.MIN_FONT_SCALE
import com.mikelward.androidzoom.compose.FontSizeState
import com.mikelward.androidzoom.compose.rememberFontSizeState
import io.kotest.matchers.shouldBe
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Display screen's text-size card: what the slider (and the buttons beside
 * it) leave stored, since that write is the only thing that outlives the
 * screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "en-rUS-w360dp-h2000dp-xhdpi")
class TextSizeCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val settled = mutableListOf<Float>()
    private lateinit var state: FontSizeState
    private var shown by mutableStateOf(true)

    /**
     * [restoring] stands in for the store across a recreation: the screen comes back at the size last
     * stored, as the app's does, rather than at [scale].
     */
    private fun render(scale: Float = 1.15f, restoring: StateRestorationTester? = null) {
        val setContent: (@Composable () -> Unit) -> Unit = restoring?.let { it::setContent } ?: composeRule::setContent
        setContent {
            state = rememberFontSizeState(
                persisted = FontSizeSettings(scale = if (restoring != null) settled.lastOrNull() ?: scale else scale),
                onScaleSettled = { settled += it },
                onPinchEnabledChange = {},
            )
            ClothesCastTheme(dynamicColor = false, fontSize = state) {
                if (shown) {
                    DisplayContent(
                        themeMode = ThemeMode.SYSTEM,
                        colorPalette = ColorPalette.RAINBOW,
                        homeSectionOrder = HomeSection.DEFAULTS,
                        padding = PaddingValues(0.dp),
                        onSetThemeMode = {},
                        onSetColorPalette = {},
                        onReorderHomeSection = { _, _ -> },
                    )
                }
            }
        }
    }

    private fun slider() = composeRule.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
    )

    // Each step in its own block, with the tree settling between: one block
    // dispatches without recomposing, so the size would not move until the drag
    // was already over.
    private fun dragRight() {
        slider().performTouchInput { down(center) }
        composeRule.waitForIdle()
        // Past the touch slop, so this is a drag rather than a press.
        slider().performTouchInput { moveTo(center + Offset(100f, 0f)) }
        composeRule.waitForIdle()
    }

    @Test
    fun releasePersistsWhereTheDragEnded() {
        render()
        dragRight()
        slider().performTouchInput { up() }
        composeRule.waitForIdle()
        (state.scale > 1.2f) shouldBe true // past where the press landed
        settled shouldBe listOf(state.scale)
    }

    // Leaving the screen with a finger still on the slider disposes it without
    // a release, so nothing else would store the size already on screen.
    @Test
    fun leavingMidDragPersistsTheDraggedSize() {
        render()
        dragRight()
        val dragged = state.scale
        (dragged > 1.2f) shouldBe true
        settled shouldBe emptyList()

        composeRule.runOnIdle { shown = false }
        composeRule.waitForIdle()

        settled shouldBe listOf(dragged)
        // And the gesture is over, so a stored value lands rather than being
        // held for a drag that has ended.
        composeRule.runOnIdle {
            state.onPersisted(FontSizeSettings(scale = 1.3f, pinchEnabled = false))
            state.scale shouldBe 1.3f
            state.pinchEnabled shouldBe false
        }
    }

    @Test
    fun plusAndMinusStepOnePercentAndPersistEachTap() {
        // Between whole percents, as a drag or a pinch can leave it: shown 115%.
        render(scale = 1.153f)
        composeRule.onNodeWithTag(TEXT_SIZE_LARGER_TAG).performClick()
        composeRule.waitForIdle()
        state.scale shouldBe 1.16f
        settled shouldBe listOf(1.16f)
        composeRule.onNodeWithText("116%").assertExists()

        composeRule.onNodeWithTag(TEXT_SIZE_SMALLER_TAG).performClick()
        composeRule.onNodeWithTag(TEXT_SIZE_SMALLER_TAG).performClick()
        composeRule.waitForIdle()
        state.scale shouldBe 1.14f
        settled shouldBe listOf(1.16f, 1.15f, 1.14f)
    }

    @Test
    fun plusDisablesAtTheTop() {
        render(scale = 1.59f)
        composeRule.onNodeWithTag(TEXT_SIZE_LARGER_TAG).assertIsEnabled().performClick()
        composeRule.waitForIdle()
        state.scale shouldBe MAX_FONT_SCALE
        composeRule.onNodeWithTag(TEXT_SIZE_LARGER_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(TEXT_SIZE_SMALLER_TAG).assertIsEnabled()
    }

    @Test
    fun minusDisablesAtTheBottom() {
        render(scale = 0.81f)
        composeRule.onNodeWithTag(TEXT_SIZE_SMALLER_TAG).assertIsEnabled().performClick()
        composeRule.waitForIdle()
        state.scale shouldBe MIN_FONT_SCALE
        composeRule.onNodeWithTag(TEXT_SIZE_SMALLER_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(TEXT_SIZE_LARGER_TAG).assertIsEnabled()
    }

    @Test
    fun buttonsSayWhatTheyDo() {
        render()
        composeRule.onNodeWithContentDescription("Smaller text").assertExists()
        composeRule.onNodeWithContentDescription("Larger text").assertExists()
    }

    /** Where [tag] sits on screen, in root pixels. */
    private fun top(tag: String): Float = composeRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    /** Taps [tag] [times] times, checking after each that both buttons are where they were. */
    private fun tapHoldsStill(tag: String, times: Int) {
        composeRule.waitForIdle()
        val larger = top(TEXT_SIZE_LARGER_TAG)
        val smaller = top(TEXT_SIZE_SMALLER_TAG)
        repeat(times) {
            composeRule.onNodeWithTag(tag).performClick()
            composeRule.waitForIdle()
            assertEquals("plus after tap ${it + 1}", larger, top(TEXT_SIZE_LARGER_TAG), 1f)
            assertEquals("minus after tap ${it + 1}", smaller, top(TEXT_SIZE_SMALLER_TAG), 1f)
        }
    }

    @Test
    fun `tapping plus again and again leaves the buttons where they were`() {
        // The screen is taller than the page, so holding still needs room added below to scroll into.
        render(scale = 1.0f)
        tapHoldsStill(TEXT_SIZE_LARGER_TAG, 30)
    }

    @Test
    fun `the buttons stay put when the screen is recreated after shrinking`() {
        // The screen is taller than the page, so the hold adds room; it is saved with the scroll position.
        val restore = StateRestorationTester(composeRule)
        render(scale = 1.5f, restoring = restore)
        tapHoldsStill(TEXT_SIZE_SMALLER_TAG, 20)
        val larger = top(TEXT_SIZE_LARGER_TAG)
        restore.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(larger, top(TEXT_SIZE_LARGER_TAG), 1f)
    }

    @Test
    fun `tapping minus again and again leaves the buttons where they were`() {
        render(scale = 1.5f)
        tapHoldsStill(TEXT_SIZE_SMALLER_TAG, 30)
    }
}
