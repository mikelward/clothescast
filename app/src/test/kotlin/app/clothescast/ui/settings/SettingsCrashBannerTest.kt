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
import java.io.File

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

    private lateinit var cacheDir: File

    @Before
    fun seedCleanCrashState() {
        cacheDir = composeRule.activity.applicationContext.cacheDir
        // These two files are the whole of the banner's state, and Robolectric's
        // cache directory is shared across tests in the class.
        File(cacheDir, CRASH_FILE).delete()
        File(cacheDir, ACK_FILE).delete()
        DiagLog.install(composeRule.activity.applicationContext)
    }

    @Test
    fun settings_root_offers_the_crash_report() {
        File(cacheDir, CRASH_FILE).writeText("java.lang.IllegalStateException: boom")
        DiagLog.refreshUnacknowledgedCrash()

        composeRule.setContent { SettingsRootUnderTest() }

        composeRule.onNodeWithText("Last run crashed").assertIsDisplayed()
    }

    @Test
    fun settings_root_shows_nothing_when_there_was_no_crash() {
        DiagLog.refreshUnacknowledgedCrash()

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

    private companion object {
        const val CRASH_FILE = "last-crash.txt"
        const val ACK_FILE = "last-crash.ack"
    }
}
