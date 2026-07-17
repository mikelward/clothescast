package app.clothescast.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.diag.DiagLog
import app.clothescast.widget.hasPlacedClothesCastWidgets
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Arms the widget-only refresh chain: a single self-re-arming alarm that fires
 * at the next schedule boundary (the morning or the tonight wall-clock time,
 * whichever comes first, every day) while at least one ClothesCast widget is
 * placed. [WidgetRefreshReceiver] handles the fire — it enqueues a *silent*
 * cache refresh (no notification / TTS / MQTT / cast) and re-arms.
 *
 * This chain exists because the delivery alarms are gated on the enable
 * toggles (and on each schedule's day-of-week set): with a slot disabled — or
 * on a day outside its day set — nothing else ever fetches in the background,
 * so a placed widget would cross a window boundary with a stale cache and sit
 * on its empty state until the user happened to open the app. The enable
 * toggles gate scheduled *delivery*, not refresh; this chain is the refresh
 * half of that contract for the launcher surface.
 *
 * Unlike [DailyAlarmScheduler] this uses `setAndAllowWhileIdle` (inexact but
 * doze-tolerant) rather than an exact alarm: nobody is waiting to *hear* the
 * result, so a few minutes of batching drift is fine and cheaper than burning
 * the exact-alarm budget twice a day.
 */
class WidgetRefreshScheduler(
    private val context: Context,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Arms (or re-arms) the chain for the next boundary after now. Boundary
     * times come from the two schedules; day-of-week sets are deliberately
     * ignored — the widget's current window flips at these times every day,
     * whether or not a cast is scheduled to go out.
     */
    fun schedule(morning: Schedule, tonight: Schedule) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: run {
            DiagLog.w(TAG, "AlarmManager unavailable; widget-refresh alarm not scheduled")
            return
        }
        val triggerAt = nextWidgetRefreshAfter(clock.instant(), morning.time, tonight.time, morning.zoneId)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt.toEpochMilli(),
            pendingIntent(context),
        )
        DiagLog.i(TAG, "Widget-refresh alarm armed for $triggerAt")
    }

    fun cancel() {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        alarmManager.cancel(pendingIntent(context))
    }

    companion object {
        private const val TAG = "WidgetRefreshScheduler"
        private const val REQUEST_CODE = 0xADA3

        internal fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WidgetRefreshReceiver::class.java)
                .setAction(WidgetRefreshReceiver.ACTION_FIRE)
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

/**
 * The next instant strictly after [now] at which either boundary time occurs
 * in [zone] — today's remaining boundary if one is still ahead, else the
 * earliest one tomorrow. Mirrors [Schedule.nextOccurrenceAfter] but daily
 * (no day-of-week filter) and across both times at once.
 */
internal fun nextWidgetRefreshAfter(
    now: Instant,
    morningTime: LocalTime,
    tonightTime: LocalTime,
    zone: ZoneId,
): Instant {
    val zoned = now.atZone(zone)
    return sequence {
        for (offset in 0L..1L) {
            yield(zoned.plusDays(offset).with(morningTime).toInstant())
            yield(zoned.plusDays(offset).with(tonightTime).toInstant())
        }
    }.filter { it.isAfter(now) }.min()
}

/**
 * One entry point for keeping the chain consistent with reality: armed while
 * any ClothesCast widget is placed, cancelled otherwise. Called from every
 * widget render (the one signal that a widget actually exists — this is what
 * starts the chain when the first widget is placed), from app start, from
 * [ScheduleRefreshReceiver] (boot / app update / clock changes wipe alarms),
 * and from `ClothesCastApplication`'s schedule-time observer (an edit to
 * either boundary time re-arms the chain immediately instead of leaving the
 * old boundary armed until its next fire). The chain's own fire also stops
 * re-arming once no widgets remain.
 */
internal fun reconcileWidgetRefreshChain(context: Context, prefs: UserPreferences) {
    val scheduler = WidgetRefreshScheduler(context)
    if (hasPlacedClothesCastWidgets(context)) {
        scheduler.schedule(prefs.schedule, prefs.tonightSchedule)
    } else {
        scheduler.cancel()
    }
}
