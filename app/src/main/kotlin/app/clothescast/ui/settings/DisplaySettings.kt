package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.ui.EdgeFadeOverlay

@Composable
internal fun DisplayContent(
    themeMode: ThemeMode,
    colorPalette: ColorPalette,
    padding: PaddingValues,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetColorPalette: (ColorPalette) -> Unit,
) {
    val scrollState = rememberScrollState()
    EdgeFadeOverlay(
        scrollState = scrollState,
        modifier = Modifier.padding(padding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = stringResource(R.string.settings_display_theme_title)) {
                ThemeMode.entries.forEach { mode ->
                    RadioRow(
                        label = stringResource(themeModeLabel(mode)),
                        selected = mode == themeMode,
                        onSelect = { onSetThemeMode(mode) },
                    )
                }
            }
            SectionCard(title = stringResource(R.string.settings_display_colors_title)) {
                ColorPalette.entries.forEach { palette ->
                    RadioRow(
                        label = stringResource(colorPaletteLabel(palette)),
                        selected = palette == colorPalette,
                        onSelect = { onSetColorPalette(palette) },
                    )
                }
                Text(
                    text = stringResource(colorPaletteDescription(colorPalette)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_display_theme_system
    ThemeMode.LIGHT -> R.string.settings_display_theme_light
    ThemeMode.DARK -> R.string.settings_display_theme_dark
}

private fun colorPaletteLabel(palette: ColorPalette): Int = when (palette) {
    ColorPalette.RAINBOW -> R.string.settings_display_palette_rainbow
    ColorPalette.ACCESSIBLE -> R.string.settings_display_palette_accessible
    ColorPalette.HIGHLIGHTER -> R.string.settings_display_palette_highlighter
}

private fun colorPaletteDescription(palette: ColorPalette): Int = when (palette) {
    ColorPalette.RAINBOW -> R.string.settings_display_palette_rainbow_description
    ColorPalette.ACCESSIBLE -> R.string.settings_display_palette_accessible_description
    ColorPalette.HIGHLIGHTER -> R.string.settings_display_palette_highlighter_description
}
