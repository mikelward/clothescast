package app.clothescast.alarm

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import app.clothescast.core.domain.model.ForecastPeriod
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Dispatch-only coverage for [ScheduledDeliveryService]: how `onStartCommand`
 * sets up — or refuses to set up — the per-worker owner gate
 * ([ScheduledDeliveryService.isHoldingForegroundFor]) under different
 * intents.
 *
 * WorkInfo-driven state-machine tests (PREPARING → DELIVERING on
 * `KEY_FETCH_COMPLETE`, teardown on SUCCEEDED / FAILED / CANCELLED, phase-2
 * exit on retry-backoff) are deferred — Robolectric's WorkManager doesn't
 * reliably drive `getWorkInfoByIdFlow` emissions on the timing the watcher
 * coroutine expects, so the test would either be flaky or pin the whole
 * watcher coroutine to a synchronous dispatcher just for the test
 * scaffolding. Worth a follow-up once the design has settled and we know
 * which transitions are most worth pinning down.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ScheduledDeliveryServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun installWorkManager() {
        // Robolectric doesn't fire the androidx.startup content provider, so
        // WorkManager has no auto-init. The Service queries WorkManager for
        // a WorkInfo flow on the workId from its intent — without an init
        // the very first getInstance() throws. Match the executors pattern
        // used by the other alarm tests.
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
    }

    @Test
    fun `start with no workId leaves owner unset`() {
        val randomId = UUID.randomUUID()
        val controller = startedServiceFor(
            ScheduledDeliveryService.intent(
                context = context,
                period = ForecastPeriod.TODAY,
                // No EXTRA_WORK_ID — strip it from the intent before start
                // so we exercise the malformed-start branch.
                workId = "",
                playsSpeech = false,
                deliveringWantsForeground = false,
            ).apply { removeExtra(ScheduledDeliveryService.EXTRA_WORK_ID) },
        )

        ScheduledDeliveryService.isHoldingForegroundFor(randomId) shouldBe false
        controller.destroy()
    }

    @Test
    fun `start with valid workId stamps owner`() {
        val workId = UUID.randomUUID()
        val controller = startedServiceFor(
            ScheduledDeliveryService.intent(
                context = context,
                period = ForecastPeriod.TODAY,
                workId = workId.toString(),
                playsSpeech = false,
                deliveringWantsForeground = false,
            ),
        )

        ScheduledDeliveryService.isHoldingForegroundFor(workId) shouldBe true
        ScheduledDeliveryService.isHoldingForegroundFor(UUID.randomUUID()) shouldBe false
        controller.destroy()
    }

    @Test
    fun `same workId arriving twice is idempotent`() {
        val workId = UUID.randomUUID()
        val intent = ScheduledDeliveryService.intent(
            context = context,
            period = ForecastPeriod.TODAY,
            workId = workId.toString(),
            playsSpeech = false,
            deliveringWantsForeground = false,
        )
        val controller = ServiceController.of(ScheduledDeliveryService(), intent)
            .create()
            .startCommand(0, 1)
        ScheduledDeliveryService.isHoldingForegroundFor(workId) shouldBe true

        controller.withIntent(intent).startCommand(0, 2)
        ScheduledDeliveryService.isHoldingForegroundFor(workId) shouldBe true

        controller.destroy()
    }

    @Test
    fun `different workId replaces owner in place`() {
        val oldId = UUID.randomUUID()
        val newId = UUID.randomUUID()
        val controller = ServiceController.of(
            ScheduledDeliveryService(),
            ScheduledDeliveryService.intent(
                context = context,
                period = ForecastPeriod.TODAY,
                workId = oldId.toString(),
                playsSpeech = false,
                deliveringWantsForeground = false,
            ),
        ).create().startCommand(0, 1)
        ScheduledDeliveryService.isHoldingForegroundFor(oldId) shouldBe true

        controller.withIntent(
            ScheduledDeliveryService.intent(
                context = context,
                period = ForecastPeriod.TONIGHT,
                workId = newId.toString(),
                playsSpeech = true,
                deliveringWantsForeground = true,
            ),
        ).startCommand(0, 2)

        ScheduledDeliveryService.isHoldingForegroundFor(oldId) shouldBe false
        ScheduledDeliveryService.isHoldingForegroundFor(newId) shouldBe true
        controller.destroy()
    }

    private fun startedServiceFor(intent: Intent): ServiceController<ScheduledDeliveryService> =
        ServiceController.of(ScheduledDeliveryService(), intent)
            .create()
            .startCommand(0, 1)
}
