package app.clothescast.work

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.clothescast.MainActivity
import app.clothescast.R
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.diag.DiagLog
import app.clothescast.notification.CHANNEL_DAILY_INSIGHT
import app.clothescast.notification.CHANNEL_TONIGHT_INSIGHT_DEFAULT
import app.clothescast.notification.InsightNotifier
import app.clothescast.notification.SpeechDismissReceiver
import app.clothescast.notification.TonightInsightNotifier

/**
 * Short-lived media-playback foreground service that owns the single notification
 * for a *scheduled, spoken* briefing.
 *
 * Why a service we control rather than WorkManager's `setForeground`: a spoken
 * briefing runs from a background WorkManager worker, and modern Android only
 * lets background audio play while a `mediaPlayback` foreground service is up
 * (15+ denies audio focus, 17+ silences `AudioTrack` outright otherwise). The
 * worker could get that via `setForeground`, but WorkManager *removes* its
 * foreground notification the moment the worker finishes — so the forecast would
 * vanish from the shade the instant speech ends. By owning the service we can
 * call `stopForeground(STOP_FOREGROUND_DETACH)` instead, which leaves the very
 * same notification behind as an ordinary one the user can read and clear on
 * their own time. One notification, full lifecycle, no duplicate.
 *
 * Lifecycle, driven by the worker ([FetchAndNotifyWorker]):
 *  1. [start] (early in the run, inside the exact-alarm FGS-start exemption):
 *     `startForeground` with a silent "Preparing your forecast…" placeholder on
 *     the period's own notification id + channel. Silent so it doesn't alert a
 *     minute before there's anything to say — the alert lands when the worker
 *     replaces this with the real forecast at the aligned briefing moment.
 *  2. The worker posts the forecast under the *same notification id* (via
 *     [InsightNotifier] / [TonightInsightNotifier]), which updates this very
 *     notification in place — now showing the outfit + prose, and alerting.
 *  3. [finish] once delivery is done: `STOP_FOREGROUND_DETACH` when the forecast
 *     was posted (leave it in the shade until the user clears it), or
 *     `STOP_FOREGROUND_REMOVE` when nothing was delivered (drop the bare
 *     placeholder), then `stopSelf`.
 *
 * A safety timeout removes the notification and stops the service if [finish]
 * never arrives (worker killed mid-run), so a crash can't strand a foreground
 * service with a "Preparing…" notification forever.
 */
class PlaybackForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        DiagLog.w(TAG, "Playback service timed out without a finish signal; stopping and clearing.")
        stopAndClear(detach = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val period = intent.getStringExtra(EXTRA_PERIOD)
                    ?.let { runCatching { ForecastPeriod.valueOf(it) }.getOrNull() }
                    ?: ForecastPeriod.TODAY
                promote(period, intent.getLongExtra(EXTRA_TOKEN, 0L))
            }
            ACTION_FINISH -> stopAndClear(detach = intent.getBooleanExtra(EXTRA_DETACH, false))
            else -> {
                // An unrecognized (or null, e.g. process-restart) start: we have
                // nothing to play and no notification to keep alive, so stop
                // rather than sit foreground with a placeholder we never finish.
                DiagLog.w(TAG, "Playback service started without a recognized action; stopping.")
                stopAndClear(detach = false)
            }
        }
        // Don't recreate with a null intent if the system kills us — there's
        // nothing to resume, and a sticky restart would re-post the placeholder.
        return START_NOT_STICKY
    }

    private fun promote(period: ForecastPeriod, token: Long) {
        val (channel, id) = when (period) {
            ForecastPeriod.TODAY ->
                CHANNEL_DAILY_INSIGHT to InsightNotifier.NOTIFICATION_ID_DAILY_INSIGHT
            ForecastPeriod.TONIGHT ->
                CHANNEL_TONIGHT_INSIGHT_DEFAULT to TonightInsightNotifier.NOTIFICATION_ID_TONIGHT_INSIGHT
        }
        try {
            ServiceCompat.startForeground(
                this,
                id,
                placeholderNotification(channel, token),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, MAX_PLAYBACK_MS)
            DiagLog.i(TAG, "Playback foreground service started for $period (notification $id).")
        } catch (t: Throwable) {
            // Most likely a ForegroundServiceStartNotAllowedException if the
            // exact-alarm exemption window had lapsed by the time we started, or
            // a missing-type error. The worker continues in the background
            // (speech may be inaudible on 15+, the documented degradation); don't
            // leave a half-started service behind.
            DiagLog.w(TAG, "Couldn't start the playback foreground service; stopping it.", t)
            stopSelf()
        }
    }

    private fun placeholderNotification(channel: String, token: Long): Notification =
        NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification_insight)
            .setContentTitle(getString(R.string.notification_preparing_title))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    REQUEST_OPEN_APP,
                    MainActivity.todayTapIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            // Carry the run token so swiping the placeholder during the
            // fetch/alignment wait (Android 14+ lets an FGS notification be
            // dismissed) is remembered and the briefing skips speech rather than
            // talking over the dismissal. Same request code + token as the
            // forecast that replaces it (FetchAndNotifyWorker.stopSpeechDeleteIntent),
            // so they share one PendingIntent.
            .setDeleteIntent(stopSpeechDeleteIntent(token))
            .setOngoing(true)
            // Silent: the real forecast (which replaces this) carries the alert
            // when speech starts; a placeholder shown during the fetch/alignment
            // wait shouldn't chime on its own.
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    // Must match FetchAndNotifyWorker.stopSpeechDeleteIntent's request-code
    // formula (token.hashCode()) so the placeholder and the forecast that
    // replaces it share one PendingIntent.
    private fun stopSpeechDeleteIntent(token: Long): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            token.hashCode(),
            Intent(this, SpeechDismissReceiver::class.java)
                .setAction(SpeechDismissReceiver.ACTION_DISMISS)
                .putExtra(SpeechDismissReceiver.EXTRA_TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun stopAndClear(detach: Boolean) {
        handler.removeCallbacks(timeout)
        val flags = if (detach) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE
        ServiceCompat.stopForeground(this, flags)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackFgsService"
        private const val ACTION_START = "app.clothescast.work.PLAYBACK_START"
        private const val ACTION_FINISH = "app.clothescast.work.PLAYBACK_FINISH"
        private const val EXTRA_PERIOD = "period"
        private const val EXTRA_DETACH = "detach"
        private const val EXTRA_TOKEN = "token"
        private const val REQUEST_OPEN_APP = 105

        // Hard ceiling on how long the service may sit foreground without a
        // finish signal. A normal run is well under two minutes (up to 30s
        // fetch jitter + 60s delivery alignment + synth + speech); five minutes
        // leaves generous headroom for a slow network while still bounding a
        // stranded service if the worker is killed mid-delivery.
        private const val MAX_PLAYBACK_MS = 5 * 60 * 1000L

        /**
         * Promote the process to a media-playback foreground service for a
         * spoken [period] briefing. Call as early in the alarm-driven run as
         * possible so the start lands inside the exact-alarm FGS-start
         * exemption. Best-effort: a refused start is logged and the run
         * continues in the background.
         *
         * Returns true if the start request was accepted. A background
         * `startForegroundService` is rejected *synchronously* with
         * `ForegroundServiceStartNotAllowedException` once the exemption window
         * has lapsed, so a false return is the caller's signal not to enable the
         * transient-notification path that assumes a running service.
         *
         * [token] is the run's per-run identifier (the alarm-fire time): it's
         * baked into the placeholder's dismiss intent so a swipe before speech
         * starts is remembered (see ActiveSpeechSession).
         */
        fun start(context: Context, period: ForecastPeriod, token: Long): Boolean {
            val intent = Intent(context, PlaybackForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PERIOD, period.name)
                .putExtra(EXTRA_TOKEN, token)
            return runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { DiagLog.w(TAG, "Failed to request playback foreground service start.", it) }
                .isSuccess
        }

        /**
         * Tear the service down once delivery is done. [detach] true leaves the
         * forecast notification in the shade (the forecast was posted); false
         * removes it (nothing was delivered — drop the bare placeholder).
         */
        fun finish(context: Context, detach: Boolean) {
            val intent = Intent(context, PlaybackForegroundService::class.java)
                .setAction(ACTION_FINISH)
                .putExtra(EXTRA_DETACH, detach)
            // The service is already running and foreground, so a plain
            // startService to deliver the finish command is allowed even from
            // the background. Guard anyway: if the original start was refused
            // there's no service to signal.
            runCatching { context.startService(intent) }
                .onFailure { DiagLog.w(TAG, "Failed to signal playback foreground service finish.", it) }
        }
    }
}
