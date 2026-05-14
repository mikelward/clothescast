package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.ThemeMode

@Composable
internal fun DisplayContent(
    themeMode: ThemeMode,
    padding: PaddingValues,
    onSetThemeMode: (ThemeMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
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
    }
}

private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_display_theme_system
    ThemeMode.LIGHT -> R.string.settings_display_theme_light
    ThemeMode.DARK -> R.string.settings_display_theme_dark
}
