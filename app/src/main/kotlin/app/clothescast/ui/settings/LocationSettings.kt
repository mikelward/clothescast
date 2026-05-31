package app.clothescast.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.clothescast.core.domain.model.Location
import app.clothescast.location.hasBackgroundLocationPermission
import app.clothescast.location.hasCoarseLocationPermission
import app.clothescast.ui.EdgeFadeOverlay
import app.clothescast.ui.today.openInMaps
import kotlinx.coroutines.launch

@Composable
internal fun LocationContent(
    location: Location?,
    useDeviceLocation: Boolean,
    locationDetecting: Boolean = false,
    // Whether a morning or evening schedule is enabled. Background ("Allow all
    // the time") location is only needed when the worker runs unattended, so
    // the always-on warning banner only nags when a schedule is on — granting
    // it now lives on the Schedule page's enable flow.
    scheduleEnabled: Boolean = false,
    padding: PaddingValues,
    onSetUseDeviceLocation: (Boolean) -> Unit,
    onSelectLocation: (Location) -> Unit,
    onClearLocation: () -> Unit,
    onSearchLocations: suspend (String) -> List<Location>,
    onRefresh: () -> Unit = {},
) {
    val context = LocalContext.current
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
            LocationCard(
                current = location,
                useDeviceLocation = useDeviceLocation,
                locationDetecting = locationDetecting,
                scheduleEnabled = scheduleEnabled,
                onSetUseDeviceLocation = onSetUseDeviceLocation,
                onSelect = onSelectLocation,
                onClear = onClearLocation,
                onSearch = onSearchLocations,
                onRefresh = onRefresh,
            )
            // Secondary link to the full privacy policy. Mirrors the same
            // affordance on Privacy settings — the user landing here from
            // the insight-card tap may want a one-tap path to the long form
            // without backing out and drilling into Privacy. Bottom of the
            // page so it doesn't compete with the primary controls above.
            TextButton(
                onClick = { openUrl(context, PRIVACY_POLICY_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_privacy_open_policy)) }
        }
    }
}

