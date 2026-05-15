package app.clothescast.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.PerModelHourly.Companion.BEST_MATCH_MODEL_ID

/**
 * Background/foreground pair used by [app.clothescast.ui.today.ConfidenceChip] and
 * the matching low-confidence callout. Two colours so the chip can render text on
 * the tinted background without the theme picking an unintended contrast pair.
 */
data class ConfidenceColors(val background: Color, val foreground: Color)

/**
 * App-specific colour choices that aren't part of [ColorScheme] — the per-model
 * chart overlay colours, the blended "Combined" main-line colour, and the
 * confidence-tier chip backgrounds. Material's scheme doesn't extend cleanly
 * to "I need three distinguishable overlay hues for the consulted forecast
 * models," so we carry our own palette alongside the theme and provide it via
 * [LocalAppPalette]. Callers in the `app.clothescast.ui.today` package read
 * [modelColors] for the chart line fills, [confidence] for the chip / callout
 * backgrounds, and [mainLineColor] for the blended main line.
 *
 * [mainLineColor] is nullable: `null` means "fall back to
 * `MaterialTheme.colorScheme.primary`," which keeps Rainbow and Accessible's
 * existing behaviour. Palettes that need the Combined line to stand out
 * against their own overlay hues (e.g. [highlighterPalette], whose cyan
 * ICON line and the default theme primary are both blue) set this
 * explicitly. Read it via [AppTheme.mainLineColor] rather than dereferencing
 * directly — that helper resolves the fallback for you.
 */
data class AppPalette(
    val modelColors: Map<String, Color>,
    val confidence: Map<ForecastConfidence, ConfidenceColors>,
    val mainLineColor: Color? = null,
)

/**
 * The "Rainbow" palette mirrors the colours the app shipped before the
 * Display-settings toggle existed: ECMWF pink (`#D81B60`), GFS orange
 * (`#FB8C00`), ICON green (`#43A047`), and grey for the best-match overlay.
 * Confidence backgrounds fall through to Material's scheme containers —
 * `secondaryContainer` for HIGH, `surfaceVariant` for MEDIUM,
 * `errorContainer` for LOW — so dynamic colour stays in play on Android
 * 12+.
 *
 * Additional global models (GEM / ARPEGE / UKMO / JMA / BOM) added with the
 * Forecasters picker pull from Material-600 saturated hues hand-tuned for
 * distinguishability against the original trio and against each other:
 * purple, blue, amber, teal, brown. Eight chart lines is visually busy and
 * we'd suggest the user not enable all of them, but the palette covers it
 * so the chart never crashes on a `modelColors.getValue` lookup.
 */
internal fun rainbowPalette(scheme: ColorScheme): AppPalette = AppPalette(
    modelColors = mapOf(
        "ecmwf_ifs04" to Color(0xFFD81B60),
        "gfs_seamless" to Color(0xFFFB8C00),
        "icon_seamless" to Color(0xFF43A047),
        "gem_seamless" to Color(0xFF8E24AA),
        "meteofrance_seamless" to Color(0xFF1E88E5),
        "ukmo_seamless" to Color(0xFFFBC02D),
        "jma_seamless" to Color(0xFF00897B),
        "bom_access_global" to Color(0xFF6D4C41),
        BEST_MATCH_MODEL_ID to Color(0xFF9E9E9E),
    ),
    confidence = mapOf(
        ForecastConfidence.HIGH to ConfidenceColors(
            background = scheme.secondaryContainer,
            foreground = scheme.onSecondaryContainer,
        ),
        ForecastConfidence.MEDIUM to ConfidenceColors(
            background = scheme.surfaceVariant,
            foreground = scheme.onSurfaceVariant,
        ),
        ForecastConfidence.LOW to ConfidenceColors(
            background = scheme.errorContainer,
            foreground = scheme.onErrorContainer,
        ),
    ),
)

