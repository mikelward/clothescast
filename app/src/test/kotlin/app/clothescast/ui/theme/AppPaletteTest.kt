package app.clothescast.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.ForecastModel
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
        palette.modelColors.keys shouldBe expectedModelKeys()
        palette.confidence.keys shouldBe ForecastConfidence.entries.toSet()
        palette.confidence.size shouldBe 3
    }

    @Test
    fun `ACCESSIBLE palette carries every model id and every confidence tier`() {
        val palette = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.ACCESSIBLE)
        palette.modelColors.keys shouldBe expectedModelKeys()
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

    @Test
    fun `HIGHLIGHTER palette carries every model id and every confidence tier`() {
        val palette = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.HIGHLIGHTER)
        palette.modelColors.keys shouldBe expectedModelKeys()
        palette.confidence.keys shouldBe ForecastConfidence.entries.toSet()
    }

    /**
     * Every [ForecastModel] the user can select in Settings ▸ Forecasters
     * plus the synthetic best-match overlay. Derived from the enum so adding
     * a new model in the domain layer trips this test until the palettes
     * gain a matching colour, rather than crashing at chart render via a
     * `.getValue(modelId)` lookup miss.
     */
    private fun expectedModelKeys(): Set<String> =
        ForecastModel.entries.map { it.openMeteoId }.toSet() + BEST_MATCH_MODEL_ID

    @Test
    fun `HIGHLIGHTER palette uses distinct hues from both RAINBOW and ACCESSIBLE`() {
        val rainbow = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.RAINBOW)
        val accessible = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.ACCESSIBLE)
        val highlighter = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.HIGHLIGHTER)
        // The whole point of Highlighter is visible difference from Rainbow
        // at a glance, so every consulted-model hue must differ from both
        // other palettes' equivalents.
        for (model in listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless")) {
            highlighter.modelColors.getValue(model) shouldNotBe rainbow.modelColors.getValue(model)
            highlighter.modelColors.getValue(model) shouldNotBe accessible.modelColors.getValue(model)
        }
    }

    @Test
    fun `HIGHLIGHTER dark palette pins the neon magenta yellow cyan hues`() {
        // Pin the exact dark-theme hex values that drive the "visibly
        // different from Rainbow" guarantee. Drift on any of these is a UX
        // regression, not a refactor — call it out at test level so a
        // future palette tweak surfaces in the diff. The light-theme
        // variant uses darker counterparts for WCAG contrast; see the
        // separate light-theme test.
        val palette = appPaletteFor(scheme, darkTheme = true, colorPalette = ColorPalette.HIGHLIGHTER)
        palette.modelColors.getValue("ecmwf_ifs04") shouldBe Color(0xFFFF2D95)
        palette.modelColors.getValue("gfs_seamless") shouldBe Color(0xFFFFEB3B)
        palette.modelColors.getValue("icon_seamless") shouldBe Color(0xFF00E5FF)
    }

    @Test
    fun `HIGHLIGHTER light palette uses deeper counterparts that pass WCAG contrast`() {
        // Light theme keeps magenta at full neon (already passes 3:1) but
        // drops the yellow and cyan slots to deeper hues so they aren't
        // washed out against the near-white card surface.
        val palette = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.HIGHLIGHTER)
        palette.modelColors.getValue("ecmwf_ifs04") shouldBe Color(0xFFFF2D95)
        palette.modelColors.getValue("gfs_seamless") shouldBe Color(0xFFB58A00)
        palette.modelColors.getValue("icon_seamless") shouldBe Color(0xFF0277BD)
    }

    @Test
    fun `HIGHLIGHTER palette overrides the Combined main-line colour`() {
        // Rainbow and Accessible leave mainLineColor null (fallback to
        // Material primary). Highlighter's ICON cyan crowds the theme
        // primary blue on the same chart, and the pale Auto best_match
        // overlay (#EAEAEA on dark) would crowd pure white — so it sets
        // the main line explicitly. Deep purple on light, Material's
        // recommended dark-theme purple #BB86FC on dark: both sit clearly
        // off the grey-white axis while staying readable against the
        // surface.
        val light = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.HIGHLIGHTER)
        val dark = appPaletteFor(scheme, darkTheme = true, colorPalette = ColorPalette.HIGHLIGHTER)
        light.mainLineColor shouldBe Color(0xFF6200EA)
        dark.mainLineColor shouldBe Color(0xFFBB86FC)
    }

    @Test
    fun `RAINBOW and ACCESSIBLE palettes leave the Combined main-line colour to the theme`() {
        val rainbow = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.RAINBOW)
        val accessible = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.ACCESSIBLE)
        rainbow.mainLineColor shouldBe null
        accessible.mainLineColor shouldBe null
    }

    @Test
    fun `dark HIGHLIGHTER palette inverts background and foreground for legibility`() {
        val light = appPaletteFor(scheme, darkTheme = false, colorPalette = ColorPalette.HIGHLIGHTER)
        val dark = appPaletteFor(scheme, darkTheme = true, colorPalette = ColorPalette.HIGHLIGHTER)
        val lightHigh = light.confidence.getValue(ForecastConfidence.HIGH)
        val darkHigh = dark.confidence.getValue(ForecastConfidence.HIGH)
        // Same legibility contract as the Accessible palette: pale tint on
        // dark text light-mode, saturated dark on pale text dark-mode.
        darkHigh.background shouldBe lightHigh.foreground
        darkHigh.foreground shouldBe lightHigh.background
    }
}
