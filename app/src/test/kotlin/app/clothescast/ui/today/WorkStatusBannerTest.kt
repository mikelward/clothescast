package app.clothescast.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.work.FetchAndNotifyWorker
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Behaviour of the "Last attempt failed" card: by default the card shows only
// the short user-facing message; the (possibly long) raw detail lives behind a
// "Show details" toggle so the card stays readable. Snapshot tests cover the
// pixel layout — these tests cover the toggle interaction.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class WorkStatusBannerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val longDetail =
        "NoTransformationFoundException: Expected response body of the type " +
            "'class app.clothescast.core.data.weather.OpenMeteoResponse'"

    @Test
    fun detail_is_hidden_by_default_and_revealed_on_tap() {
        composeRule.setContent {
            Frame {
                WorkStatusBanner(
                    WorkStatus.Failed(
                        reason = FetchAndNotifyWorker.REASON_UNHANDLED,
                        detail = longDetail,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("An unexpected error occurred.").assertIsDisplayed()
        composeRule.onNodeWithText("Show details").assertIsDisplayed()
        composeRule.onNodeWithText(longDetail, substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("Show details").performClick()

        composeRule.onNodeWithText("Hide details").assertIsDisplayed()
        composeRule.onNodeWithText(longDetail, substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("Hide details").performClick()

        composeRule.onNodeWithText("Show details").assertIsDisplayed()
        composeRule.onNodeWithText(longDetail, substring = true).assertDoesNotExist()
    }

    @Test
    fun no_toggle_shown_when_detail_is_absent() {
        composeRule.setContent {
            Frame {
                WorkStatusBanner(
                    WorkStatus.Failed(
                        reason = FetchAndNotifyWorker.REASON_UNHANDLED,
                        detail = null,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("An unexpected error occurred.").assertIsDisplayed()
        composeRule.onNodeWithText("Show details").assertDoesNotExist()
    }

    @Test
    fun no_location_failure_shows_actionable_copy() {
        composeRule.setContent {
            Frame {
                WorkStatusBanner(
                    WorkStatus.Failed(
                        reason = FetchAndNotifyWorker.REASON_NO_LOCATION,
                        detail = null,
                    ),
                )
            }
        }

        composeRule.onNodeWithText(
            "We couldn't get your location",
            substring = true,
        ).assertIsDisplayed()
    }
}
