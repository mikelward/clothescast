package app.clothescast.update

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import app.clothescast.diag.DiagLog
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin wrapper around Play's [AppUpdateManager] for the Today-screen update
 * banner.
 *
 * On non-Play installs (sideload, F-Droid) the check short-circuits to
 * [UpdateState.UpToDate] without touching Play Services — see [InstallSource].
 * Any failure from `requestAppUpdateInfo` (no Play Services, airplane mode,
 * Play SDK init failure) also collapses to UpToDate so the banner stays
 * hidden rather than surfacing a confusing error: a missing update prompt
 * is the right default when we can't determine the answer.
 *
 * Three states drive the banner: [UpdateState.Available] (a newer build is
 * published — show "Update"), [UpdateState.Downloading] (the flexible
 * download is in flight — show progress), and [UpdateState.ReadyToInstall]
 * (download finished — show "Restart"). All three are reachable both
 * within a session (the install-state listener drives the transitions live)
 * and across process recreation ([refresh] consults `installStatus()`
 * directly, which handles the case where the user backgrounded the app
 * between phases — Play returns `installStatus == DOWNLOADED` /
 * `DOWNLOADING` with `updateAvailability ==
 * DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS`, so a naive `updateAvailability`
 * check alone would lose the user's in-flight or downloaded update).
 */
class AppUpdateChecker(private val context: Context) {
    private val manager: AppUpdateManager by lazy { AppUpdateManagerFactory.create(context) }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.NotChecked)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile private var listenerRegistered = false
    private val listener = InstallStateUpdatedListener { installState ->
        // Live-session state machine: Available → Downloading (PENDING /
        // DOWNLOADING, with progress) → ReadyToInstall (DOWNLOADED). On
        // FAILED / CANCELED we drop back to Available so the user can retry.
        // Across process recreation, `refresh()` is the source of truth.
        val active = _state.value.activeUpdate() ?: return@InstallStateUpdatedListener
        _state.value = when (installState.installStatus()) {
            InstallStatus.PENDING,
            InstallStatus.DOWNLOADING ->
                UpdateState.Downloading(
                    availableVersionCode = active.availableVersionCode,
                    info = active.info,
                    bytesDownloaded = installState.bytesDownloaded(),
                    totalBytes = installState.totalBytesToDownload(),
                )
            InstallStatus.DOWNLOADED ->
                UpdateState.ReadyToInstall(active.availableVersionCode, active.info)
            InstallStatus.FAILED,
            InstallStatus.CANCELED ->
                UpdateState.Available(active.availableVersionCode, active.info)
            else -> _state.value
        }
    }

    suspend fun refresh() {
        if (!InstallSource.isFromPlayStore(context)) {
            _state.value = UpdateState.UpToDate
            return
        }
        val info = runCatching { manager.requestAppUpdateInfo() }
            .onFailure { DiagLog.w(TAG, "requestAppUpdateInfo failed", it) }
            .getOrNull()
        if (info == null) {
            _state.value = UpdateState.UpToDate
            return
        }
        ensureListenerRegistered()
        _state.value = when {
            // Already-downloaded path: a previous session's flexible download
            // finished while the process was dead. Play returns
            // `updateAvailability == DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS`
            // here (not UPDATE_AVAILABLE), so check installStatus() first or
            // we'd silently strand a downloaded update behind a hidden banner.
            info.installStatus() == InstallStatus.DOWNLOADED ->
                UpdateState.ReadyToInstall(info.availableVersionCode(), info)
            // In-flight download recovered from a previous foreground or session:
            // Play returns DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS while a FLEXIBLE
            // download is still pending or in progress. Surface it as Downloading
            // so the banner shows progress instead of looking idle, and so the
            // listener can promote it to ReadyToInstall when DOWNLOADED arrives.
            info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
                info.installStatus() in setOf(InstallStatus.PENDING, InstallStatus.DOWNLOADING) ->
                UpdateState.Downloading(
                    availableVersionCode = info.availableVersionCode(),
                    info = info,
                    bytesDownloaded = info.bytesDownloaded(),
                    totalBytes = info.totalBytesToDownload(),
                )
            info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                UpdateState.Available(info.availableVersionCode(), info)
            else -> UpdateState.UpToDate
        }
    }

    fun startFlexibleUpdate(
        info: AppUpdateInfo,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
    ) {
        manager.startUpdateFlowForResult(
            info,
            launcher,
            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
        )
    }

    /** Triggers the install once the FLEXIBLE download has completed. */
    fun completeUpdate() {
        manager.completeUpdate()
    }

    private fun ensureListenerRegistered() {
        if (listenerRegistered) return
        manager.registerListener(listener)
        listenerRegistered = true
    }

    companion object {
        private const val TAG = "AppUpdateChecker"
    }
}

sealed interface UpdateState {
    data object NotChecked : UpdateState
    data object UpToDate : UpdateState
    data class Available(val availableVersionCode: Int, val info: AppUpdateInfo) : UpdateState
    data class Downloading(
        val availableVersionCode: Int,
        val info: AppUpdateInfo,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : UpdateState
    data class ReadyToInstall(val availableVersionCode: Int, val info: AppUpdateInfo) : UpdateState
}

/**
 * Carrier for the version + Play handle shared by the three "we have an
 * update for this version" states. Used by the install-state listener to
 * carry forward the right `AppUpdateInfo` when transitioning between them
 * without needing a separate cast for each.
 */
private data class ActiveUpdate(val availableVersionCode: Int, val info: AppUpdateInfo)

private fun UpdateState.activeUpdate(): ActiveUpdate? = when (this) {
    is UpdateState.Available -> ActiveUpdate(availableVersionCode, info)
    is UpdateState.Downloading -> ActiveUpdate(availableVersionCode, info)
    is UpdateState.ReadyToInstall -> ActiveUpdate(availableVersionCode, info)
    UpdateState.NotChecked, UpdateState.UpToDate -> null
}
