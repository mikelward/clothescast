package app.clothescast.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.ui.settings.SettingsVoiceGeminiNoKeySharedPreview
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Voice → Gemini (no BYOK key, shared proxy reachable) is taller than
// PreviewSnapshots' standard 640dp viewport — accent card + the full Gemini
// engine card (subhead, status text, key field, Save button, voice picker,
// style picker, Test voice button). The default snapshot
// (settings_voice_gemini_no_key_shared.png) crops just below the voice picker,
// which hides the very thing the new copy unlocks: the Test voice button being
// *enabled* in this state, so the user can hear Gemini without a key.
//
// Lives in its own class because Robolectric `@Config(qualifiers = …)` is
// per-class. Same shape as SettingsClothesFullSnapshot.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h2400dp-xhdpi")
class SettingsVoiceGeminiNoKeySharedFullSnapshot {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val outputDir: String = System.getProperty("roborazzi.output.dir")
        ?: error("roborazzi.output.dir not set; configure in app/build.gradle.kts testOptions")

    @Test fun settings_voice_gemini_no_key_shared_full() {
        composeRule.setContent { SettingsVoiceGeminiNoKeySharedPreview() }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(filePath = "$outputDir/settings_voice_gemini_no_key_shared_full.png")
    }
}
