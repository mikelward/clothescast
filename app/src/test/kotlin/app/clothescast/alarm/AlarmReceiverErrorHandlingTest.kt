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
 *  - on TONIGHT no fallback worker is enqueued, per the "only the morning
 *    slot gets the fallback" comment in [AlarmReceiver];
 *  - on BOTH slots the catch re-arms the alarm (with the slot's default
 *    schedule, since prefs never materialized) — the chain is
 *    self-perpetuating, so a transient failure that skipped the re-arm would
 *    silently end every future delivery until the next app open / reboot.
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
        // WorkManager's instance is a process-wide static that survives
        // Robolectric's per-test application reset, so unique work enqueued by
        // an earlier test in this class leaks into the next one (JUnit 4's
        // hash-based method order decides which runs first). Cancel it so the
        // non-finished-work assertions below start from a clean slate in
        // either order.
        workManager.cancelUniqueWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME).result.get()

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
    fun `TODAY fire falls back to worker enqueue and re-arm when preferences read fails`() {
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
        // The catch also keeps the self-perpetuating chain alive by re-arming
        // with the slot's default schedule.
        waitForAlarms(1)
        shadowOf(alarmManager).scheduledAlarms shouldHaveAtLeastSize 1
    }

    @Test
    fun `TONIGHT fire re-arms the alarm but enqueues no worker when preferences read fails`() {
        AlarmReceiver().onReceive(
            context,
            Intent(AlarmReceiver.ACTION_FIRE_TONIGHT).setPackage(context.packageName),
        )

        collectLatch.shouldHaveTickedDown()
        // The catch's last observable act is the fallback re-arm; once the
        // alarm lands, the coroutine has walked past the (TODAY-only) worker
        // fallback, so the queue's emptiness is a settled fact, not a race.
        waitForAlarms(1)
        shadowOf(alarmManager).scheduledAlarms shouldHaveAtLeastSize 1
        // Non-finished only: the @Before cancel leaves earlier tests' leaked
        // work in CANCELLED state, which still shows up in the unique-work
        // list. Only a live enqueue from *this* fire would appear here.
        activeWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME).shouldBeEmpty()
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
            val infos = activeWork(workName)
            if (infos.size >= expected) return infos
            Thread.sleep(25)
        }
        return activeWork(workName)
    }

    // The unique-work list filtered to live entries — CANCELLED leftovers from
    // the @Before cleanup (or a REPLACE) don't count as an enqueue.
    private fun activeWork(workName: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(workName).get().filterNot { it.state.isFinished }
}
