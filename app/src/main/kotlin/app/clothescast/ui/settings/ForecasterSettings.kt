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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.defaultsFor

/**
 * Minimum number of models the confidence fetcher needs to compute a spread.
 * The picker disables a checkbox's "uncheck" path when the user is at this
 * floor with the box still checked, so they can't deselect their way below
 * it. [MultiModelConfidenceFetcher] would silently return null without two
 * usable models; the floor keeps the chip working without a settings-side
 * "you've broken something" affordance.
 */
private const val MIN_MODELS = 2

/**
 * Cap on the number of models the picker accepts. The chart palette covers
 * all eight [ForecastModel] entries, so this isn't a "we'd crash above" limit
 * — it's a readability floor. Five overlay lines on the per-model chart is
 * already busy; eight turns it into a rainbow nobody can read. The picker
 * disables unchecked rows when the user is at the cap, with a one-line hint
 * directing them to deselect first.
 */
private const val MAX_MODELS = 5

@Composable
internal fun ForecastersContent(
    forecastModels: Set<ForecastModel>?,
    location: Location?,
    padding: PaddingValues,
    onSetForecastModels: (Set<ForecastModel>?) -> Unit,
) {
    val context = LocalContext.current
    // When the stored selection is null we're in Auto mode: derive a
    // location-aware trio for display via [ForecastModel.defaultsFor] and
    // disable the per-model checkboxes. When non-null, the user has
    // explicitly customised: show their set, enable the checkboxes, and
    // the Auto switch is off. Toggling Auto on clears the stored set to
    // null; toggling any checkbox switches to a non-null effective set
    // with the toggle applied (so an Auto user who unchecks one model
    // ends up explicitly customised, starting from what Auto picked).
    val isAuto = forecastModels == null
    val effective = forecastModels ?: ForecastModel.defaultsFor(location)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = stringResource(R.string.settings_forecasters_title)) {
            Text(
                text = stringResource(R.string.settings_forecasters_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AutoSwitchRow(
                isAuto = isAuto,
                onToggle = { nowAuto ->
                    if (nowAuto) {
                        onSetForecastModels(null)
                    } else {
                        // Flipping Auto off persists whatever Auto was
                        // already resolving to, so the visible checkboxes
                        // don't suddenly jump — the user then explicitly
                        // edits from that starting point.
                        onSetForecastModels(effective)
                    }
                },
            )
            HorizontalDivider()
            val atCap = effective.size >= MAX_MODELS
            if (atCap && !isAuto) {
                Text(
                    text = stringResource(R.string.settings_forecasters_cap_hint, MAX_MODELS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ForecastModel.entries.forEach { model ->
                val checked = model in effective
                // Auto mode: every row reflects the resolver's pick but is
                // not toggleable — user has to flip Auto off first. Custom
                // mode: block unchecking when we'd drop below MIN_MODELS,
                // block checking when we'd exceed MAX_MODELS. An already-
                // checked row stays interactable above the cap so the user
                // can still toggle it off to free a slot.
                val canToggleOff = effective.size > MIN_MODELS
                val canToggleOn = !atCap
                val enabled = !isAuto && (if (checked) canToggleOff else canToggleOn)
                ForecasterRow(
                    label = stringResource(forecastModelLabel(model)),
                    subtitle = stringResource(forecastModelSubtitle(model)),
                    checked = checked,
                    enabled = enabled,
                    onToggle = { nowChecked ->
                        val updated = if (nowChecked) effective + model else effective - model
                        if (updated.size in MIN_MODELS..MAX_MODELS) onSetForecastModels(updated)
                    },
                )
            }
            TextButton(
                onClick = { openUrl(context, OPEN_METEO_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_about_open_meteo)) }
        }
    }
}

@Composable
private fun AutoSwitchRow(
    isAuto: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = isAuto,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_forecasters_auto_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_forecasters_auto_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = isAuto, onCheckedChange = null)
    }
}

@Composable
private fun ForecasterRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onToggle,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun forecastModelLabel(model: ForecastModel): Int = when (model) {
    ForecastModel.ECMWF_IFS04 -> R.string.settings_forecasters_ecmwf
    ForecastModel.ICON_SEAMLESS -> R.string.settings_forecasters_icon
    ForecastModel.GFS_SEAMLESS -> R.string.settings_forecasters_gfs
    ForecastModel.GEM_SEAMLESS -> R.string.settings_forecasters_gem
    ForecastModel.METEOFRANCE_SEAMLESS -> R.string.settings_forecasters_arpege
    ForecastModel.UKMO_SEAMLESS -> R.string.settings_forecasters_ukmo
    ForecastModel.JMA_SEAMLESS -> R.string.settings_forecasters_jma
}

private fun forecastModelSubtitle(model: ForecastModel): Int = when (model) {
    ForecastModel.ECMWF_IFS04 -> R.string.settings_forecasters_ecmwf_subtitle
    ForecastModel.ICON_SEAMLESS -> R.string.settings_forecasters_icon_subtitle
    ForecastModel.GFS_SEAMLESS -> R.string.settings_forecasters_gfs_subtitle
    ForecastModel.GEM_SEAMLESS -> R.string.settings_forecasters_gem_subtitle
    ForecastModel.METEOFRANCE_SEAMLESS -> R.string.settings_forecasters_arpege_subtitle
    ForecastModel.UKMO_SEAMLESS -> R.string.settings_forecasters_ukmo_subtitle
    ForecastModel.JMA_SEAMLESS -> R.string.settings_forecasters_jma_subtitle
}
