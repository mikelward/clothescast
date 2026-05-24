package app.clothescast.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.ForecastPeriod
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Drives the [FetchAndNotifyWorker]'s `doWork()` decision tree along the
 * branches reachable without network or LocationManager — gate behaviour,
 * input-data parsing, and the terminal-result stamping contract — plus the
 * companion enqueue helpers' unique-work-name routing.
 *
 * The deep-path scenarios (forecast fetch, insight generation, notification +
 * TTS + MQTT + cast fan-out) intentionally aren't covered here: they pull in
 * the upstream Open-Meteo API, a real LocationResolver, the on-device TTS
 * engine, and the MQTT broker, none of which are realistic to stand up under
 * unit tests. The pre-existing branch coverage (DeliveryGatesTest,
 * InsightFormatterTest, InsightNotifierTest, MqttPublisherTest, …) carries
 * those concerns; this class pins the worker's *own* decisions.
 *
 * Setup clears the user's location and disables tonight delivery so each
 * `doWork()` call lands deterministically on a no-network branch. The
 * WorkManager test driver is wired with a [SynchronousExecutor] so the
 * `NetworkType.CONNECTED` constraint keeps the enqueue assertions in
 * `ENQUEUED` state (the worker never actually runs against the network).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FetchAndNotifyWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as ClothesCastApplication

    @Before
    fun resetPrefsAndWorkManager() {
        runBlocking {
            app.settingsRepository.setUseDeviceLocation(false)
            app.settingsRepository.clearLocation()
            app.settingsRepository.setTonightEnabled(false)
        }
        // SynchronousExecutor keeps WorkManager off the real background
        // thread pool — combined with the NetworkType.CONNECTED constraint
        // the worker's enqueue methods set, enqueued work stays in
        // ENQUEUED state instead of being dispatched (which would try to
        // hit Open-Meteo).
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    // --- doWork() branches ----------------------------------------------

    @Test
    fun `tonight disabled with TONIGHT period returns success with skip_telemetry`() {
        runBlocking {
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(workDataOf(FetchAndNotifyWorker.KEY_PERIOD to ForecastPeriod.TONIGHT.name))
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Success>()
            result.outputData.getBoolean(FetchAndNotifyWorker.KEY_SKIP_TELEMETRY, false) shouldBe true
        }
    }

    @Test
    fun `same-day force refresh bypasses the tonight-disabled gate`() {
        // Force refresh enqueued *today* — the gate is meant to honour the
        // user's recent tap. Without a saved location the run then falls
        // through to the no-location failure, which is exactly what proves
        // the gate was bypassed (otherwise we'd see the skip_telemetry
        // success path).
        runBlocking {
            val today = LocalDate.now().toEpochDay()
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(
                    workDataOf(
                        FetchAndNotifyWorker.KEY_PERIOD to ForecastPeriod.TONIGHT.name,
                        FetchAndNotifyWorker.KEY_FORCE_REFRESH to true,
                        FetchAndNotifyWorker.KEY_REQUESTED_EPOCH_DAY to today,
                    )
                )
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Failure>()
            result.outputData.getString(FetchAndNotifyWorker.KEY_REASON) shouldBe
                FetchAndNotifyWorker.REASON_NO_LOCATION
        }
    }

    @Test
    fun `stale force refresh from a previous day is dropped and the tonight gate still fires`() {
        runBlocking {
            val yesterday = LocalDate.now().minusDays(1).toEpochDay()
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(
                    workDataOf(
                        FetchAndNotifyWorker.KEY_PERIOD to ForecastPeriod.TONIGHT.name,
                        FetchAndNotifyWorker.KEY_FORCE_REFRESH to true,
                        FetchAndNotifyWorker.KEY_REQUESTED_EPOCH_DAY to yesterday,
                    )
                )
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Success>()
            result.outputData.getBoolean(FetchAndNotifyWorker.KEY_SKIP_TELEMETRY, false) shouldBe true
        }
    }

    @Test
    fun `missing period defaults to TODAY`() {
        // No KEY_PERIOD → falls back to TODAY, so the tonight gate doesn't
        // apply and we land on the no-location failure path. That path
        // surfacing here pins the default; if the worker silently defaulted
        // to TONIGHT instead, the gate would short-circuit to success.
        runBlocking {
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context).build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Failure>()
            result.outputData.getString(FetchAndNotifyWorker.KEY_REASON) shouldBe
                FetchAndNotifyWorker.REASON_NO_LOCATION
        }
    }

    @Test
    fun `invalid period string falls back to TODAY`() {
        runBlocking {
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(workDataOf(FetchAndNotifyWorker.KEY_PERIOD to "NOT_A_PERIOD"))
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Failure>()
            result.outputData.getString(FetchAndNotifyWorker.KEY_REASON) shouldBe
                FetchAndNotifyWorker.REASON_NO_LOCATION
        }
    }

    @Test
    fun `no location yields failure with REASON_NO_LOCATION and a completed_at stamp`() {
        runBlocking {
            val before = System.currentTimeMillis()
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(workDataOf(FetchAndNotifyWorker.KEY_PERIOD to ForecastPeriod.TODAY.name))
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Failure>()
            result.outputData.getString(FetchAndNotifyWorker.KEY_REASON) shouldBe
                FetchAndNotifyWorker.REASON_NO_LOCATION
            result.outputData.getLong(FetchAndNotifyWorker.KEY_COMPLETED_AT, 0L) shouldBeGreaterThan
                before - 1
        }
    }

    @Test
    fun `cache-only refresh returns success even with no fallback location`() {
        // The cache-only path is meant to be a no-op when there's nothing to
        // cache — resolveLocation returning null is fine and shouldn't escalate
        // to the REASON_NO_LOCATION failure that the forecast pipeline does.
        runBlocking {
            val before = System.currentTimeMillis()
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(workDataOf(FetchAndNotifyWorker.KEY_CACHE_LOCATION_ONLY to true))
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Success>()
            // No skip_telemetry on this branch — the cache-only run is filtered
            // higher up in doWork() via its own `skipTelemetry` local, not via
            // the output-data flag.
            result.outputData.getBoolean(FetchAndNotifyWorker.KEY_SKIP_TELEMETRY, false) shouldBe false
            result.outputData.getLong(FetchAndNotifyWorker.KEY_COMPLETED_AT, 0L) shouldBeGreaterThan
                before - 1
        }
    }

    @Test
    fun `tonight-disabled success result carries a completed_at stamp`() {
        runBlocking {
            val before = System.currentTimeMillis()
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(workDataOf(FetchAndNotifyWorker.KEY_PERIOD to ForecastPeriod.TONIGHT.name))
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Success>()
            result.outputData.getLong(FetchAndNotifyWorker.KEY_COMPLETED_AT, 0L) shouldBeGreaterThan
                before - 1
        }
    }

    // --- companion enqueue routing --------------------------------------

    @Test
    fun `enqueueOneShot for TODAY enqueues under the daily unique work name`() {
        FetchAndNotifyWorker.enqueueOneShot(context, period = ForecastPeriod.TODAY)

        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME).map { it.state } shouldContainExactlyInAnyOrder
            listOf(WorkInfo.State.ENQUEUED)
        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_TONIGHT) shouldHaveSize 0
    }

    @Test
    fun `enqueueOneShot for TONIGHT enqueues under the tonight unique work name`() {
        FetchAndNotifyWorker.enqueueOneShot(context, period = ForecastPeriod.TONIGHT)

        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_TONIGHT).map { it.state } shouldContainExactlyInAnyOrder
            listOf(WorkInfo.State.ENQUEUED)
        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME) shouldHaveSize 0
    }

    @Test
    fun `enqueueLocationCacheRefresh enqueues under the cache-only unique work name`() {
        FetchAndNotifyWorker.enqueueLocationCacheRefresh(context)

        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_LOCATION_CACHE).map { it.state } shouldContainExactlyInAnyOrder
            listOf(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `enqueueSilentRefresh enqueues under the silent unique work name`() {
        FetchAndNotifyWorker.enqueueSilentRefresh(context)

        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_SILENT).map { it.state } shouldContainExactlyInAnyOrder
            listOf(WorkInfo.State.ENQUEUED)
    }

    private fun workInfosFor(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
}
