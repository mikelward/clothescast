package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.R
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.insight.InsightFormatter
import app.clothescast.ui.EdgeFadeOverlay
import java.time.LocalTime
import kotlin.math.roundToInt

// Preset °C thresholds offered for the significant-change clause; null = Off.
// Stored as °C and labelled in the user's unit so the gate stays consistent
// with the rendered "5° warmer than yesterday." delta (see InsightFormatter).
private val DELTA_THRESHOLD_PRESETS: List<Double?> = listOf(null, 3.0, 5.0, 8.0)

// Feels-like delta the preview sample differs from "yesterday" by. The preview
// shows the delta clause only while the selected threshold is at or below this,
// mirroring RenderInsightSummary's gate so the user sees the clause appear and
// disappear as they change the setting.
private const val PREVIEW_DELTA_C = 5.0

@Composable
internal fun FormatPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_root_format, onBack) { padding ->
        FormatContent(
            rangeFormat = state.rangeFormat,
            deltaThresholdC = state.deltaThresholdC,
            region = state.region,
            temperatureUnit = state.temperatureUnit,
            padding = padding,
            onSetRangeFormat = viewModel::setRangeFormat,
            onSetDeltaThresholdC = viewModel::setDeltaThresholdC,
        )
    }
}

@Composable
internal fun FormatContent(
    rangeFormat: RangeFormat,
    deltaThresholdC: Double?,
    region: Region,
    temperatureUnit: TemperatureUnit,
    padding: PaddingValues,
    onSetRangeFormat: (RangeFormat) -> Unit,
    onSetDeltaThresholdC: (Double?) -> Unit,
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
            PreviewCard(rangeFormat, deltaThresholdC, region, temperatureUnit)
            SectionCard(title = stringResource(R.string.settings_format_what_to_say)) {
                FormatDropdownRow(
                    label = stringResource(R.string.settings_format_range_label),
                    options = RangeFormat.entries,
                    selected = rangeFormat,
                    optionLabel = { stringResource(rangeFormatLabel(it)) },
                    onSelect = onSetRangeFormat,
                )
                FormatDropdownRow(
                    label = stringResource(R.string.settings_format_change_label),
                    options = DELTA_THRESHOLD_PRESETS,
                    selected = deltaThresholdC,
                    optionLabel = { thresholdLabel(it, temperatureUnit) },
                    onSelect = onSetDeltaThresholdC,
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(
    rangeFormat: RangeFormat,
    deltaThresholdC: Double?,
    region: Region,
    temperatureUnit: TemperatureUnit,
) {
    val context = LocalContext.current
    val formatter = remember(context, region, temperatureUnit, rangeFormat) {
        InsightFormatter.forRegion(context, region, temperatureUnit, rangeFormat)
    }
    // Drop the delta clause when the selected threshold is above the sample's
    // delta, so the preview reflects the significant-change setting too.
    val showDelta = deltaThresholdC != null && PREVIEW_DELTA_C >= deltaThresholdC
    val sample = InsightSummary(
        period = ForecastPeriod.TODAY,
        band = BandClause(
            low = TemperatureBand.COOL,
            high = TemperatureBand.MILD,
            feelsLikeMinC = 12.0,
            feelsLikeMaxC = 20.0,
        ),
        delta = if (showDelta) {
            DeltaClause(degrees = PREVIEW_DELTA_C.roundToInt(), direction = DeltaClause.Direction.WARMER)
        } else {
            null
        },
        clothes = ClothesClause(items = listOf("sweater")),
        precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(17, 0), PrecipLikelihood.LIKELY),
    )
    SectionCard(title = stringResource(R.string.settings_format_preview_title)) {
        // Match the Today screen's insight card (headlineSmall) so the preview
        // reads like the real thing — larger than body text, normal weight.
        Text(
            text = formatter.format(sample),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun <T> FormatDropdownRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(optionLabel(selected))
                Icon(imageVector = Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

private fun rangeFormatLabel(format: RangeFormat): Int = when (format) {
    RangeFormat.NONE -> R.string.settings_format_range_none
    RangeFormat.DEGREES -> R.string.settings_format_range_degrees
    RangeFormat.BANDS -> R.string.settings_format_range_bands
}

@Composable
private fun thresholdLabel(thresholdC: Double?, unit: TemperatureUnit): String {
    if (thresholdC == null) return stringResource(R.string.settings_format_change_off)
    // Temperature *differences* convert with the ratio only (no +32 offset),
    // matching InsightFormatter.formatDelta — so a Fahrenheit user sees 5° / 9° /
    // 14° for the same 3° / 5° / 8° °C presets.
    val shown = when (unit) {
        TemperatureUnit.CELSIUS -> thresholdC.roundToInt()
        TemperatureUnit.FAHRENHEIT -> (thresholdC * 9.0 / 5.0).roundToInt()
    }
    return stringResource(R.string.settings_format_change_degrees, shown)
}
