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
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.PreambleVisibility
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.RainAccessory
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.insight.InsightFormatter
import app.clothescast.insight.InsightSurface
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
            periodPreamble = state.periodPreamble,
            wearPreamble = state.wearPreamble,
            rangeFormat = state.rangeFormat,
            clothesFormat = state.clothesFormat,
            bottomsFormat = state.bottomsFormat,
            rainAccessory = state.rainAccessory,
            deltaThresholdC = state.deltaThresholdC,
            clothesMentionMode = state.clothesMentionMode,
            region = state.region,
            temperatureUnit = state.temperatureUnit,
            currentInsightSummary = state.currentInsightSummary,
            padding = padding,
            onSetPeriodPreamble = viewModel::setPeriodPreamble,
            onSetWearPreamble = viewModel::setWearPreamble,
            onSetRangeFormat = viewModel::setRangeFormat,
            onSetClothesFormat = viewModel::setClothesFormat,
            onSetBottomsFormat = viewModel::setBottomsFormat,
            onSetRainAccessory = viewModel::setRainAccessory,
            onSetDeltaThresholdC = viewModel::setDeltaThresholdC,
            onSetClothesMentionMode = viewModel::setClothesMentionMode,
        )
    }
}

@Composable
internal fun FormatContent(
    periodPreamble: PreambleVisibility,
    wearPreamble: PreambleVisibility,
    rangeFormat: RangeFormat,
    clothesFormat: ClothesFormat,
    bottomsFormat: BottomsFormat,
    rainAccessory: RainAccessory,
    deltaThresholdC: Double?,
    clothesMentionMode: ClothesMentionMode,
    region: Region,
    temperatureUnit: TemperatureUnit,
    currentInsightSummary: InsightSummary? = null,
    padding: PaddingValues,
    onSetPeriodPreamble: (PreambleVisibility) -> Unit,
    onSetWearPreamble: (PreambleVisibility) -> Unit,
    onSetRangeFormat: (RangeFormat) -> Unit,
    onSetClothesFormat: (ClothesFormat) -> Unit,
    onSetBottomsFormat: (BottomsFormat) -> Unit,
    onSetRainAccessory: (RainAccessory) -> Unit,
    onSetDeltaThresholdC: (Double?) -> Unit,
    onSetClothesMentionMode: (ClothesMentionMode) -> Unit,
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
            PreviewCard(
                periodPreamble,
                wearPreamble,
                rangeFormat,
                clothesFormat,
                bottomsFormat,
                rainAccessory,
                deltaThresholdC,
                clothesMentionMode,
                region,
                temperatureUnit,
            )
            CurrentForecastPreviewCard(
                currentInsightSummary,
                region,
                temperatureUnit,
                periodPreamble,
                wearPreamble,
                rangeFormat,
                clothesFormat,
                bottomsFormat,
                rainAccessory,
            )
            SectionCard(title = stringResource(R.string.settings_format_what_to_say)) {
                // Ordered to match the insight itself: lead-in, then the
                // temperature clause (range + change), then the wear lead-in and
                // the clothes clause (mention / wording / bottoms), then rain.
                FormatDropdownRow(
                    label = stringResource(R.string.settings_format_period_preamble_label),
                    options = PreambleVisibility.entries,
                    selected = periodPreamble,
                    optionLabel = { stringResource(preambleVisibilityLabel(it)) },
                    onSelect = onSetPeriodPreamble,
                )
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
                FormatDropdownRow(
                    label = stringResource(R.string.settings_format_wear_preamble_label),
                    options = PreambleVisibility.entries,
                    selected = wearPreamble,
                    optionLabel = { stringResource(preambleVisibilityLabel(it)) },
                    onSelect = onSetWearPreamble,
                )
                FormatDropdownRow(
                    label = stringResource(R.string.settings_clothes_mention_label),
                    options = ClothesMentionMode.entries,
                    selected = clothesMentionMode,
                    optionLabel = { stringResource(clothesMentionModeLabel(it)) },
                    onSelect = onSetClothesMentionMode,
                )
                FormatDropdownRow(
                    label = stringResource(R.string.settings_format_clothes_label),
                    options = ClothesFormat.entries,
                    selected = clothesFormat,
                    optionLabel = { stringResource(clothesFormatLabel(it)) },
                    onSelect = onSetClothesFormat,
                )
                FormatDropdownRow(
                    label = stringResource(R.string.settings_format_bottoms_label),
                    options = BottomsFormat.entries,
                    selected = bottomsFormat,
                    optionLabel = { stringResource(bottomsFormatLabel(it)) },
                    onSelect = onSetBottomsFormat,
                )
                FormatDropdownRow(
                    label = stringResource(R.string.settings_format_rain_accessory_label),
                    options = RainAccessory.entries,
                    selected = rainAccessory,
                    optionLabel = { stringResource(rainAccessoryLabel(it)) },
                    onSelect = onSetRainAccessory,
                )
            }
        }
    }
}

