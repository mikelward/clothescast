package app.clothescast.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.clothescast.R
import app.clothescast.calendar.CalendarPermission
import app.clothescast.ui.EdgeFadeOverlay

@Composable
internal fun CalendarContent(
    useCalendarEvents: Boolean,
    padding: PaddingValues,
    onSetUseCalendarEvents: (Boolean) -> Unit,
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
            CalendarCard(
                useEvents = useCalendarEvents,
                onSetUseEvents = onSetUseCalendarEvents,
            )
        }
    }
}

@Composable
private fun CalendarCard(
    useEvents: Boolean,
    onSetUseEvents: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(CalendarPermission.isGranted(context)) }

    val currentUseEvents by rememberUpdatedState(useEvents)
    val currentOnSetUseEvents by rememberUpdatedState(onSetUseEvents)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Re-check on resume so the toggle reflects whatever the user did in system
        // Settings while we were backgrounded. If permission was revoked, also flip
        // the persisted pref off so the worker stops consulting the reader.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = CalendarPermission.isGranted(context)
                permissionGranted = granted
                if (!granted && currentUseEvents) {
                    currentOnSetUseEvents(false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        // Only flip the toggle on if the user granted; otherwise the worker would
        // consult the reader, get an empty list every morning, and silently log a
        // permission-denied warning forever.
        onSetUseEvents(granted)
    }

    SectionCard(title = stringResource(R.string.settings_calendar_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_calendar_use_events),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = useEvents && permissionGranted,
                onCheckedChange = { wantsOn ->
                    if (!wantsOn) {
                        onSetUseEvents(false)
                        return@Switch
                    }
                    if (permissionGranted) {
                        onSetUseEvents(true)
                    } else {
                        launcher.launch(CalendarPermission.MANIFEST_PERMISSION)
                    }
                },
            )
        }
        Text(
            text = stringResource(
                if (useEvents && permissionGranted) R.string.settings_calendar_description_on
                else R.string.settings_calendar_description_off,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (useEvents && !permissionGranted) {
            Text(
                text = stringResource(R.string.settings_calendar_open_settings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = { openAppDetails(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_calendar_grant_permission)) }
        }
    }
}