/**
 * The "Accessible" palette is Okabe-Ito-derived and designed to stay
 * distinguishable under all three common colour-blindness types
 * (deuteranopia, protanopia, tritanopia). Chart overlay hues drop the
 * red-green pair (ECMWF pink ↔ ICON green) for vermillion / orange /
 * bluish-green, which differ in both hue *and* luminance so the lines stay
 * readable even when hue collapses. Confidence backgrounds drop the Material
 * teal-vs-red contrast — which is the textbook deuteranopia trap — for sky
 * blue (HIGH) and amber (LOW); the text labels still disambiguate, but the
 * colour cue now reinforces rather than fights the label.
 *
 * Light/dark variants are picked by hand rather than read from
 * [ColorScheme] so the chip tints stay consistent across dynamic colour
 * (Android 12+) — a Wallpaper-derived teal-ish `secondaryContainer` on the
 * device would defeat the whole point of the accessible path.
 */
internal fun accessiblePalette(darkTheme: Boolean): AppPalette = AppPalette(
    modelColors = mapOf(
        // Okabe-Ito vermillion, orange, bluish-green — all three readable on
        // both light and dark surfaces and distinguishable under deutan / protan
        // / tritan simulations.
        "ecmwf_ifs04" to Color(0xFFD55E00),
        "gfs_seamless" to Color(0xFFE69F00),
        "icon_seamless" to Color(0xFF009E73),
        // Additional Okabe-Ito-derived hues for the models added in the
        // Forecasters picker. Yellow (#F0E442) is famously low-contrast on
        // white in the original palette, so UKMO drops to a dark amber that
        // still reads as the same hue family but clears WCAG 3:1 against
        // the light surfaceContainer. BOM uses a coffee brown — outside
        // Okabe-Ito strictly but with sufficient hue separation from the
        // rest to stay distinguishable under all three CVD profiles.
        "gem_seamless" to Color(0xFF0072B2),
        "meteofrance_seamless" to Color(0xFF56B4E9),
        "ukmo_seamless" to Color(0xFFB47C00),
        "jma_seamless" to Color(0xFFCC79A7),
        "bom_access_global" to Color(0xFF6F4E37),
        BEST_MATCH_MODEL_ID to if (darkTheme) Color(0xFFBFBFBF) else Color(0xFF595959),
    ),
    confidence = if (darkTheme) {
        mapOf(
            ForecastConfidence.HIGH to ConfidenceColors(
                background = Color(0xFF0B3D62),
                foreground = Color(0xFFCFE5F4),
            ),
            ForecastConfidence.MEDIUM to ConfidenceColors(
                background = Color(0xFF3A3A3F),
                foreground = Color(0xFFE3E3E6),
            ),
            ForecastConfidence.LOW to ConfidenceColors(
                background = Color(0xFF6B3A00),
                foreground = Color(0xFFFBE2C9),
            ),
        )
    } else {
        mapOf(
            ForecastConfidence.HIGH to ConfidenceColors(
                background = Color(0xFFCFE5F4),
                foreground = Color(0xFF0B3D62),
            ),
            ForecastConfidence.MEDIUM to ConfidenceColors(
                background = Color(0xFFE5E5E8),
                foreground = Color(0xFF1B1C1F),
            ),
            ForecastConfidence.LOW to ConfidenceColors(
                background = Color(0xFFFBE2C9),
                foreground = Color(0xFF6B3A00),
            ),
        )
    },
)

