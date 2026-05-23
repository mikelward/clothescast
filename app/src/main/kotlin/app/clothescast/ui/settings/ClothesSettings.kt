package app.clothescast.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.FallbackRange
import app.clothescast.core.domain.model.FallbackTier
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.fallbackRange
import app.clothescast.core.domain.model.fromUnit
import app.clothescast.core.domain.model.symbol
import app.clothescast.core.domain.model.toUnit
import app.clothescast.ui.EdgeFadeOverlay
import app.clothescast.ui.garment.outfitBottomDefaults
import app.clothescast.ui.garment.outfitTopDefaults
import kotlin.math.roundToInt

/**
 * Lists the user's clothes rules and lets them add / edit / delete one. The
 * garment is picked from a fixed [Garment] dropdown rather than free-form
 * text — free-form names defeated translation in the German insight prose
 * (see PR that locked editing down). The dropdown labels are localised via
 * [garmentLabelRes]; the stored rule's `item` field is always the en-US
 * key (e.g. "sweater"), which the German phraser then translates to
 * "Pullover" at insight-render time.
 */
@Composable
internal fun ClothesContent(
    rules: List<ClothesRule>,
    defaultBottom: OutfitSuggestion.Bottom,
    defaultTop: OutfitSuggestion.Top,
    temperatureUnit: TemperatureUnit,
    outfitTopColors: Map<OutfitSuggestion.Top, Long>,
    outfitBottomColors: Map<OutfitSuggestion.Bottom, Long>,
    padding: PaddingValues,
    onAdd: (ClothesRule) -> Unit,
    onReplace: (Int, ClothesRule) -> Unit,
    onDelete: (Int) -> Unit,
    onSetDefaultBottom: (OutfitSuggestion.Bottom) -> Unit,
    onSetDefaultTop: (OutfitSuggestion.Top) -> Unit,
    onSetOutfitTopColor: (OutfitSuggestion.Top, Long?) -> Unit,
    onSetOutfitBottomColor: (OutfitSuggestion.Bottom, Long?) -> Unit,
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
            ClothesRulesCard(rules, temperatureUnit, onAdd, onReplace, onDelete)
            FallbackOutfitCard(
                rules = rules,
                defaultTop = defaultTop,
                defaultBottom = defaultBottom,
                temperatureUnit = temperatureUnit,
                onSetDefaultTop = onSetDefaultTop,
                onSetDefaultBottom = onSetDefaultBottom,
            )
            GarmentColorsCard(
                outfitTopColors = outfitTopColors,
                outfitBottomColors = outfitBottomColors,
                onSetOutfitTopColor = onSetOutfitTopColor,
                onSetOutfitBottomColor = onSetOutfitBottomColor,
            )
        }
    }
}

/**
 * Lets the user pick a fill colour for each rendered outfit-icon tier.
 * Lives under Clothes (alongside the rule list and the default-bottom
 * picker) rather than Display because it's a per-garment customisation
 * keyed off the same icon catalogue the rules drive — not a global
 * appearance preference like the chart palette.
 */
@Composable
private fun GarmentColorsCard(
    outfitTopColors: Map<OutfitSuggestion.Top, Long>,
    outfitBottomColors: Map<OutfitSuggestion.Bottom, Long>,
    onSetOutfitTopColor: (OutfitSuggestion.Top, Long?) -> Unit,
    onSetOutfitBottomColor: (OutfitSuggestion.Bottom, Long?) -> Unit,
) {
    var pickerTarget by remember { mutableStateOf<GarmentPickerTarget?>(null) }
    SectionCard(title = stringResource(R.string.settings_display_garment_colors_title)) {
        // Tops first (in OutfitSuggestion's declaration order — coldest tier
        // last), then bottoms, matching the reading order of the Today
        // screen's stacked icons.
        OutfitSuggestion.Top.entries.forEach { top ->
            GarmentColorRow(
                label = stringResource(topOutfitLabelRes(top)),
                effectiveColor = colorFor(outfitTopColors[top], outfitTopDefaults.getValue(top).fillArgb),
                onClick = { pickerTarget = GarmentPickerTarget.Top(top) },
            )
        }
        OutfitSuggestion.Bottom.entries.forEach { bottom ->
            GarmentColorRow(
                label = stringResource(bottomOutfitLabelRes(bottom)),
                effectiveColor = colorFor(outfitBottomColors[bottom], outfitBottomDefaults.getValue(bottom).fillArgb),
                onClick = { pickerTarget = GarmentPickerTarget.Bottom(bottom) },
            )
        }
    }
    pickerTarget?.let { target ->
        val (label, current) = when (target) {
            is GarmentPickerTarget.Top -> stringResource(topOutfitLabelRes(target.top)) to outfitTopColors[target.top]
            is GarmentPickerTarget.Bottom -> stringResource(bottomOutfitLabelRes(target.bottom)) to outfitBottomColors[target.bottom]
        }
        GarmentColorPickerDialog(
            garmentLabel = label,
            currentArgb = current,
            onPick = { picked ->
                when (target) {
                    is GarmentPickerTarget.Top -> onSetOutfitTopColor(target.top, picked)
                    is GarmentPickerTarget.Bottom -> onSetOutfitBottomColor(target.bottom, picked)
                }
            },
            onDismiss = { pickerTarget = null },
        )
    }
}

