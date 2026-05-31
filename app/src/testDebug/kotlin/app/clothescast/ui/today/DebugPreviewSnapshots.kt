package app.clothescast.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.ui.settings.SettingsVoiceAppCheckDebugTokenPreview
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot tests for debug-only previews that the shared
 * [PreviewSnapshots] in `src/test` can't reference — those would
 * fail to compile against the release source set when someone runs
 * `:app:testReleaseUnitTest` or the aggregate `:app:test`, since the
 * debug-variant preview composables don't exist there.
 *
 * Each test is intentionally minimal — one preview, one capture, no
 * frame-pump loop. The preview targeted here is a static layout (no
 * animation, no async content), so the first composed frame is the
 * one we want; PreviewSnapshots' captureUntilStable is overkill.
 *
 * Lives under `src/testDebug/`, which AGP merges into
 * `:app:testDebugUnitTest`'s sources but excludes from
 * `:app:testReleaseUnitTest`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class DebugPreviewSnapshots {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Match the file-name override scheme in PreviewSnapshots — bare
    // `<method>.png` rather than the package-qualified default.
    @get:Rule
    val testName = TestName()

    private val outputDir: String = System.getProperty("roborazzi.output.dir")
        ?: error("roborazzi.output.dir not set; configure in app/build.gradle.kts testOptions")

    @Test
    fun settings_voice_app_check_debug_token() {
        composeRule.setContent { SettingsVoiceAppCheckDebugTokenPreview() }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            filePath = "$outputDir/${testName.methodName}.png",
        )
    }
}