private fun clothesMentionModeLabel(mode: ClothesMentionMode): Int = when (mode) {
    ClothesMentionMode.ALWAYS -> R.string.settings_clothes_mention_always
    ClothesMentionMode.IF_CHANGED -> R.string.settings_clothes_mention_if_changed
    ClothesMentionMode.NEVER -> R.string.settings_clothes_mention_never
}

private fun clothesFormatLabel(format: ClothesFormat): Int = when (format) {
    ClothesFormat.ITEMS -> R.string.settings_format_clothes_items
    ClothesFormat.LAYER_COUNT -> R.string.settings_format_clothes_layer_count
}

private fun bottomsFormatLabel(format: BottomsFormat): Int = when (format) {
    BottomsFormat.ALWAYS -> R.string.settings_format_bottoms_always
    BottomsFormat.IF_GARMENTS -> R.string.settings_format_bottoms_if_garments
    BottomsFormat.NEVER -> R.string.settings_format_bottoms_never
}

private fun rainAccessoryLabel(accessory: RainAccessory): Int = when (accessory) {
    RainAccessory.NONE -> R.string.settings_format_rain_accessory_none
    RainAccessory.UMBRELLA -> R.string.settings_format_rain_accessory_umbrella
}

private fun preambleVisibilityLabel(visibility: PreambleVisibility): Int = when (visibility) {
    PreambleVisibility.ALWAYS -> R.string.settings_format_preamble_always
    PreambleVisibility.SPEECH_ONLY -> R.string.settings_format_preamble_speech_only
    PreambleVisibility.NEVER -> R.string.settings_format_preamble_never
}

@Composable
private fun PreviewCard(
    periodPreamble: PreambleVisibility,
    wearPreamble: PreambleVisibility,
    rangeFormat: RangeFormat,
    clothesFormat: ClothesFormat,
    bottomsFormat: BottomsFormat,
    rainAccessory: RainAccessory,
    deltaThresholdC: Double?,
    clothesMentionMode: ClothesMentionMode,
    region: Region,
    temperatureUnit: TemperatureUnit,
) {
    val context = LocalContext.current
    val formatter = remember(
        context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat, rainAccessory,
        periodPreamble, wearPreamble,
    ) {
        InsightFormatter.forRegion(
            context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat, rainAccessory,
            periodPreamble, wearPreamble,
        )
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
        // Mirror RenderInsightSummary's mode gating: NEVER drops the clause,
        // ALWAYS keeps it, and IF_CHANGED keeps it here because the sample
        // depicts a changed day (it already shows a "warmer than yesterday"
        // delta), so today's clothes would differ from yesterday's.
        //
        // Include a bottom in the sample so the BottomsFormat picker has
        // something visible to flip: ALWAYS / IF_GARMENTS render "a sweater
        // and pants", NEVER strips the pants out.
        clothes = if (clothesMentionMode == ClothesMentionMode.NEVER) {
            null
        } else {
            ClothesClause(items = listOf("sweater", "pants"))
        },
        precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(17, 0), PrecipLikelihood.LIKELY),
    )
    SectionCard(title = stringResource(R.string.settings_format_preview_example_title)) {
        // Match the Today screen's insight card (headlineSmall) so the preview
        // reads like the real thing — larger than body text, normal weight.
        // SETTINGS_PREVIEW so a Speech-only preamble shows parenthesised rather
        // than silently vanishing.
        Text(
            text = formatter.format(sample, surface = InsightSurface.SETTINGS_PREVIEW),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

/**
 * Renders the user's *real* cached current forecast through the same formatter
 * the Today screen uses, so the format-settings preview reflects their actual
 * ClothesCast — not just the synthetic example. Every setting on this page
 * updates the preview live because the cache stores the raw upstream forecast
 * snapshot, and [SettingsViewModel] derives the [InsightSummary] reactively
 * against the preferences flow; the range-format and clothes-format settings
 * also apply at format time. Falls back to a short placeholder when nothing's
 * cached yet.
 */
@Composable
private fun CurrentForecastPreviewCard(
    summary: InsightSummary?,
    region: Region,
    temperatureUnit: TemperatureUnit,
    periodPreamble: PreambleVisibility,
    wearPreamble: PreambleVisibility,
    rangeFormat: RangeFormat,
    clothesFormat: ClothesFormat,
    bottomsFormat: BottomsFormat,
    rainAccessory: RainAccessory,
) {
    val context = LocalContext.current
    val formatter = remember(
        context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat, rainAccessory,
        periodPreamble, wearPreamble,
    ) {
        InsightFormatter.forRegion(
            context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat, rainAccessory,
            periodPreamble, wearPreamble,
        )
    }
    SectionCard(title = stringResource(R.string.settings_format_preview_current_title)) {
        Text(
            text = summary?.let { formatter.format(it, surface = InsightSurface.SETTINGS_PREVIEW) }
                ?: stringResource(R.string.settings_format_preview_current_empty),
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
