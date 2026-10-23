package app.clothescast.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.R
import app.clothescast.alerts.AlertDisclaimer
import app.clothescast.alerts.AlertIcon
import app.clothescast.alerts.AlertLevelIcon
import app.clothescast.core.domain.model.AlertNotifyLevel
import app.clothescast.data.AlertsCheckOutcome
import app.clothescast.data.WeatherAlertsState
import app.clothescast.diag.DiagLog
import app.clothescast.notification.CHANNEL_WEATHER_ALERTS
import app.clothescast.notification.NotificationPermission
import app.clothescast.ui.EdgeFadeOverlay
import app.clothescast.ui.LocalTimeFormat
import app.clothescast.ui.formatHourMinute
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun AlertsPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_page_alerts, onBack) { padding ->
        CompositionLocalProvider(LocalTimeFormat provides state.timeFormat) {
            AlertsContent(
                enabled = state.weatherAlerts,
                notifyLevel = state.weatherAlertsNotifyLevel,
                status = state.weatherAlertsStatus,
                padding = padding,
                onSetEnabled = viewModel::setWeatherAlerts,
                onSetNotifyLevel = viewModel::setWeatherAlertsNotifyLevel,
            )
        }
    }
}

/**
 * The Alerts page (docs/ALERTS.md → Settings): a master switch, off by
 * default, with the last check's outcome under it (so a place no source
 * covers reads as unavailable, never as all clear), then which levels notify.
 */
@Composable
internal fun AlertsContent(
    enabled: Boolean,
    notifyLevel: AlertNotifyLevel,
    status: WeatherAlertsState,
    padding: PaddingValues,
    onSetEnabled: (Boolean) -> Unit,
    onSetNotifyLevel: (AlertNotifyLevel) -> Unit,
) {
    // Alert notifications need POST_NOTIFICATIONS (Android 13+): asked for
    // when alerts go on or a notifying level is picked; the banner below
    // stays while it's missing. No follow-up here: returning from the prompt
    // resumes the app, which settles the shade (WeatherAlertShade).
    val context = LocalContext.current
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }
    val requestNotificationPermission: () -> Unit = {
        if (NotificationPermission.isRequired() && !NotificationPermission.isGranted(context)) {
            notificationLauncher.launch(NotificationPermission.MANIFEST_PERMISSION)
        }
    }
    val notifies = enabled && notifyLevel != AlertNotifyLevel.NONE
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
            if (notifies) {
                NotificationPermissionBanner(description = R.string.settings_alerts_notification_permission)
            }
            SectionCard(title = stringResource(R.string.settings_weather_alerts_title)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_weather_alerts_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { nowChecked ->
                            onSetEnabled(nowChecked)
                            if (nowChecked && notifyLevel != AlertNotifyLevel.NONE) {
                                requestNotificationPermission()
                            }
                        },
                    )
                }
                if (enabled) {
                    Text(
                        text = alertsStatusText(status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Shown before alerts go on, too; each alert's card links the
                // source's own.
                AlertDisclaimer(sourceDisclaimerUrl = null)
            }
            SectionCard(title = stringResource(R.string.settings_alerts_notify_title)) {
                Column {
                    for (option in NOTIFY_OPTIONS) {
                        NotifyLevelRow(
                            option = option,
                            selected = notifyLevel == option.level,
                            enabled = enabled,
                            onSelect = {
                                onSetNotifyLevel(option.level)
                                if (option.level != AlertNotifyLevel.NONE) requestNotificationPermission()
                            },
                        )
                    }
                }
                TextButton(
                    onClick = { openAlertsChannelSettings(context) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_alerts_channel)) }
            }
        }
    }
}

/** One "Notify me for" choice: its label and the level icons it covers. */
private class NotifyOption(
    val level: AlertNotifyLevel,
    @StringRes val label: Int,
    val icons: List<AlertIcon>,
)

private val NOTIFY_OPTIONS = listOf(
    NotifyOption(AlertNotifyLevel.RED_ONLY, R.string.settings_alerts_notify_red, listOf(AlertIcon.RED)),
    NotifyOption(
        AlertNotifyLevel.ORANGE_AND_RED,
        R.string.settings_alerts_notify_orange_red,
        listOf(AlertIcon.ORANGE, AlertIcon.RED),
    ),
    NotifyOption(
        AlertNotifyLevel.ALL,
        R.string.settings_alerts_notify_all,
        listOf(AlertIcon.YELLOW, AlertIcon.ORANGE, AlertIcon.RED),
    ),
    NotifyOption(AlertNotifyLevel.NONE, R.string.settings_alerts_notify_none, emptyList()),
)

@Composable
private fun NotifyLevelRow(
    option: NotifyOption,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick = null so the row's selectable takes the tap (as RadioRow).
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = stringResource(option.label),
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        )
        // The label names the levels, so the icons are decorative here.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            option.icons.forEach { AlertLevelIcon(it, size = 20.dp, decorative = true) }
        }
    }
}

/**
 * The last check's outcome: no alerts in force says so, and a failed check or
 * a place without coverage never reads as all clear.
 */
@Composable
private fun alertsStatusText(status: WeatherAlertsState): String {
    val checkedAt = status.checkedAt
    return when (status.outcome) {
        AlertsCheckOutcome.UNSUPPORTED -> stringResource(R.string.settings_alerts_status_unsupported)
        AlertsCheckOutcome.FAILED -> stringResource(R.string.settings_alerts_status_failed)
        AlertsCheckOutcome.OK -> if (checkedAt != null) {
            val zone = ZoneId.systemDefault()
            val at = checkedAt.atZone(zone)
            val time = formatHourMinute(at.toLocalTime())
            val label = if (at.toLocalDate() == LocalDate.now(zone)) {
                time
            } else {
                val locale = LocalConfiguration.current.locales[0]
                "${DateTimeFormatter.ofPattern("EEE", locale).format(at)} $time"
            }
            // Nothing in force: said outright, so a quiet check reads as a
            // result and not as a blank.
            val none = status.active(Instant.now()).isEmpty()
            stringResource(
                if (none) R.string.settings_alerts_status_none else R.string.settings_alerts_status_checked,
                label,
            )
        } else {
            stringResource(R.string.settings_alerts_status_waiting)
        }
        // Just turned on: the check is on its way.
        AlertsCheckOutcome.NEVER -> stringResource(R.string.settings_alerts_status_waiting)
    }
}

/** The system page for the alerts channel: its sound, and whether it shows on the lock screen. */
private fun openAlertsChannelSettings(context: Context) {
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_WEATHER_ALERTS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        // Some builds lack the per-channel page; the app's page has it one tap on.
        DiagLog.w("AlertsSettings", e, "Channel settings unavailable; opening the app's details.")
        openAppDetails(context)
    }
}