/**
 * "Highlighter" — a neon magenta / yellow / cyan triad for users who want a
 * chart that's *obviously* different from Rainbow at a glance. The vibe is
 * arcade-cabinet / Tron-readout: saturated hues that look like they're
 * glowing rather than printed.
 *
 * Why these three specifically:
 *
 *  - **Hot magenta** (`#FF2D95`) sits on the red end of the wheel far enough
 *    from Rainbow's pink that the two don't read as the same hue family on a
 *    phone screen.
 *  - **Highlighter yellow** (`#FFEB3B`) is the bright slot in the classic
 *    CMY (Tron-canon) trio. The trade-off is tritanopia: cyan-vs-yellow
 *    collapses on the blue-yellow CVD axis. Tritanopia affects <0.01% of
 *    males, so the deutan / protan / "no CVD" majority still gets the
 *    cleanest distinction.
 *  - **Electric cyan** (`#00E5FF`) is the canonical "Tron blue", and the
 *    high luminance keeps it distinct from magenta and yellow.
 *  - best_match stays neutral grey, same reasoning as the other palettes —
 *    it's an Open-Meteo auto-selection comparison, not a consulted model.
 *
 * **Theme-aware luminance.** Neon hues need a dark backdrop to read — the
 * full-saturation set above lands at ~1.2:1 contrast against the light
 * theme's near-white `surfaceContainer` card, which makes the yellow and
 * cyan lines practically invisible. The dark theme keeps the full neon; the
 * light theme uses deeper saturated counterparts that clear WCAG 1.4.11's
 * 3:1 minimum for graphical objects while staying in the same hue families:
 *
 *  - light ECMWF `#FF2D95` (3.2:1) — same as dark, already passes
 *  - light GFS    `#B58A00` (3.5:1) — saturated dark amber
 *  - light ICON   `#0277BD` (3.8:1) — deep electric blue
 *  - light best   `#2A2A2A`         — neutral dark grey
 *
 * **CVD coverage** (applies to both light and dark variants, since the hue
 * families are preserved):
 *
 *  - Deuteranopia / protanopia (~7% of males): magenta + yellow + cyan stay
 *    distinguishable — magenta has a strong blue component, yellow lies on
 *    the high-luminance red-green centroid, cyan sits in blue-yellow space.
 *    No red-vs-green pair to collapse.
 *  - Tritanopia (<0.01% of males): cyan + yellow is the textbook
 *    blue-yellow collision and these two can read as similar. Accepted
 *    trade-off — Accessible and the future tritan-safe variants exist for
 *    that use case.
 *
 * **Combined main line.** The blended Combined line normally renders in
 * `MaterialTheme.colorScheme.primary` (a blue), which on Highlighter stacks
 * against both the cyan ICON line and the charcoal Auto best_match. We
 * route it through [AppPalette.mainLineColor] so this palette can pick a
 * distinct hue: deep purple on light, pure white on dark.
 *
 * **Confidence backgrounds** are hand-picked to match the neon mood:
 * pale-cyan HIGH, neutral MEDIUM, pale-magenta LOW, following the same
 * light-and-dark inversion contract as [accessiblePalette]. Hard-coded so
 * dynamic colour on Android 12+ can't dilute the look.
 */
