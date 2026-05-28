package app.clothescast.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.notification.NotificationPermission
import app.clothescast.ui.EdgeFadeOverlay
import app.clothescast.ui.LocalTimeFormat
import app.clothescast.ui.formatHourMinute
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle

@Composable
internal fun ScheduleContent(
    time: LocalTime,
    days: Set<DayOfWeek>,
    dailyEnabled: Boolean,
    tonightTime: LocalTime,
    tonightDays: Set<DayOfWeek>,
    tonightEnabled: Boolean,
    tonightNotifyOnlyOnEvents: Boolean,
    dailyMentionEveningEvents: Boolean,
    deliveryMode: DeliveryMode,
    tonightDeliveryMode: DeliveryMode,
    ttsEngine: TtsEngine,
    geminiKeyConfigured: Boolean,
    padding: PaddingValues,
    onSetSchedule: (LocalTime, Set<DayOfWeek>) -> Unit,
    onSetDailyEnabled: (Boolean) -> Unit,
    onSetTonightSchedule: (LocalTime, Set<DayOfWeek>) -> Unit,
    onSetTonightEnabled: (Boolean) -> Unit,
    onSetTonightNotifyOnlyOnEvents: (Boolean) -> Unit,
    onSetDailyMentionEveningEvents: (Boolean) -> Unit,
    onSetDeliveryMode: (DeliveryMode) -> Unit,
    onSetTonightDeliveryMode: (DeliveryMode) -> Unit,
    onSetTtsEngine: (TtsEngine) -> Unit,
    onSetGeminiKey: (String) -> Unit,
    onClearGeminiKey: () -> Unit,
    onDone: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var speechSheetOpen by rememberSaveable { mutableStateOf(false) }

    // Turning a delivery channel on requests exactly what that channel needs,
    // just-in-time. Notify → the system POST_NOTIFICATIONS prompt (no-op on
    // pre-Android-13 or when already granted; a denial is recoverable via the
    // NotificationPermissionBanner shown beside the toggle). Speak → the
    // cut-down Speech setup sheet to pick Gemini-with-key or device TTS.
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* Banner re-checks on resume; nothing to do with the result here. */ }
    val requestNotificationPermission: () -> Unit = {
        if (NotificationPermission.isRequired() && !NotificationPermission.isGranted(context)) {
            notificationLauncher.launch(NotificationPermission.MANIFEST_PERMISSION)
        }
    }
    val requestSpeechSetup: () -> Unit = { speechSheetOpen = true }

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
            DayCard(
                time = time,
                days = days,
                enabled = dailyEnabled,
                deliveryMode = deliveryMode,
                mentionEveningEvents = dailyMentionEveningEvents,
                onSetEnabled = onSetDailyEnabled,
                onChange = onSetSchedule,
                onSetDeliveryMode = onSetDeliveryMode,
                onSetMentionEveningEvents = onSetDailyMentionEveningEvents,
                onRequestNotificationPermission = requestNotificationPermission,
                onRequestSpeechSetup = requestSpeechSetup,
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
                onRequestNotificationPermission = requestNotificationPermission,
                onRequestSpeechSetup = requestSpeechSetup,
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

    if (speechSheetOpen) {
        SpeechSetupSheet(
            selectedEngine = ttsEngine,
            geminiKeyConfigured = geminiKeyConfigured,
            onSetTtsEngine = onSetTtsEngine,
            onSetGeminiKey = onSetGeminiKey,
            onClearGeminiKey = onClearGeminiKey,
            onConfirm = { speechSheetOpen = false },
            onDismiss = { speechSheetOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DayCard(
    time: LocalTime,
    days: Set<DayOfWeek>,
    enabled: Boolean,
    deliveryMode: DeliveryMode,
    mentionEveningEvents: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onChange: (LocalTime, Set<DayOfWeek>) -> Unit,
    onSetDeliveryMode: (DeliveryMode) -> Unit,
    onSetMentionEveningEvents: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestSpeechSetup: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    SectionCard(title = stringResource(R.string.settings_schedule_title)) {
        ToggleRow(
            label = stringResource(R.string.settings_schedule_enabled),
            checked = enabled,
            onCheckedChange = onSetEnabled,
        )
        if (enabled) {
            TimeRow(
                label = stringResource(R.string.settings_schedule_time_label),
                time = time,
                onClick = { pickerOpen = true },
            )
            DaysSelector(
                days = days,
                onChange = { next -> onChange(time, next) },
            )
            DeliveryModeSection(
                selected = deliveryMode,
                onSelect = onSetDeliveryMode,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onRequestSpeechSetup = onRequestSpeechSetup,
            )
            ToggleRow(
                label = stringResource(R.string.settings_daily_mention_evening_events),
                checked = mentionEveningEvents,
                onCheckedChange = onSetMentionEveningEvents,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    // Match the user's clock preference so the picker UI (the AM/PM toggle
    // or its absence) lines up with the value shown on the row that opened
    // it. A 12h reader who sees "7am" on the schedule row shouldn't get a
    // 24h dial when they tap to edit.
    val is24Hour = LocalTimeFormat.current == TimeFormat.TWENTY_FOUR_HOUR
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = is24Hour,
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
    onRequestNotificationPermission: () -> Unit,
    onRequestSpeechSetup: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    SectionCard(title = stringResource(R.string.settings_tonight_title)) {
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
                days = days,
                onChange = { next -> onChange(time, next) },
            )
            DeliveryModeSection(
                selected = deliveryMode,
                onSelect = onSetDeliveryMode,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onRequestSpeechSetup = onRequestSpeechSetup,
            )
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
            Text(text = formatHourMinute(time))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaysSelector(
    days: Set<DayOfWeek>,
    onChange: (Set<DayOfWeek>) -> Unit,
) {
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
    onRequestNotificationPermission: () -> Unit,
    onRequestSpeechSetup: () -> Unit,
) {
    val (notifyOn, ttsOn) = selected.toChannels()
    ToggleRow(
        label = stringResource(R.string.settings_delivery_notification),
        checked = notifyOn,
        onCheckedChange = { enabled ->
            onSelect(deliveryModeOf(notify = enabled, tts = ttsOn))
            if (enabled) onRequestNotificationPermission()
        },
    )
    // Surface the recoverable grant path whenever the notify channel is on but
    // permission is still missing; the banner renders nothing once granted (or
    // on pre-Android-13), so it's safe to keep mounted while notify is on.
    if (notifyOn) {
        NotificationPermissionBanner()
    }
    ToggleRow(
        label = stringResource(R.string.settings_delivery_tts),
        checked = ttsOn,
        onCheckedChange = { enabled ->
            onSelect(deliveryModeOf(notify = notifyOn, tts = enabled))
            if (enabled) onRequestSpeechSetup()
        },
    )
}

private fun DeliveryMode.toChannels(): Pair<Boolean, Boolean> = when (this) {
    DeliveryMode.SILENT -> false to false
    DeliveryMode.NOTIFICATION_ONLY -> true to false
    DeliveryMode.TTS_ONLY -> false to true
    DeliveryMode.NOTIFICATION_AND_TTS -> true to true
}

private fun deliveryModeOf(notify: Boolean, tts: Boolean): DeliveryMode = when {
    notify && tts -> DeliveryMode.NOTIFICATION_AND_TTS
    notify -> DeliveryMode.NOTIFICATION_ONLY
    tts -> DeliveryMode.TTS_ONLY
    else -> DeliveryMode.SILENT
}
