package app.clothescast.alarm

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.work.FetchAndNotifyWorker
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Receiver routing coverage: given a slot's enabled/disabled state and the
 * configured delivery mode (+ smart-home toggles), does the receiver
 *  - cancel the alarm and bail (disabled slot),
 *  - enqueue the worker AND start [ScheduledDeliveryService] (NOTIFICATION /
 *    TTS / MQTT / cast modes),
 *  - or enqueue the worker but *not* start the Service (SILENT with no
 *    publishable smart-home destinations — the user opted out of every
 *    visible affordance)?
 *
 * Pairs with [AlarmReceiverErrorHandlingTest] (prefs-read-failure
 * fallback) and [ScheduledDeliveryServiceTest] (Service state machine).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AlarmReceiverRoutingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as ClothesCastApplication
    private val alarmManager: AlarmManager = context.getSystemService()!!
    private val shadowApp = shadowOf(context.applicationContext as android.app.Application)
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
        // App startup arms default-prefs alarms on a background coroutine;
        // wait for the queue to settle and then clear shadows so each test
        // starts from a clean slate (matches AlarmReceiverErrorHandlingTest).
        waitForAlarms(2)
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TODAY))
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TONIGHT))
        drainStartedServices()
        // Reset prefs to a known disabled state at the top of each test;
        // individual tests opt slots in via the real SettingsRepository.
        runBlocking {
            app.settingsRepository.setDailyEnabled(false)
            app.settingsRepository.setTonightEnabled(false)
            app.settingsRepository.setDeliveryMode(DeliveryMode.NOTIFICATION_AND_TTS)
            app.settingsRepository.setMqttBridgeEnabled(false)
            app.settingsRepository.setMqttConfig(host = null, port = 1883, useTls = false, username = null, topic = "clothescast/default")
            app.settingsRepository.setCastEnabled(false)
            app.settingsRepository.setCastRoute(null, null)
        }
        // Make sure WorkManager is initialized before any test runs.
        workManager
        workManager.cancelUniqueWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME)
    }

    @After
    fun restoreDefaults() {
        runBlocking {
            app.settingsRepository.setDailyEnabled(false)
            app.settingsRepository.setTonightEnabled(false)
            app.settingsRepository.setMqttBridgeEnabled(false)
            app.settingsRepository.setMqttConfig(host = null, port = 1883, useTls = false, username = null, topic = "clothescast/default")
            app.settingsRepository.setCastEnabled(false)
            app.settingsRepository.setCastRoute(null, null)
        }
    }

    @Test
    fun `daily disabled fire cancels the alarm without starting Service or worker`() {
        // dailyEnabled=false is the default state we reset to in @Before.
        fireDailyAlarm()

        shadowApp.peekNextStartedService() shouldBe null
        workManager.getWorkInfosForUniqueWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME).get(5, TimeUnit.SECONDS)
            .none { !it.state.isFinished } shouldBe true
    }

    @Test
    fun `daily enabled with NOTIFICATION_AND_TTS starts Service and enqueues worker`() {
        runBlocking {
            app.settingsRepository.setDailyEnabled(true)
            app.settingsRepository.setDeliveryMode(DeliveryMode.NOTIFICATION_AND_TTS)
        }

        fireDailyAlarm()

        val started = waitForStartedService()
        started?.component?.className shouldBe ScheduledDeliveryService::class.java.name
        started?.getStringExtra(ScheduledDeliveryService.EXTRA_PERIOD) shouldBe ForecastPeriod.TODAY.name
        started?.getBooleanExtra(ScheduledDeliveryService.EXTRA_PLAYS_SPEECH, false) shouldBe true
        // EXTRA_WORK_ID is the freshly-enqueued worker — assert it's present
        // (the receiver always passes it on the FGS path) without pinning a
        // specific UUID.
        (started?.getStringExtra(ScheduledDeliveryService.EXTRA_WORK_ID)?.isNotBlank() ?: false) shouldBe true

        waitForEnqueuedWorker(FetchAndNotifyWorker.UNIQUE_WORK_NAME)
    }

    @Test
    fun `daily enabled with SILENT mode and no smart-home destinations skips the Service`() {
        runBlocking {
            app.settingsRepository.setDailyEnabled(true)
            app.settingsRepository.setDeliveryMode(DeliveryMode.SILENT)
            // mqttBridgeEnabled=false, castEnabled=false from @Before.
        }

        fireDailyAlarm()

        // No Service start — the user opted out of every visible affordance.
        // But the worker still runs (cache + widget refresh contract for
        // SILENT delivery).
        waitForEnqueuedWorker(FetchAndNotifyWorker.UNIQUE_WORK_NAME)
        shadowApp.peekNextStartedService() shouldBe null
    }

    @Test
    fun `daily enabled with SILENT plus publishable MQTT starts the Service`() {
        runBlocking {
            app.settingsRepository.setDailyEnabled(true)
            app.settingsRepository.setDeliveryMode(DeliveryMode.SILENT)
            // isMqttPublishable requires both toggle on AND host non-blank.
            app.settingsRepository.setMqttBridgeEnabled(true)
            app.settingsRepository.setMqttConfig(host = "broker.local", port = 1883, useTls = false, username = null, topic = "clothescast/default")
        }

        fireDailyAlarm()

        val started = waitForStartedService()
        started?.component?.className shouldBe ScheduledDeliveryService::class.java.name
        started?.getBooleanExtra(ScheduledDeliveryService.EXTRA_DELIVERING_WANTS_FOREGROUND, false) shouldBe true
        // playsSpeech stays false — SILENT doesn't speak even when MQTT
        // publishes; the FGS type stays dataSync.
        started?.getBooleanExtra(ScheduledDeliveryService.EXTRA_PLAYS_SPEECH, false) shouldBe false
        waitForEnqueuedWorker(FetchAndNotifyWorker.UNIQUE_WORK_NAME)
    }

    private fun fireDailyAlarm() {
        AlarmReceiver().onReceive(
            context,
            Intent(AlarmReceiver.ACTION_FIRE).setPackage(context.packageName),
        )
    }

    private fun waitForStartedService(): Intent? {
        // The receiver finishes asynchronously via goAsync + Dispatchers.Default,
        // so we poll the shadow application's started-service queue.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val intent = shadowApp.peekNextStartedService()
            if (intent != null) return shadowApp.nextStartedService
            Thread.sleep(25)
        }
        return null
    }

    private fun waitForEnqueuedWorker(workName: String): List<WorkInfo> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val infos = workManager.getWorkInfosForUniqueWork(workName).get(2, TimeUnit.SECONDS)
            if (infos.any { !it.state.isFinished }) return infos
            Thread.sleep(25)
        }
        return workManager.getWorkInfosForUniqueWork(workName).get(2, TimeUnit.SECONDS)
    }

    private fun drainStartedServices() {
        while (shadowApp.peekNextStartedService() != null) {
            shadowApp.nextStartedService
        }
    }

    private fun waitForAlarms(expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (shadowOf(alarmManager).scheduledAlarms.size >= expected) return
            Thread.sleep(25)
        }
    }
}
