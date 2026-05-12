package app.clothescast.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clothescast.diag.DiagLog
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fires when an insight alarm goes off. Two actions arrive on this receiver:
 *
 *  - [ACTION_FIRE]         — the morning ([ForecastPeriod.TODAY]) alarm
 *  - [ACTION_FIRE_TONIGHT] — the evening ([ForecastPeriod.TONIGHT]) alarm
 *
 * For each, the receiver:
 * 1. Enqueues a WorkManager OneTimeWorkRequest ([FetchAndNotifyWorker]) which actually
 *    does the fetch + insight + notification under network/retry constraints.
 * 2. Re-arms the same slot's alarm for the next day. Re-arming here (rather than only
 *    inside the Worker) keeps the alarm chain alive even if the Worker is permanently
 *    failing.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val period = when (intent.action) {
            ACTION_FIRE -> ForecastPeriod.TODAY
            ACTION_FIRE_TONIGHT -> ForecastPeriod.TONIGHT
            else -> return
        }
        // Stamped by DailyAlarmScheduler at schedule time; the alarm-fire timestamp
        // is captured below as `firedAtMs`. The pair lets the worker emit the
        // doze-delay metric via Telemetry.logNotificationDelivery. Defaults to 0
        // when the extra is missing (boot-completed re-arm path, stale alarms
        // scheduled before this code shipped) so downstream code can treat 0 as
        // "no scheduled time known" and skip the delay event.
        val scheduledAtMs = intent.getLongExtra(
            DailyAlarmScheduler.EXTRA_SCHEDULED_AT_MS,
            0L,
        )
        val firedAtMs = System.currentTimeMillis()
        DiagLog.i(TAG, "AlarmReceiver fired (action=${intent.action}, period=$period)")

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            val appCtx = context.applicationContext
            val app = appCtx as ClothesCastApplication
            val scheduler = DailyAlarmScheduler(appCtx)
            try {
                val prefs = app.settingsRepository.preferences.first()
                if (period == ForecastPeriod.TONIGHT && !prefs.tonightEnabled) {
                    // The user disabled tonight after this alarm was already armed.
                    // Cancel any future tonight alarm (belt-and-braces: SettingsViewModel
                    // already cancels on toggle-off, but a stale OS-scheduled alarm or a
                    // failed cancel would otherwise keep us re-arming forever) and skip
                    // the worker enqueue so we don't pay alarm + work overhead daily.
                    DiagLog.i(TAG, "Tonight alarm fired but tonight is disabled; cancelling and not re-arming.")
                    scheduler.cancel(ForecastPeriod.TONIGHT)
                    return@launch
                }
                FetchAndNotifyWorker.enqueueOneShot(
                    appCtx,
                    period = period,
                    alarmScheduledAtMs = scheduledAtMs,
                    alarmFiredAtMs = firedAtMs,
                )
                val schedule = when (period) {
                    ForecastPeriod.TODAY -> prefs.schedule
                    ForecastPeriod.TONIGHT -> prefs.tonightSchedule
                }
                scheduler.schedule(schedule, period)
            } catch (t: Throwable) {
                DiagLog.e(TAG, "Re-arm failed for $period", t)
                // Best-effort: still enqueue the worker even if pref read failed,
                // so the user gets *something* if it's the morning slot.
                if (period == ForecastPeriod.TODAY) {
                    FetchAndNotifyWorker.enqueueOneShot(
                        appCtx,
                        period = period,
                        alarmScheduledAtMs = scheduledAtMs,
                        alarmFiredAtMs = firedAtMs,
                    )
                }
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "app.clothescast.alarm.FIRE"
        const val ACTION_FIRE_TONIGHT = "app.clothescast.alarm.FIRE_TONIGHT"
        private const val TAG = "AlarmReceiver"
    }
}
