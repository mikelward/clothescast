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
import app.clothescast.ui.AppMenuShape
import app.clothescast.R
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.AccessoriesFormat
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.DeltaFormat
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.PreambleVisibility
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
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

// The temperature-change clause picker. A single dropdown selects one of: Off
// (no clause), a significant-change degree threshold (3° / 5° / 8°, rendered as
// "5° warmer than yesterday."), or Band (an absolute band callout that replaces
// the temperature sentence with "Today, it will be hot.", emitted only when
// today's high band differs from yesterday's). Band replaces the degree forms
// rather than coexisting with them — it's a different phrasing of the same
// clause.
//
// Internally the choice maps onto two preferences: [DeltaFormat] (degrees vs
// bands) and the °C threshold. Picking a degree option sets DEGREES + that
// threshold; Off clears the threshold (and keeps DEGREES so the selection reads
// back as Off, not Band); Band sets BANDS and pins the threshold to the 3°C
// default so the week-ahead headline — which keys off the same threshold — stays
// alive. Thresholds are stored as °C and labelled in the user's unit so the gate
// matches the rendered delta (see InsightFormatter).
private sealed interface ChangeOption {
    data object Off : ChangeOption
    data class Degrees(val thresholdC: Double) : ChangeOption
    data object Band : ChangeOption
}

private val CHANGE_OPTIONS: List<ChangeOption> = listOf(
    ChangeOption.Off,
    ChangeOption.Degrees(3.0),
    ChangeOption.Degrees(5.0),
    ChangeOption.Degrees(8.0),
    ChangeOption.Band,
)

// Threshold pinned when the user picks Band, so the week-ahead headline (which
// also reads deltaThresholdC) keeps firing — the band clause itself ignores it.
private const val BAND_MODE_DEFAULT_THRESHOLD_C = 3.0

private fun changeOptionFor(deltaThresholdC: Double?, deltaFormat: DeltaFormat): ChangeOption = when {
    deltaFormat == DeltaFormat.BANDS -> ChangeOption.Band
    deltaThresholdC == null -> ChangeOption.Off
    else -> ChangeOption.Degrees(deltaThresholdC)
}

private fun applyChangeOption(
    option: ChangeOption,
    onSetDeltaThresholdC: (Double?) -> Unit,
    onSetDeltaFormat: (DeltaFormat) -> Unit,
) {
    when (option) {
        ChangeOption.Off -> {
            onSetDeltaFormat(DeltaFormat.DEGREES)
            onSetDeltaThresholdC(null)
        }
        is ChangeOption.Degrees -> {
            onSetDeltaFormat(DeltaFormat.DEGREES)
            onSetDeltaThresholdC(option.thresholdC)
        }
        ChangeOption.Band -> {
            onSetDeltaFormat(DeltaFormat.BANDS)
            onSetDeltaThresholdC(BAND_MODE_DEFAULT_THRESHOLD_C)
        }
    }
}

// Feels-like delta the preview sample differs from "yesterday" by. The preview
// shows the delta clause only while the selected threshold is at or below this,
// mirroring RenderInsightSummary's gate so the user sees the clause appear and
// disappear as they change the setting.
private const val PREVIEW_DELTA_C = 5.0