internal fun highlighterPalette(darkTheme: Boolean): AppPalette = AppPalette(
    modelColors = if (darkTheme) {
        mapOf(
            // Full neon on dark — chart card is dark, every line glows.
            "ecmwf_ifs04" to Color(0xFFFF2D95),
            "gfs_seamless" to Color(0xFFFFEB3B),
            "icon_seamless" to Color(0xFF00E5FF),
            // Five more neon-ish hues for the Forecasters picker additions.
            // Saturation stays high; luminance is similar to the original
            // trio so they read as the same arcade-cabinet family.
            // Distinguishability across 8 lines is a stretch — at full
            // selection the chart turns into a noisy rainbow — but every
            // pair stays separable, and most users will pick 3-4 models.
            "gem_seamless" to Color(0xFFB388FF),
            "meteofrance_seamless" to Color(0xFF00FFA1),
            "ukmo_seamless" to Color(0xFFFF7043),
            "jma_seamless" to Color(0xFFFF6EC7),
            "bom_access_global" to Color(0xFFFFCA28),
            BEST_MATCH_MODEL_ID to Color(0xFFEAEAEA),
        )
    } else {
        mapOf(
            // Same hue families as the dark set, darkened just enough to
            // clear 3:1 contrast (WCAG 1.4.11) against the light
            // surfaceContainer card. Magenta already passes at full neon
            // (luminance ≈ 0.25); yellow and cyan don't, so they drop to
            // dark amber and deep electric blue. The palette still reads
            // as "neon-ish" because saturation stays high — only luminance
            // shifts.
            "ecmwf_ifs04" to Color(0xFFFF2D95),
            "gfs_seamless" to Color(0xFFB58A00),
            "icon_seamless" to Color(0xFF0277BD),
            // Light-theme variants of the Forecasters additions — same hue
            // family as the dark map, darkened to clear the 3:1 contrast
            // floor on the near-white surfaceContainer.
            "gem_seamless" to Color(0xFF6A1B9A),
            "meteofrance_seamless" to Color(0xFF2E7D32),
            "ukmo_seamless" to Color(0xFFBF360C),
            "jma_seamless" to Color(0xFFAD1457),
            "bom_access_global" to Color(0xFFE65100),
            BEST_MATCH_MODEL_ID to Color(0xFF2A2A2A),
        )
    },
    // The Combined main line lives outside the model-overlay trio, but it
    // sits on the same chart and needs to read as distinct from both the
    // cyan ICON line and the pale Auto best_match line. Material's theme
    // primary (a blue) collapses against the cyan, so we route it through
    // the palette: deep purple on light theme, light purple on dark. We
    // can't use pure white on dark because the best_match overlay is
    // #EAEAEA — the two reads as the same pale line. Material's
    // recommended dark-theme purple (`#BB86FC`) sits clearly off the
    // grey-white axis while staying high-luminance enough to read against
    // a dark surface.
    mainLineColor = if (darkTheme) Color(0xFFBB86FC) else Color(0xFF6200EA),
    confidence = if (darkTheme) {
        mapOf(
            ForecastConfidence.HIGH to ConfidenceColors(
                background = Color(0xFF015564),
                foreground = Color(0xFFC9F0F6),
            ),
            ForecastConfidence.MEDIUM to ConfidenceColors(
                background = Color(0xFF3A3A3F),
                foreground = Color(0xFFE3E3E6),
            ),
            ForecastConfidence.LOW to ConfidenceColors(
                background = Color(0xFF7A1448),
                foreground = Color(0xFFF8C9E1),
            ),
        )
    } else {
        mapOf(
            ForecastConfidence.HIGH to ConfidenceColors(
                background = Color(0xFFC9F0F6),
                foreground = Color(0xFF015564),
            ),
            ForecastConfidence.MEDIUM to ConfidenceColors(
                background = Color(0xFFE5E5E8),
                foreground = Color(0xFF1B1C1F),
            ),
            ForecastConfidence.LOW to ConfidenceColors(
                background = Color(0xFFF8C9E1),
                foreground = Color(0xFF7A1448),
            ),
        )
    },
)

/**
 * CompositionLocal carrying the active [AppPalette]. [ClothesCastTheme]
 * provides it; downstream composables read it via [LocalAppPalette.current]
 * (or the [AppTheme.palette] shorthand). The fallback default isn't user
 * facing — anything reading the local outside a [ClothesCastTheme] is
 * misconfigured — so we error rather than silently producing the light
 * default palette.
 */
val LocalAppPalette = compositionLocalOf<AppPalette> {
    error("LocalAppPalette not provided — wrap your content in ClothesCastTheme")
}

/** Shortcut for reading the active palette inside composables. */
object AppTheme {
    val palette: AppPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalAppPalette.current

    /**
     * Effective blended-main-line colour for the current palette: the
     * palette's [AppPalette.mainLineColor] when set, otherwise Material's
     * scheme primary. Use this in chart wrappers instead of reading
     * `MaterialTheme.colorScheme.primary` directly so palettes can override
     * the main line when their model-overlay hues already include the
     * primary's blue.
     */
    val mainLineColor: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalAppPalette.current.mainLineColor
            ?: androidx.compose.material3.MaterialTheme.colorScheme.primary
}

/** Resolves the palette to provide given the active scheme and user pick. */
@Composable
internal fun rememberAppPalette(
    scheme: ColorScheme,
    darkTheme: Boolean,
    colorPalette: ColorPalette,
): AppPalette = appPaletteFor(scheme, darkTheme, colorPalette)

/** Non-`@Composable` accessor used by tests that don't spin up a Compose runtime. */
internal fun appPaletteFor(
    scheme: ColorScheme,
    darkTheme: Boolean,
    colorPalette: ColorPalette,
): AppPalette = when (colorPalette) {
    ColorPalette.RAINBOW -> rainbowPalette(scheme)
    ColorPalette.ACCESSIBLE -> accessiblePalette(darkTheme)
    ColorPalette.HIGHLIGHTER -> highlighterPalette(darkTheme)
}
