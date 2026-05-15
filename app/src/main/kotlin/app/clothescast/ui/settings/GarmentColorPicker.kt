package app.clothescast.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.ui.garment.garmentColorPresets

/**
 * Modal picker for one garment's fill colour. Shows a grid of preset
 * swatches plus a "Default" tile that clears the override.
 *
 * - [garmentLabel] is the localised garment name shown in the title and
 *   used in the swatch content descriptions ("Reset T-shirt to the default
 *   colour").
 * - [currentArgb] is the user's current pick (drawn with a ring), or null
 *   when the icon is on its baked-in default.
 * - [onPick] is called with the chosen ARGB; pass `null` to clear.
 */
@Composable
internal fun GarmentColorPickerDialog(
    garmentLabel: String,
    currentArgb: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.settings_garment_color_picker_title))
                Text(
                    text = garmentLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(SWATCH_COLUMNS),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    DefaultSwatch(
                        garmentLabel = garmentLabel,
                        selected = currentArgb == null,
                        onClick = {
                            onPick(null)
                            onDismiss()
                        },
                    )
                }
                itemsIndexed(garmentColorPresets) { _, color ->
                    val argb = color.toLongArgb()
                    PresetSwatch(
                        color = color,
                        selected = currentArgb == argb,
                        garmentLabel = garmentLabel,
                        onClick = {
                            onPick(argb)
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_garment_color_dialog_close))
            }
        },
    )
}

@Composable
private fun PresetSwatch(
    color: Color,
    selected: Boolean,
    garmentLabel: String,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.settings_garment_color_swatch_description, garmentLabel)
    Box(
        modifier = Modifier
            .size(SWATCH_SIZE)
            .clip(CircleShape)
            .background(color)
            .selectionRing(selected)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
                this.role = Role.RadioButton
            },
    )
}

@Composable
private fun DefaultSwatch(
    garmentLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.settings_garment_color_default_swatch_description, garmentLabel)
    Box(
        modifier = Modifier
            .size(SWATCH_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .selectionRing(selected)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
                this.role = Role.RadioButton
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_garment_color_default),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Modifier.selectionRing(selected: Boolean): Modifier =
    if (selected) {
        this.border(
            width = 3.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
        )
    } else {
        this
    }

/**
 * Packs a Compose [Color] (sRGB) into the ARGB long shape we persist. The
 * conversion goes through `toArgb()` so non-sRGB colour spaces can't sneak
 * past the round-trip; presets are sRGB anyway, but the helper makes the
 * contract explicit.
 */
private fun Color.toLongArgb(): Long = toArgb().toLong() and 0xFFFFFFFFL

private val SWATCH_SIZE = 56.dp
private const val SWATCH_COLUMNS = 4
