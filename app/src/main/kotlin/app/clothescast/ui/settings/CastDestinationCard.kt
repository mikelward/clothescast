package app.clothescast.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.cast.DiscoveredCastRoute
import app.clothescast.ui.formatHourMinute
import java.time.Instant
import java.time.ZoneId

/**
 * Settings → Smart Home → Cast section. Mirrors [MqttBridgeCard]'s shape:
 *
 *  - Section header + privacy note.
 *  - Picker row showing the saved smart display ("Living-room display")
 *    or the "No display picked" placeholder, with Change / Clear /
 *    "Choose smart display" actions.
 *  - "Cast now" button — disabled until a display is picked.
 *  - Status row, mirroring MQTT's: green-tinted "Last cast 14:32" or
 *    red-tinted "Last cast failed — <reason>".
 *
 * The actual route discovery + selection happens via the [pickerOpen]
 * dialog, which displays a live-updating list of discovered routes
 * from the SDK's MediaRouter.
 */
@Composable
internal fun CastDestinationCard(
    routeName: String?,
    pickerOpen: Boolean,
    discoveredRoutes: List<DiscoveredCastRoute>,
    castInProgress: Boolean,
    lastError: String?,
    lastErrorAt: Long,
    lastSuccessAt: Long,
    castMorning: Boolean,
    castTonight: Boolean,
    castSkipPhoneSpeech: Boolean,
    onOpenPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onPickRoute: (DiscoveredCastRoute) -> Unit,
    onClearRoute: () -> Unit,
    onCastNow: () -> Unit,
    onSetCastMorning: (Boolean) -> Unit,
    onSetCastTonight: (Boolean) -> Unit,
    onSetCastSkipPhoneSpeech: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_smart_home_cast_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_smart_home_cast_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_smart_home_cast_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CastDeviceRow(
                routeName = routeName,
                onOpenPicker = onOpenPicker,
                onClearRoute = onClearRoute,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onCastNow,
                    enabled = routeName != null && !castInProgress,
                ) {
                    if (castInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.settings_smart_home_cast_now))
                    }
                }
                if (castInProgress) {
                    Text(
                        text = stringResource(R.string.settings_smart_home_cast_casting),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            CastStatusRow(
                lastError = lastError,
                lastErrorAt = lastErrorAt,
                lastSuccessAt = lastSuccessAt,
            )

            // Per-period toggles + the audio-handoff toggle gate
            // scheduled-run cast deliveries; the picker above is the
            // overall on/off. Disabled when no route is picked so the
            // user notices that the picker is the precondition.
            CastToggleRow(
                title = stringResource(R.string.settings_smart_home_cast_morning_title),
                description = stringResource(R.string.settings_smart_home_cast_morning_description),
                checked = castMorning,
                enabled = routeName != null,
                onCheckedChange = onSetCastMorning,
            )
            CastToggleRow(
                title = stringResource(R.string.settings_smart_home_cast_tonight_title),
                description = stringResource(R.string.settings_smart_home_cast_tonight_description),
                checked = castTonight,
                enabled = routeName != null,
                onCheckedChange = onSetCastTonight,
            )
            CastToggleRow(
                title = stringResource(R.string.settings_smart_home_cast_skip_phone_speech_title),
                description = stringResource(R.string.settings_smart_home_cast_skip_phone_speech_description),
                checked = castSkipPhoneSpeech,
                enabled = routeName != null,
                onCheckedChange = onSetCastSkipPhoneSpeech,
            )
        }
    }

    if (pickerOpen) {
        CastPickerDialog(
            discoveredRoutes = discoveredRoutes,
            onDismiss = onClosePicker,
            onPick = onPickRoute,
        )
    }
}

@Composable
private fun CastToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun CastDeviceRow(
    routeName: String?,
    onOpenPicker: () -> Unit,
    onClearRoute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpenPicker)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_smart_home_cast_device_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = routeName
                    ?: stringResource(R.string.settings_smart_home_cast_device_none),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (routeName == null) {
            TextButton(onClick = onOpenPicker) {
                Text(stringResource(R.string.settings_smart_home_cast_choose))
            }
        } else {
            TextButton(onClick = onOpenPicker) {
                Text(stringResource(R.string.settings_smart_home_cast_change))
            }
            TextButton(onClick = onClearRoute) {
                Text(stringResource(R.string.settings_smart_home_cast_clear))
            }
        }
    }
}

@Composable
private fun CastStatusRow(
    lastError: String?,
    lastErrorAt: Long,
    lastSuccessAt: Long,
) {
    when {
        lastError != null -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_smart_home_cast_last_error_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = lastError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        lastSuccessAt > 0 -> {
            Text(
                text = stringResource(
                    R.string.settings_smart_home_cast_last_success,
                    formatTimestamp(lastSuccessAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            Text(
                text = stringResource(R.string.settings_smart_home_cast_never),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CastPickerDialog(
    discoveredRoutes: List<DiscoveredCastRoute>,
    onDismiss: () -> Unit,
    onPick: (DiscoveredCastRoute) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_smart_home_cast_picker_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (discoveredRoutes.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.settings_smart_home_cast_picker_scanning),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_smart_home_cast_picker_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    discoveredRoutes.forEach { route ->
                        OutlinedButton(
                            onClick = { onPick(route) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(route.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_smart_home_cast_picker_cancel))
            }
        },
    )
}

@Composable
private fun formatTimestamp(epochMs: Long): String = formatHourMinute(
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalTime(),
)
