package app.clothescast.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.PerModelHourly.Companion.BEST_MATCH_MODEL_ID
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for [AppPalette]. These pin the contract every consumer
 * relies on:
 *
 * - both palettes carry an entry for every model id the charts draw, so the
 *   `.getValue(modelId)` lookups in `ForecastChart` / `PrecipitationChart` /
 *   `ModelSpreadLegend` can't NPE under a future palette refactor;
 * - both palettes carry an entry for every [ForecastConfidence] tier;
 * - the [ColorPalette.ACCESSIBLE] palette doesn't accidentally re-use the
 *   [ColorPalette.RAINBOW] pink/orange/green model trio (which is the entire
 *   point of having two palettes).
 */
class AppPaletteTest {

    private val scheme = lightColorScheme()

    @Test
    fun `RAINBOW palette carries every model id and every confidence tier`() {
        val palette = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.RAINBOW)
        palette.modelColors.keys shouldBe setOf(
            "ecmwf_ifs04",
            "gfs_seamless",
            "icon_seamless",
            BEST_MATCH_MODEL_ID,
        )
        palette.confidence.keys shouldBe ForecastConfidence.entries.toSet()
        palette.confidence.size shouldBe 3
    }

    @Test
    fun `ACCESSIBLE palette carries every model id and every confidence tier`() {
        val palette = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.ACCESSIBLE)
        palette.modelColors.keys shouldBe setOf(
            "ecmwf_ifs04",
            "gfs_seamless",
            "icon_seamless",
            BEST_MATCH_MODEL_ID,
        )
        palette.confidence.keys shouldBe ForecastConfidence.entries.toSet()
    }

    @Test
    fun `ACCESSIBLE palette swaps the model trio away from the RAINBOW hues`() {
        val rainbow = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.RAINBOW)
        val accessible = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.ACCESSIBLE)
        // The Rainbow trio is the deuteranopia-hostile pink/green pair plus
        // orange. The Accessible path must pick different hues for at least
        // ECMWF and ICON — those are the two that collide under red-green
        // colour blindness. GFS orange happens to already be safe but the
        // Accessible palette still re-picks it for consistency, so assert
        // all three differ from their Rainbow counterparts.
        accessible.modelColors.getValue("ecmwf_ifs04") shouldNotBe rainbow.modelColors.getValue("ecmwf_ifs04")
        accessible.modelColors.getValue("icon_seamless") shouldNotBe rainbow.modelColors.getValue("icon_seamless")
        accessible.modelColors.getValue("gfs_seamless") shouldNotBe rainbow.modelColors.getValue("gfs_seamless")
    }

    @Test
    fun `ACCESSIBLE palette swaps the LOW confidence background away from Material error red`() {
        // The Rainbow LOW chip uses errorContainer (red-tinted on the stock
        // light scheme). The Accessible palette drops that for an amber so
        // the HIGH-vs-LOW contrast doesn't rely on the red-green axis. Pin
        // the exact light-mode amber so a future tweak that re-introduces a
        // red here trips a test, not a colour-blind user.
        val accessible = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.ACCESSIBLE)
        accessible.confidence.getValue(ForecastConfidence.LOW).background shouldBe Color(0xFFFBE2C9)
        accessible.confidence.getValue(ForecastConfidence.HIGH).background shouldBe Color(0xFFCFE5F4)
    }

    @Test
    fun `dark ACCESSIBLE palette inverts background and foreground for legibility`() {
        val light = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.ACCESSIBLE)
        val dark = appPaletteFor(scheme, darkTheme = true, colorPalette = ColorPalette.ACCESSIBLE)
        val lightHigh = light.confidence.getValue(ForecastConfidence.HIGH)
        val darkHigh = dark.confidence.getValue(ForecastConfidence.HIGH)
        // The light tier uses a pale tint behind dark text; the dark tier
        // flips that — saturated dark behind pale text — so the chips stay
        // legible in either theme. Pinning equality both ways guards against
        // a one-sided update that leaves dark mode showing pale-on-pale.
        darkHigh.background shouldBe lightHigh.foreground
        darkHigh.foreground shouldBe lightHigh.background
    }
}
