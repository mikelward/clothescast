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
import app.clothescast.widget.OutfitWidgetReceiver
import app.clothescast.work.FetchAndNotifyWorker
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Receiver coverage for the widget-only refresh chain:
 *  - no widgets placed → the fire is a no-op and the chain ends (no re-arm);
 *  - widgets placed with both delivery slots disabled → a silent refresh is
 *    enqueued and the chain re-arms;
 *  - widgets placed with delivery covering the boundary → no double-fetch,
 *    but the chain still re-arms.
 *
 * The boundary / day-set decision logic itself is pure and covered by
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
        listOf(
            DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TODAY),
            DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TONIGHT),
            WidgetRefreshScheduler.pendingIntent(context),
        ).forEach { alarmManager.cancel(it) }

        runBlocking {
            app.settingsRepository.setDailyEnabled(false)
            app.settingsRepository.setTonightEnabled(false)
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

    private fun fire() {
        WidgetRefreshReceiver().onReceive(
            context,
            Intent(WidgetRefreshReceiver.ACTION_FIRE).setPackage(context.packageName),
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
        val armed = shadowOf(alarmManager).scheduledAlarms.any {
            shadowOf(it.operation).savedIntent.action == WidgetRefreshReceiver.ACTION_FIRE
        }
        if (!armed) error("Widget-refresh alarm was not re-armed")
    }
}
