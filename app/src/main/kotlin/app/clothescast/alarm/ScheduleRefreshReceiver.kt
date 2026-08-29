package app.clothescast.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clothescast.ClothesCastApplication
import app.clothescast.diag.DiagLog
import app.clothescast.core.domain.model.ForecastPeriod
import kotlinx.coroutines.flow.first

/**
 * Re-arms both the morning and the tonight insight alarms whenever the wall-clock
 * context changes:
 *  - device boot (alarms are wiped)
 *  - app update (Android also wipes alarms)
 *  - timezone change (the user travelled across zones)
 *  - clock change (a manual or carrier correction moves the wall clock, so the
 *    armed RTC instant no longer matches the scheduled wall-clock time)
 *  - locale change (the next insight should be generated in the new language)
 *
 * The schedules' `zoneId` is re-resolved from `ZoneId.systemDefault()` at read time, so
 * we just need to recompute "next 7am" / "next 7pm" with the now-current zone and rearm.
 */
class ScheduleRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DiagLog.i(TAG, "ScheduleRefreshReceiver action=${intent.action}")

        val pending = goAsync()
        ReceiverWork.launch(pending) {
            try {
                val app = context.applicationContext as ClothesCastApplication
                val prefs = app.settingsRepository.preferences.first()
                val scheduler = DailyAlarmScheduler(context.applicationContext)
                if (prefs.dailyEnabled) {
                    scheduler.schedule(prefs.schedule, ForecastPeriod.TODAY)
                } else {
                    scheduler.cancel(ForecastPeriod.TODAY)
                }
                if (prefs.tonightEnabled) {
                    scheduler.schedule(prefs.tonightSchedule, ForecastPeriod.TONIGHT)
                } else {
                    scheduler.cancel(ForecastPeriod.TONIGHT)
                }
                // Boot / update / clock changes wipe the widget-only refresh
                // chain too; re-arm it while any widget is placed so widgets
                // keep refreshing even with both delivery slots disabled.
                reconcileWidgetRefreshChain(context.applicationContext, prefs)
            } catch (t: Throwable) {
                DiagLog.e(TAG, "Re-arm failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "ScheduleRefreshReceiver"
    }
}