@Composable
internal fun FormatPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_page_format, onBack) { padding ->
        FormatContent(
            periodPreamble = state.periodPreamble,
            wearPreamble = state.wearPreamble,
            rangeFormat = state.rangeFormat,
            clothesFormat = state.clothesFormat,
            bottomsFormat = state.bottomsFormat,
            accessoriesFormat = state.accessoriesFormat,
            deltaThresholdC = state.deltaThresholdC,
            deltaFormat = state.deltaFormat,
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
            onSetAccessoriesFormat = viewModel::setAccessoriesFormat,
            onSetDeltaThresholdC = viewModel::setDeltaThresholdC,
            onSetDeltaFormat = viewModel::setDeltaFormat,
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
    accessoriesFormat: AccessoriesFormat,
    deltaThresholdC: Double?,
    deltaFormat: DeltaFormat,
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
    onSetAccessoriesFormat: (AccessoriesFormat) -> Unit,
    onSetDeltaThresholdC: (Double?) -> Unit,
    onSetDeltaFormat: (DeltaFormat) -> Unit,
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
                accessoriesFormat,
                deltaThresholdC,
                deltaFormat,
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
                accessoriesFormat,
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
                    options = CHANGE_OPTIONS,
                    selected = changeOptionFor(deltaThresholdC, deltaFormat),
                    optionLabel = { changeOptionLabel(it, temperatureUnit) },
                    onSelect = { applyChangeOption(it, onSetDeltaThresholdC, onSetDeltaFormat) },
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
                // Carrying an umbrella is a precip-keyed clothes rule (Settings →
                // Clothes); this only governs whether the prose *speaks* it.
                // NEVER keeps the umbrella icon on the outfit card but drops the
                // spoken "bring an umbrella" — see it without hearing it.
                FormatDropdownRow(
                    label = stringResource(R.string.settings_format_accessories_label),
                    options = AccessoriesFormat.entries,
                    selected = accessoriesFormat,
                    optionLabel = { stringResource(accessoriesFormatLabel(it)) },
                    onSelect = onSetAccessoriesFormat,
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

private fun accessoriesFormatLabel(format: AccessoriesFormat): Int = when (format) {
    AccessoriesFormat.ALWAYS -> R.string.settings_format_accessories_always
    AccessoriesFormat.NEVER -> R.string.settings_format_accessories_never
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
    accessoriesFormat: AccessoriesFormat,
    deltaThresholdC: Double?,
    deltaFormat: DeltaFormat,
    clothesMentionMode: ClothesMentionMode,
    region: Region,
    temperatureUnit: TemperatureUnit,
) {
    val context = LocalContext.current
    val formatter = remember(
        context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat,
        accessoriesFormat, periodPreamble, wearPreamble,
    ) {
        InsightFormatter.forRegion(
            context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat,
            accessoriesFormat, periodPreamble, wearPreamble,
        )
    }
    // Drop the delta clause when the selected threshold is above the sample's
    // delta, so the preview reflects the significant-change setting too. In
    // band mode the callout always shows so the user sees the band wording
    // replace the temperature sentence ("Today, it will be mild."); it names
    // the sample's own high band so the replacement stays truthful.
    val showDelta = deltaThresholdC != null && PREVIEW_DELTA_C >= deltaThresholdC
    val sample = InsightSummary(
        period = ForecastPeriod.TODAY,
        band = BandClause(
            low = TemperatureBand.COOL,
            high = TemperatureBand.MILD,
            feelsLikeMinC = 12.0,
            feelsLikeMaxC = 20.0,
        ),
        delta = when (deltaFormat) {
            DeltaFormat.BANDS -> DeltaClause(
                degrees = PREVIEW_DELTA_C.roundToInt(),
                direction = DeltaClause.Direction.WARMER,
                band = TemperatureBand.MILD,
                style = DeltaClause.Style.BANDS,
            )
            DeltaFormat.DEGREES -> if (showDelta) {
                DeltaClause(degrees = PREVIEW_DELTA_C.roundToInt(), direction = DeltaClause.Direction.WARMER)
            } else {
                null
            }
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
        // Carry an umbrella so the Accessories picker has something to flip:
        // ALWAYS renders "Rain, bring an umbrella.", NEVER drops to "Rain.".
        carriedAccessories = listOf("umbrella"),
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
    accessoriesFormat: AccessoriesFormat,
) {
    val context = LocalContext.current
    val formatter = remember(
        context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat,
        accessoriesFormat, periodPreamble, wearPreamble,
    ) {
        InsightFormatter.forRegion(
            context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat,
            accessoriesFormat, periodPreamble, wearPreamble,
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
                Text(optionLabel(selected), style = MaterialTheme.typography.bodyLarge)
                Icon(imageVector = Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = AppMenuShape,
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option), style = MaterialTheme.typography.bodyLarge) },
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
private fun changeOptionLabel(option: ChangeOption, unit: TemperatureUnit): String = when (option) {
    ChangeOption.Off -> stringResource(R.string.settings_format_change_off)
    is ChangeOption.Degrees -> thresholdLabel(option.thresholdC, unit)
    ChangeOption.Band -> stringResource(R.string.settings_format_change_band)
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
