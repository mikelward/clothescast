package app.clothescast.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
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
import app.clothescast.ui.today.WorkInfoLite
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
import java.time.LocalTime
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
            // Nothing resolved → nothing to fetch a forecast for; don't spin up
            // a recovery run that would just fail the same way.
            recoveryWorkInfos() shouldHaveSize 0
        }
    }

    @Test
    fun `cache-only refresh with a resolvable location but empty cache kicks an observed recovery refresh`() {
        // The reported bug: the scheduled run failed for lack of a location, the
        // user then grants it, and the cache-only refresh resolves a fix but —
        // with nothing cached — leaves the Today screen empty until the next
        // alarm. A saved fallback city makes resolveLocation return non-null
        // here; with an empty cache the branch should kick a recovery refresh so
        // a forecast actually populates the screen.
        //
        // It must land on the *observed* daily / tonight queue (not the
        // unobserved silent queue) so its success supersedes the stale
        // REASON_NO_LOCATION failure the scheduled run left there — otherwise
        // the forecast populates but the "no location" banner lingers once the
        // saved fallback drops the location-required suppression.
        runBlocking {
            app.settingsRepository.setLocation(
                Location(latitude = 51.5, longitude = -0.1, displayName = "London"),
            )
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(workDataOf(FetchAndNotifyWorker.KEY_CACHE_LOCATION_ONLY to true))
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Success>()
            // Exactly one recovery run, on one of the two observed queues
            // (which one depends on the current schedule window).
            observedRefreshWorkInfos().map { it.state } shouldContainExactlyInAnyOrder
                listOf(WorkInfo.State.ENQUEUED)
            workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_SILENT) shouldHaveSize 0
        }
    }

    @Test
    fun `cache-only refresh with a forecast already cached stays a location-only write`() {
        // When a forecast is already on screen there's nothing to populate, so
        // the cache-only refresh stays cheap (location write only) rather than
        // burning a fresh fetch — that's the whole reason the path exists apart
        // from the recovery refresh.
        runBlocking {
            app.settingsRepository.setLocation(
                Location(latitude = 51.5, longitude = -0.1, displayName = "London"),
            )
            app.insightCache.store(
                InsightCache.Slot.THIS_PERIOD,
                sampleSnapshot(period = ForecastPeriod.TODAY),
            )
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(workDataOf(FetchAndNotifyWorker.KEY_CACHE_LOCATION_ONLY to true))
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Success>()
            recoveryWorkInfos() shouldHaveSize 0
        }
    }

    // --- staleNoLocationFailure (cross-queue recovery predicate) --------

    @Test
    fun `staleNoLocationFailure is false for an empty history`() {
        staleNoLocationFailure(emptyList()) shouldBe false
    }

    @Test
    fun `staleNoLocationFailure is true when the latest terminal is a no-location failure`() {
        staleNoLocationFailure(
            listOf(lite(WorkInfo.State.FAILED, completedAt = 100, reason = FetchAndNotifyWorker.REASON_NO_LOCATION)),
        ) shouldBe true
    }

    @Test
    fun `staleNoLocationFailure ignores the failure while a run is active`() {
        // A pending / running recovery on the queue means the failure isn't
        // stale — it's about to be superseded — so don't kick another.
        staleNoLocationFailure(
            listOf(
                lite(WorkInfo.State.FAILED, completedAt = 100, reason = FetchAndNotifyWorker.REASON_NO_LOCATION),
                lite(WorkInfo.State.ENQUEUED),
            ),
        ) shouldBe false
    }

    @Test
    fun `staleNoLocationFailure is false when a newer success supersedes the failure`() {
        staleNoLocationFailure(
            listOf(
                lite(WorkInfo.State.FAILED, completedAt = 100, reason = FetchAndNotifyWorker.REASON_NO_LOCATION),
                lite(WorkInfo.State.SUCCEEDED, completedAt = 200),
            ),
        ) shouldBe false
    }

    @Test
    fun `staleNoLocationFailure is false for a failure with a different reason`() {
        // A network / HTTP failure isn't cleared by saving a location, so it's
        // not ours to supersede from the cache-only path.
        staleNoLocationFailure(
            listOf(lite(WorkInfo.State.FAILED, completedAt = 100, reason = FetchAndNotifyWorker.REASON_UNHANDLED)),
        ) shouldBe false
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
    fun `enqueuePlay uses its own unique work queue so it can't block scheduled refreshes`() {
        // Play sits on UNIQUE_WORK_NAME_PLAY rather than the alarm
        // queues; otherwise an offline Play tap parked behind the
        // network constraint would let WorkManager's KEEP policy on
        // the next alarm fire drop the scheduled morning refresh in
        // favour of the pending play — the user would wake up to a
        // stale cached announcement instead of a fresh forecast.
        FetchAndNotifyWorker.enqueuePlay(context, period = ForecastPeriod.TODAY)

        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_PLAY).map { it.state } shouldContainExactlyInAnyOrder
            listOf(WorkInfo.State.ENQUEUED)
        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME) shouldHaveSize 0
        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_TONIGHT) shouldHaveSize 0
    }

    @Test
    fun `enqueuePlay for TONIGHT also lands on the play queue, not the tonight queue`() {
        FetchAndNotifyWorker.enqueuePlay(context, period = ForecastPeriod.TONIGHT)

        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_PLAY).map { it.state } shouldContainExactlyInAnyOrder
            listOf(WorkInfo.State.ENQUEUED)
        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME) shouldHaveSize 0
        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_TONIGHT) shouldHaveSize 0
    }

    @Test
    fun `play with no fresh cached match falls through to a fresh fetch`() {
        // The only cached snapshot is a stale (non-today) TODAY one; the
        // user taps "Play now" for TONIGHT. Neither the period nor the date
        // matches, so the play path doesn't replay it — it falls through to
        // a fresh fetch. With no location configured that fetch can't run, so
        // it no-ops with skip_telemetry rather than delivering the wrong
        // (stale / mismatched) cast or surfacing a no-location failure.
        runBlocking {
            app.insightCache.store(
                InsightCache.Slot.THIS_PERIOD,
                sampleSnapshot(period = ForecastPeriod.TODAY),
            )
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(
                    workDataOf(
                        FetchAndNotifyWorker.KEY_PLAY to true,
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
    fun `play with empty cache falls through to a fresh fetch and no-ops without a no-location failure`() {
        // Empty cache → nothing to replay → the play path fetches fresh. With
        // no location configured the fetch can't run, but unlike the scheduled
        // forecast path it must NOT surface the REASON_NO_LOCATION failure —
        // a manual Play tap with no location just quietly no-ops (the Today
        // screen's location banner already prompts the fix).
        runBlocking {
            val worker = TestListenableWorkerBuilder<FetchAndNotifyWorker>(context)
                .setInputData(workDataOf(FetchAndNotifyWorker.KEY_PLAY to true))
                .build()

            val result = worker.doWork()

            result.shouldBeInstanceOf<Result.Success>()
            result.outputData.getBoolean(FetchAndNotifyWorker.KEY_SKIP_TELEMETRY, false) shouldBe true
        }
    }

    @Test
    fun `play target date follows the schedule window across midnight`() {
        val morning = LocalTime.of(7, 0)
        val tonight = LocalTime.of(19, 0)
        val date = LocalDate.of(2026, 5, 27)
        fun target(period: ForecastPeriod, time: LocalTime) =
            FetchAndNotifyWorker.playTargetDate(period, date.atTime(time), morning, tonight)

        // Daytime (10:00) — both casts are today's.
        target(ForecastPeriod.TODAY, LocalTime.of(10, 0)) shouldBe date
        target(ForecastPeriod.TONIGHT, LocalTime.of(10, 0)) shouldBe date

        // Evening (20:00) — today's daytime has gone out, so Daily means
        // tomorrow; the nightly cast is the current (today's) one.
        target(ForecastPeriod.TODAY, LocalTime.of(20, 0)) shouldBe date.plusDays(1)
        target(ForecastPeriod.TONIGHT, LocalTime.of(20, 0)) shouldBe date

        // Overnight (02:00, past midnight, before the morning cutoff) — the
        // current ongoing night began the previous evening, so it's dated
        // yesterday; the next daytime cast is *this* morning, i.e. today (not
        // tomorrow). This is the midnight-crossing case the offset must handle.
        target(ForecastPeriod.TODAY, LocalTime.of(2, 0)) shouldBe date
        target(ForecastPeriod.TONIGHT, LocalTime.of(2, 0)) shouldBe date.minusDays(1)
    }

    // Minimal WorkInfoLite for the staleNoLocationFailure pure-function tests.
    private fun lite(
        state: WorkInfo.State,
        completedAt: Long = 0L,
        reason: String? = null,
    ): WorkInfoLite {
        val data = Data.Builder()
            .putLong(FetchAndNotifyWorker.KEY_COMPLETED_AT, completedAt)
            .apply { if (reason != null) putString(FetchAndNotifyWorker.KEY_REASON, reason) }
            .build()
        return WorkInfoLite(state = state, runAttemptCount = 1, outputData = data)
    }

    private fun workInfosFor(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    // The two observed refresh queues the Today banner watches; the cache-only
    // recovery lands on whichever matches the current schedule window.
    private fun observedRefreshWorkInfos(): List<WorkInfo> =
        workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME) +
            workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_TONIGHT)

    // Every queue a cache-only recovery could plausibly enqueue on — used to
    // assert the branch *didn't* kick one.
    private fun recoveryWorkInfos(): List<WorkInfo> =
        observedRefreshWorkInfos() + workInfosFor(FetchAndNotifyWorker.UNIQUE_WORK_NAME_SILENT)

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