@Composable
private fun LocationCard(
    current: Location?,
    useDeviceLocation: Boolean,
    locationDetecting: Boolean,
    scheduleEnabled: Boolean,
    onSetUseDeviceLocation: (Boolean) -> Unit,
    onSelect: (Location) -> Unit,
    onClear: () -> Unit,
    onSearch: suspend (String) -> List<Location>,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    var dialogOpen by remember { mutableStateOf(false) }
    var coarseGranted by remember { mutableStateOf(hasCoarseLocationPermission(context)) }
    var backgroundGranted by remember { mutableStateOf(hasBackgroundLocationPermission(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Re-check on resume so the prominent "Grant" warning disappears once the
        // user grants the permission via the system Settings deep-link.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                coarseGranted = hasCoarseLocationPermission(context)
                backgroundGranted = hasBackgroundLocationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var backgroundRationaleOpen by remember { mutableStateOf(false) }
    var backgroundDeniedOpen by remember { mutableStateOf(false) }

    val backgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        backgroundGranted = granted
        if (!granted) backgroundDeniedOpen = true
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        coarseGranted = granted
        // Only flip the toggle on if foreground was granted; otherwise the worker
        // would hit our isPermissionGranted check, return null, and quietly fall
        // through to the settings location every day. We no longer auto-chain into
        // the always-on prompt here — background location is only needed once a
        // schedule runs the worker unattended, so that grant moved to the Schedule
        // page's enable flow.
        onSetUseDeviceLocation(granted)
    }

    // Only nag for always-on once a schedule actually needs it: device location +
    // a foreground refresh works fine without ACCESS_BACKGROUND_LOCATION; it's the
    // unattended scheduled run that can't read the fix without it.
    if (useDeviceLocation && scheduleEnabled && !backgroundGranted) {
        BackgroundLocationWarningBanner(
            onGrant = { backgroundRationaleOpen = true },
        )
    }

    SectionCard(title = stringResource(R.string.settings_location_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_location_use_device),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = useDeviceLocation,
                onCheckedChange = { wantsOn ->
                    if (!wantsOn) {
                        onSetUseDeviceLocation(false)
                        return@Switch
                    }
                    // Just get foreground location here — that's all turning on
                    // device location needs to resolve a fix on a foreground
                    // refresh. The always-on ("Allow all the time") grant is
                    // requested later, when the user enables a schedule and the
                    // worker actually needs to read the fix unattended.
                    if (!coarseGranted) {
                        foregroundLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    } else {
                        onSetUseDeviceLocation(true)
                    }
                },
            )
        }
        // The display-name summary. Manual / forward-geocoded picks have no
        // addressDetail line below to carry the tap-to-maps action, so in
        // that case make the summary itself the maps deep link — same
        // affordance as the GPS path, just hung off the one place-name we
        // have. Detecting / unset states fall through to the plain label
        // since they have no coords to open.
        if (current != null && current.addressDetail == null) {
            Text(
                text = currentLocationSummary(current, useDeviceLocation, locationDetecting),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        openInMaps(
                            context = context,
                            latitude = current.latitude,
                            longitude = current.longitude,
                            label = current.displayName,
                        )
                    },
            )
        } else {
            Text(
                text = currentLocationSummary(current, useDeviceLocation, locationDetecting),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        // Reverse-geocoded address with the leading "house number / street"
        // component dropped, so we surface neighbourhood-level detail
        // (suburb + city + postal code + country) without naming a specific
        // street. Only populated by the device-location path; manual /
        // forward-geocoded picks leave this null and the line is omitted
        // (the summary above carries the tap-to-maps action in that case).
        // Rendered in the primary colour and tappable, opening the user's
        // chosen maps app at the 2dp-coarsened coords the rest of the app
        // already operates on (Location.coarsened() rounds every entry-point
        // write to ~1 km) — same affordance as the city link on Today's
        // insight cards, but here the destination is the map rather than
        // this very page.
        current?.addressDetail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        openInMaps(
                            context = context,
                            latitude = current.latitude,
                            longitude = current.longitude,
                            label = current.displayName,
                        )
                    },
            )
        }

        // Privacy footnote: the coords leaving the device for the weather
        // request, the bug-report payload, and the maps deep link have all
        // been rounded to a ~1km grid. Shown whenever a location is on
        // screen — manual picks coarsen too (Location.coarsened() on every
        // entry point), so the note isn't device-location-specific.
        if (current != null) {
            Text(
                text = stringResource(R.string.settings_location_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Refresh button — re-queues the same one-shot worker
        // [setUseDeviceLocation(true)] enqueues so the detecting indicator
        // lights up for the duration. Only useful when device location is
        // the active source: a manual override wouldn't change on a
        // refresh, so we hide the button entirely in that mode. Disabled
        // while a refresh is already in flight to prevent re-enqueuing
        // mid-flight (which would just bill the geocoder a second time).
        if (useDeviceLocation) {
            TextButton(
                onClick = onRefresh,
                enabled = !locationDetecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_location_refresh)) }
        }

        // Manual override is the escape hatch for "the system returned the wrong
        // location" — demoted to a TextButton so it doesn't compete with the
        // primary device-location toggle. Selecting a city automatically turns
        // off auto-detect (handled in SettingsViewModel.selectLocation) so the
        // pick sticks; the disclosure tells the user up front.
        TextButton(
            onClick = { dialogOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_location_manual_override)) }
        if (useDeviceLocation) {
            Text(
                text = stringResource(R.string.settings_location_manual_override_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (current != null && !useDeviceLocation) {
            // Hide Clear when device location is on — the cache repopulates on the
            // next worker run anyway, so a Clear tap would have no lasting effect
            // and the user would rightly find that confusing.
            TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_location_clear))
            }
        }
    }

    if (dialogOpen) {
        LocationSearchDialog(
            onDismiss = { dialogOpen = false },
            onSelect = {
                onSelect(it)
                dialogOpen = false
            },
            onSearch = onSearch,
        )
    }

    if (backgroundRationaleOpen) {
        AlertDialog(
            onDismissRequest = { backgroundRationaleOpen = false },
            title = { Text(stringResource(R.string.settings_location_background_rationale_title)) },
            text = { Text(stringResource(R.string.settings_location_background_rationale_body)) },
            confirmButton = {
                TextButton(onClick = {
                    backgroundRationaleOpen = false
                    // Launching ACCESS_BACKGROUND_LOCATION deep-links to the system
                    // Location-permission picker (the page with the "Allow all the
                    // time" radio). An earlier version routed through openAppDetails
                    // here, which only opens the generic App info screen and forces
                    // the user to drill in via Permissions → Location themselves.
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

@Composable
private fun currentLocationSummary(
    current: Location?,
    useDeviceLocation: Boolean,
    locationDetecting: Boolean,
): String {
    if (current != null) {
        return current.displayName ?: "${current.latitude}, ${current.longitude}"
    }
    // Show "Detecting…" only while the cache-refresh worker is actively running.
    // Once it reaches a terminal state locationDetecting flips false, so the
    // label falls back to the unset string instead of staying stuck forever when
    // the worker finishes without finding a fix (no permission, provider timeout).
    return if (useDeviceLocation && locationDetecting) {
        stringResource(R.string.settings_location_detecting)
    } else {
        stringResource(R.string.settings_location_unset)
    }
}

@Composable
private fun BackgroundLocationWarningBanner(onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_location_background_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_location_background_banner_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_location_grant_background)) }
        }
    }
}

