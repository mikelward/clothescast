package app.clothescast.ui.nav

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.width
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A back swipe peeks at the page underneath instead of scrubbing the pop slide
 * (`NavTransitions`): mid-swipe, the page being left is shrunk and the one it
 * returns to is already on screen beneath it.
 *
 * Driven through the activity's real `OnBackPressedDispatcher`, which is what
 * the platform feeds a predictive-back gesture into, so the NavHost's own
 * handler seeks its transition exactly as it does on a device. Robolectric
 * can't show what the peek looks like; a device check owns that.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PredictiveBackTransitionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    private fun setUp() {
        composeRule.setContent {
            nav = rememberNavController()
            NavHost(
                navController = nav,
                startDestination = SettingsRootDest,
                enterTransition = NavTransitions.enter,
                exitTransition = NavTransitions.exit,
                popEnterTransition = NavTransitions.popEnter,
                popExitTransition = NavTransitions.popExit,
                predictivePopEnterTransition = NavTransitions.predictivePopEnter,
                predictivePopExitTransition = NavTransitions.predictivePopExit,
            ) {
                composable<SettingsRootDest> { Box(Modifier.fillMaxSize().testTag(ROOT)) }
                composable<DisplayDest> { Box(Modifier.fillMaxSize().testTag(SUB)) }
            }
        }
        composeRule.runOnUiThread { nav.navigate(DisplayDest) }
        composeRule.waitForIdle()
    }

    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher

    private fun swipe(progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) =
        BackEventCompat(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = edge)

    private fun subWidth() = composeRule.onNodeWithTag(SUB).getBoundsInRoot().width.value

    @Test
    fun `mid-swipe the page shrinks and the one beneath shows`() {
        setUp()
        val full = subWidth()

        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(swipe(0f))
            dispatcher.dispatchOnBackProgressed(swipe(0.4f))
        }
        composeRule.waitForIdle()

        // Shrunk toward the peek scale, not slid away at full size.
        subWidth() shouldBeLessThan full
        subWidth() shouldBeGreaterThan full * PREDICTIVE_BACK_SCALE - 1f
        // The page being returned to is already there to peek at.
        composeRule.onNodeWithTag(ROOT).assertExists()
    }

    @Test
    fun `a long drag holds the page at the peek rather than taking it away`() {
        setUp()
        val full = subWidth()

        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(swipe(0f))
            dispatcher.dispatchOnBackProgressed(swipe(0.95f))
        }
        composeRule.waitForIdle()

        // Still the page, still on screen, shrunk no further than the peek.
        subWidth() shouldBeGreaterThan full * PREDICTIVE_BACK_SCALE - 1f
        composeRule.onNodeWithTag(SUB).assertExists()
    }

    @Test
    fun `a canceled swipe puts the page back as it was`() {
        setUp()
        val full = subWidth()

        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(swipe(0f))
            dispatcher.dispatchOnBackProgressed(swipe(0.4f))
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()

        subWidth() shouldBe full
        composeRule.onNodeWithTag(ROOT).assertDoesNotExist()
    }

    @Test
    fun `a committed swipe goes back`() {
        setUp()

        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(swipe(0f, BackEventCompat.EDGE_RIGHT))
            dispatcher.dispatchOnBackProgressed(swipe(0.4f, BackEventCompat.EDGE_RIGHT))
            dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SUB).assertDoesNotExist()
        composeRule.onNodeWithTag(ROOT).assertExists()
    }

    private companion object {
        const val ROOT = "root"
        const val SUB = "sub"
    }
}
