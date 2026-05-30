package app.clothescast.ui.garment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
 * Pixel snapshots for [renderConditionsStripBitmap] — the horizontal strip of
 * thermometer / rain / wind / UV cells shown on the conditions widget. The
 * function returns a [Bitmap], so we encode to PNG and write straight to the
 * tracked snapshots directory (same pattern as [OutfitCardSnapshotTest]).
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

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun thermo(label: String, c: Double) =
        ConditionsCell(ConditionsGlyph.THERMOMETER, label, fillFraction = thermometerFillFractionFor(c))

    private fun rain(label: String, fraction: Float) =
        ConditionsCell(ConditionsGlyph.DROPLET, label, fillFraction = fraction)

    private fun wind(label: String, kmh: Double) =
        ConditionsCell(ConditionsGlyph.WIND, label, tintArgb = windScaleColorArgb(kmh))

    private fun uv(label: String, index: Double) =
        ConditionsCell(ConditionsGlyph.UV, label, tintArgb = uvScaleColorArgb(index))

    private fun write(cells: List<ConditionsCell>, widthPx: Int, darkTheme: Boolean) {
        val strip = renderConditionsStripBitmap(
            context = ctx,
            cells = cells,
            widthPx = widthPx,
            heightPx = 120,
            darkTheme = darkTheme,
        )
        // The renderer draws on a transparent bitmap (the widget Box paints the
        // surface); composite onto that same themed surface here so the snapshot
        // shows what the user sees — and so the dark case is a real legibility
        // check rather than light text on the viewer's white.
        val bitmap = Bitmap.createBitmap(strip.width, strip.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(if (darkTheme) STRIP_SURFACE_DARK_ARGB else STRIP_SURFACE_LIGHT_ARGB)
            drawBitmap(strip, 0f, 0f, null)
        }
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        File("$outputDir/${testName.methodName}.png")
            .also { it.parentFile?.mkdirs() }
            .writeBytes(png)
    }

    @Test
    fun conditions_strip_warm_with_rain_light() {
        write(listOf(thermo("9–28°C", 28.0), rain("60%", 0.60f)), widthPx = 480, darkTheme = false)
    }

    @Test
    fun conditions_strip_cold_no_rain_light() {
        write(listOf(thermo("2–7°C", 7.0)), widthPx = 480, darkTheme = false)
    }

    @Test
    fun conditions_strip_warm_with_rain_dark() {
        write(listOf(thermo("9–28°C", 28.0), rain("60%", 0.60f)), widthPx = 480, darkTheme = true)
    }

    @Test
    fun conditions_strip_all_indicators_light() {
        write(
            listOf(
                thermo("18–34°C", 34.0),
                rain("40%", 0.40f),
                wind("45 km/h", 45.0), // orange (strong breeze)
                uv("UV 9", 9.0), // red (very high)
            ),
            widthPx = 720,
            darkTheme = false,
        )
    }

    @Test
    fun conditions_strip_all_indicators_dark() {
        write(
            listOf(
                thermo("18–34°C", 34.0),
                rain("40%", 0.40f),
                wind("45 km/h", 45.0),
                uv("UV 9", 9.0),
            ),
            widthPx = 720,
            darkTheme = true,
        )
    }
}
