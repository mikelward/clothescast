package app.clothescast.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.clothescast.core.domain.model.ColorPalette

// TODO: Consider unifying this with the brand blue. The logo / launcher
// icons / Play artwork use the clothes palette's medium blue (#1E88E5);
// this UI primary is a deeper royal blue. See docs/TODO.md.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1E6FFF),
    onPrimary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1B1C1F),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002E6E),
    background = Color(0xFF101114),
    onBackground = Color(0xFFE3E3E6),
)

@Composable
fun ClothesCastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    // Defaults to [ColorPalette.RAINBOW] — the pink / orange / green model
    // lines and Material teal/red confidence chips stay in place for
    // everyone who hasn't explicitly picked [ColorPalette.ACCESSIBLE] from
    // Display settings. See [AppPalette] for what each palette swaps and
    // why.
    colorPalette: ColorPalette = ColorPalette.RAINBOW,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val palette = rememberAppPalette(
        scheme = colorScheme,
        darkTheme = darkTheme,
        colorPalette = colorPalette,
    )
    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalAppPalette provides palette, content = content)
    }
}
