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
 * chart overlay colours and the confidence-tier chip backgrounds. Material's
 * scheme doesn't extend cleanly to "I need three distinguishable overlay hues
 * for the consulted forecast models," so we carry our own palette alongside
 * the theme and provide it via [LocalAppPalette]. Callers in the
 * `app.clothescast.ui.today` package read [modelColors] for the chart line
 * fills and [confidence] for the chip / callout backgrounds — flipping between
 * [rainbowPalette] and [accessiblePalette] swaps every consumer in
 * lockstep.
 */
data class AppPalette(
    val modelColors: Map<String, Color>,
    val confidence: Map<ForecastConfidence, ConfidenceColors>,
)

/**
 * The "Rainbow" palette mirrors the colours the app shipped before the
 * Display-settings toggle existed: ECMWF pink (`#D81B60`), GFS orange
 * (`#FB8C00`), ICON green (`#43A047`), and grey for the best-match overlay.
 * Confidence backgrounds fall through to Material's scheme containers —
 * `secondaryContainer` for HIGH, `surfaceVariant` for MEDIUM,
 * `errorContainer` for LOW — so dynamic colour stays in play on Android
 * 12+.
 */
internal fun rainbowPalette(scheme: ColorScheme): AppPalette = AppPalette(
    modelColors = mapOf(
        "ecmwf_ifs04" to Color(0xFFD81B60),
        "gfs_seamless" to Color(0xFFFB8C00),
        "icon_seamless" to Color(0xFF43A047),
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
}
