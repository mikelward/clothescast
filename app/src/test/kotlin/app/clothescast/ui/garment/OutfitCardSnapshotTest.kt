package app.clothescast.ui.garment

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import app.clothescast.core.domain.model.OutfitSuggestion
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode.NATIVE
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

/**
 * Pixel snapshots for [renderOutfitCard]. The function returns raw PNG bytes
 * rather than a Compose composition, so we write them straight to the tracked
 * snapshots directory — no composeRule or captureRoboImage needed.
 *
 * [GraphicsMode.Mode.NATIVE] enables the real Skia pipeline so font
 * rendering is accurate (otherwise text paints as blank rectangles under
 * Robolectric's legacy software renderer).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(NATIVE)
@Config(sdk = [33])
class OutfitCardSnapshotTest {

    @get:Rule
    val testName = TestName()

    private val outputDir: String = System.getProperty("roborazzi.output.dir")
        ?: error("roborazzi.output.dir not set; configure in app/build.gradle.kts testOptions")

    private fun writeCard(png: ByteArray) {
        File("$outputDir/${testName.methodName}.png")
            .also { it.parentFile?.mkdirs() }
            .writeBytes(png)
    }

    @Test
    fun outfit_card_today_tshirt_shorts() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        writeCard(
            renderOutfitCard(
                context = ctx,
                outfit = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS),
                periodLabel = "Today",
                prose = "A warm one today. Wear a t-shirt and shorts. " +
                    "High of 28°C, feels like 30°C. Don't forget sunscreen.",
                topColors = emptyMap(),
                bottomColors = emptyMap(),
            ),
        )
    }

    @Test
    fun outfit_card_tonight_sweater_long_pants() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        writeCard(
            renderOutfitCard(
                context = ctx,
                outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
                periodLabel = "Tonight",
                prose = "Cooling down this evening. Wear a sweater and long pants. " +
                    "Low of 14°C, feels like 11°C. Bring a jacket if heading out after 10pm.",
                topColors = emptyMap(),
                bottomColors = emptyMap(),
            ),
        )
    }

    @Test
    fun outfit_card_today_jacket_jeans_custom_colors() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        writeCard(
            renderOutfitCard(
                context = ctx,
                outfit = OutfitSuggestion(OutfitSuggestion.Top.THIN_JACKET, OutfitSuggestion.Bottom.JEANS),
                periodLabel = "Today",
                prose = "Chilly but dry. Wear a light jacket and jeans. " +
                    "High of 15°C, feels like 13°C.",
                topColors = mapOf(OutfitSuggestion.Top.THIN_JACKET to 0xFFE53935L), // red jacket
                bottomColors = mapOf(OutfitSuggestion.Bottom.JEANS to 0xFF1A237EL),  // navy jeans
            ),
        )
    }
}
