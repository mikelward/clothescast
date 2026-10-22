package app.clothescast.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.clothescast.ClothesCastApplication
import app.clothescast.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Runs the weather alerts check (docs/ALERTS.md → Cadence). The periodic run
 * wakes hourly and checks only when the adaptive cadence says it's due — every
 * 3 hours, hourly while the forecast looks severe or an alert is active — so a
 * quiet hour costs no network. The on-screen run follows the forecast's own
 * 1-hour rule when the app opens. The start-time run only settles the shade
 * ([WeatherAlertShade]) when a stored alert begins.
 */
class WeatherAlertsWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ClothesCastApplication
        if (inputData.getBoolean(KEY_SHADE_ONLY, false)) {
            return try {
                app.weatherAlertShade.reconcile()
                Result.success()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // The next check or app open settles it anyway.
                DiagLog.w(TAG, e, "Weather alert notifications at an alert's start failed.")
                Result.success()
            }
        }
        val trigger = if (inputData.getBoolean(KEY_ON_SCREEN, false)) {
            WeatherAlertsRefresher.Trigger.ON_SCREEN
        } else {
            WeatherAlertsRefresher.Trigger.BACKGROUND
        }
        return try {
            val prefs = app.settingsRepository.preferences.first()
            if (!prefs.weatherAlerts) {
                app.weatherAlertsRefresher.turnedOff()
                WeatherAlertsScheduler.cancelPeriodic(applicationContext)
                return Result.success()
            }
            // The forecast's place and hours, so alerts always describe the
            // place on screen (docs/ALERTS.md → Location).
            val snapshot = app.insightCache.thisPeriod.first()
            app.weatherAlertsRefresher.refreshIfDue(
                prefs = prefs,
                location = snapshot?.location ?: prefs.location,
                forecast = WeatherAlertsRefresher.hoursAhead(snapshot),
                trigger = trigger,
            )
            Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // The next periodic run tries again; a retry storm buys nothing.
            DiagLog.w(TAG, e, "Weather alerts check failed (%s).", trigger)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "WeatherAlertsWorker"
        internal const val KEY_ON_SCREEN = "on_screen"
        internal const val KEY_SHADE_ONLY = "shade_only"
    }
}

/** Schedules [WeatherAlertsWorker] while alerts are on. */
object WeatherAlertsScheduler {
    private const val PERIODIC_WORK = "weather_alerts_periodic"
    private const val ON_SCREEN_WORK = "weather_alerts_on_screen"
    private const val START_WORK = "weather_alerts_start"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Starts or stops the hourly background check (and stops any in flight) to match [enabled]. */
    fun sync(context: Context, enabled: Boolean) {
        if (!enabled) {
            cancelPeriodic(context)
            return
        }
        val request = PeriodicWorkRequestBuilder<WeatherAlertsWorker>(1, TimeUnit.HOURS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Stops both the hourly check and any on-screen check still in flight. */
    fun cancelPeriodic(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_WORK)
        workManager.cancelUniqueWork(ON_SCREEN_WORK)
    }

    /**
     * Settles the shade at [at], when a stored alert starts, so its
     * notification posts then rather than when it was issued; null cancels.
     * WorkManager may run it a few minutes late; it needs no network.
     */
    fun wakeAt(context: Context, at: Instant?, now: Instant = Instant.now()) {
        val workManager = WorkManager.getInstance(context)
        if (at == null) {
            workManager.cancelUniqueWork(START_WORK)
            return
        }
        val request = OneTimeWorkRequestBuilder<WeatherAlertsWorker>()
            .setInitialDelay(Duration.between(now, at).coerceAtLeast(Duration.ZERO))
            .setInputData(workDataOf(WeatherAlertsWorker.KEY_SHADE_ONLY to true))
            .build()
        workManager.enqueueUniqueWork(START_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * A check under the on-screen rule (the app opened, alerts just turned on).
     * The worker itself returns early when alerts are off or the last check is
     * under an hour old, so calling this freely costs no network.
     */
    fun refreshOnScreen(context: Context) {
        val request = OneTimeWorkRequestBuilder<WeatherAlertsWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(WeatherAlertsWorker.KEY_ON_SCREEN to true))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ON_SCREEN_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}
