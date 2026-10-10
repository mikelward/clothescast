package app.clothescast.ui.garment

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.OutfitSuggestion
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode.NATIVE

/** The tonight card is dark, the day card white — read off the corner pixel. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(NATIVE)
@Config(sdk = [33])
class OutfitCardThemeTest {

    private fun render(darkTheme: Boolean): android.graphics.Bitmap {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val png = renderOutfitCard(
            context = ctx,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
            header = "Tonight's ClothesCast",
            prose = "Tonight, it will be cool.",
            info = OutfitCardInfoLines(tempLine = "11–18°C", tempFillFraction = 0.5f, rainFillFraction = null),
            topColors = emptyMap(),
            bottomColors = emptyMap(),
            darkTheme = darkTheme,
        )
        return BitmapFactory.decodeByteArray(png, 0, png.size)
    }

    private fun cornerPixel(darkTheme: Boolean): Int =
        render(darkTheme).let { it.getPixel(it.width - 1, it.height - 1) }

    // Just inside the garment tile's top-left corner, clear of the artwork.
    private fun tilePixel(darkTheme: Boolean): Int = render(darkTheme).getPixel(28, 40)

    @Test
    fun light_card_has_white_background() {
        assertEquals(android.graphics.Color.WHITE, cornerPixel(darkTheme = false))
    }

    @Test
    fun dark_card_has_dark_surface_background() {
        assertEquals(DARK_CARD_PALETTE.background, cornerPixel(darkTheme = true))
    }

    @Test
    fun dark_card_puts_garments_on_a_light_tile() {
        // Dark garments (default long pants, navy) vanish on the dark surface
        // without it.
        assertEquals(DARK_CARD_PALETTE.iconTile, tilePixel(darkTheme = true))
    }

    @Test
    fun light_card_has_no_tile() {
        assertEquals(android.graphics.Color.WHITE, tilePixel(darkTheme = false))
    }
}
