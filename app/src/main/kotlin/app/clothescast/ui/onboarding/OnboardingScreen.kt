package app.clothescast.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.R
import app.clothescast.core.domain.model.Location
import app.clothescast.location.hasBackgroundLocationPermission
import app.clothescast.location.hasCoarseLocationPermission
import app.clothescast.notification.NotificationPermission
import app.clothescast.ui.EdgeFadeOverlay
import app.clothescast.ui.isTelevision
import app.clothescast.ui.settings.KeyEntryFields
import app.clothescast.ui.settings.LinkifiedText
import app.clothescast.ui.settings.LocationSearchDialog
import app.clothescast.ui.settings.openAppDetails

/**
 * First-run onboarding. Shown when the user lands on a fresh install missing any
 * of the things ClothesCast can't pick a sensible default for: notification
 * permission, a location, or a Gemini API key. Walks the user through each, then
 * drops them on Settings → Schedule so they can accept or change the default
 * 7am every-day schedule before reaching Today.
 *
 * "Skip for now" is session-only — the onboarding will reappear on cold launch
 * if the conditions still hold.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onPairFromPhone: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isTV = remember(context) { isTelevision(context) }

    Scaffold(
        // Drop the default `safeDrawing` content insets so the onboarding
        // content extends edge-to-edge. The inner Column adds
        // `windowInsetsPadding(WindowInsets.navigationBars)` so its content
        // can scroll above the nav bar.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        OnboardingContent(
            geminiKeyConfigured = state.geminiKeyConfigured,
            location = state.location,
            useDeviceLocation = state.useDeviceLocation,
            locationDetecting = state.locationDetecting,
            isTelevision = isTV,
            padding = padding,
            onSetApiKey = viewModel::setApiKey,
            onSetUseDeviceLocation = viewModel::setUseDeviceLocation,
            onSelectLocation = viewModel::selectLocation,
            onSearchLocations = viewModel::searchLocations,
            onPairFromPhone = onPairFromPhone,
            onContinue = onContinue,
            onSkip = onSkip,
        )
    }
}

@Composable
internal fun OnboardingContent(
    geminiKeyConfigured: Boolean,
    location: Location?,
    useDeviceLocation: Boolean,
    locationDetecting: Boolean = false,
    isTelevision: Boolean = false,
    padding: PaddingValues,
    onSetApiKey: (String) -> Unit,
    onSetUseDeviceLocation: (Boolean) -> Unit,
    onSelectLocation: (Location) -> Unit,
    onSearchLocations: suspend (String) -> List<Location>,
    onPairFromPhone: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
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
                // Onboarding has no TopAppBar to consume the status-bar /
                // cutout insets, so use `safeDrawing` (= status + navigation
                // + cutout + IME) here rather than just `navigationBars`.
                // Without the top inset the title can start under the
                // status bar on devices with a tall bar or a display cutout.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(if (isTelevision) R.string.onboarding_subtitle_tv else R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // TV OS does not expose POST_NOTIFICATIONS as a runtime permission — skip it.
            if (!isTelevision) {
                NotificationStep()
                HorizontalDivider()
            }
            LocationStep(
                location = location,
                useDeviceLocation = useDeviceLocation,
                locationDetecting = locationDetecting,
                isTelevision = isTelevision,
                onSetUseDeviceLocation = onSetUseDeviceLocation,
                onSelectLocation = onSelectLocation,
                onSearchLocations = onSearchLocations,
            )
            HorizontalDivider()
            GeminiKeyStep(
                configured = geminiKeyConfigured,
                onSave = onSetApiKey,
                onPairFromPhone = onPairFromPhone,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.onboarding_skip)) }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.onboarding_continue)) }
            }
        }
    }
}

@Composable
private fun NotificationStep() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(NotificationPermission.isGranted(context)) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Re-check on resume so toggling permission from system Settings is reflected
        // when the user returns to onboarding.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationPermission.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    StepCard(
        title = stringResource(R.string.onboarding_notifications_title),
        description = stringResource(R.string.onboarding_notifications_description),
        complete = granted,
    ) {
        if (!granted && NotificationPermission.isRequired()) {
            Button(
                onClick = { launcher.launch(NotificationPermission.MANIFEST_PERMISSION) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_notifications_grant)) }
        }
    }
}

@Composable
private fun LocationStep(
    location: Location?,
    useDeviceLocation: Boolean,
    locationDetecting: Boolean,
    isTelevision: Boolean,
    onSetUseDeviceLocation: (Boolean) -> Unit,
    onSelectLocation: (Location) -> Unit,
    onSearchLocations: suspend (String) -> List<Location>,
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(hasCoarseLocationPermission(context))
    }
    var backgroundGranted by remember {
        mutableStateOf(hasBackgroundLocationPermission(context))
    }
    var permissionAsked by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var backgroundRationaleOpen by remember { mutableStateOf(false) }
    var backgroundDeniedOpen by remember { mutableStateOf(false) }

    // Background launcher: launching ACCESS_BACKGROUND_LOCATION deep-links to the
    // system Location-permission picker (the page with "Allow all the time").
    val backgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        backgroundGranted = granted
        if (granted) {
            // Re-trigger cache refresh: the foreground-grant callback above
            // already enqueued a refresh, but that run can fire and complete
            // before the user has tapped "Allow all the time" — in which case
            // LocationResolver returns null (SecurityException from
            // requestSingleUpdate without ACCESS_BACKGROUND_LOCATION) and
            // onboarding stays stuck without a resolved city. Calling
            // setUseDeviceLocation(true) is idempotent for the preference but
            // re-enqueues the cache-refresh worker via REPLACE, which now
            // succeeds with both perms in place.
            onSetUseDeviceLocation(true)
        } else {
            backgroundDeniedOpen = true
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        permissionAsked = true
        // Mirrors LocationEditor: only flip the toggle on if foreground was granted,
        // otherwise the worker would consult the reader and silently fall through to
        // the configured fallback location every day.
        if (granted) {
            onSetUseDeviceLocation(true)
            // Auto-chain into the background-permission ask: the daily worker needs
            // ACCESS_BACKGROUND_LOCATION to read the device fix when the app isn't
            // foregrounded. Without this chain the user finishes onboarding with
            // foreground-only and the worker silently falls back to the saved city.
            if (!hasBackgroundLocationPermission(context)) {
                backgroundRationaleOpen = true
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Re-check on resume so toggling permission from system Settings is reflected
        // when the user returns to onboarding.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = hasCoarseLocationPermission(context)
                backgroundGranted = hasBackgroundLocationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // On TV device location is unavailable; only a manually picked city counts.
    // Otherwise we treat the step as "complete" only when both foreground *and*
    // background are granted (or the user picked a manual fallback city). Marking
    // the step complete on foreground-only would put a green check on a state that
    // still breaks the morning worker.
    val deviceLocationFullyConfigured =
        useDeviceLocation && permissionGranted && backgroundGranted
    val configured = if (isTelevision) {
        location != null
    } else {
        deviceLocationFullyConfigured || location != null
    }
    val needsBackgroundPrompt =
        !isTelevision && useDeviceLocation && permissionGranted && !backgroundGranted

    StepCard(
        title = stringResource(R.string.onboarding_location_title),
        description = stringResource(R.string.onboarding_location_description),
        complete = configured,
    ) {
        // While the cache-refresh worker is in flight, surface "Detecting…"
        // *before* any cached location — otherwise a manual fallback the
        // user picked earlier in onboarding would mask the fact that a
        // device-location lookup is still running, and they'd treat the
        // stale manual pick as the freshly-resolved device fix. Once the
        // worker writes through, location is non-null and we show the
        // real displayName so the user can verify the fix matches reality.
        // Fall back to the static device-location confirmation text only
        // when both perms are granted but we have no fix yet (e.g.
        // NETWORK_PROVIDER returned no result).
        if (useDeviceLocation && locationDetecting) {
            Text(
                text = stringResource(R.string.settings_location_detecting),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (location != null) {
            Text(
                text = location.displayName ?: "${location.latitude}, ${location.longitude}",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (deviceLocationFullyConfigured) {
            Text(
                text = stringResource(R.string.onboarding_location_using_device),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (needsBackgroundPrompt) {
            // Foreground granted but background isn't — surface the always-on prompt
            // as the primary affordance. The worker can't read the device fix without
            // this, so don't let the user breeze past with foreground-only.
            Text(
                text = stringResource(R.string.onboarding_location_background_needed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { backgroundRationaleOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_location_grant_background)) }
        }

        if (!configured) {
            // Once useDeviceLocation is on, the always-on prompt above takes over and
            // this grant-foreground button is redundant. Hide it then but keep the
            // manual fallback reachable below so the user can still finish onboarding
            // with a valid configuration if they skip always-on.
            if (!isTelevision && !useDeviceLocation) {
                Button(
                    onClick = {
                        if (permissionGranted) {
                            onSetUseDeviceLocation(true)
                            if (!backgroundGranted) {
                                backgroundRationaleOpen = true
                            }
                        } else {
                            launcher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onboarding_location_grant)) }

                // Once the user has been asked once and denied, surface the manual
                // fallback hint as a primary path. Showing it from the start would
                // compete with the permission button; showing it only on denial keeps
                // the happier path uncluttered.
                if (permissionAsked && !permissionGranted) {
                    Text(
                        text = stringResource(R.string.onboarding_location_denied_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Caption the manual fallback so new users don't read it as a co-equal
            // choice and pick it just to dodge the permission prompt. Suppressed on
            // TV (manual is the *only* path) and after a denial (the existing
            // denied_hint already says the same thing).
            if (!isTelevision && !(permissionAsked && !permissionGranted)) {
                Text(
                    text = stringResource(R.string.onboarding_location_enter_manual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { searchOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_location_enter_manual)) }
        }
    }

    if (searchOpen) {
        LocationSearchDialog(
            onDismiss = { searchOpen = false },
            onSelect = {
                onSelectLocation(it)
                searchOpen = false
            },
            onSearch = onSearchLocations,
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
                    // Location-permission picker so the user can tap "Allow all the
                    // time". An earlier version routed through openAppDetails here,
                    // which dumps them on the generic App info screen instead.
                    backgroundLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
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
private fun GeminiKeyStep(
    configured: Boolean,
    onSave: (String) -> Unit,
    onPairFromPhone: () -> Unit,
) {
    StepCard(
        title = stringResource(R.string.onboarding_gemini_title),
        description = stringResource(R.string.onboarding_gemini_description),
        complete = configured,
    ) {
        if (!configured) {
            KeyEntryFields(
                configured = false,
                statusText = stringResource(R.string.settings_api_key_status_unset),
                placeholder = stringResource(R.string.settings_api_key_placeholder),
                saveLabel = stringResource(R.string.settings_api_key_save),
                replaceLabel = stringResource(R.string.settings_api_key_replace),
                clearLabel = stringResource(R.string.settings_api_key_clear),
                onSave = onSave,
                onClear = {},
            )
            TextButton(
                onClick = onPairFromPhone,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_pair_from_phone)) }
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    description: String,
    complete: Boolean,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (complete) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.onboarding_step_done),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            LinkifiedText(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}
