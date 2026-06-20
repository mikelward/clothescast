package app.clothescast.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.data.SettingsRepository
import app.clothescast.work.FetchAndNotifyWorker
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The receiver's try/catch is the only thing standing between a transient
 * preferences-read failure on the alarm-fire path and an uncaught crash inside
 * the goAsync coroutine. We swap in a [SettingsRepository] whose `preferences`
 * flow always throws, then send each fire action and verify:
 *  - the receiver returns without propagating the exception;
 *  - on TODAY the best-effort fallback inside the catch still enqueues the
 *    worker so the morning insight runs even when prefs blew up (the whole
 *    reason the fallback exists);
 *  - on TONIGHT the receiver bails silently — no fallback enqueue, per the
 *    "only the morning slot gets the fallback" comment in [AlarmReceiver].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AlarmReceiverErrorHandlingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as ClothesCastApplication
    private val alarmManager: AlarmManager = context.getSystemService()!!
    private val workManager: WorkManager by lazy {
        // Robolectric doesn't fire the androidx.startup content provider that
        // normally auto-inits WorkManager on a real device, so getInstance()
        // throws here without an explicit init. Use real background executors
        // because WorkManager's internal Room database refuses main-thread
        // access — a synchronous executor would surface as a Room
        // "Cannot access database on the main thread" failure here. The
        // worker request itself stays queued (its NetworkType.CONNECTED
        // constraint never becomes satisfied under Robolectric) so we're
        // tracking enqueue, not execution.
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

    private lateinit var originalDelegate: Lazy<SettingsRepository>
    private lateinit var collectLatch: CountDownLatch

    @Before
    fun installFailingRepository() {
        // ClothesCastApplication.onCreate reads preferences on a background
        // coroutine to arm the morning + tonight alarms. Wait for that pass to
        // settle (both default-prefs slots land) before swapping the repo —
        // otherwise the real repo is still in use and the assertions race the
        // startup work.
        waitForAlarms(2)
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TODAY))
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TONIGHT))
        // The startup pass also enqueues no worker, so the WorkManager queue is
        // empty here — assertions on the unique-work lists start from a clean
        // slate without needing an explicit cancel.

        collectLatch = CountDownLatch(1)
        originalDelegate = swapRepository(throwingRepository(collectLatch))
    }

    @After
    fun restoreRepository() {
        // Restore the real repo so the next test (and any background work the
        // app holds onto) sees the original instance.
        swapRepository(originalDelegate)
    }

    @Test
    fun `TODAY fire falls back to worker enqueue when preferences read fails`() {
        AlarmReceiver().onReceive(
            context,
            Intent(AlarmReceiver.ACTION_FIRE).setPackage(context.packageName),
        )

        // Latch fires on the first collect of `dataStore.data`, which is exactly
        // when preferences.first() throws — i.e. the try block has just failed
        // and control is about to enter the catch.
        collectLatch.shouldHaveTickedDown()
        val infos = waitForWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME, expected = 1)
        infos shouldHaveAtLeastSize 1
    }

    @Test
    fun `TONIGHT fire does not enqueue a worker when preferences read fails`() {
        AlarmReceiver().onReceive(
            context,
            Intent(AlarmReceiver.ACTION_FIRE_TONIGHT).setPackage(context.packageName),
        )

        collectLatch.shouldHaveTickedDown()
        // Give the goAsync coroutine time to walk out of the catch and finish.
        // The catch for TONIGHT does nothing observable, so we can't poll for a
        // positive signal — wait a beat, then assert nothing was enqueued.
        Thread.sleep(500)
        workManager.getWorkInfosForUniqueWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME)
            .get()
            .shouldBeEmpty()
        // The alarm was also never re-armed (scheduler.schedule lives inside
        // the failing try block), so the alarm slot stays clear too.
        shadowOf(alarmManager).scheduledAlarms.shouldBeEmpty()
    }

    private fun throwingRepository(latch: CountDownLatch): SettingsRepository {
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                latch.countDown()
                throw IOException("simulated DataStore read failure")
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = throw IOException("simulated DataStore write failure")
        }
        return SettingsRepository(store)
    }

    // Replace the Kotlin `by lazy` backing delegate so `app.settingsRepository`
    // resolves to [repo] on the next access. Returns the previous delegate so
    // @After can restore it.
    private fun swapRepository(repo: SettingsRepository): Lazy<SettingsRepository> =
        swapRepository(lazyOf(repo))

    private fun swapRepository(delegate: Lazy<SettingsRepository>): Lazy<SettingsRepository> {
        val field = ClothesCastApplication::class.java
            .getDeclaredField("settingsRepository\$delegate")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val previous = field.get(app) as Lazy<SettingsRepository>
        field.set(app, delegate)
        return previous
    }

    private fun CountDownLatch.shouldHaveTickedDown() {
        if (!await(5, TimeUnit.SECONDS)) {
            error("preferences flow was never collected — receiver did not enter the try block")
        }
    }

    // Identical shape to ScheduleRefreshReceiverTest.waitForAlarms — the
    // receiver finishes asynchronously via goAsync + Dispatchers.Default, so
    // there's no Looper to idle.
    private fun waitForAlarms(expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (shadowOf(alarmManager).scheduledAlarms.size >= expected) return
            Thread.sleep(25)
        }
    }

    private fun waitForWork(workName: String, expected: Int): List<WorkInfo> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val infos = workManager.getWorkInfosForUniqueWork(workName).get()
            if (infos.size >= expected) return infos
            Thread.sleep(25)
        }
        return workManager.getWorkInfosForUniqueWork(workName).get()
    }
}
