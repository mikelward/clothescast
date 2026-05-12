package app.clothescast.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.clothescast.diag.DiagLog
import androidx.core.content.getSystemService
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import java.time.Clock
import java.time.Instant

/**
 * Schedules an exact wake-up at the next occurrence of [Schedule]. Wraps AlarmManager
 * so the receiver code stays small and so we can stub the manager in tests.
 *
 * Two slots are supported via [ForecastPeriod]: the morning ([ForecastPeriod.TODAY])
 * insight at the user's wake-up time, and the evening ([ForecastPeriod.TONIGHT])
 * insight at the user's evening time. Each slot has its own request code + receiver
 * action so AlarmManager keeps them independent and the receiver can route by action.
 *
 * Design choices:
 * - `setExactAndAllowWhileIdle` is the only primitive that fires at a precise wall-clock
 *   time even in Doze. The plan calls for it.
 * - Permission is gated by `USE_EXACT_ALARM` (API 33+, no user prompt) and
 *   `SCHEDULE_EXACT_ALARM` (API 31-32, granted by default). The fallback to
 *   `setAndAllowWhileIdle` covers a manual revoke at runtime.
 * - `Schedule.zoneId` is intentionally re-resolved by the repository on each read so DST
 *   and travel changes pick up automatically. This scheduler does not cache the zone.
 */
class DailyAlarmScheduler(
    private val context: Context,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun schedule(schedule: Schedule, period: ForecastPeriod = ForecastPeriod.TODAY) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: run {
            DiagLog.w(TAG, "AlarmManager unavailable; alarm not scheduled")
            return
        }

        val triggerAt: Instant = schedule.nextOccurrenceAfter(clock.instant())
        val pendingIntent = pendingIntent(context, period, scheduledAtMs = triggerAt.toEpochMilli())

        if (!alarmManager.canScheduleExactAlarms()) {
            // USE_EXACT_ALARM (API 33+) auto-grants this; on the API 31-32 shim path it's
            // also granted by default. If somehow it's denied (user revoked manually),
            // fall back to setAndAllowWhileIdle so the user still gets the notification,
            // just possibly drifted by a few minutes.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pendingIntent)
            DiagLog.w(TAG, "Exact-alarm permission denied; using inexact alarm at $triggerAt for $period")
            return
        }

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pendingIntent)
        DiagLog.i(TAG, "Insight alarm armed for $triggerAt ($period)")
    }

    fun cancel(period: ForecastPeriod = ForecastPeriod.TODAY) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        alarmManager.cancel(pendingIntent(context, period))
    }

    companion object {
        private const val TAG = "DailyAlarmScheduler"
        private const val ALARM_REQUEST_CODE_TODAY = 0xADA1
        private const val ALARM_REQUEST_CODE_TONIGHT = 0xADA2

        /**
         * Wall-clock millis of the moment we asked AlarmManager to fire. Read by
         * [AlarmReceiver] to compute the doze-induced delivery delay and pass it
         * along to the worker. FLAG_UPDATE_CURRENT swaps the extras on each
         * re-arm so the value the receiver reads is always the most recently
         * scheduled trigger time.
         */
        const val EXTRA_SCHEDULED_AT_MS = "app.clothescast.alarm.SCHEDULED_AT_MS"

        // The cancel() path doesn't care about the timestamp — it just needs a
        // PendingIntent that equals() the one previously handed to AlarmManager.
        // PendingIntent equality ignores extras (matches action + component +
        // requestCode), so calling cancel without a real schedule time is fine.
        internal fun pendingIntent(
            context: Context,
            period: ForecastPeriod,
            scheduledAtMs: Long = 0L,
        ): PendingIntent {
            val action = when (period) {
                ForecastPeriod.TODAY -> AlarmReceiver.ACTION_FIRE
                ForecastPeriod.TONIGHT -> AlarmReceiver.ACTION_FIRE_TONIGHT
            }
            val requestCode = when (period) {
                ForecastPeriod.TODAY -> ALARM_REQUEST_CODE_TODAY
                ForecastPeriod.TONIGHT -> ALARM_REQUEST_CODE_TONIGHT
            }
            val intent = Intent(context, AlarmReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_SCHEDULED_AT_MS, scheduledAtMs)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