private sealed interface GarmentPickerTarget {
    data class Top(val top: OutfitSuggestion.Top) : GarmentPickerTarget
    data class Bottom(val bottom: OutfitSuggestion.Bottom) : GarmentPickerTarget
}

@Composable
private fun GarmentColorRow(
    label: String,
    effectiveColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(effectiveColor)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

private fun colorFor(customArgb: Long?, defaultArgb: Int): Color =
    Color((customArgb?.toInt()) ?: defaultArgb)

@StringRes
private fun topOutfitLabelRes(top: OutfitSuggestion.Top): Int = when (top) {
    OutfitSuggestion.Top.TSHIRT -> R.string.today_outfit_top_tshirt
    OutfitSuggestion.Top.POLO -> R.string.today_outfit_top_polo
    OutfitSuggestion.Top.SWEATER -> R.string.today_outfit_top_sweater
    OutfitSuggestion.Top.THIN_JACKET -> R.string.today_outfit_top_thin_jacket
    OutfitSuggestion.Top.THICK_JACKET -> R.string.today_outfit_top_thick_jacket
    OutfitSuggestion.Top.THICK_COAT -> R.string.today_outfit_top_thick_coat
    OutfitSuggestion.Top.PUFFER_JACKET -> R.string.today_outfit_top_puffer_jacket
}

@StringRes
private fun bottomOutfitLabelRes(bottom: OutfitSuggestion.Bottom): Int = when (bottom) {
    OutfitSuggestion.Bottom.SHORTS -> R.string.today_outfit_bottom_shorts
    OutfitSuggestion.Bottom.LONG_SKIRT -> R.string.today_outfit_bottom_long_skirt
    OutfitSuggestion.Bottom.JEANS -> R.string.today_outfit_bottom_jeans
    OutfitSuggestion.Bottom.LONG_PANTS -> R.string.today_outfit_bottom_long_pants
}

/**
 * The "If no rules match" card — picks the fallback top *and* bottom the
 * home-screen outfit lands on when no clothes rule fires for that tier. Each
 * row mirrors [ClothesRuleRow]'s shape (garment name + temperature description
 * + Edit) so the section reads as the rules card's natural complement: those
 * rules name what fires, this one names what shows when none of them do.
 *
 * The secondary description is derived from the rules list via
 * [fallbackRange] — "above 18°C" if the warmest top rule fires below 18,
 * "below 24°C" if the coldest bottom rule fires above 24, etc. When rules
 * fully cover the temperature space (the fallback would never apply), the
 * row reads "never"; when no relevant rules exist at all, the secondary
 * line is omitted.
 */
@Composable
private fun FallbackOutfitCard(
    rules: List<ClothesRule>,
    defaultTop: OutfitSuggestion.Top,
    defaultBottom: OutfitSuggestion.Bottom,
    temperatureUnit: TemperatureUnit,
    onSetDefaultTop: (OutfitSuggestion.Top) -> Unit,
    onSetDefaultBottom: (OutfitSuggestion.Bottom) -> Unit,
) {
    var editing by remember { mutableStateOf<FallbackSlot?>(null) }
    SectionCard(title = stringResource(R.string.settings_default_outfit_title)) {
        FallbackOutfitRow(
            label = stringResource(topOutfitLabelRes(defaultTop)),
            description = describeFallbackRange(
                fallbackRange(rules, FallbackTier.TOP),
                temperatureUnit,
            ),
            onEdit = { editing = FallbackSlot.TOP },
        )
        HorizontalDivider()
        FallbackOutfitRow(
            label = stringResource(bottomOutfitLabelRes(defaultBottom)),
            description = describeFallbackRange(
                fallbackRange(rules, FallbackTier.BOTTOM),
                temperatureUnit,
            ),
            onEdit = { editing = FallbackSlot.BOTTOM },
        )
    }
    when (editing) {
        FallbackSlot.TOP -> FallbackTopPickerDialog(
            selected = defaultTop,
            onPick = {
                onSetDefaultTop(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
        FallbackSlot.BOTTOM -> FallbackBottomPickerDialog(
            selected = defaultBottom,
            onPick = {
                onSetDefaultBottom(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
        null -> Unit
    }
}

private enum class FallbackSlot { TOP, BOTTOM }

@Composable
private fun FallbackOutfitRow(
    label: String,
    description: String?,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.settings_clothes_edit)) }
    }
}

@Composable
private fun describeFallbackRange(
    range: FallbackRange,
    temperatureUnit: TemperatureUnit,
): String? {
    if (range.empty) return stringResource(R.string.settings_default_outfit_range_never)
    val lower = range.lowerC
    val upper = range.upperC
    return when {
        lower != null && upper != null -> stringResource(
            R.string.settings_default_outfit_range_between,
            formatFallbackThreshold(lower, temperatureUnit),
            formatFallbackThreshold(upper, temperatureUnit),
        )
        lower != null -> stringResource(
            R.string.settings_default_outfit_range_above,
            formatFallbackThreshold(lower, temperatureUnit),
        )
        upper != null -> stringResource(
            R.string.settings_default_outfit_range_below,
            formatFallbackThreshold(upper, temperatureUnit),
        )
        else -> null
    }
}

// The fallback range is derived from rules, not typed by the user, so the
// dual-unit parenthesised display [formatThreshold] uses (which preserves "the
// number the user typed") doesn't apply — render in the display unit only.
private fun formatFallbackThreshold(celsius: Double, displayUnit: TemperatureUnit): String =
    "%.0f%s".format(celsius.toUnit(displayUnit), displayUnit.symbol())

@Composable
private fun FallbackTopPickerDialog(
    selected: OutfitSuggestion.Top,
    onPick: (OutfitSuggestion.Top) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        title = { Text(stringResource(R.string.settings_default_top_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutfitSuggestion.Top.entries.forEach { entry ->
                    RadioRow(
                        label = stringResource(topOutfitLabelRes(entry)),
                        selected = entry == selected,
                        onSelect = { onPick(entry) },
                    )
                }
            }
        },
    )
}

@Composable
private fun FallbackBottomPickerDialog(
    selected: OutfitSuggestion.Bottom,
    onPick: (OutfitSuggestion.Bottom) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        title = { Text(stringResource(R.string.settings_default_bottom_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutfitSuggestion.Bottom.entries.forEach { entry ->
                    RadioRow(
                        label = stringResource(bottomOutfitLabelRes(entry)),
                        selected = entry == selected,
                        onSelect = { onPick(entry) },
                    )
                }
            }
        },
    )
}

@Composable
private fun ClothesRulesCard(
    rules: List<ClothesRule>,
    temperatureUnit: TemperatureUnit,
    onAdd: (ClothesRule) -> Unit,
    onReplace: (Int, ClothesRule) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var addOpen by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }

    SectionCard(title = stringResource(R.string.settings_clothes_title)) {
        Text(
            text = stringResource(R.string.settings_clothes_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (rules.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_clothes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rules.forEachIndexed { index, rule ->
            if (index > 0) HorizontalDivider()
            ClothesRuleRow(
                rule = rule,
                temperatureUnit = temperatureUnit,
                onEdit = { editIndex = index },
                onDelete = { onDelete(index) },
            )
        }
        Button(
            onClick = { addOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_clothes_add)) }
    }

    if (addOpen) {
        ClothesRuleDialog(
            initial = null,
            temperatureUnit = temperatureUnit,
            onDismiss = { addOpen = false },
            onConfirm = {
                addOpen = false
                onAdd(it)
            },
        )
    }

    val editing = editIndex
    if (editing != null && editing in rules.indices) {
        ClothesRuleDialog(
            initial = rules[editing],
            temperatureUnit = temperatureUnit,
            onDismiss = { editIndex = null },
            onConfirm = {
                onReplace(editing, it)
                editIndex = null
            },
        )
    }
}

@Composable
private fun ClothesRuleRow(
    rule: ClothesRule,
    temperatureUnit: TemperatureUnit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Show the localised garment name for catalog items; fall back to
            // the raw stored key for any older / custom items so the user can
            // still see and delete them.
            val garment = Garment.fromKey(rule.item)
            val label = if (garment != null) stringResource(garmentLabelRes(garment)) else rule.item
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = describeCondition(rule.condition, temperatureUnit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.settings_clothes_edit)) }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.settings_clothes_delete),
            )
        }
    }
}

