package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.ThemeMode

@Composable
internal fun DisplayContent(
    themeMode: ThemeMode,
    showModelSpread: Boolean,
    padding: PaddingValues,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetShowModelSpread: (Boolean) -> Unit,
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
        SectionCard(title = stringResource(R.string.settings_display_charts_title)) {
            ModelSpreadToggle(
                checked = showModelSpread,
                onCheckedChange = onSetShowModelSpread,
            )
            Text(
                text = stringResource(R.string.settings_display_model_spread_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelSpreadToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_display_model_spread_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_display_theme_system
    ThemeMode.LIGHT -> R.string.settings_display_theme_light
    ThemeMode.DARK -> R.string.settings_display_theme_dark
}
