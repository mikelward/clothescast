package app.clothescast.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.clothescast.R
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.diag.DiagLog
import app.clothescast.diag.Telemetry
import app.clothescast.notification.CHANNEL_PLAYBACK
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Owns the foreground-service notification for a scheduled-delivery run, from
 * the alarm firing through to the worker finishing its fan-out. See
 * docs/schedule-lifecycle.md for the full state machine.
 *
 * Started by [AlarmReceiver] via [androidx.core.content.ContextCompat.startForegroundService]
 * inside the exact-alarm FGS-from-background exemption window. The receiver
 * has already read preferences, decided the run needs a foreground surface
 * (i.e. the period isn't SILENT-with-no-MQTT-no-cast), and enqueued the
 * worker. The Service receives the worker's UUID, the `playsSpeech` /
 * `deliveringWantsForeground` flags, and is responsible only for:
 *
 *  - posting the "Preparing your ClothesCast" FGS notification immediately,
 *  - swapping it to "Delivering your ClothesCast" when the worker stamps
 *    [FetchAndNotifyWorker.KEY_FETCH_COMPLETE] in its progress data,
 *  - tearing the FGS down when the worker reaches a terminal state (or the
 *    5-minute safety timeout fires for a worker that never starts).
 *
 * Two foreground-service types are declared in the manifest:
 *  - `dataSync` — used while fetching + generating, and while delivering on
 *    paths that don't play audio (MQTT or cast without phone speech).
 *  - `mediaPlayback` — used while speech is playing on the phone, so audio
 *    focus can be claimed on Android 15+.
 *
 * The Service exposes [isHoldingForegroundFor] as a per-worker owner gate so
 * the worker can decide whether to promote itself to its own FGS — needed
 * for the corner cases where the Service's 5-minute safety timeout fires
 * before the worker (deferred by [androidx.work.NetworkType.CONNECTED]) gets
 * to run, or where a second alarm fires for a different period while we're
 * still shepherding the first.
 */
class ScheduledDeliveryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val period = parsePeriod(intent)
        val workIdString = intent?.getStringExtra(EXTRA_WORK_ID)
        val workId = workIdString?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val playsSpeech = intent?.getBooleanExtra(EXTRA_PLAYS_SPEECH, false) ?: false
        val deliveringWantsForeground =
            intent?.getBooleanExtra(EXTRA_DELIVERING_WANTS_FOREGROUND, false) ?: false
        DiagLog.i(
            TAG,
            "ScheduledDeliveryService onStartCommand period=$period workId=$workIdString",
        )

        if (workId == null) {
            DiagLog.w(TAG, "No workId on Service intent; nothing to watch — exiting")
            stopSelfIfIdle()
            return START_NOT_STICKY
        }

        // Same workId arriving twice (rebroadcast / restart) → idempotent.
        if (workId == foregroundWorkId) {
            DiagLog.i(TAG, "Already shepherding workId=$workId; no-op")
            return START_NOT_STICKY
        }

        // A different workId arriving while we're already shepherding one
        // means the receiver enqueued a new alarm-driven worker. Both
        // periods share a single unique-work queue (see UNIQUE_WORK_NAME),
        // so the REPLACE policy at the WorkManager layer cancels the
        // previous worker — regardless of which slot it belonged to. We
        // just need to cancel the old watcher coroutine so it doesn't run
        // its teardown — we want to keep the Service foregrounded and swap
        // the notification in place rather than tear down and re-create.
        // enterForeground below updates the existing id 1005 with the new
        // content and stamps foregroundWorkId = newId.
        if (foregroundWorkId != null) {
            DiagLog.i(TAG, "Replacing shepherd: oldWorkId=$foregroundWorkId → newWorkId=$workId")
            watcher?.cancel()
            watcher = null
        }

        if (!enterForeground(stage = Stage.PREPARING, playsSpeech = playsSpeech, workId = workId)) {
            DiagLog.w(TAG, "Couldn't enter foreground; exiting (worker runs without Service FGS)")
            stopSelfIfIdle()
            return START_NOT_STICKY
        }

        watcher = scope.launch {
            watchWorkerUntilDone(workId, period, playsSpeech, deliveringWantsForeground)
            teardown(workId)
        }
        return START_NOT_STICKY
    }

    private suspend fun watchWorkerUntilDone(
        workId: UUID,
        period: ForecastPeriod,
        playsSpeech: Boolean,
        deliveringWantsForeground: Boolean,
    ) {
        val wm = WorkManager.getInstance(applicationContext)
        var stage = Stage.PREPARING

        // Stage upgrade is shared between both phases — encode it once so the
        // predicates below stay focused on their own exit conditions.
        fun maybeUpgradeStage(info: WorkInfo?) {
            if (info == null) return
            if (stage == Stage.PREPARING && deliveringWantsForeground &&
                info.progress.getBoolean(FetchAndNotifyWorker.KEY_FETCH_COMPLETE, false)
            ) {
                stage = Stage.DELIVERING
                enterForeground(stage = Stage.DELIVERING, playsSpeech = playsSpeech, workId = workId)
            }
        }

        val flow = wm.getWorkInfoByIdFlow(workId)

        // Phase 1 — wait for the worker to start running (or finish without
        // running, or hit the pre-start cap). The cap protects against a
        // worker that's deferred indefinitely by NetworkType.CONNECTED on a
        // flaky connection: we don't want "Preparing" sitting on the user's
        // shade for hours waiting on a network that never returns. Five
        // minutes is generous enough for typical doze deferrals and short
        // outages without leaving a stale notification all day.
        val phase1Info = withTimeoutOrNull(PRE_RUNNING_TIMEOUT_MS) {
            flow.firstOrNull { info ->
                maybeUpgradeStage(info)
                info != null && (info.state == WorkInfo.State.RUNNING || info.state.isFinished)
            }
        }
        if (phase1Info == null) {
            DiagLog.w(TAG, "Worker for $period didn't reach RUNNING within ${PRE_RUNNING_TIMEOUT_MS}ms; exiting service")
            Telemetry.logScheduledDeliveryTimeout(slot = slotName(period))
            return
        }
        if (phase1Info.state.isFinished) {
            logTerminal(phase1Info, period)
            return
        }

        // Phase 2 — worker is RUNNING. Wait for the state to leave
        // RUNNING — either a terminal state (SUCCEEDED / FAILED /
        // CANCELLED) or a step back to ENQUEUED / BLOCKED because the
        // worker returned `Result.retry()` for an exponential-backoff
        // pause (Open-Meteo 429, transient socket failure, etc.). We
        // tear down on the retry transition too: backoff can stretch
        // for minutes-to-hours across repeated transient failures, and
        // leaving "Preparing" up for the whole thing is exactly the
        // stuck-FGS case we're trying to avoid. The worker's *next*
        // attempt will find isHoldingForegroundFor(id) false (we cleared
        // foregroundWorkId in teardown) and promote itself for the
        // speech window if it gets to deliver.
        //
        // No timeout in this phase: TTS + cast + delivery alignment can
        // legitimately run several minutes when the worker is actually
        // executing, and WorkManager already caps foreground workers at
        // ~10 minutes via its own machinery. Racing that cap with our
        // own timeout was the previous risk.
        val finalInfo = flow.firstOrNull { info ->
            maybeUpgradeStage(info)
            info != null && info.state != WorkInfo.State.RUNNING
        }
        if (finalInfo != null) logTerminal(finalInfo, period)
    }

    /**
     * Diagnostic log of the worker's terminal state so a missing forecast
     * can be triaged in the field. CANCELLED is the interesting one —
     * either a downstream REPLACE (newer alarm canceled this run) or
     * WorkManager's own ~10-minute foreground-worker cap kicked in.
     */
    private fun logTerminal(info: WorkInfo, period: ForecastPeriod) {
        when (info.state) {
            WorkInfo.State.SUCCEEDED -> DiagLog.i(TAG, "$period worker reached SUCCEEDED")
            WorkInfo.State.FAILED -> DiagLog.w(TAG, "$period worker reached FAILED")
            WorkInfo.State.CANCELLED -> DiagLog.w(TAG, "$period worker reached CANCELLED (possibly REPLACE or WorkManager's ~10-min cap)")
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                DiagLog.i(TAG, "$period worker stepped back to ${info.state} (Result.retry backoff); exiting service — next attempt will promote itself if needed")
            else -> DiagLog.i(TAG, "$period worker phase-2 exit state=${info.state}")
        }
    }

    /**
     * Tears down the FGS *only* when [foregroundWorkId] still matches the
     * workId the caller (a watcher coroutine) was responsible for. Guards
     * the replace-in-flight race: a previous watcher can observe CANCELLED
     * on `Dispatchers.Default` and enter teardown after [onStartCommand]
     * has already swapped `foregroundWorkId` to a new workId. Without the
     * guard, the stale watcher would `stopForeground(REMOVE)` on the
     * *new* run's notification and leave its scheduled TTS without the
     * FGS it relies on.
     */
    private fun teardown(workId: UUID) {
        if (foregroundWorkId == workId) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundWorkId = null
            // Bare stopSelf (no startId) so any startIds the overlap-rejection
            // branch left dangling don't keep the service alive past the
            // shepherded run.
            stopSelf()
        } else {
            DiagLog.i(TAG, "Skipping teardown for workId=$workId; current foregroundWorkId=$foregroundWorkId")
        }
    }

    /**
     * Used by the early-exit branches before any watcher has launched
     * (missing workId, foreground promotion refused). Only stops the
     * service when nothing is being shepherded — if a watcher is already
     * running we leave the service alone and let [teardown] handle it.
     */
    private fun stopSelfIfIdle() {
        if (watcher == null && foregroundWorkId == null) stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        foregroundWorkId = null
        super.onDestroy()
    }

    /**
     * Calls [startForeground] with a notification matching [stage]. Returns
     * false if the platform refused the start (the caller exits without
     * holding the FGS in that case). Stamps [foregroundWorkId] on success so
     * [isHoldingForegroundFor] reports the live owner.
     */
    private fun enterForeground(stage: Stage, playsSpeech: Boolean, workId: UUID): Boolean {
        val titleRes = when (stage) {
            Stage.PREPARING -> R.string.notification_playback_title
            Stage.DELIVERING -> R.string.notification_delivering_title
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification_insight)
            .setContentTitle(getString(titleRes))
            .setOngoing(true)
            .setSilent(true)
            // Android 12+ defers FGS notifications by ~10 seconds by default
            // (system optimization for short-lived FGSs). Our whole
            // "Preparing your ClothesCast" rationale is "let the user know
            // the schedule fired *now*" — a 10-second delay defeats that on
            // fast runs (notification-only mode finishing before the
            // notification even shows). FOREGROUND_SERVICE_IMMEDIATE turns
            // that deferral off for this notification.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        // When the run plays phone speech, claim mediaPlayback from the
        // start — not just at DELIVERING. The race we're avoiding is the
        // worker reaching TTS before the watcher's async progress observer
        // upgrades the FGS type: a slow fetch (>60s) can swallow the
        // alignment wait, and the cached redelivery path also goes
        // straight from setProgress to deliver(). On Android 15+ a dataSync
        // FGS can't grant audio focus, so the speech would be silenced.
        // Starting mediaPlayback during PREPARING closes the window
        // completely; the cost is a slightly broader FGS type for the few
        // seconds before deliver() actually starts speaking — negligible.
        val type = when {
            playsSpeech -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return runCatching { startForeground(FOREGROUND_NOTIFICATION_ID, notification, type) }
            .onSuccess { foregroundWorkId = workId }
            .onFailure { DiagLog.w(TAG, "startForeground($stage) failed", it) }
            .isSuccess
    }

    private fun slotName(period: ForecastPeriod): String = when (period) {
        ForecastPeriod.TODAY -> "today"
        ForecastPeriod.TONIGHT -> "tonight"
    }

    private fun parsePeriod(intent: Intent?): ForecastPeriod {
        val name = intent?.getStringExtra(EXTRA_PERIOD) ?: return ForecastPeriod.TODAY
        return runCatching { ForecastPeriod.valueOf(name) }.getOrDefault(ForecastPeriod.TODAY)
    }

    private enum class Stage { PREPARING, DELIVERING }

    companion object {
        private const val TAG = "SchedDeliveryService"

        // Same notification id as the worker's fallback playback FGS. Only
        // one FGS can hold a given id at a time, and the worker's
        // promoteToPlaybackServiceIfNeeded reads [isHoldingForegroundFor] to
        // skip its own setForeground only when we own this id for the worker
        // making the check.
        const val FOREGROUND_NOTIFICATION_ID = 1005

        /**
         * How long the watcher will hold "Preparing" while waiting for the
         * worker to reach RUNNING. Covers doze deferrals and short network
         * outages without leaving the FGS notification stranded all day on
         * a constraint that never resolves. Once the worker reaches
         * RUNNING, the watcher drops the timeout entirely and waits for a
         * terminal state — WorkManager's own ~10-min foreground-worker cap
         * is the upper bound there, so we don't race it.
         */
        private const val PRE_RUNNING_TIMEOUT_MS = 5L * 60L * 1000L

        const val EXTRA_PERIOD = "app.clothescast.alarm.PERIOD"
        const val EXTRA_WORK_ID = "app.clothescast.alarm.WORK_ID"
        const val EXTRA_PLAYS_SPEECH = "app.clothescast.alarm.PLAYS_SPEECH"
        const val EXTRA_DELIVERING_WANTS_FOREGROUND = "app.clothescast.alarm.DELIVERING_WANTS_FOREGROUND"

        // Per-worker owner gate. Holds the UUID of the worker whose run is
        // currently being shepherded by the Service (FGS notification id
        // [FOREGROUND_NOTIFICATION_ID] is up), or null when the Service is
        // not foregrounded. Read by [FetchAndNotifyWorker.promoteToPlaybackServiceIfNeeded]
        // via [isHoldingForegroundFor] to decide whether to promote itself.
        //
        // A *per-worker* gate (not a process-wide bool) is what correctly
        // handles overlapping runs: when a second alarm fires for a different
        // period while we're still shepherding the first, the second worker's
        // [isHoldingForegroundFor] check returns false (different UUID) and it
        // promotes itself. The first worker's check still returns true and it
        // keeps relying on the Service's FGS.
        //
        // Resets to null on teardown / onDestroy / process restart — all
        // cases where the next worker must take over background-audio FGS
        // duties for Android 15+.
        @Volatile
        private var foregroundWorkId: UUID? = null

        @JvmStatic
        fun isHoldingForegroundFor(workId: UUID): Boolean = foregroundWorkId == workId

        fun intent(
            context: Context,
            period: ForecastPeriod,
            workId: String,
            playsSpeech: Boolean,
            deliveringWantsForeground: Boolean,
        ): Intent = Intent(context, ScheduledDeliveryService::class.java)
            .putExtra(EXTRA_PERIOD, period.name)
            .putExtra(EXTRA_WORK_ID, workId)
            .putExtra(EXTRA_PLAYS_SPEECH, playsSpeech)
            .putExtra(EXTRA_DELIVERING_WANTS_FOREGROUND, deliveringWantsForeground)
    }
}
