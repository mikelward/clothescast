package app.clothescast.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import app.clothescast.diag.DiagLog
import app.clothescast.widget.hasPlacedClothesCastWidgets
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

/**
 * Fires when the widget-only refresh alarm goes off (see
 * [WidgetRefreshScheduler]). At each schedule boundary while any ClothesCast
 * widget is placed:
 *
 *  1. No widgets left → do nothing and don't re-arm; the chain ends here.
 *  2. The boundary's delivery alarm would fire anyway (slot enabled and today
 *     in its day set) → skip the enqueue — the delivery run refreshes the
 *     cache and repaints the widgets itself — but still re-arm.
 *  3. Otherwise → enqueue a silent refresh (fetch + cache + widget repaint,
 *     no notification / TTS / MQTT / cast) and re-arm for the next boundary.
 *
 * The silent run derives its period from wall-clock time inside the worker
 * ([FetchAndNotifyWorker.currentPeriodForSchedule]), which at a boundary fire
 * is exactly the window that just opened.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        DiagLog.i(TAG, "WidgetRefreshReceiver fired")

        val pending = goAsync()
        ReceiverWork.launch(pending) {
            val appCtx = context.applicationContext
            try {
                if (!hasPlacedClothesCastWidgets(appCtx)) {
                    DiagLog.i(TAG, "No ClothesCast widgets placed; ending the widget-refresh chain.")
                    return@launch
                }
                val app = appCtx as ClothesCastApplication
                val prefs = app.settingsRepository.preferences.first()
                if (deliveryCoversWidgetRefresh(
                        dailyEnabled = prefs.dailyEnabled,
                        tonightEnabled = prefs.tonightEnabled,
                        morning = prefs.schedule,
                        tonight = prefs.tonightSchedule,
                        now = LocalDateTime.now(),
                    )
                ) {
                    DiagLog.i(TAG, "Delivery alarm covers this boundary; skipping the widget-only refresh.")
                } else {
                    FetchAndNotifyWorker.enqueueSilentRefresh(appCtx, alarmFiredAtMs = System.currentTimeMillis())
                }
                WidgetRefreshScheduler(appCtx).schedule(prefs.schedule, prefs.tonightSchedule)
            } catch (t: Throwable) {
                DiagLog.e(TAG, t, "Widget-refresh fire failed")
                // Widgets are (probably) still placed, so a one-off failure —
                // typically the prefs read — shouldn't end the chain: refresh
                // and re-arm on the default schedule times so the next fire
                // gets another chance to read the real ones.
                runCatching {
                    FetchAndNotifyWorker.enqueueSilentRefresh(appCtx, alarmFiredAtMs = System.currentTimeMillis())
                    WidgetRefreshScheduler(appCtx).schedule(Schedule.default(), Schedule.defaultTonight())
                }.onFailure { DiagLog.e(TAG, it, "Widget-refresh fallback re-arm failed") }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "app.clothescast.alarm.WIDGET_REFRESH"
        private const val TAG = "WidgetRefreshReceiver"
    }
}

/**
 * True when the window boundary being crossed at [now] is already covered by
 * an armed delivery alarm — the slot is enabled *and* today is in its
 * day-of-week set — so the widget chain should defer to the delivery run
 * rather than double-fetching alongside it. Pure and time-injected for tests.
 */
internal fun deliveryCoversWidgetRefresh(
    dailyEnabled: Boolean,
    tonightEnabled: Boolean,
    morning: Schedule,
    tonight: Schedule,
    now: LocalDateTime,
): Boolean = when (
    FetchAndNotifyWorker.currentPeriodForSchedule(morning.time, tonight.time, now.toLocalTime())
) {
    ForecastPeriod.TODAY -> dailyEnabled && now.dayOfWeek in morning.days
    ForecastPeriod.TONIGHT -> tonightEnabled && now.dayOfWeek in tonight.days
}
