package app.clothescast.ui.settings

import android.widget.Toast
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.notification.NotificationPermission
import app.clothescast.work.FetchAndNotifyWorker
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
    // Gates the per-section "Play now" buttons: false while any daily / tonight
    // / play worker is active, so a preview can't start a second concurrent
    // delivery. Defaulted true for previews/tests that don't observe work state.
    previewEnabled: Boolean = true,
) {
    val context = LocalContext.current
    var speechSheetOpen by rememberSaveable { mutableStateOf(false) }

    // Whether POST_NOTIFICATIONS is granted gates how the notification channel
    // toggles read: without the permission the OS drops every post, so the
    // channel can't really be on — the toggle reads off regardless of the
    // stored preference and only settles on once the grant lands. Re-check on
    // resume so a grant/revoke made in system Settings is reflected without an
    // in-app action (same approach as NotificationPermissionBanner). Reports
    // true on pre-Android-13, where the permission is implicit.
    var notificationGranted by remember {
        mutableStateOf(NotificationPermission.isGranted(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationGranted = NotificationPermission.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Turning a delivery channel on requests exactly what that channel needs,
    // just-in-time. Notify → the system POST_NOTIFICATIONS prompt (no-op on
    // pre-Android-13 or when already granted; a denial is recoverable via the
    // NotificationPermissionBanner shown beside the toggle). Speak → the
    // cut-down Speech setup sheet to pick Gemini-with-key or device TTS.
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }
    val requestNotificationPermission: () -> Unit = {
        if (NotificationPermission.isRequired() && !notificationGranted) {
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
                notificationGranted = notificationGranted,
                mentionEveningEvents = dailyMentionEveningEvents,
                // Enabling a master switch is the user opting into scheduled
                // delivery, so prompt for notification permission right then —
                // not only when the notification channel is toggled. The channel
                // is on by default, so without this the prompt would never fire
                // on the enable path and the worker would later no-op silently.
                onSetEnabled = { enabled ->
                    onSetDailyEnabled(enabled)
                    if (enabled) requestNotificationPermission()
                },
                onChange = onSetSchedule,
                onSetDeliveryMode = onSetDeliveryMode,
                onSetMentionEveningEvents = onSetDailyMentionEveningEvents,
                onRequestNotificationPermission = requestNotificationPermission,
                onRequestSpeechSetup = requestSpeechSetup,
                onPreview = { triggerPreview(context, ForecastPeriod.TODAY) },
                previewEnabled = previewEnabled,
            )
            NightCard(
                time = tonightTime,
                days = tonightDays,
                enabled = tonightEnabled,
                notifyOnlyOnEvents = tonightNotifyOnlyOnEvents,
                deliveryMode = tonightDeliveryMode,
                notificationGranted = notificationGranted,
                // Same as the morning card: prompt for notification permission
                // the moment the user enables the evening schedule.
                onSetEnabled = { enabled ->
                    onSetTonightEnabled(enabled)
                    if (enabled) requestNotificationPermission()
                },
                onSetNotifyOnlyOnEvents = onSetTonightNotifyOnlyOnEvents,
                onChange = onSetTonightSchedule,
                onSetDeliveryMode = onSetTonightDeliveryMode,
                onRequestNotificationPermission = requestNotificationPermission,
                onRequestSpeechSetup = requestSpeechSetup,
                onPreview = { triggerPreview(context, ForecastPeriod.TONIGHT) },
                previewEnabled = previewEnabled,
            )
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
    notificationGranted: Boolean,
    mentionEveningEvents: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onChange: (LocalTime, Set<DayOfWeek>) -> Unit,
    onSetDeliveryMode: (DeliveryMode) -> Unit,
    onSetMentionEveningEvents: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestSpeechSetup: () -> Unit,
    onPreview: () -> Unit,
    previewEnabled: Boolean,
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
                notificationGranted = notificationGranted,
                onSelect = onSetDeliveryMode,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onRequestSpeechSetup = onRequestSpeechSetup,
            )
            ToggleRow(
                label = stringResource(R.string.settings_daily_mention_evening_events),
                checked = mentionEveningEvents,
                onCheckedChange = onSetMentionEveningEvents,
            )
            PreviewButton(onClick = onPreview, enabled = previewEnabled)
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
    notificationGranted: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetNotifyOnlyOnEvents: (Boolean) -> Unit,
    onChange: (LocalTime, Set<DayOfWeek>) -> Unit,
    onSetDeliveryMode: (DeliveryMode) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestSpeechSetup: () -> Unit,
    onPreview: () -> Unit,
    previewEnabled: Boolean,
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
                notificationGranted = notificationGranted,
                onSelect = onSetDeliveryMode,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onRequestSpeechSetup = onRequestSpeechSetup,
            )
            ToggleRow(
                label = stringResource(R.string.settings_tonight_notify_only_on_events),
                checked = notifyOnlyOnEvents,
                onCheckedChange = onSetNotifyOnlyOnEvents,
            )
            PreviewButton(onClick = onPreview, enabled = previewEnabled)
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
    notificationGranted: Boolean,
    onSelect: (DeliveryMode) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestSpeechSetup: () -> Unit,
) {
    val (notifyStored, ttsOn) = selected.toChannels()
    // Without notification permission the OS drops every post, so the channel
    // can't really be on — read it as off no matter the stored preference.
    // Toggling it on requests the permission; the row only settles on once the
    // grant lands (and immediately on pre-Android-13, where it's implicit).
    val notifyOn = notifyStored && notificationGranted
    ToggleRow(
        label = stringResource(R.string.settings_delivery_notification),
        checked = notifyOn,
        onCheckedChange = { enabled ->
            onSelect(deliveryModeOf(notify = enabled, tts = ttsOn))
            if (enabled) onRequestNotificationPermission()
        },
    )
    // Surface the recoverable grant path whenever the user wants notifications
    // (stored preference on) but the permission is still missing; the banner
    // renders nothing once granted (or on pre-Android-13), so it's safe to keep
    // mounted while the channel is enabled.
    if (notifyStored && !notificationGranted) {
        NotificationPermissionBanner()
    }
    ToggleRow(
        label = stringResource(R.string.settings_delivery_tts),
        checked = ttsOn,
        onCheckedChange = { enabled ->
            // Persist the *stored* notify bit, not the permission-gated display
            // value: while permission is denied notifyOn reads false, and using
            // it here would silently drop a saved notification preference (and
            // with it the recovery banner) just because the user touched speech.
            onSelect(deliveryModeOf(notify = notifyStored, tts = enabled))
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

/** True when this delivery mode posts a notification (so it needs the permission). */
internal fun DeliveryMode.usesNotification(): Boolean =
    this == DeliveryMode.NOTIFICATION_ONLY || this == DeliveryMode.NOTIFICATION_AND_TTS

private fun deliveryModeOf(notify: Boolean, tts: Boolean): DeliveryMode = when {
    notify && tts -> DeliveryMode.NOTIFICATION_AND_TTS
    notify -> DeliveryMode.NOTIFICATION_ONLY
    tts -> DeliveryMode.TTS_ONLY
    else -> DeliveryMode.SILENT
}

/**
 * "Play now" action at the bottom of each schedule section — lets the user
 * preview the cast that section will deliver without waiting for the alarm.
 * Right-aligned so it reads as a secondary action under the section's toggles.
 */
@Composable
private fun PreviewButton(onClick: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        FilledTonalButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
            Text(
                text = stringResource(R.string.settings_schedule_preview),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * Plays the [period] cast on demand via the shared play path — replaying a
 * fresh cached snapshot when one exists, else fetching fresh (see
 * [FetchAndNotifyWorker.playInsight]). Honours the section's DeliveryMode, so
 * the preview matches what the scheduled cast will do. Mirrors the Today
 * screen's Play button (triggerPlay).
 */
private fun triggerPreview(context: android.content.Context, period: ForecastPeriod) {
    FetchAndNotifyWorker.enqueuePlay(context.applicationContext, period)
    val toastRes = when (period) {
        ForecastPeriod.TODAY -> R.string.today_play_toast_daily
        ForecastPeriod.TONIGHT -> R.string.today_play_toast_nightly
    }
    Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
}
