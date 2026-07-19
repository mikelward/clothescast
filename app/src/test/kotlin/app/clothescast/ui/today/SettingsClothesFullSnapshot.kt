package app.clothescast.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.ui.settings.SettingsClothesPreview
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Settings → Clothes is taller than PreviewSnapshots' standard 640dp viewport
// (three stacked cards: rules list, "If no rules match" fallbacks, and the
// per-garment colour swatches). The default snapshot crops above the colour
// swatches, which is the thing reviewers most often want to eyeball when a
// new garment tier or a colour default changes — adding a new top/bottom
// enum value silently surfaces here as an extra swatch row, and a recolour
// of an existing icon shows as a swatch pixel diff.
//
// Lives in its own test class because Robolectric `@Config(qualifiers = …)`
// is per-class. PreviewSnapshots' meta-test only audits its own methods, so
// this file's @Test isn't required to match any @Preview in the catalogue —
// it's a one-off whole-screen capture of an existing preview at a different
// device height.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h2400dp-xhdpi")
class SettingsClothesFullSnapshot {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val outputDir: String = System.getProperty("roborazzi.output.dir")
        ?: error("roborazzi.output.dir not set; configure in app/build.gradle.kts testOptions")

    @Test fun settings_clothes_full() {
        composeRule.setContent { SettingsClothesPreview() }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(filePath = "$outputDir/settings_clothes_full.png")
    }
}
