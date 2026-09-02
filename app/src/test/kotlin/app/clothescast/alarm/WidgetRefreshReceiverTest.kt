package app.clothescast.alarm

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import app.clothescast.widget.OutfitWidgetReceiver
import app.clothescast.work.FetchAndNotifyWorker
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Receiver coverage for the widget-only refresh chain:
 *  - no widgets placed → the fire cancels both slots and the arm record;
 *  - widgets placed with both delivery slots disabled → a silent refresh is
 *    enqueued and the chain re-arms;
 *  - widgets placed with delivery covering the boundary → no double-fetch,
 *    but the chain still re-arms;
 *  - an hourly tick repaints, refetches a stale cache, and re-arms;
 *  - the two slots are armed independently, at their own times and with the
 *    alarm type each is supposed to have (a boundary wakes the device, an
 *    hourly tick rides its wakefulness);
 *  - an hourly fire re-arms only itself, so a late one can't carry the
 *    boundary past its time;
 *  - the in-flight gate tells a merely-overdue boundary apart from one a
 *    force-stop removed, and survives a schedule edit made across it.
 *
 * The tick-choice and day-set decision logic itself is pure and covered by
 * [WidgetRefreshSchedulerTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WidgetRefreshReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as ClothesCastApplication
    private val alarmManager: AlarmManager = context.getSystemService()!!
    private val workManager: WorkManager by lazy {
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(
                context,
                Configuration.Builder()
                    .setMinimumLoggingLevel(Log.WARN)
                    .setExecutor(Executors.newSingleThreadExecutor())
                    .setTaskExecutor(Executors.newSingleThreadExecutor())
                    .build(),
            )
        }
        WorkManager.getInstance(context)
    }

    @Before
    fun resetState() {
        // ClothesCastApplication.onCreate reconciles the delivery alarms and
        // the widget chain on a background coroutine; with default prefs (both
        // slots off) and no widgets bound yet, that pass *cancels* all three.
        // Join it so a late cancel can't wipe the alarm the receiver under test
        // arms, then clear all three slots (same pattern as
        // ScheduleRefreshReceiverTest).
        awaitInitialScheduling()
        // Both widget slots: they are separate PendingIntents, so canceling
        // only the boundary one would leave an hourly alarm behind and the
        // "chain ended" assertion below could never be empty.
        (
            listOf(
                DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TODAY),
                DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TONIGHT),
            ) + WidgetRefreshKind.entries.map { WidgetRefreshScheduler.pendingIntent(context, it) }
            ).forEach { alarmManager.cancel(it) }

        runBlocking {
            app.settingsRepository.setDailyEnabled(false)
            app.settingsRepository.setTonightEnabled(false)
            // The hourly-fire test moves the boundary times to straddle the
            // real clock, and the receiver reads them back from prefs — so
            // restore the defaults here or a later test's outcome depends on
            // which order the class happened to run in.
            Schedule.default().let { app.settingsRepository.setSchedule(it.time, it.days) }
            Schedule.defaultTonight().let { app.settingsRepository.setTonightSchedule(it.time, it.days) }
        }
        // WorkManager's static instance (and its DB) survives across test
        // methods in this class, so a previous test's silent-refresh record
        // would still satisfy getWorkInfosForUniqueWork here. Cancel leaves a
        // CANCELLED record behind — prune finished work too so each test
        // starts from an empty queue and "isNotEmpty" can only mean "this
        // test enqueued".
        workManager.cancelUniqueWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME_SILENT)
            .result.get(5, TimeUnit.SECONDS)
        workManager.pruneWork().result.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `fire with no widgets placed ends the chain without refreshing`() {
        // Both slots armed, then every widget removed: the fire must cancel the
        // slot it did *not* arrive on as well as its own, or that one stays
        // pending against a chain that is over.
        armBoth(LocalTime.of(6, 30))

        fire()

        // This path leaves no side effect to wait for, so the negatives below
        // are only meaningful once the receiver's coroutine has actually run.
        awaitBroadcasts()
        shadowOf(alarmManager).scheduledAlarms shouldBe emptyList()
        workManager.getWorkInfosForUniqueWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME_SILENT)
            .get(5, TimeUnit.SECONDS)
            .none { !it.state.isFinished } shouldBe true
    }

    @Test
    fun `a fire with no widgets left clears the arm record too`() {
        // The record must not outlive the chain. A boundary fire that finds no
        // widgets has *consumed* the alarm the record names, so a widget
        // re-added inside the delivery grace would otherwise read that record as
        // a live alarm and skip arming one.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        armBoth(LocalTime.of(6, 30))

        fire()
        awaitBroadcasts()

        // A reconcile a heartbeat after the consumed 07:00 boundary now arms,
        // because nothing claims to be pending any more.
        reconcileAt(LocalDateTime.of(today, LocalTime.of(7, 0)).atZone(zone).toInstant().plusMillis(50))
        armedByKind().getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe
            LocalDateTime.of(today, LocalTime.of(19, 0)).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `fire with a widget placed and delivery disabled enqueues a silent refresh and re-arms`() {
        placeOutfitWidget()

        fire()

        waitForWidgetRefreshAlarm()
        // Re-arm happens after the enqueue, so once the alarm is visible the
        // silent queue's state is settled.
        workManager.getWorkInfosForUniqueWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME_SILENT)
            .get(5, TimeUnit.SECONDS)
            .isNotEmpty() shouldBe true
    }

    @Test
    fun `fire with delivery covering the boundary re-arms without double-fetching`() {
        placeOutfitWidget()
        runBlocking {
            // Both slots enabled on the default every-day schedules — whichever
            // window the test's wall clock lands in, delivery covers it.
            app.settingsRepository.setDailyEnabled(true)
            app.settingsRepository.setTonightEnabled(true)
        }

        fire()

        waitForWidgetRefreshAlarm()
        // The queue was pruned in @Before, and this run's worker (had one been
        // enqueued) could by now be in any state — so assert no record exists
        // at all, finished or not.
        workManager.getWorkInfosForUniqueWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME_SILENT)
            .get(5, TimeUnit.SECONDS)
            .isEmpty() shouldBe true
    }

    @Test
    fun `an hourly fire refreshes an empty cache and re-arms`() {
        // Delivery coverage is irrelevant to an hourly tick — there is no
        // newly-opened window to defer to a delivery run — so both slots
        // enabled must not stop it repainting, refetching, or re-arming.
        placeOutfitWidget()
        runBlocking {
            app.settingsRepository.setDailyEnabled(true)
            app.settingsRepository.setTonightEnabled(true)
        }

        fire(WidgetRefreshKind.HOURLY)

        waitForWidgetRefreshAlarm()
        armedByKind().containsKey(WidgetRefreshKind.HOURLY) shouldBe true
        // Nothing has ever written the cache in this test process, so the
        // snapshot is null — the "nothing to show" case, always stale.
        workManager.getWorkInfosForUniqueWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME_SILENT)
            .get(5, TimeUnit.SECONDS)
            .isNotEmpty() shouldBe true
    }

    @Test
    fun `the widget's staleness threshold is longer than the app-open one`() {
        // Same pipeline, same queue, different threshold — and the difference is
        // the point: an app open is a person on the screen, an hourly tick may
        // be nobody. If these ever converged, the widget would be fetching at
        // app-open rates for a launcher nobody has glanced at.
        (WidgetRefreshReceiver.WIDGET_REFRESH_MAX_AGE > FetchAndNotifyWorker.SILENT_REFRESH_MIN_AGE) shouldBe true
    }

    @Test
    fun `the boundary slot wakes the device and the hourly slot does not`() {
        val armed = armBoth(LocalTime.of(14, 33))

        armed.getValue(WidgetRefreshKind.BOUNDARY).type shouldBe AlarmManager.RTC_WAKEUP
        armed.getValue(WidgetRefreshKind.HOURLY).type shouldBe AlarmManager.RTC
    }

    @Test
    fun `both slots are armed independently, at their own times`() {
        // Default 07:00 / 19:00 schedules: at 14:33 the boundary is tonight's
        // and the repaint is the top of the next hour. Two alarms, not one —
        // a single slot armed for the sooner of the two is what let a late
        // non-wakeup tick re-arm past the boundary and skip its fetch.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val armed = armBoth(LocalTime.of(14, 33))

        armed.getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe
            LocalDateTime.of(today, LocalTime.of(19, 0)).atZone(zone).toInstant().toEpochMilli()
        armed.getValue(WidgetRefreshKind.HOURLY).triggerAtTime shouldBe
            LocalDateTime.of(today, LocalTime.of(15, 0)).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `an hourly fire leaves the boundary alarm where it was`() {
        // The regression the two-slot split prevents, end to end: the hourly
        // alarm is non-wakeup, so its fire can land long after the boundary it
        // preceded. Re-arming from that fire must not move the boundary.
        //
        // The receiver runs on the real clock — its repaint reconciles the
        // chain through the widget render — so the boundary times are chosen
        // *relative to now* rather than pinned to a synthetic hour. Anchored to
        // a fixed 07:00 this passed or failed on CI's wall clock: after 19:00
        // local the reconcile finds that record past its successor and moves it
        // to tomorrow. Straddling now keeps the recorded boundary overdue and
        // its successor hours away whatever time it is, which is also the state
        // the assertion needs: only a re-arm of the boundary slot itself can
        // move it from there.
        placeOutfitWidget()
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val morning = Schedule.default().copy(time = now.minusSeconds(300).atZone(zone).toLocalTime())
        val tonight = Schedule.defaultTonight().copy(time = now.plusSeconds(6 * 3600).atZone(zone).toLocalTime())
        runBlocking {
            app.settingsRepository.setSchedule(morning.time, morning.days)
            app.settingsRepository.setTonightSchedule(tonight.time, tonight.days)
        }
        val boundaryBefore = armBoth(now.minusSeconds(600), morning, tonight)
            .getValue(WidgetRefreshKind.BOUNDARY)

        fire(WidgetRefreshKind.HOURLY)
        awaitBroadcasts()

        armedByKind().getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe
            boundaryBefore.triggerAtTime
    }

    @Test
    fun `a reconcile leaves a pending boundary alarm alone`() {
        // The collision the guard exists for. Both slots fall due at 07:00 on
        // the default schedules; the hourly fire's repaint reconciles the chain
        // through every widget render, and a plain re-arm there would replace
        // the 07:00 boundary alarm — armed, due, not yet delivered — with
        // 19:00, so the morning window is never fetched.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        armBoth(LocalTime.of(6, 30))
        val morning = LocalDateTime.of(today, LocalTime.of(7, 0)).atZone(zone).toInstant()
        armedByKind().getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe morning.toEpochMilli()

        // A reconcile a heartbeat after the boundary — what a repaint-driven
        // render does.
        reconcileAt(morning.plusMillis(50))

        armedByKind().getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe morning.toEpochMilli()
        // The hourly slot is still re-armed by that same reconcile — the guard
        // is about the boundary alarm only.
        armedByKind().getValue(WidgetRefreshKind.HOURLY).triggerAtTime shouldBe
            LocalDateTime.of(today, LocalTime.of(8, 0)).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `a reconcile arms the boundary slot when none is pending`() {
        // The other half, and why the guard reads a record of what was armed
        // rather than the clock: a widget placed a few minutes past a boundary
        // renders, reconciles, and must get a boundary alarm. A guard keyed on
        // "are we near a boundary time" would skip it and leave only the
        // non-wakeup hourly alarm behind.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        WidgetRefreshScheduler(context).cancel()

        reconcileAt(LocalDateTime.of(today, LocalTime.of(7, 5)).atZone(zone).toInstant())

        armedByKind().getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe
            LocalDateTime.of(today, LocalTime.of(19, 0)).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `a reconcile re-arms a boundary alarm still in the future`() {
        // Force-stop removes an app's alarms but leaves this SharedPreferences
        // record behind, so the next launch reads a *future* trigger with no
        // alarm behind it. Re-arming a future trigger costs nothing — it
        // computes the same instant — so it must not be treated as live, or
        // that launch ends up with only the non-wakeup hourly slot.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        armBoth(LocalTime.of(6, 30))
        WidgetRefreshKind.entries.forEach { alarmManager.cancel(WidgetRefreshScheduler.pendingIntent(context, it)) }
        armedByKind().containsKey(WidgetRefreshKind.BOUNDARY) shouldBe false

        // Still before the recorded 07:00 trigger — the force-stop-then-launch
        // moment, with the record intact and the alarm gone.
        reconcileAt(LocalDateTime.of(today, LocalTime.of(6, 45)).atZone(zone).toInstant())

        armedByKind().getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe
            LocalDateTime.of(today, LocalTime.of(7, 0)).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `a reconcile protects an overdue boundary alarm, not just a punctual one`() {
        // setAndAllowWhileIdle has no maximum delay — Doze can hold one well
        // past any fixed grace — so an alarm still undelivered 90 minutes after
        // its trigger is overdue, not dead. Expiring the record on a latency
        // guess would cancel it here and skip the window's fetch entirely.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        armBoth(LocalTime.of(6, 30))
        val morning = LocalDateTime.of(today, LocalTime.of(7, 0)).atZone(zone).toInstant()

        reconcileAt(LocalDateTime.of(today, LocalTime.of(8, 30)).atZone(zone).toInstant())

        armedByKind().getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe morning.toEpochMilli()
    }

    @Test
    fun `a reconcile past the next boundary stops protecting the old one`() {
        // The far end, and it is derived from the schedule rather than assumed:
        // once the successor boundary is due, the recorded one has either been
        // delivered or is never coming, and the slot needs arming for the new
        // window either way.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        armBoth(LocalTime.of(6, 30))
        WidgetRefreshKind.entries.forEach { alarmManager.cancel(WidgetRefreshScheduler.pendingIntent(context, it)) }

        reconcileAt(LocalDateTime.of(today, LocalTime.of(19, 30)).atZone(zone).toInstant())

        armedByKind().getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe
            LocalDateTime.of(today.plusDays(1), LocalTime.of(7, 0)).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `a launch after a force-stopped boundary arms instead of trusting the record`() {
        // Force-stop at 06:00, launch at 08:00. Android dropped the 07:00 alarm
        // and its PendingIntent, but the record still names 07:00 — which read
        // on its own is "overdue, successor at 19:00, so still in flight", and
        // the slot would sit unarmed for eleven hours. Asking whether the
        // PendingIntent survived is what separates gone from merely late.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        armBoth(LocalTime.of(6, 0))
        forceStop()

        reconcileAt(LocalDateTime.of(today, LocalTime.of(8, 0)).atZone(zone).toInstant())

        armedByKind().getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe
            LocalDateTime.of(today, LocalTime.of(19, 0)).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `a schedule edit leaves a boundary that is already in flight`() {
        // Editing only tonight's time, at 07:05, while the 07:00 wakeup is
        // overdue and undelivered. Re-arming the slot wholesale here — what a
        // caller-declared "the schedule changed, replace it" used to do —
        // cancels that alarm and arms 20:00 instead, so the morning window
        // never gets the fetch it was about to make. Nothing is gained by it
        // either: the alarm fires within moments and re-arms itself on the
        // edited schedule.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        armBoth(LocalTime.of(6, 30))
        val morning = LocalDateTime.of(today, LocalTime.of(7, 0)).atZone(zone).toInstant()

        reconcileAt(
            LocalDateTime.of(today, LocalTime.of(7, 5)).atZone(zone).toInstant(),
            tonight = Schedule.defaultTonight().copy(time = LocalTime.of(20, 0)),
        )

        armedByKind().getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe morning.toEpochMilli()
    }

    @Test
    fun `a schedule edit re-arms the boundary when none is in flight`() {
        // The other half: with the armed boundary still in the future, an edit
        // has to take effect immediately rather than waiting for the old time
        // to come round.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        armBoth(LocalTime.of(14, 0))

        reconcileAt(
            LocalDateTime.of(today, LocalTime.of(14, 5)).atZone(zone).toInstant(),
            tonight = Schedule.defaultTonight().copy(time = LocalTime.of(20, 0)),
        )

        armedByKind().getValue(WidgetRefreshKind.BOUNDARY).triggerAtTime shouldBe
            LocalDateTime.of(today, LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `cancelling the chain clears the arm record`() {
        // The record must never outlive the alarm it describes: a stale one
        // would read as pending and stop the next reconcile arming anything.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        armBoth(LocalTime.of(6, 30))
        WidgetRefreshScheduler(context).cancel()

        reconcileAt(LocalDateTime.of(today, LocalTime.of(6, 45)).atZone(zone).toInstant())

        armedByKind().containsKey(WidgetRefreshKind.BOUNDARY) shouldBe true
    }

    /** A routine reconcile — what a widget render does — at a pinned instant. */
    private fun reconcileAt(
        now: Instant,
        morning: Schedule = Schedule.default(),
        tonight: Schedule = Schedule.defaultTonight(),
    ) {
        WidgetRefreshScheduler(context, Clock.fixed(now, ZoneId.systemDefault()))
            .schedule(morning, tonight)
    }

    /**
     * What a force-stop leaves behind: Android drops the app's alarms *and*
     * the PendingIntents behind them, while this chain's SharedPreferences
     * record survives untouched.
     */
    private fun forceStop() {
        WidgetRefreshKind.entries
            .map { WidgetRefreshScheduler.pendingIntent(context, it) }
            .forEach {
                alarmManager.cancel(it)
                it.cancel()
            }
    }

    /**
     * Arms the whole chain from a clock pinned to [localTime] today and returns
     * the alarm each slot landed. Goes through the real [WidgetRefreshScheduler]
     * so the kind → alarm-type and kind → trigger-time mappings are what's under
     * test, not restated here.
     */
    private fun armBoth(localTime: LocalTime) = ZoneId.systemDefault().let { zone ->
        armBoth(LocalDateTime.of(LocalDate.now(zone), localTime).atZone(zone).toInstant())
    }

    /** As above, from an arbitrary instant and an arbitrary schedule pair. */
    private fun armBoth(
        now: Instant,
        morning: Schedule = Schedule.default(),
        tonight: Schedule = Schedule.defaultTonight(),
    ) = run {
        WidgetRefreshKind.entries.forEach { alarmManager.cancel(WidgetRefreshScheduler.pendingIntent(context, it)) }
        WidgetRefreshScheduler(context, Clock.fixed(now, ZoneId.systemDefault()))
            .schedule(morning, tonight)
        armedByKind()
    }

    /** The chain's currently-armed alarms, keyed by the kind their intent carries. */
    private fun armedByKind() =
        shadowOf(alarmManager).scheduledAlarms
            .mapNotNull { alarm ->
                val intent = shadowOf(alarm.operation).savedIntent
                if (intent.action != WidgetRefreshReceiver.ACTION_FIRE) return@mapNotNull null
                val kind = WidgetRefreshKind.valueOf(intent.getStringExtra(WidgetRefreshReceiver.EXTRA_KIND)!!)
                kind to alarm
            }
            .toMap()

    private fun fire(kind: WidgetRefreshKind? = null) {
        WidgetRefreshReceiver().onReceive(
            context,
            Intent(WidgetRefreshReceiver.ACTION_FIRE)
                .setPackage(context.packageName)
                // Omitted entirely by default: the receiver treats a fire with
                // no kind as a boundary, which is what the alarms an older build
                // armed look like, so the boundary tests exercise that path too.
                .apply { kind?.let { putExtra(WidgetRefreshReceiver.EXTRA_KIND, it.name) } },
        )
    }

    private fun placeOutfitWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        shadowOf(appWidgetManager).setAllowedToBindAppWidgets(true)
        appWidgetManager.bindAppWidgetIdIfAllowed(
            42,
            ComponentName(context, OutfitWidgetReceiver::class.java),
        )
    }

    private fun waitForWidgetRefreshAlarm() {
        awaitBroadcasts()
        if (armedByKind().isEmpty()) error("Widget-refresh alarm was not re-armed")
    }
}