/**
 * Settings root warning card — a compact deep-link variant of
 * [BackgroundLocationWarningBanner]. Shown on the Settings root when device
 * location is on, a schedule is enabled, but background access is missing;
 * tapping the card navigates into the Location sub-page where the full launcher
 * and rationale dialogs live. Renders nothing while permission is granted,
 * device location is off, or no schedule needs an unattended fix, and re-checks
 * on resume so granting from system Settings clears the card without an in-app
 * action.
 */
@Composable
internal fun BackgroundLocationWarningCard(
    useDeviceLocation: Boolean,
    scheduleEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var backgroundGranted by remember { mutableStateOf(hasBackgroundLocationPermission(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                backgroundGranted = hasBackgroundLocationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!useDeviceLocation || !scheduleEnabled || backgroundGranted) return

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_location_background_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_location_background_banner_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Search-by-city-name dialog used by the location page and the onboarding
 * screen's location step. Shows a query field, runs [onSearch] on demand, and
 * lets the user pick exactly one of the geocoder results.
 */
@Composable
internal fun LocationSearchDialog(
    onDismiss: () -> Unit,
    onSelect: (Location) -> Unit,
    onSearch: suspend (String) -> List<Location>,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Location>>(emptyList()) }
    var inFlight by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Location?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let(onSelect) },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.settings_location_search_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.settings_location_query_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    val coroutineScope = rememberCoroutineScope()
                    TextButton(
                        enabled = query.isNotBlank() && !inFlight,
                        onClick = {
                            coroutineScope.launch {
                                inFlight = true
                                error = null
                                try {
                                    results = onSearch(query)
                                    selected = null
                                } catch (t: Throwable) {
                                    error = t.message ?: t.javaClass.simpleName
                                    results = emptyList()
                                } finally {
                                    inFlight = false
                                }
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text(stringResource(R.string.settings_location_search)) }
                }

                when {
                    inFlight -> Text(stringResource(R.string.settings_location_searching))
                    error != null -> Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    results.isEmpty() && query.isNotBlank() ->
                        Text(stringResource(R.string.settings_location_no_results))
                    else -> results.forEach { result ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == result,
                                onClick = { selected = result },
                            )
                            Text(
                                text = result.displayName ?: "${result.latitude}, ${result.longitude}",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}
