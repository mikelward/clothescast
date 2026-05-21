package app.clothescast.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.clothescast.R
import app.clothescast.calendar.CalendarPermission
import app.clothescast.diag.findActivity
import app.clothescast.ui.EdgeFadeOverlay

@Composable
internal fun CalendarContent(
    calendarEnabled: Boolean,
    useCalendarEvents: Boolean,
    themeFromCalendarHolidays: Boolean,
    themeFromCalendarBirthdays: Boolean,
    padding: PaddingValues,
    onSetCalendarEnabled: (Boolean) -> Unit,
    onSetUseCalendarEvents: (Boolean) -> Unit,
    onSetThemeFromCalendarHolidays: (Boolean) -> Unit,
    onSetThemeFromCalendarBirthdays: (Boolean) -> Unit,
    onCalendarPermissionRechecked: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    // One source of permission truth for the whole page. Re-checked on resume so
    // a grant/revoke from system Settings is reflected without leaving the page;
    // a revoke flips the master switch off (and pings consumers to refresh) so we
    // don't keep claiming calendar access we no longer have.
    var permissionGranted by remember { mutableStateOf(CalendarPermission.isGranted(context)) }
    // True once a permission request comes back denied *and* the system won't
    // show the rationale dialog again ("don't ask again" / permanently denied).
    // In that state tapping the toggle does nothing — the OS suppresses the
    // prompt — so the only way back is the app's system-settings screen, which
    // is the sole reason we surface a button at all.
    var permanentlyDenied by remember { mutableStateOf(false) }
    val currentCalendarEnabled by rememberUpdatedState(calendarEnabled)
    val currentOnSetCalendarEnabled by rememberUpdatedState(onSetCalendarEnabled)
    val currentOnRechecked by rememberUpdatedState(onCalendarPermissionRechecked)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = CalendarPermission.isGranted(context)
                permissionGranted = granted
                if (granted) {
                    // Granted out-of-band (e.g. via system settings) — clear the
                    // permanently-denied affordance.
                    permanentlyDenied = false
                } else if (currentCalendarEnabled) {
                    currentOnSetCalendarEnabled(false)
                    currentOnRechecked()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Single permission launcher with a pending action: whichever toggle asked
    // to turn on runs its enable callback once permission is granted. The VM
    // flips the master switch on when any sub-feature is enabled, so enabling a
    // sub-feature from scratch (master off) prompts once and lights up both.
    var pendingEnable by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            permanentlyDenied = false
            pendingEnable?.invoke()
            currentOnRechecked()
        } else {
            // Denied. If the system would still show the rationale dialog the
            // user can just try the toggle again; if not, they're permanently
            // denied and we point them at system settings.
            val activity = context.findActivity()
            permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    CalendarPermission.MANIFEST_PERMISSION,
                )
        }
        pendingEnable = null
    }
    val requestThenEnable: (() -> Unit) -> Unit = { action ->
        if (permissionGranted) action() else {
            pendingEnable = action
            launcher.launch(CalendarPermission.MANIFEST_PERMISSION)
        }
    }

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
            SectionCard(title = stringResource(R.string.settings_calendar_title)) {
                CalendarToggleRow(
                    label = stringResource(R.string.settings_calendar_master),
                    checked = calendarEnabled && permissionGranted,
                    onToggle = { wantsOn ->
                        // On: the toggle itself is the permission prompt — no
                        // separate "grant" button needed. Off: we just stop
                        // reading the calendar in-app (the *Active gates). We
                        // deliberately don't relinquish the OS permission:
                        // there's no immediate API for it, and
                        // `revokeSelfPermissionsOnKill` is API 33+ (minSdk is 31)
                        // and only takes effect after the process is killed, so
                        // it'd add a version-gated, surprising code path for no
                        // real benefit until we bump minSdk to Android 13+.
                        if (wantsOn) requestThenEnable { onSetCalendarEnabled(true) }
                        else onSetCalendarEnabled(false)
                    },
                )
                Text(
                    text = stringResource(
                        if (calendarEnabled && permissionGranted) R.string.settings_calendar_master_description_on
                        else R.string.settings_calendar_master_description_off,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (permanentlyDenied && !permissionGranted) {
                    Text(
                        text = stringResource(R.string.settings_calendar_open_settings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = { openAppDetails(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_calendar_open_system_settings)) }
                }
            }

            SectionCard(title = stringResource(R.string.settings_calendar_features_title)) {
                CalendarFeatureRow(
                    label = stringResource(R.string.settings_calendar_evening_tie_ins),
                    description = stringResource(R.string.settings_calendar_evening_tie_ins_description),
                    checked = calendarEnabled && useCalendarEvents && permissionGranted,
                    onToggle = { wantsOn ->
                        if (wantsOn) requestThenEnable { onSetUseCalendarEvents(true) }
                        else onSetUseCalendarEvents(false)
                    },
                )
                CalendarFeatureRow(
                    label = stringResource(R.string.settings_calendar_birthdays),
                    description = stringResource(R.string.settings_calendar_birthdays_description),
                    checked = calendarEnabled && themeFromCalendarBirthdays && permissionGranted,
                    onToggle = { wantsOn ->
                        if (wantsOn) requestThenEnable { onSetThemeFromCalendarBirthdays(true) }
                        else onSetThemeFromCalendarBirthdays(false)
                    },
                )
                CalendarFeatureRow(
                    label = stringResource(R.string.settings_calendar_public_holidays),
                    description = stringResource(R.string.settings_calendar_public_holidays_description),
                    checked = calendarEnabled && themeFromCalendarHolidays && permissionGranted,
                    onToggle = { wantsOn ->
                        if (wantsOn) requestThenEnable { onSetThemeFromCalendarHolidays(true) }
                        else onSetThemeFromCalendarHolidays(false)
                    },
                )
            }
        }
    }
}

/** A bare label + switch row (master toggle). */
@Composable
private fun CalendarToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/** A sub-feature row: label + supporting description on the left, switch right. */
@Composable
private fun CalendarFeatureRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
