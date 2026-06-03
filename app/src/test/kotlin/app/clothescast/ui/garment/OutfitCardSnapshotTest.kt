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
                header = "Today's ClothesCast",
                prose = "Today, it will be warm. 3° warmer than yesterday. " +
                    "Wear a t-shirt and shorts. Chance of rain at 3pm.",
                info = OutfitCardInfoLines(
                    tempLine = "22–30°C",
                    // Warm day: high 30°C, HOT band → (5 + (30-28)/12) / 6 ≈ 0.86.
                    tempFillFraction = thermometerFillFractionFor(30.0),
                    rainFillFraction = 0.40f, // Peak 40% rain → droplet 40% full.
                    rainLineShort = "40%",
                ),
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
                header = "Tonight's ClothesCast",
                prose = "Tonight, it will be cool. Wear a sweater and long pants.",
                info = OutfitCardInfoLines(
                    tempLine = "11–18°C",
                    // Peak below threshold — rain cell hidden (rainLineShort null).
                    // Cool evening: high 18°C, start of MILD band → exactly 0.5 fill.
                    tempFillFraction = thermometerFillFractionFor(18.0),
                    rainFillFraction = null,
                ),
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
                header = "Today's ClothesCast",
                prose = "Today, it will be cool. 2° cooler than yesterday. " +
                    "Wear a jacket and jeans.",
                info = OutfitCardInfoLines(
                    tempLine = "9–15°C",
                    // Cool day: high 15°C, midpoint of COOL band → 0.417 fill.
                    tempFillFraction = thermometerFillFractionFor(15.0),
                    rainFillFraction = null,
                ),
                topColors = mapOf(OutfitSuggestion.Top.THIN_JACKET to 0xFFE53935L), // red jacket
                bottomColors = mapOf(OutfitSuggestion.Bottom.JEANS to 0xFF1A237EL),  // navy jeans
            ),
        )
    }

    @Test
    fun outfit_card_freezing_coat_pants_gloves() {
        // Freezing day: the gloves overlay sits over the coat at the body's
        // sides. Exercises the optional hands slot on the cast card.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        writeCard(
            renderOutfitCard(
                context = ctx,
                outfit = OutfitSuggestion(
                    OutfitSuggestion.Top.THICK_COAT,
                    OutfitSuggestion.Bottom.LONG_PANTS,
                    OutfitSuggestion.Hands.GLOVES,
                ),
                header = "Today's ClothesCast",
                prose = "A freezing one today. Wear a coat and long pants, and gloves.",
                info = OutfitCardInfoLines(
                    tempLine = "-3–2°C",
                    // Freezing: high 2°C, FREEZING band → low fill.
                    tempFillFraction = thermometerFillFractionFor(2.0),
                    rainFillFraction = null,
                ),
                topColors = emptyMap(),
                bottomColors = emptyMap(),
            ),
        )
    }

    @Test
    fun outfit_card_freezing_coat_pants_red_gloves() {
        // A user-picked gloves colour threads through handsColors and recolours
        // the overlay — proving the hands colour-picker reaches the cast card.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        writeCard(
            renderOutfitCard(
                context = ctx,
                outfit = OutfitSuggestion(
                    OutfitSuggestion.Top.THICK_COAT,
                    OutfitSuggestion.Bottom.LONG_PANTS,
                    OutfitSuggestion.Hands.GLOVES,
                ),
                header = "Today's ClothesCast",
                prose = "A freezing one today. Wear a coat and long pants, and gloves.",
                info = OutfitCardInfoLines(
                    tempLine = "-3–2°C",
                    tempFillFraction = thermometerFillFractionFor(2.0),
                    rainFillFraction = null,
                ),
                topColors = emptyMap(),
                bottomColors = emptyMap(),
                handsColors = mapOf(OutfitSuggestion.Hands.GLOVES to 0xFFE53935L), // red gloves
            ),
        )
    }

    @Test
    fun outfit_card_rainy_jacket_jeans_umbrella() {
        // Rainy day: the full-figure umbrella overlay spans both icons, held at
        // the hip and hanging down beside the leg. Exercises the optional carried
        // slot on the cast card.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        writeCard(
            renderOutfitCard(
                context = ctx,
                outfit = OutfitSuggestion(
                    OutfitSuggestion.Top.THICK_JACKET,
                    OutfitSuggestion.Bottom.JEANS,
                    carried = OutfitSuggestion.Carried.UMBRELLA,
                ),
                header = "Today's ClothesCast",
                prose = "A wet one today. Wear a jacket and jeans, and bring an umbrella.",
                info = OutfitCardInfoLines(
                    tempLine = "9–15°C",
                    tempFillFraction = thermometerFillFractionFor(15.0),
                    rainFillFraction = 0.80f,
                    rainLineShort = "80%",
                ),
                topColors = emptyMap(),
                bottomColors = emptyMap(),
            ),
        )
    }

    @Test
    fun outfit_card_freezing_coat_gloves_yellow_umbrella() {
        // A cold rainy day lights both overlays at once — the gloved hand grips
        // the umbrella's crook — and a user-picked umbrella colour threads
        // through carriedColors and recolours the canopy, proving the carried
        // colour-picker reaches the cast card and composites cleanly with gloves.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        writeCard(
            renderOutfitCard(
                context = ctx,
                outfit = OutfitSuggestion(
                    OutfitSuggestion.Top.THICK_COAT,
                    OutfitSuggestion.Bottom.LONG_PANTS,
                    hands = OutfitSuggestion.Hands.GLOVES,
                    carried = OutfitSuggestion.Carried.UMBRELLA,
                ),
                header = "Today's ClothesCast",
                prose = "A freezing, wet one. Wear a coat and long pants, and gloves, " +
                    "and bring an umbrella.",
                info = OutfitCardInfoLines(
                    tempLine = "-3–2°C",
                    tempFillFraction = thermometerFillFractionFor(2.0),
                    rainFillFraction = 0.70f,
                    rainLineShort = "70%",
                ),
                topColors = emptyMap(),
                bottomColors = emptyMap(),
                carriedColors = mapOf(OutfitSuggestion.Carried.UMBRELLA to 0xFFFDD835L), // yellow canopy
            ),
        )
    }

    @Test
    fun outfit_card_rainy_sweater_jeans_rain_jacket() {
        // Rainy day: the rain-jacket outer shell paints over the sweater at the
        // top icon's footprint, while the sweater stays underneath in the model.
        // Exercises the optional outer slot on the cast card, including its
        // baked-in yellow default colour.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        writeCard(
            renderOutfitCard(
                context = ctx,
                outfit = OutfitSuggestion(
                    OutfitSuggestion.Top.SWEATER,
                    OutfitSuggestion.Bottom.JEANS,
                    outer = OutfitSuggestion.Outer.RAIN_JACKET,
                ),
                header = "Today's ClothesCast",
                prose = "A cool, wet one. Wear a sweater and jeans, and a rain jacket.",
                info = OutfitCardInfoLines(
                    tempLine = "11–16°C",
                    tempFillFraction = thermometerFillFractionFor(16.0),
                    rainFillFraction = 0.75f,
                    rainLineShort = "75%",
                ),
                topColors = emptyMap(),
                bottomColors = emptyMap(),
            ),
        )
    }

    @Test
    fun outfit_card_all_indicators() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        writeCard(
            renderOutfitCard(
                context = ctx,
                outfit = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS),
                header = "Today's ClothesCast",
                prose = "A hot, breezy one. Wear a t-shirt and shorts, and watch the sun.",
                info = OutfitCardInfoLines(
                    tempLine = "18–34°C",
                    tempFillFraction = thermometerFillFractionFor(34.0),
                    rainFillFraction = 0.40f,
                    rainLineShort = "40%",
                    windLabel = "45 km/h", // orange (strong breeze)
                    windMaxKmh = 45.0,
                    uvLabel = "UV 9", // red (very high)
                    uvMax = 9.0,
                ),
                topColors = emptyMap(),
                bottomColors = emptyMap(),
            ),
        )
    }
}
