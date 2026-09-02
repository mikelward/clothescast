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
import java.time.temporal.ChronoUnit

/**
 * Arms the widget-only refresh chain while at least one ClothesCast widget is
 * placed. It has two self-re-arming inexact alarms — see [WidgetRefreshKind]
 * for what each fire does — and they are deliberately **independent slots**,
 * each with its own request code, each re-armed only by its own fire.
 *
 * **Boundary alarm.** Fires at the next schedule boundary (the morning or the
 * tonight wall-clock time, whichever comes first, every day), and enqueues a
 * silent cache refresh for the window that just opened. It exists because the
 * delivery alarms are gated on the enable toggles (and on each schedule's
 * day-of-week set): with a slot disabled — or on a day outside its day set —
 * nothing else ever fetches in the background, so a placed widget would cross
 * a window boundary with a stale cache and sit on its empty state until the
 * user happened to open the app. The enable toggles gate scheduled *delivery*,
 * not refresh; this is the refresh half of that contract for the launcher.
 *
 * **Hourly alarm.** Fires at the top of every hour and repaints. It exists
 * because everything a widget draws that is anchored to *now* — the feels-like
 * chart's current-time line and its "64°F at 07:00" readout, the conditions
 * strip — is computed at render time and then frozen into a bitmap. Between
 * boundaries nothing re-rendered, so a widget placed with a 07:00 snapshot
 * still read 07:00 at half past two. The repaint moves the now-line, and a
 * fetch alongside it (only once the snapshot has aged past
 * `SILENT_REFRESH_MIN_AGE`) keeps the numbers themselves current rather than
 * drawing a morning forecast all afternoon.
 *
 * Unlike [DailyAlarmScheduler] neither uses an exact alarm: nobody is waiting
 * to *hear* the result, so batching drift is fine and cheaper than burning the
 * exact-alarm budget. They differ in wakeup, and that difference is exactly why
 * they cannot share one slot:
 *
 *  - The boundary alarm is `setAndAllowWhileIdle(RTC_WAKEUP)` — it may be the
 *    only thing that refreshes the cache for the window it opens, so it is
 *    worth waking for.
 *  - The hourly alarm is a plain non-wakeup `set(RTC)`. It never wakes a
 *    sleeping device: it lands the next time the device is up anyway, which is
 *    approximately "the next time someone could be looking at the launcher",
 *    and overnight the ticks collapse into Doze's maintenance windows instead
 *    of firing eight times at a screen nobody is watching.
 *
 * **Why two slots, not one.** A single slot armed for whichever tick came
 * first would let a *late* hourly tick swallow a boundary: with the device
 * asleep, the 06:00 non-wakeup tick is deferred to whenever the device next
 * wakes, and if that is after 07:00 its re-arm computes the *next* boundary
 * (19:00) — so the morning refresh never fires at all, and the first morning
 * glance finds the previous window's snapshot and blanks to the empty state.
 * That is the exact failure the boundary alarm was added to prevent, so the
 * hourly cadence must not be able to defer it. Independent slots mean the
 * boundary alarm's wakeup guarantee is unchanged by anything the hourly one
 * does.
 */