@Composable
private fun describeCondition(
    condition: ClothesRule.Condition,
    temperatureUnit: TemperatureUnit,
): String = when (condition) {
    is ClothesRule.TemperatureBelow ->
        stringResource(
            R.string.settings_clothes_cond_temp_below,
            formatThreshold(condition.value, condition.unit, temperatureUnit),
        )
    is ClothesRule.TemperatureAbove ->
        stringResource(
            R.string.settings_clothes_cond_temp_above,
            formatThreshold(condition.value, condition.unit, temperatureUnit),
        )
    is ClothesRule.PrecipitationProbabilityAbove ->
        stringResource(R.string.settings_clothes_cond_precip_above, condition.percent)
}

/**
 * Formats a temperature threshold for display. The user's currently-selected
 * [displayUnit] always comes first (that's the unit the rest of the app speaks
 * to them in); when the rule was *entered* in a different unit, the original
 * is appended in parentheses so unit-switches don't silently mutate what the
 * user remembers typing — e.g. a 65°F rule viewed under °C reads "18°C (65°F)".
 */
private fun formatThreshold(
    storedValue: Double,
    storedUnit: TemperatureUnit,
    displayUnit: TemperatureUnit,
): String {
    val storedC = storedValue.fromUnit(storedUnit)
    val displayed = "%.0f%s".format(storedC.toUnit(displayUnit), displayUnit.symbol())
    if (storedUnit == displayUnit) return displayed
    val original = "%.0f%s".format(storedValue, storedUnit.symbol())
    return "$displayed ($original)"
}

