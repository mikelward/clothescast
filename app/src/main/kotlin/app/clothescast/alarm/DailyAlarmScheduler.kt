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
        val triggerAt: Instant = schedule.nextOccurrenceAfter(clock.instant())
        armAt(triggerAt, pendingIntent(context, period, scheduledAtMs = triggerAt.toEpochMilli()), "$period")
    }

    fun cancel(period: ForecastPeriod = ForecastPeriod.TODAY) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        alarmManager.cancel(pendingIntent(context, period))
    }

    /**
     * Arms one of the additional morning casts ([app.clothescast.core.domain.model.MorningScheduleEntry]).
     * Each entry gets its own request code (derived from [entryId]) + receiver
     * action so AlarmManager keeps it independent of the primary morning / tonight
     * alarms and of the other entries. The fired insight is still the morning
     * ([ForecastPeriod.TODAY]) one — only the wall-clock time and days differ.
     */
    fun scheduleMorningExtra(schedule: Schedule, entryId: Int) {
        val triggerAt: Instant = schedule.nextOccurrenceAfter(clock.instant())
        armAt(
            triggerAt,
            morningExtraPendingIntent(context, entryId, scheduledAtMs = triggerAt.toEpochMilli()),
            "morning-extra#$entryId",
        )
    }

    fun cancelMorningExtra(entryId: Int) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        alarmManager.cancel(morningExtraPendingIntent(context, entryId))
    }

    private fun armAt(triggerAt: Instant, pendingIntent: PendingIntent, label: String) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: run {
            DiagLog.w(TAG, "AlarmManager unavailable; alarm not scheduled")
            return
        }
        if (!alarmManager.canScheduleExactAlarms()) {
            // USE_EXACT_ALARM (API 33+) auto-grants this; on the API 31-32 shim path it's
            // also granted by default. If somehow it's denied (user revoked manually),
            // fall back to setAndAllowWhileIdle so the user still gets the notification,
            // just possibly drifted by a few minutes.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pendingIntent)
            DiagLog.w(TAG, "Exact-alarm permission denied; using inexact alarm at $triggerAt for $label")
            return
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pendingIntent)
        DiagLog.i(TAG, "Insight alarm armed for $triggerAt ($label)")
    }

    companion object {
        private const val TAG = "DailyAlarmScheduler"
        private const val ALARM_REQUEST_CODE_TODAY = 0xADA1
        private const val ALARM_REQUEST_CODE_TONIGHT = 0xADA2

        /**
         * Request codes for the additional morning casts are derived from this
         * base plus the entry id (always > 0, so they never collide with
         * [ALARM_REQUEST_CODE_TODAY] / [ALARM_REQUEST_CODE_TONIGHT]). Each entry's
         * code is stable across re-arms so `FLAG_UPDATE_CURRENT` swaps the extras
         * in place rather than stacking duplicate alarms.
         */
        private const val ALARM_REQUEST_CODE_MORNING_EXTRA_BASE = 0xADB0

        /** Entry id of the firing additional morning cast — read by [AlarmReceiver]. */
        const val EXTRA_ENTRY_ID = "app.clothescast.alarm.ENTRY_ID"

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

        internal fun morningExtraPendingIntent(
            context: Context,
            entryId: Int,
            scheduledAtMs: Long = 0L,
        ): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FIRE_MORNING_EXTRA)
                // The action is shared by every extra; the request code keeps the
                // PendingIntents distinct, and the id rides as an extra so the
                // receiver knows which entry fired.
                .setData(android.net.Uri.parse("clothescast://morning-extra/$entryId"))
                .putExtra(EXTRA_ENTRY_ID, entryId)
                .putExtra(EXTRA_SCHEDULED_AT_MS, scheduledAtMs)
            return PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE_MORNING_EXTRA_BASE + entryId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
