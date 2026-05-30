package app.clothescast.ui.garment

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode.NATIVE
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pixel snapshots for [renderConditionsStripBitmap] — the horizontal
 * thermometer-plus-rain strip shown on the conditions widget. The function
 * returns a [Bitmap], so we encode to PNG and write straight to the tracked
 * snapshots directory (same pattern as [OutfitCardSnapshotTest]).
 *
 * [GraphicsMode.Mode.NATIVE] enables the real Skia pipeline so text + glyph
 * fills rasterize accurately. The dark-theme case is the key legibility check:
 * the glyph interior must recede into a dark widget background while the
 * outline + text stay readable.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(NATIVE)
@Config(sdk = [33])
class ConditionsStripSnapshotTest {

    @get:Rule
    val testName = TestName()

    private val outputDir: String = System.getProperty("roborazzi.output.dir")
        ?: error("roborazzi.output.dir not set; configure in app/build.gradle.kts testOptions")

    private fun writeStrip(bitmap: Bitmap) {
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        File("$outputDir/${testName.methodName}.png")
            .also { it.parentFile?.mkdirs() }
            .writeBytes(png)
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun conditions_strip_warm_with_rain_light() {
        writeStrip(
            renderConditionsStripBitmap(
                context = ctx,
                rangeLabel = "9–28°C",
                rainLabel = "60% at 3pm",
                tempFraction = thermometerFillFractionFor(28.0),
                rainFraction = 0.60f,
                widthPx = 480,
                heightPx = 120,
                darkTheme = false,
            ),
        )
    }

    @Test
    fun conditions_strip_cold_no_rain_light() {
        writeStrip(
            renderConditionsStripBitmap(
                context = ctx,
                rangeLabel = "2–7°C",
                // Below the 30% threshold the card hides the row → null here.
                rainLabel = null,
                tempFraction = thermometerFillFractionFor(7.0),
                rainFraction = null,
                widthPx = 480,
                heightPx = 120,
                darkTheme = false,
            ),
        )
    }

    @Test
    fun conditions_strip_warm_with_rain_dark() {
        writeStrip(
            renderConditionsStripBitmap(
                context = ctx,
                rangeLabel = "9–28°C",
                rainLabel = "60% at 3pm",
                tempFraction = thermometerFillFractionFor(28.0),
                rainFraction = 0.60f,
                widthPx = 480,
                heightPx = 120,
                darkTheme = true,
            ),
        )
    }
}
