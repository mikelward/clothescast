package app.clothescast.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.clothescast.diag.DiagLog
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.playsSpeech
import app.clothescast.core.domain.model.postsNotification
import app.clothescast.core.domain.usecase.isMqttPublishable
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.flow.first

/**
 * Fires when an insight alarm goes off. Two actions arrive on this receiver:
 *
 *  - [ACTION_FIRE]         — the morning ([ForecastPeriod.TODAY]) alarm
 *  - [ACTION_FIRE_TONIGHT] — the evening ([ForecastPeriod.TONIGHT]) alarm
 *
 * The receiver reads preferences once and then routes:
 *  1. Slot disabled → cancel the alarm, do nothing else.
 *  2. SILENT delivery (no notification, no audio, no MQTT, no cast) →
 *     enqueue the worker as a normal background refresh and re-arm. No
 *     foreground service, no "Preparing" notification — the user opted out
 *     of every visible surface and we honor that.
 *  3. Otherwise → enqueue the worker, re-arm, and (best-effort) start
 *     [ScheduledDeliveryService] so the user sees "Preparing your
 *     ClothesCast" → "Delivering your ClothesCast" while the run happens.
 *     If the Service start is refused, the worker's own
 *     [FetchAndNotifyWorker.promoteToPlaybackServiceIfNeeded] covers the
 *     speech window — worst case is the pre-Service behavior, never nothing.
 *
 * See docs/schedule-lifecycle.md for the full state machine.
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
        DiagLog.i(TAG, "AlarmReceiver fired (action=%s, period=%s)", intent.action, period)

        val pending = goAsync()
        ReceiverWork.launch(pending) {
            val appCtx = context.applicationContext
            val app = appCtx as ClothesCastApplication
            val scheduler = DailyAlarmScheduler(appCtx)
            var readPrefs: UserPreferences? = null
            try {
                val prefs = app.settingsRepository.preferences.first()
                readPrefs = prefs
                if (period == ForecastPeriod.TODAY && !prefs.dailyEnabled) {
                    // Mirror of the tonight branch below: the user disabled the
                    // daily ClothesCast after this alarm was already armed.
                    DiagLog.i(TAG, "Daily alarm fired but daily is disabled; cancelling and not re-arming.")
                    scheduler.cancel(ForecastPeriod.TODAY)
                    return@launch
                }
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

                val workId = FetchAndNotifyWorker.enqueueOneShot(
                    appCtx,
                    period = period,
                    alarmScheduledAtMs = scheduledAtMs,
                    alarmFiredAtMs = firedAtMs,
                )

                if (needsForegroundSurface(prefs, period)) {
                    // Best-effort: if the platform refuses (background-restricted,
                    // lapsed exemption window, OEM quirk), the worker still runs
                    // and its own promoteToPlaybackServiceIfNeeded covers the
                    // speech window via the static isHoldingForeground gate.
                    tryStartService(appCtx, period, prefs, workId.toString())
                }

                val schedule = when (period) {
                    ForecastPeriod.TODAY -> prefs.schedule
                    ForecastPeriod.TONIGHT -> prefs.tonightSchedule
                }
                scheduler.schedule(schedule, period)
            } catch (t: Throwable) {
                DiagLog.e(TAG, t, "Alarm handling failed for %s", period)
                // Best-effort: still enqueue the worker even if pref read failed,
                // so the user gets *something* if it's the morning slot.
                if (period == ForecastPeriod.TODAY) {
                    runCatching {
                        FetchAndNotifyWorker.enqueueOneShot(
                            appCtx,
                            period = period,
                            alarmScheduledAtMs = scheduledAtMs,
                            alarmFiredAtMs = firedAtMs,
                        )
                    }.onFailure { DiagLog.e(TAG, it, "Fallback worker enqueue failed for %s", period) }
                }
                // Keep the self-perpetuating alarm chain alive: each fire arms
                // the next, so a single transient failure (a DataStore read
                // hiccup, an AlarmManager refusal) would otherwise silently end
                // every future delivery until the next app open / reboot /
                // update. Use the read prefs' schedule when we got that far,
                // else the slot's default time; if the slot was actually
                // disabled, the next fire reads prefs and cancels itself.
                runCatching {
                    val schedule = when (period) {
                        ForecastPeriod.TODAY -> readPrefs?.schedule ?: Schedule.default()
                        ForecastPeriod.TONIGHT -> readPrefs?.tonightSchedule ?: Schedule.defaultTonight()
                    }
                    scheduler.schedule(schedule, period)
                }.onFailure { DiagLog.e(TAG, it, "Fallback re-arm failed for %s", period) }
            }
        }
    }

    /**
     * True when the period's delivery configuration calls for a visible
     * foreground-service surface during the run. SILENT delivery with no
     * usable smart-home destinations means the user opted out of every
     * visible / audible affordance — posting "Preparing your ClothesCast"
     * would regress that contract.
     *
     * Uses the same publishability rules the delivery pipeline does:
     * [isMqttPublishable] requires both the toggle *and* a non-blank
     * host, and cast requires both the toggle *and* a picked route ID
     * (matches `DeliveryGates.willCast`). A SILENT slot with the MQTT
     * toggle on but no host configured, or cast enabled with no route
     * picked, falls through the same way as plain SILENT.
     */
    private fun needsForegroundSurface(prefs: UserPreferences, period: ForecastPeriod): Boolean {
        val deliveryMode = when (period) {
            ForecastPeriod.TODAY -> prefs.deliveryMode
            ForecastPeriod.TONIGHT -> prefs.tonightDeliveryMode
        }
        val castThisPeriod = when (period) {
            ForecastPeriod.TODAY -> prefs.castMorning
            ForecastPeriod.TONIGHT -> prefs.castTonight
        }
        val willCast = prefs.castEnabled && prefs.castRouteId != null && castThisPeriod
        return deliveryMode.postsNotification ||
            deliveryMode.playsSpeech ||
            isMqttPublishable(prefs) ||
            willCast
    }

    /**
     * Starts the Service with the specific [workId] to watch, and the
     * pre-resolved mode flags. Best-effort: a refusal
     * (`ForegroundServiceStartNotAllowedException` and friends) is logged
     * and the worker-only path covers the delivery.
     */
    private fun tryStartService(
        context: Context,
        period: ForecastPeriod,
        prefs: UserPreferences,
        workId: String,
    ) {
        val deliveryMode = when (period) {
            ForecastPeriod.TODAY -> prefs.deliveryMode
            ForecastPeriod.TONIGHT -> prefs.tonightDeliveryMode
        }
        val castThisPeriod = when (period) {
            ForecastPeriod.TODAY -> prefs.castMorning
            ForecastPeriod.TONIGHT -> prefs.castTonight
        }
        val willCast = prefs.castEnabled && prefs.castRouteId != null && castThisPeriod
        val deliveringWantsForeground =
            deliveryMode.playsSpeech ||
                isMqttPublishable(prefs) ||
                willCast
        val intent = ScheduledDeliveryService.intent(
            context = context,
            period = period,
            workId = workId,
            playsSpeech = deliveryMode.playsSpeech,
            deliveringWantsForeground = deliveringWantsForeground,
        )
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { t ->
                DiagLog.w(TAG, t, "startForegroundService for %s refused; falling back to worker-only path", period)
            }
    }

    companion object {
        const val ACTION_FIRE = "app.clothescast.alarm.FIRE"
        const val ACTION_FIRE_TONIGHT = "app.clothescast.alarm.FIRE_TONIGHT"
        private const val TAG = "AlarmReceiver"
    }
}