private enum class ConditionType(@StringRes val labelRes: Int) {
    TEMP_BELOW(R.string.settings_clothes_cond_type_temp_below),
    TEMP_ABOVE(R.string.settings_clothes_cond_type_temp_above),
    PRECIP_ABOVE(R.string.settings_clothes_cond_type_precip_above),
}

/** Maps a [Garment] enum entry to its localised display label resource. */
@StringRes
private fun garmentLabelRes(garment: Garment): Int = when (garment) {
    Garment.SWEATER -> R.string.garment_sweater
    Garment.HOODIE -> R.string.garment_hoodie
    Garment.JACKET -> R.string.garment_jacket
    Garment.COAT -> R.string.garment_coat
    Garment.PUFFER -> R.string.garment_puffer
    Garment.THIN_JACKET -> R.string.garment_thin_jacket
    Garment.TSHIRT -> R.string.garment_tshirt
    Garment.POLO -> R.string.garment_polo
    Garment.SHIRT -> R.string.garment_shirt
    Garment.SHORTS -> R.string.garment_shorts
    Garment.SKIRT -> R.string.garment_skirt
    Garment.PANTS -> R.string.garment_pants
    Garment.JEANS -> R.string.garment_jeans
}

@Composable
private fun ClothesRuleDialog(
    initial: ClothesRule?,
    temperatureUnit: TemperatureUnit,
    onDismiss: () -> Unit,
    onConfirm: (ClothesRule) -> Unit,
) {
    // Pre-select the initial rule's garment when editing; default to SWEATER
    // when adding (the most common cold-weather rule). Items not in the catalog
    // (older free-form rules) preselect SWEATER too — the user can pick any
    // catalog garment and confirm to migrate the rule onto a known key.
    var garment by remember {
        mutableStateOf(initial?.item?.let(Garment::fromKey) ?: Garment.SWEATER)
    }
    val initialType = when (initial?.condition) {
        is ClothesRule.TemperatureBelow -> ConditionType.TEMP_BELOW
        is ClothesRule.TemperatureAbove -> ConditionType.TEMP_ABOVE
        is ClothesRule.PrecipitationProbabilityAbove -> ConditionType.PRECIP_ABOVE
        null -> ConditionType.TEMP_BELOW
    }
    var type by remember { mutableStateOf(initialType) }
    // Pre-fill in the user's *current* display unit. A 65°F rule opened by a °C
    // user pre-fills as "18" (the saved value converted via Celsius); when the
    // user *changes* the value and confirms, the new condition takes the
    // current `temperatureUnit`. A no-op confirm preserves the original unit
    // and value verbatim — see the confirm-button branch.
    val initialValue = when (val c = initial?.condition) {
        is ClothesRule.TemperatureBelow -> c.value.fromUnit(c.unit).toUnit(temperatureUnit)
        is ClothesRule.TemperatureAbove -> c.value.fromUnit(c.unit).toUnit(temperatureUnit)
        is ClothesRule.PrecipitationProbabilityAbove -> c.percent
        null -> 18.0.toUnit(temperatureUnit)
    }
    val initialInt = initialValue.roundToInt()
    // Whole-number input only. Keeps the row label (rendered with %.0f) in
    // sync with what the user typed and dodges locale-specific decimal
    // separators (German keyboards default to "18,5", which Double.toDoubleOrNull
    // rejects). Existing rules with fractional values round to the nearest
    // int when first opened — defaults are all integers so this is purely
    // defensive for legacy data.
    var valueText by remember { mutableStateOf(initialInt.toString()) }

    val parsedValue = valueText.toIntOrNull()
    // Precip rules are bounded 0–100 (it's a probability percentage); temperature
    // rules can be any int. Disable confirm when out of range so the rule can't
    // be saved with a nonsense threshold.
    val valueValid = parsedValue != null &&
        (type != ConditionType.PRECIP_ABOVE || parsedValue in 0..100)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = valueValid,
                onClick = {
                    val v = parsedValue!!.toDouble()
                    // No-op edit guard: if the user opened the dialog and confirmed
                    // without changing the displayed integer (or the condition
                    // type), preserve the original condition object verbatim. The
                    // pre-fill rounds across unit conversion (65°F → "18" under
                    // °C); without this guard, "OK" would silently rewrite the
                    // rule as 18°C and lose the original 65°F on the next switch.
                    val unchanged = initial != null && parsedValue == initialInt
                    val initialCond = initial?.condition
                    val condition = when (type) {
                        ConditionType.TEMP_BELOW ->
                            if (unchanged && initialCond is ClothesRule.TemperatureBelow) initialCond
                            else ClothesRule.TemperatureBelow(v, temperatureUnit)
                        ConditionType.TEMP_ABOVE ->
                            if (unchanged && initialCond is ClothesRule.TemperatureAbove) initialCond
                            else ClothesRule.TemperatureAbove(v, temperatureUnit)
                        ConditionType.PRECIP_ABOVE ->
                            if (unchanged && initialCond is ClothesRule.PrecipitationProbabilityAbove) initialCond
                            else ClothesRule.PrecipitationProbabilityAbove(v)
                    }
                    onConfirm(ClothesRule(garment.itemKey, condition))
                },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.settings_clothes_dialog_add_title
                    else R.string.settings_clothes_dialog_edit_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GarmentDropdown(
                    selected = garment,
                    onSelect = { garment = it },
                )
                Text(
                    text = stringResource(R.string.settings_clothes_condition_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                ConditionType.entries.forEach { entry ->
                    RadioRow(
                        label = stringResource(entry.labelRes),
                        selected = type == entry,
                        onSelect = { type = entry },
                    )
                }
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = {
                        Text(
                            if (type == ConditionType.PRECIP_ABOVE) {
                                stringResource(R.string.settings_clothes_value_label_precip)
                            } else {
                                stringResource(
                                    R.string.settings_clothes_value_label_temp,
                                    temperatureUnit.symbol(),
                                )
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !valueValid,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GarmentDropdown(
    selected: Garment,
    onSelect: (Garment) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(garmentLabelRes(selected)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_clothes_item_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Garment.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(garmentLabelRes(entry))) },
                    onClick = {
                        onSelect(entry)
                        expanded = false
                    },
                )
            }
        }
    }
}
