package app.clothescast.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The post-crash banner reaches the settings root, not only Today.
 *
 * A crash is what sends the user to Settings in the first place — they go
 * looking for what went wrong — and Today, the one screen that offered to send
 * the report, is the screen they just left. The manual bug report already
 * reaches every screen through the overflow menu; this pins the proactive half
 * of the same affordance to the place the user actually goes.
 *
 * Snapshot tests can't cover this: the preview frames provide
 * `LocalInspectionMode`, and [app.clothescast.ui.today.LastCrashBanner]
 * deliberately draws nothing under it — its lifecycle-aware flow collection has
 * no main dispatcher inside a capture. A composition test is the only place the
 * wiring shows.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class SettingsCrashBannerTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * The main dispatcher has to outlive the composition, not just the test
     * body: the banner collects two flows with `collectAsStateWithLifecycle`,
     * which reaches for `Dispatchers.Main.immediate`, and tearing the
     * composition down touches it again. A plain `@After` reset runs *before*
     * the compose rule's teardown, and the cancellation then fails on a missing
     * dispatcher — so the dispatcher rule wraps the compose rule instead.
     */
    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(
            object : TestWatcher() {
                override fun starting(description: Description) {
                    Dispatchers.setMain(UnconfinedTestDispatcher(TestCoroutineScheduler()))
                }

                override fun finished(description: Description) {
                    Dispatchers.resetMain()
                }
            },
        )
        .around(composeRule)

    @Before
    fun seedCleanCrashState() {
        // Deliberately no `DiagLog.install(...)`: this test is about the screen
        // reacting to the crash state, not about deriving it. Without the file
        // sink the banner's ON_RESUME refresh is a no-op, so the state below is
        // the only thing driving the assertion — with the sink installed, that
        // refresh re-derives from an empty cache dir on the library's worker
        // and races the seeded value to false.
        DiagLog.publishCrashStateForTest(false)
    }

    @Test
    fun settings_root_offers_the_crash_report() {
        DiagLog.publishCrashStateForTest(true)

        composeRule.setContent { SettingsRootUnderTest() }

        composeRule.onNodeWithText("Last run crashed").assertIsDisplayed()
    }

    @Test
    fun settings_root_shows_nothing_when_there_was_no_crash() {
        DiagLog.publishCrashStateForTest(false)

        composeRule.setContent { SettingsRootUnderTest() }

        // The banner is mounted unconditionally, so "no crash" has to mean it
        // renders nothing at all — otherwise every settings visit would carry a
        // scar from a crash the user already dealt with.
        composeRule.onNodeWithText("Last run crashed").assertDoesNotExist()
    }

    @Composable
    private fun SettingsRootUnderTest() {
        SettingsRoot(
            useDeviceLocation = false,
            scheduleEnabled = false,
            items = emptyList(),
            padding = PaddingValues(),
            onOpenLocation = {},
        )
    }

}
