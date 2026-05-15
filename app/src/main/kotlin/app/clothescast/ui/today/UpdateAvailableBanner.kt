package app.clothescast.ui.today

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.ClothesCastApplication
import app.clothescast.R
import app.clothescast.update.UpdateState
import kotlinx.coroutines.launch

/**
 * Banner shown on the Today screen when Play has a newer build of the app.
 *
 * Only surfaces for Play-Store installs ([app.clothescast.update.InstallSource]
 * gates the check) and only when the available version code is higher than
 * the one the user has dismissed. Tapping **Update** kicks off Play's flexible
 * in-app update flow — the new APK downloads in the background; while it's
 * in flight the banner shows a "Downloading update…" progress bar and the
 * action button reads "Updating" (disabled — Play's AppUpdateManager has
 * no cancel-download API, so a real cancel isn't possible). Once
 * [UpdateState.ReadyToInstall] arrives the banner switches to a **Restart**
 * prompt that calls [app.clothescast.update.AppUpdateChecker.completeUpdate].
 * The dismiss button stays in every phase (relabelled "Cancel" during
 * Downloading, where the user has logically progressed past "Not now")
 * to keep the card height stable as state transitions; in all phases it
 * persists the available version code so the banner stays hidden until a
 * still-newer version is published.
 *
 * All three on-screen variants are driven entirely by the checker's state:
 * an in-session listener moves Available → Downloading → ReadyToInstall
 * (and back to Available on FAILED / CANCELED), and `refresh()` recovers
 * the same state across process recreation by consulting `installStatus()`
 * directly. So a user who backgrounded the app between download and install
 * still sees the Restart prompt on the next open instead of being stranded
 * on "Update".
 *
 * Re-checks on `ON_RESUME` so a long-running app picks up newly-published
 * updates without a cold start, matching the [LastCrashBanner] lifecycle
 * pattern.
 */
@Composable
internal fun UpdateAvailableBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as ClothesCastApplication
    val checker = app.appUpdateChecker
    val settings = app.settingsRepository
    val coroutineScope = rememberCoroutineScope()

    val state by checker.state.collectAsStateWithLifecycle()
    val dismissedVersion by settings.dismissedUpdateVersion.collectAsStateWithLifecycle(initialValue = 0)

    LaunchedEffect(Unit) { checker.refresh() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                coroutineScope.launch { checker.refresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        // Result is RESULT_OK / RESULT_CANCELED / RESULT_IN_APP_UPDATE_FAILED.
        // No action needed: a successful FLEXIBLE start means the download is
        // now in flight, and the checker's install-state listener drives
        // Available → Downloading → ReadyToInstall from PENDING / DOWNLOADING /
        // DOWNLOADED events. Cancellation just leaves the banner in its current
        // "Update" state for next time.
    }

    val available = state as? UpdateState.Available
    val downloading = state as? UpdateState.Downloading
    val ready = state as? UpdateState.ReadyToInstall
    val versionCode = available?.availableVersionCode
        ?: downloading?.availableVersionCode
        ?: ready?.availableVersionCode
        ?: return
    if (versionCode <= dismissedVersion) return

    val phase = when {
        ready != null -> UpdatePhase.ReadyToInstall
        downloading != null -> UpdatePhase.Downloading(
            progress = downloading.totalBytes
                .takeIf { it > 0 }
                ?.let { downloading.bytesDownloaded.toFloat() / it.toFloat() },
        )
        else -> UpdatePhase.Available
    }

    UpdateAvailableBannerCard(
        modifier = modifier,
        phase = phase,
        onAction = {
            when {
                ready != null -> checker.completeUpdate()
                available != null -> checker.startFlexibleUpdate(available.info, launcher)
            }
        },
        onDismiss = {
            coroutineScope.launch { settings.setDismissedUpdateVersion(versionCode) }
        },
    )
}

internal sealed interface UpdatePhase {
    data object Available : UpdatePhase
    /** [progress] is null while bytes/total are unknown (PENDING). */
    data class Downloading(val progress: Float?) : UpdatePhase
    data object ReadyToInstall : UpdatePhase
}

@Composable
internal fun UpdateAvailableBannerCard(
    phase: UpdatePhase,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // primaryContainer (not errorContainer): a stale build is informational,
    // not an error state. Sets the banner apart from LastCrashBanner /
    // LocationActionRequiredBanner / WorkStatusBanner.Failed which all use
    // errorContainer for genuine "something went wrong" cases.
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.today_update_available_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    when (phase) {
                        UpdatePhase.Available -> R.string.today_update_available_body
                        is UpdatePhase.Downloading -> R.string.today_update_downloading_body
                        UpdatePhase.ReadyToInstall -> R.string.today_update_downloaded_body
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (phase is UpdatePhase.Downloading) {
                if (phase.progress != null) {
                    LinearProgressIndicator(
                        progress = { phase.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = ProgressIndicatorDefaults.linearTrackColor,
                    )
                }
            }
            // Keep the button row in every phase so the card doesn't change
            // height when Available → Downloading → ReadyToInstall flips.
            // During Downloading, the action button reads "Updating" and is
            // disabled — Play's AppUpdateManager has no cancel-download API,
            // so we can't offer a real cancel; "Not now" stays active with
            // its usual dismiss semantics for users who want the banner gone.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    // "Cancel" in Downloading reads as a change-of-mind action,
                    // distinct from the initial "Not now" the user has already
                    // logically passed. Same dismiss semantics under the hood
                    // (persists the version) — Play has no real cancel API.
                    Text(
                        stringResource(
                            if (phase is UpdatePhase.Downloading) {
                                R.string.today_update_downloading_dismiss
                            } else {
                                R.string.today_update_available_dismiss
                            },
                        ),
                    )
                }
                Button(
                    onClick = onAction,
                    enabled = phase !is UpdatePhase.Downloading,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        stringResource(
                            when (phase) {
                                UpdatePhase.Available -> R.string.today_update_available_action
                                is UpdatePhase.Downloading -> R.string.today_update_downloading_action
                                UpdatePhase.ReadyToInstall -> R.string.today_update_downloaded_action
                            },
                        ),
                    )
                }
            }
        }
    }
}