class WidgetRefreshScheduler(
    private val context: Context,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Arms (or re-arms) both slots. The boundary slot is left alone only while
     * one is genuinely in flight — see [boundaryAlarmPending], which is the
     * whole gate, for every caller.
     */
    fun schedule(morning: Schedule, tonight: Schedule) {
        scheduleTick(WidgetRefreshKind.HOURLY, morning, tonight)
        if (boundaryAlarmPending(clock.instant(), morning.time, tonight.time, morning.zoneId)) {
            DiagLog.i(TAG, "A boundary alarm is already pending; leaving it as armed.")
        } else {
            scheduleTick(WidgetRefreshKind.BOUNDARY, morning, tonight)
        }
    }

    /**
     * Whether a boundary alarm this scheduler armed is **in flight** — its
     * trigger has arrived and it has not been delivered yet. The hazard being
     * avoided is narrow: arming a matching `PendingIntent` cancels the pending
     * one, and a re-arm computes the next boundary strictly after now, so a
     * reconcile landing between a boundary's trigger and its delivery would
     * drop that window's fetch.
     *
     * Three things have to hold, and each closes a way the record can outlive
     * the alarm it names:
     *
     *  - **The alarm still exists.** `FLAG_NO_CREATE` finds nothing once
     *    Android has dropped our `PendingIntent`, which is what a force-stop,
     *    a reboot and a package replacement all do while leaving this record
     *    behind. Without asking, a record naming a trigger already in the past
     *    would read as live and the slot would go unarmed until the *next*
     *    boundary — half a day, on the default schedules.
     *  - **The trigger has passed.** One still in the future is never
     *    protected: re-arming it computes the same instant and replaces it
     *    with an identical alarm, so there is nothing to lose by arming.
     *  - **Its successor has not.** Delivery is what ends the in-flight
     *    window, and a fire records its own re-arm, so a record still naming a
     *    past trigger means the fire has not run. `setAndAllowWhileIdle` has
     *    no maximum delay — Doze can hold one well past any figure worth
     *    writing down — so expiring the record after a fixed grace would
     *    cancel an overdue but still-live alarm and skip that window. Once the
     *    successor is due the old one is moot either way: it has been
     *    delivered, or it is never coming and the slot needs arming for the
     *    window that just opened.
     *
     * Together those make one gate that suits every caller, including the ones
     * that arrive *because* something changed. Boot, a package replacement and
     * a clock change all fail one of the first two tests, so they arm. A
     * schedule edit measures the successor against the times the user just
     * chose; an alarm genuinely mid-flight when they edit is left to fire and
     * re-arm itself against the new schedule, rather than being canceled in
     * favor of a boundary hours away.
     */
    private fun boundaryAlarmPending(
        now: Instant,
        morningTime: LocalTime,
        tonightTime: LocalTime,
        zone: ZoneId,
    ): Boolean {
        val armedAtMs = armRecord(context).getLong(KEY_BOUNDARY_ARMED_AT, 0L)
        if (armedAtMs <= 0L) return false
        if (existingPendingIntent(context, WidgetRefreshKind.BOUNDARY) == null) return false
        val armedAt = Instant.ofEpochMilli(armedAtMs)
        if (armedAt.isAfter(now)) return false
        return nextWidgetRefreshAfter(armedAt, morningTime, tonightTime, zone).isAfter(now)
    }

    /**
     * Arms (or re-arms) one slot for its next occurrence after now. Boundary
     * times come from the two schedules; day-of-week sets are deliberately
     * ignored — the widget's current window flips at these times every day,
     * whether or not a cast is scheduled to go out.
     *
     * A fire re-arms **only its own kind**: re-arming the other slot from here
     * would cancel-and-replace an alarm that may be due at this very instant
     * and not yet delivered (both ticks coincide whenever a boundary sits on
     * the hour, which the default 07:00 / 19:00 schedules do), silently
     * dropping that fire.
     */
    fun scheduleTick(kind: WidgetRefreshKind, morning: Schedule, tonight: Schedule) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: run {
            DiagLog.w(TAG, "AlarmManager unavailable; widget-refresh alarm not scheduled")
            return
        }
        val zone = morning.zoneId
        val now = clock.instant()
        val operation = pendingIntent(context, kind)
        val triggerAt = when (kind) {
            WidgetRefreshKind.BOUNDARY -> nextWidgetRefreshAfter(now, morning.time, tonight.time, zone)
            WidgetRefreshKind.HOURLY -> nextHourlyWidgetRefreshAfter(now, zone)
        }
        when (kind) {
            WidgetRefreshKind.BOUNDARY -> alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt.toEpochMilli(),
                operation,
            )
            WidgetRefreshKind.HOURLY -> alarmManager.set(
                AlarmManager.RTC,
                triggerAt.toEpochMilli(),
                operation,
            )
        }
        if (kind == WidgetRefreshKind.BOUNDARY) {
            // What [boundaryAlarmPending] reads. Written after the arm, so a
            // throwing AlarmManager leaves no record claiming an alarm exists.
            armRecord(context).edit().putLong(KEY_BOUNDARY_ARMED_AT, triggerAt.toEpochMilli()).apply()
        }
        DiagLog.i(TAG, "Widget-refresh alarm armed for %s (%s)", triggerAt, kind)
    }

    /** Ends the chain: both slots, so no tick survives to re-arm the other. */
    fun cancel() {
        // Clear the record first: it must never outlive the alarm it describes,
        // or a later reconcile would read a canceled alarm as pending and
        // decline to arm one.
        armRecord(context).edit().remove(KEY_BOUNDARY_ARMED_AT).apply()
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        for (kind in WidgetRefreshKind.entries) alarmManager.cancel(pendingIntent(context, kind))
    }

    companion object {
        private const val TAG = "WidgetRefreshScheduler"
        private const val ARM_RECORD_PREFS = "widget_refresh_chain"
        private const val KEY_BOUNDARY_ARMED_AT = "boundary_armed_at_ms"

        /**
         * Where the trigger time of the boundary alarm we armed is recorded.
         * Deliberately its own tiny SharedPreferences file rather than the
         * DataStore-backed settings: this is alarm bookkeeping, not a user
         * preference, and it is read on the reconcile path where a suspending
         * read would not fit.
         */
        private fun armRecord(context: Context) =
            context.getSharedPreferences(ARM_RECORD_PREFS, Context.MODE_PRIVATE)

        /**
         * One request code per slot, so the two alarms never replace each
         * other. `0xADA3` stays the boundary's — it is the code every already
         * -armed alarm in the field carries, and keeping it means an upgrade
         * re-arms that alarm rather than orphaning it beside a new one.
         */
        private fun requestCode(kind: WidgetRefreshKind): Int = when (kind) {
            WidgetRefreshKind.BOUNDARY -> 0xADA3
            WidgetRefreshKind.HOURLY -> 0xADA4
        }

        /**
         * [WidgetRefreshKind.BOUNDARY] is the default because it is also the
         * receiver's: an alarm armed before this file grew a second slot
         * carries no kind extra, and a fire that arrives without one should do
         * the more thorough of the two jobs rather than the cheaper one.
         */
        internal fun pendingIntent(
            context: Context,
            kind: WidgetRefreshKind = WidgetRefreshKind.BOUNDARY,
        ): PendingIntent = buildPendingIntent(context, kind, PendingIntent.FLAG_UPDATE_CURRENT)!!

        /**
         * The `PendingIntent` of an already-armed alarm, or null if Android
         * has dropped it — the only way to ask, and what
         * [boundaryAlarmPending] reads. `FLAG_NO_CREATE` matches on request
         * code, component and action but *not* extras, so it finds an alarm
         * armed before this file grew a kind extra as readily as a new one.
         */
        private fun existingPendingIntent(context: Context, kind: WidgetRefreshKind): PendingIntent? =
            buildPendingIntent(context, kind, PendingIntent.FLAG_NO_CREATE)

        private fun buildPendingIntent(
            context: Context,
            kind: WidgetRefreshKind,
            flags: Int,
        ): PendingIntent? {
            val intent = Intent(context, WidgetRefreshReceiver::class.java)
                .setAction(WidgetRefreshReceiver.ACTION_FIRE)
                .putExtra(WidgetRefreshReceiver.EXTRA_KIND, kind.name)
            return PendingIntent.getBroadcast(
                context,
                requestCode(kind),
                intent,
                PendingIntent.FLAG_IMMUTABLE or flags,
            )
        }
    }
}

