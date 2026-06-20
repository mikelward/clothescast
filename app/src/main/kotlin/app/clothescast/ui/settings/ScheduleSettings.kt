package app.clothescast.ui.settings

import android.Manifest
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
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.location.hasBackgroundLocationPermission
import app.clothescast.location.hasCoarseLocationPermission
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
    // True when speech already has a working engine (device TTS, or Gemini with
    // a BYOK key / the shared proxy). When set, turning on "Speak" skips the
    // jump to Voice settings — there's nothing left to configure.
    speechConfigured: Boolean,
    // Location state, so enabling a schedule can request exactly the location
    // access an unattended scheduled run needs: foreground when no location is
    // set yet, then always-on once device location is the source.
    useDeviceLocation: Boolean,
    location: Location?,
    padding: PaddingValues,
    onSetSchedule: (LocalTime, Set<DayOfWeek>) -> Unit,
    onSetDailyEnabled: (Boolean) -> Unit,
    onSetTonightSchedule: (LocalTime, Set<DayOfWeek>) -> Unit,
    onSetTonightEnabled: (Boolean) -> Unit,
    onSetUseDeviceLocation: (Boolean) -> Unit,
    onSetTonightNotifyOnlyOnEvents: (Boolean) -> Unit,
    onSetDailyMentionEveningEvents: (Boolean) -> Unit,
    onSetDeliveryMode: (DeliveryMode) -> Unit,
    onSetTonightDeliveryMode: (DeliveryMode) -> Unit,
    // Enabling the "Speak" delivery channel jumps to the full Voice settings page
    // to pick the engine and (for Gemini) drop in a key. The page shows a "Done"
    // button that returns here — see ClothesCastNavHost wiring it to VoiceDest.
    // Only fires when speech isn't set up yet (see [speechConfigured]).
    onSetUpSpeech: () -> Unit,
    // Smart-home delivery toggles, surfaced here as well as on the Smart Home
    // page so a user wiring up their schedule can see — and flip — where each
    // cast lands without leaving the page. Each block only renders when its
    // destination's master switch is on in Smart Home settings: the Cast block
    // when [castEnabled] (and Cast is available on the device), the MQTT block
    // when [mqttBridgeEnabled]. The toggles, gating, and copy mirror the Smart
    // Home cards exactly. Defaulted so previews / tests that don't exercise
    // smart-home delivery keep the sections hidden.
    mqttBridgeEnabled: Boolean = false,
    mqttSkipPhoneSpeech: Boolean = true,
    castAvailable: Boolean = false,
    castRouteName: String? = null,
    castEnabled: Boolean = false,
    castMorning: Boolean = true,
    castTonight: Boolean = true,
    castSkipPhoneSpeech: Boolean = true,
    onSetMqttSkipPhoneSpeech: (Boolean) -> Unit = {},
    onSetCastMorning: (Boolean) -> Unit = {},
    onSetCastTonight: (Boolean) -> Unit = {},
    onSetCastSkipPhoneSpeech: (Boolean) -> Unit = {},
    // Jumps to the full Smart Home settings page — where the cast display is
    // picked and the MQTT bridge configured. Shown beneath the smart-home
    // sections so the toggles here aren't a dead end (the per-period cast
    // toggles stay disabled until a display is picked, which only happens
    // there). No-op default for previews / tests.
    onOpenSmartHome: () -> Unit = {},
    // Gates the per-section "Play now" buttons: false while any daily / tonight
    // / play worker is active, so a preview can't start a second concurrent
    // delivery. Defaulted true for previews/tests that don't observe work state.
    previewEnabled: Boolean = true,
) {
    val context = LocalContext.current

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
    // Location grants gate the just-in-time prompts on the schedule-enable path.
    // Re-checked on resume alongside notifications so a grant/revoke made in
    // system Settings (the always-on picker deep-links there) is reflected
    // without an in-app action.
    var coarseGranted by remember { mutableStateOf(hasCoarseLocationPermission(context)) }
    var backgroundGranted by remember { mutableStateOf(hasBackgroundLocationPermission(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationGranted = NotificationPermission.isGranted(context)
                coarseGranted = hasCoarseLocationPermission(context)
                backgroundGranted = hasBackgroundLocationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var backgroundRationaleOpen by remember { mutableStateOf(false) }
    var backgroundDeniedOpen by remember { mutableStateOf(false) }
    // Set while a notification prompt fired from the enable path is in flight, so
    // its result callback knows to continue into the location chain rather than
    // showing two system dialogs at once. Delivery-channel toggles reuse the same
    // launcher but leave this false, so they don't trigger the location chain.
    var pendingScheduleLocationCheck by remember { mutableStateOf(false) }

    // Turning a delivery channel on requests exactly what that channel needs,
    // just-in-time. Notify → the system POST_NOTIFICATIONS prompt (no-op on
    // pre-Android-13 or when already granted; a denial is recoverable via the
    // NotificationPermissionBanner shown beside the toggle). Speak → the full
    // Voice settings page (via onSetUpSpeech) to pick Gemini-with-key or device
    // TTS, which returns here via its "Done" button.
    val backgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        backgroundGranted = granted
        if (!granted) backgroundDeniedOpen = true
    }

    // Launching ACCESS_BACKGROUND_LOCATION on Android 11+ deep-links to the
    // system Location-permission picker (the "Allow all the time" page). We
    // front it with our own rationale so the deep-link isn't a surprise.
    val ensureBackgroundLocation: () -> Unit = {
        if (!backgroundGranted) backgroundRationaleOpen = true
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        coarseGranted = granted
        if (granted) {
            // Granting foreground from the schedule path means the user wants the
            // scheduled cast to follow the device, so switch the source on — then
            // chain into always-on, which the unattended run actually needs.
            onSetUseDeviceLocation(true)
            ensureBackgroundLocation()
        }
    }

    // The location access a scheduled run needs, requested just-in-time when a
    // schedule is enabled. No location set yet → ask for foreground (which turns
    // device location on); already on device location → make sure always-on is
    // granted. A manual city needs no location permission, so nothing fires.
    val ensureLocationForSchedule: () -> Unit = {
        when {
            useDeviceLocation && !coarseGranted ->
                foregroundLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            useDeviceLocation -> ensureBackgroundLocation()
            location == null ->
                foregroundLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationGranted = granted
        // Continue the enable chain only if this prompt came from enabling a
        // schedule; sequencing after the notification result avoids stacking two
        // system permission dialogs.
        if (pendingScheduleLocationCheck) {
            pendingScheduleLocationCheck = false
            ensureLocationForSchedule()
        }
    }
    val requestNotificationPermission: () -> Unit = {
        if (NotificationPermission.isRequired() && !notificationGranted) {
            notificationLauncher.launch(NotificationPermission.MANIFEST_PERMISSION)
        }
    }

    // Enabling a schedule opts the user into unattended delivery, so request
    // everything that run needs: notifications first, then — sequenced after its
    // result — the location access the worker needs to resolve a fix on its own.
    val onScheduleEnabled: () -> Unit = {
        if (NotificationPermission.isRequired() && !notificationGranted) {
            pendingScheduleLocationCheck = true
            notificationLauncher.launch(NotificationPermission.MANIFEST_PERMISSION)
        } else {
            ensureLocationForSchedule()
        }
    }

    // Turning on "Speak" jumps to Voice settings so a first-time user can pick
    // an engine and (for Gemini) drop in a key. But if speech is already set up,
    // there's nothing to configure — stay put rather than bouncing them away.
    val requestSpeechSetup: () -> Unit = {
        if (!speechConfigured) onSetUpSpeech()
    }

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
                // delivery, so prompt for everything that unattended run needs —
                // notifications and, when device location is the source, the
                // always-on location grant — right then, not only when a channel
                // is toggled. The notification channel is on by default, so
                // without this the prompts would never fire on the enable path
                // and the worker would later no-op silently.
                onSetEnabled = { enabled ->
                    onSetDailyEnabled(enabled)
                    if (enabled) onScheduleEnabled()
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
                // Same as the morning card: prompt for notification and (when
                // device location is the source) always-on location the moment
                // the user enables the evening schedule.
                onSetEnabled = { enabled ->
                    onSetTonightEnabled(enabled)
                    if (enabled) onScheduleEnabled()
                },
                onSetNotifyOnlyOnEvents = onSetTonightNotifyOnlyOnEvents,
                onChange = onSetTonightSchedule,
                onSetDeliveryMode = onSetTonightDeliveryMode,
                onRequestNotificationPermission = requestNotificationPermission,
                onRequestSpeechSetup = requestSpeechSetup,
                onPreview = { triggerPreview(context, ForecastPeriod.TONIGHT) },
                previewEnabled = previewEnabled,
            )
            // Cast section — only when Cast is available and switched on. Mirrors
            // the Smart Home card's per-period toggles and the same gate: the
            // toggles stay disabled until a display is picked (on the Smart Home
            // page), so the preconditions stay visible.
            if (castAvailable && castEnabled) {
                val castPerPeriodEnabled = castRouteName != null
                SectionCard(title = stringResource(R.string.settings_smart_home_cast_title)) {
                    CastToggleRow(
                        title = stringResource(R.string.settings_smart_home_cast_morning_title),
                        description = stringResource(R.string.settings_smart_home_cast_morning_description),
                        checked = castMorning,
                        enabled = castPerPeriodEnabled,
                        onCheckedChange = onSetCastMorning,
                    )
                    CastToggleRow(
                        title = stringResource(R.string.settings_smart_home_cast_tonight_title),
                        description = stringResource(R.string.settings_smart_home_cast_tonight_description),
                        checked = castTonight,
                        enabled = castPerPeriodEnabled,
                        onCheckedChange = onSetCastTonight,
                    )
                    CastToggleRow(
                        title = stringResource(R.string.settings_smart_home_cast_skip_phone_speech_title),
                        description = stringResource(R.string.settings_smart_home_cast_skip_phone_speech_description),
                        checked = castSkipPhoneSpeech,
                        enabled = castPerPeriodEnabled,
                        onCheckedChange = onSetCastSkipPhoneSpeech,
                    )
                }
            }
            // MQTT section — only when the bridge is switched on.
            if (mqttBridgeEnabled) {
                SectionCard(title = stringResource(R.string.settings_smart_home_mqtt_title)) {
                    MqttToggleRow(
                        title = stringResource(R.string.settings_smart_home_mqtt_skip_phone_speech_title),
                        description = stringResource(R.string.settings_smart_home_mqtt_skip_phone_speech_description),
                        checked = mqttSkipPhoneSpeech,
                        onCheckedChange = onSetMqttSkipPhoneSpeech,
                    )
                }
            }
            // Escape hatch to the full Smart Home page (display picker, MQTT
            // config). Shown whenever a smart-home section is visible above.
            if ((castAvailable && castEnabled) || mqttBridgeEnabled) {
                OutlinedButton(
                    onClick = onOpenSmartHome,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_page_smart_home)) }
            }
        }
    }

    if (backgroundRationaleOpen) {
        AlertDialog(
            onDismissRequest = { backgroundRationaleOpen = false },
            title = { Text(stringResource(R.string.settings_location_background_rationale_title)) },
            text = { Text(stringResource(R.string.settings_location_background_rationale_body)) },
            confirmButton = {
                TextButton(onClick = {
                    backgroundRationaleOpen = false
                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text(stringResource(R.string.settings_location_background_rationale_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { backgroundRationaleOpen = false }) {
                    Text(stringResource(R.string.settings_location_background_rationale_dismiss))
                }
            },
        )
    }

    if (backgroundDeniedOpen) {
        AlertDialog(
            onDismissRequest = { backgroundDeniedOpen = false },
            title = { Text(stringResource(R.string.settings_location_background_denied_title)) },
            text = { Text(stringResource(R.string.settings_location_background_denied_body)) },
            confirmButton = {
                TextButton(onClick = {
                    backgroundDeniedOpen = false
                    openAppDetails(context)
                }) { Text(stringResource(R.string.settings_location_background_denied_open)) }
            },
            dismissButton = {
                TextButton(onClick = { backgroundDeniedOpen = false }) {
                    Text(stringResource(R.string.settings_location_background_denied_keep))
                }
            },
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
