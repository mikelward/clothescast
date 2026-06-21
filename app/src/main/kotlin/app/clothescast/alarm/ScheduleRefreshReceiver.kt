package app.clothescast.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clothescast.ClothesCastApplication
import app.clothescast.diag.DiagLog
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-arms both the morning and the tonight insight alarms whenever the wall-clock
 * context changes:
 *  - device boot (alarms are wiped)
 *  - app update (Android also wipes alarms)
 *  - timezone change (the user travelled across zones)
 *  - locale change (the next insight should be generated in the new language)
 *
 * The schedules' `zoneId` is re-resolved from `ZoneId.systemDefault()` at read time, so
 * we just need to recompute "next 7am" / "next 7pm" with the now-current zone and rearm.
 */
class ScheduleRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DiagLog.i(TAG, "ScheduleRefreshReceiver action=${intent.action}")

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val app = context.applicationContext as ClothesCastApplication
                val prefs = app.settingsRepository.preferences.first()
                val scheduler = DailyAlarmScheduler(context.applicationContext)
                if (prefs.dailyEnabled) {
                    scheduler.schedule(prefs.schedule, ForecastPeriod.TODAY)
                    // Additional morning casts ride the same master switch.
                    prefs.additionalMorningSchedules.forEach { entry ->
                        scheduler.scheduleMorningExtra(
                            Schedule(entry.time, entry.days, prefs.schedule.zoneId),
                            entry.id,
                        )
                    }
                } else {
                    scheduler.cancel(ForecastPeriod.TODAY)
                    prefs.additionalMorningSchedules.forEach { scheduler.cancelMorningExtra(it.id) }
                }
                if (prefs.tonightEnabled) {
                    scheduler.schedule(prefs.tonightSchedule, ForecastPeriod.TONIGHT)
                } else {
                    scheduler.cancel(ForecastPeriod.TONIGHT)
                }
            } catch (t: Throwable) {
                DiagLog.e(TAG, "Re-arm failed", t)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        private const val TAG = "ScheduleRefreshReceiver"
    }
}
