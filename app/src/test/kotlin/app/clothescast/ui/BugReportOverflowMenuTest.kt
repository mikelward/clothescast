package app.clothescast.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.R
import app.clothescast.ui.theme.ClothesCastTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app bar's ⋮ menu drops from the ⋮ itself, at the top right. With the button and the menu as
 * bare siblings in the actions row, the menu anchored to the row slot instead; a composition test
 * reads where the open menu landed, which a snapshot of the screen can't see (it opens its own window).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class BugReportOverflowMenuTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `the overflow menu opens from its button at the top right`() {
        composeRule.setContent {
            ClothesCastTheme(darkTheme = false, dynamicColor = false) {
                // Other actions ahead of the ⋮, as on the Today screen: the row slot is then
                // wider than the button, which is what exposed the wrong anchor.
                TopAppBar(title = { Text("Title") }, actions = {
                    IconButton(onClick = {}) { Text("A") }
                    IconButton(onClick = {}) { Text("B") }
                    BugReportOverflowMenu(onNavigateToAbout = {})
                })
            }
        }
        val activity = composeRule.activity
        composeRule.onNodeWithContentDescription(activity.getString(R.string.today_more_options)).performClick()
        composeRule.waitForIdle()
        val about = composeRule.onNodeWithText(activity.getString(R.string.settings_root_about)).fetchSemanticsNode()
        val right = about.positionOnScreen.x + about.size.width
        val button = composeRule.onNodeWithContentDescription(activity.getString(R.string.today_more_options)).fetchSemanticsNode()
        // Anchored to the button, the menu's right edge lines up with the button's
        // (give or take the menu's own inset). Anchored to the whole actions row,
        // it hangs a button-width or so to the left of it.
        val buttonRight = button.positionOnScreen.x + button.size.width
        assertTrue(
            "menu's right edge at $right, ⋮ button's at $buttonRight",
            right > buttonRight - button.size.width / 4f,
        )
    }
}
