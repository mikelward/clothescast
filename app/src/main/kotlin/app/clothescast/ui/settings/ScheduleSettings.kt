package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.clothescast.R
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.location.hasBackgroundLocationPermission
import app.clothescast.location.hasCoarseLocationPermission
import app.clothescast.ui.EdgeFadeOverlay
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun ScheduleContent(
    time: LocalTime,
    days: Set<DayOfWeek>,
    tonightTime: LocalTime,
    tonightDays: Set<DayOfWeek>,
    tonightEnabled: Boolean,
    tonightNotifyOnlyOnEvents: Boolean,
    dailyMentionEveningEvents: Boolean,
    deliveryMode: DeliveryMode,
    tonightDeliveryMode: DeliveryMode,
    skipTtsAtHome: Boolean,
    homeLocationConfigured: Boolean,
    padding: PaddingValues,
    onSetSchedule: (LocalTime, Set<DayOfWeek>) -> Unit,
    onSetTonightSchedule: (LocalTime, Set<DayOfWeek>) -> Unit,
    onSetTonightEnabled: (Boolean) -> Unit,
    onSetTonightNotifyOnlyOnEvents: (Boolean) -> Unit,
    onSetDailyMentionEveningEvents: (Boolean) -> Unit,
    onSetDeliveryMode: (DeliveryMode) -> Unit,
    onSetTonightDeliveryMode: (DeliveryMode) -> Unit,
    onSetSkipTtsAtHome: (Boolean) -> Unit,
    onDone: (() -> Unit)? = null,
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DayCard(
                time = time,
                days = days,
                deliveryMode = deliveryMode,
                mentionEveningEvents = dailyMentionEveningEvents,
                onChange = onSetSchedule,
                onSetDeliveryMode = onSetDeliveryMode,
                onSetMentionEveningEvents = onSetDailyMentionEveningEvents,
            )
            NightCard(
                time = tonightTime,
                days = tonightDays,
                enabled = tonightEnabled,
                notifyOnlyOnEvents = tonightNotifyOnlyOnEvents,
                deliveryMode = tonightDeliveryMode,
                onSetEnabled = onSetTonightEnabled,
                onSetNotifyOnlyOnEvents = onSetTonightNotifyOnlyOnEvents,
                onChange = onSetTonightSchedule,
                onSetDeliveryMode = onSetTonightDeliveryMode,
            )
            SkipTtsAtHomeCard(
                skipTtsAtHome = skipTtsAtHome,
                homeLocationConfigured = homeLocationConfigured,
                onSetSkipTtsAtHome = onSetSkipTtsAtHome,
            )
            if (onDone != null) {
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.onboarding_step_done))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DayCard(
    time: LocalTime,
    days: Set<DayOfWeek>,
    deliveryMode: DeliveryMode,
    mentionEveningEvents: Boolean,
    onChange: (LocalTime, Set<DayOfWeek>) -> Unit,
    onSetDeliveryMode: (DeliveryMode) -> Unit,
    onSetMentionEveningEvents: (Boolean) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    SectionCard(title = stringResource(R.string.settings_schedule_title)) {
        TimeRow(
            label = stringResource(R.string.settings_schedule_time_label),
            time = time,
            onClick = { pickerOpen = true },
        )
        DaysSelector(
            label = stringResource(R.string.settings_schedule_days_label),
            days = days,
            onChange = { next -> onChange(time, next) },
        )
        DeliveryModeSection(deliveryMode, onSetDeliveryMode)
        ToggleRow(
            label = stringResource(R.string.settings_daily_mention_evening_events),
            checked = mentionEveningEvents,
            onCheckedChange = onSetMentionEveningEvents,
        )
    }

    if (pickerOpen) {
        TimePickerDialog(
            initial = time,
            onDismiss = { pickerOpen = false },
            onConfirm = { newTime ->
                pickerOpen = false
                onChange(newTime, days)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        text = { TimePicker(state = state) },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NightCard(
    time: LocalTime,
    days: Set<DayOfWeek>,
    enabled: Boolean,
    notifyOnlyOnEvents: Boolean,
    deliveryMode: DeliveryMode,
    onSetEnabled: (Boolean) -> Unit,
    onSetNotifyOnlyOnEvents: (Boolean) -> Unit,
    onChange: (LocalTime, Set<DayOfWeek>) -> Unit,
    onSetDeliveryMode: (DeliveryMode) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    SectionCard(title = stringResource(R.string.settings_tonight_title)) {
        Text(
            text = stringResource(R.string.settings_tonight_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            label = stringResource(R.string.settings_tonight_enabled),
            checked = enabled,
            onCheckedChange = onSetEnabled,
        )
        if (enabled) {
            TimeRow(
                label = stringResource(R.string.settings_tonight_time_label),
                time = time,
                onClick = { pickerOpen = true },
            )
            DaysSelector(
                label = stringResource(R.string.settings_tonight_days_label),
                days = days,
                onChange = { next -> onChange(time, next) },
            )
            DeliveryModeSection(deliveryMode, onSetDeliveryMode)
            ToggleRow(
                label = stringResource(R.string.settings_tonight_notify_only_on_events),
                checked = notifyOnlyOnEvents,
                onCheckedChange = onSetNotifyOnlyOnEvents,
            )
        }
    }

    if (pickerOpen) {
        TimePickerDialog(
            initial = time,
            onDismiss = { pickerOpen = false },
            onConfirm = { newTime ->
                pickerOpen = false
                onChange(newTime, days)
            },
        )
    }
}

@Composable
private fun SkipTtsAtHomeCard(
    skipTtsAtHome: Boolean,
    homeLocationConfigured: Boolean,
    onSetSkipTtsAtHome: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // Re-check on resume so granting permission in system Settings clears the
    // warning without needing an in-app action — same pattern as LocationSettings.
    var coarseGranted by remember { mutableStateOf(hasCoarseLocationPermission(context)) }
    var backgroundGranted by remember { mutableStateOf(hasBackgroundLocationPermission(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                coarseGranted = hasCoarseLocationPermission(context)
                backgroundGranted = hasBackgroundLocationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Worker runs in the background, so both grants are needed for
    // resolveFresh to actually return a fix — without them the gate fails
    // open and the user sees no behaviour change despite the toggle being on.
    val permissionGranted = coarseGranted && backgroundGranted

    SectionCard(title = stringResource(R.string.settings_skip_tts_at_home_title)) {
        Text(
            text = stringResource(R.string.settings_skip_tts_at_home_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            label = stringResource(R.string.settings_skip_tts_at_home_label),
            checked = skipTtsAtHome,
            onCheckedChange = onSetSkipTtsAtHome,
        )
        if (skipTtsAtHome) {
            when {
                !homeLocationConfigured -> Text(
                    text = stringResource(R.string.settings_skip_tts_at_home_needs_home),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                !permissionGranted -> {
                    Text(
                        text = stringResource(R.string.settings_skip_tts_at_home_needs_permission),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Deep-link to the system app-details page rather than
                    // duplicating the rationale → foreground → background
                    // launcher chain LocationSettings uses; the user can
                    // grant Location → "Allow all the time" from there. The
                    // ON_RESUME observer above will clear the warning when
                    // they come back.
                    TextButton(onClick = { openAppDetails(context) }) {
                        Text(stringResource(R.string.settings_skip_tts_at_home_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Modifier.toggleable + onCheckedChange = null on the Switch itself merges
    // semantics so TalkBack announces the label together with the switch state,
    // and makes the whole row a tap target instead of just the thumb.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun TimeRow(label: String, time: LocalTime, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 12.dp),
        )
        OutlinedButton(onClick = onClick) {
            Text(text = TIME_FORMAT.format(time))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaysSelector(
    label: String,
    days: Set<DayOfWeek>,
    onChange: (Set<DayOfWeek>) -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
    )
    val uiLocale = LocalContext.current.resourcesLocale()
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DayOfWeek.entries.forEach { dow ->
            val selected = dow in days
            FilterChip(
                selected = selected,
                onClick = {
                    val next = if (selected) days - dow else days + dow
                    if (next.isNotEmpty()) onChange(next)
                },
                label = {
                    Text(text = dow.getDisplayName(TextStyle.SHORT, uiLocale))
                },
                leadingIcon = if (selected) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun DeliveryModeSection(
    selected: DeliveryMode,
    onSelect: (DeliveryMode) -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_delivery_label),
        style = MaterialTheme.typography.bodyMedium,
    )
    DeliveryMode.entries.forEach { mode ->
        RadioRow(
            label = stringResource(deliveryModeLabel(mode)),
            selected = mode == selected,
            onSelect = { onSelect(mode) },
        )
    }
}

private fun deliveryModeLabel(mode: DeliveryMode): Int = when (mode) {
    DeliveryMode.NOTIFICATION_ONLY -> R.string.settings_delivery_notification_only
    DeliveryMode.TTS_ONLY -> R.string.settings_delivery_tts_only
    DeliveryMode.NOTIFICATION_AND_TTS -> R.string.settings_delivery_notification_and_tts
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