/** What a widget-refresh fire should do — see [WidgetRefreshReceiver]. */
enum class WidgetRefreshKind {
    /**
     * A schedule boundary: the window the widgets draw has just flipped, so the
     * cache needs the *new* window fetched (unless an armed delivery alarm is
     * already covering this boundary).
     */
    BOUNDARY,

    /**
     * The top of an hour: repaint so every "now"-anchored element moves on, and
     * refetch only if the cached snapshot has aged out.
     */
    HOURLY,
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
 * The next top of the hour strictly after [now], in [zone]. Truncating the
 * *zoned* local time rather than the instant is what keeps a zone at a half- or
 * quarter-hour offset (Asia/Kathmandu, Australia/Adelaide) ticking on the hour
 * the user reads on the clock instead of at :15 or :30 past it.
 */
internal fun nextHourlyWidgetRefreshAfter(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant()

/**
 * One entry point for keeping the chain consistent with reality: armed while
 * any ClothesCast widget is placed, canceled otherwise. Called from every
 * widget render (the one signal that a widget actually exists — this is what
 * starts the chain when the first widget is placed), from app start, from
 * [ScheduleRefreshReceiver] (boot / app update / clock changes wipe alarms),
 * and from `ClothesCastApplication`'s schedule-time observer (an edit to
 * either boundary time re-arms the chain immediately instead of leaving the
 * old boundary armed until its next fire). The chain's own fires also stop
 * re-arming once no widgets remain.
 *
 * Arming both slots is also the recovery path: a fire only re-arms itself, so
 * this is what restores a slot that was somehow lost. Callers that arrive
 * *because* the alarms are gone or the boundary times changed need nothing
 * extra — [WidgetRefreshScheduler.schedule]'s gate asks whether an alarm is
 * really in flight rather than trusting the arm record, so a boot that
 * outlived the record, or a schedule edit that invalidated it, arms.
 *
 * Re-arming is idempotent: tick times are absolute, so repeated calls collapse
 * onto the same triggers rather than stacking alarms.
 */
internal fun reconcileWidgetRefreshChain(context: Context, prefs: UserPreferences) {
    val scheduler = WidgetRefreshScheduler(context)
    if (hasPlacedClothesCastWidgets(context)) {
        scheduler.schedule(prefs.schedule, prefs.tonightSchedule)
    } else {
        scheduler.cancel()
    }
}
