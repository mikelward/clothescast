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
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.repository.ForecastBundle
import app.clothescast.data.InsightCache
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
 * Setup clears the user's location, enables daily, and disables tonight
 * delivery so each `doWork()` call lands deterministically on a no-network
 * branch. The
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
            // Daily is off by default now; enable it so the TODAY-period runs
            // clear the disabled-period gate and land on the no-network
            // branches these tests pin. Tonight stays off for the gate test.
            app.settingsRepository.setDailyEnabled(true)
            app.settingsRepository.setTonightEnabled(false)
            // The insight cache is process-shared across tests via the real
            // application DataStore; a snapshot leaked from a previous case
            // would let the replay branch deliver against it. Reset it so
            // each test starts from a clean cache.
            app.insightCache.clear()
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
    fun `silent refresh bypasses the disabled-period gate`() {
        // A silent refresh (app-open / onboarding / manual Refresh) updates the
        // cache regardless of the enable toggles — the gate only guards
        // scheduled delivery. Tonight is disabled in setup; without a saved
        // location the run falls through to the no-location failure, which
        // proves it bypassed the gate (otherwise we'd see the skip_telemetry
        // success path the scheduled tonight run takes above).
        runBlocking {
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(workDataOf(FetchAndNotifyWorker.KEY_SILENT_REFRESH to true))
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Failure>()
            result.outputData.getString(FetchAndNotifyWorker.KEY_REASON) shouldBe
                FetchAndNotifyWorker.REASON_NO_LOCATION
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

    @Test
    fun `enqueueReplay uses its own unique work queue so it can't block scheduled refreshes`() {
        // Play sits on UNIQUE_WORK_NAME_REPLAY rather than the alarm
        // queues; otherwise an offline Play tap parked behind the
        // network constraint would let WorkManager's KEEP policy on
        // the next alarm fire drop the scheduled morning refresh in
        // favour of the pending replay — the user would wake up to a
        // stale cached announcement instead of a fresh forecast.
        FetchAndNotifyWorker.enqueueReplay(context, period = ForecastPeriod.TODAY)

        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_REPLAY).map { it.state } shouldContainExactlyInAnyOrder
            listOf(WorkInfo.State.ENQUEUED)
        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME) shouldHaveSize 0
        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_TONIGHT) shouldHaveSize 0
    }

    @Test
    fun `enqueueReplay for TONIGHT also lands on the replay queue, not the tonight queue`() {
        FetchAndNotifyWorker.enqueueReplay(context, period = ForecastPeriod.TONIGHT)

        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_REPLAY).map { it.state } shouldContainExactlyInAnyOrder
            listOf(WorkInfo.State.ENQUEUED)
        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME) shouldHaveSize 0
        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_TONIGHT) shouldHaveSize 0
    }

    @Test
    fun `replay whose requested period no longer matches the cached snapshot is dropped`() {
        // User taps Play at 11pm (TONIGHT) while offline; the replay
        // request sits in the queue under UNIQUE_WORK_NAME_TONIGHT. Before
        // it runs, the morning alarm fires and overwrites THIS_PERIOD
        // with a fresh TODAY snapshot. When the queued replay finally
        // executes, the snapshot in cache no longer matches the period
        // the user actually tapped Play for — drop it rather than
        // delivering the wrong announcement.
        runBlocking {
            app.insightCache.store(
                InsightCache.Slot.THIS_PERIOD,
                sampleSnapshot(period = ForecastPeriod.TODAY),
            )
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(
                    workDataOf(
                        FetchAndNotifyWorker.KEY_REPLAY_ONLY to true,
                        FetchAndNotifyWorker.KEY_PERIOD to ForecastPeriod.TONIGHT.name,
                    )
                )
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Success>()
            result.outputData.getBoolean(FetchAndNotifyWorker.KEY_SKIP_TELEMETRY, false) shouldBe true
        }
    }

    @Test
    fun `replay with empty cache succeeds and skips telemetry without falling through to fetch`() {
        // The Today screen disables Play when the cache is empty, but a
        // racing cache-clear (or a stale enqueued tap surviving a process
        // death) could still land here. The branch must no-op rather than
        // escalating to the no-location failure the fetch path would hit.
        runBlocking {
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(workDataOf(FetchAndNotifyWorker.KEY_REPLAY_ONLY to true))
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Success>()
            result.outputData.getBoolean(FetchAndNotifyWorker.KEY_SKIP_TELEMETRY, false) shouldBe true
        }
    }

    private fun workInfosFor(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    private fun sampleSnapshot(period: ForecastPeriod): ForecastSnapshot {
        val today = LocalDate.of(2026, 5, 27)
        val daily = DailyForecast(
            date = today,
            temperatureMinC = 10.0,
            temperatureMaxC = 18.0,
            feelsLikeMinC = 9.0,
            feelsLikeMaxC = 17.0,
            precipitationProbabilityMaxPct = 0.0,
            precipitationMmTotal = 0.0,
            condition = WeatherCondition.CLEAR,
            hourly = emptyList(),
        )
        return ForecastSnapshot(
            bundle = ForecastBundle(
                today = daily,
                yesterday = daily.copy(date = today.minusDays(1)),
                forecastZone = ZoneId.of("UTC"),
            ),
            events = emptyList(),
            location = Location(latitude = 51.5, longitude = -0.1, displayName = "London"),
            period = period,
            generatedAt = Instant.parse("2026-05-27T07:00:00Z"),
        )
    }
}
