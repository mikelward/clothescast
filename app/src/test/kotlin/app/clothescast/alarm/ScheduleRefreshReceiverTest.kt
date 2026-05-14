package app.clothescast.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.ForecastPeriod
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The receiver fires on BOOT_COMPLETED, MY_PACKAGE_REPLACED, timezone, and locale
 * changes — every wall-clock context shift that wipes alarms or changes when they
 * should next fire. These tests cover the two prefs-driven branches:
 *  - tonight enabled  → both the TODAY and TONIGHT slots are re-armed;
 *  - tonight disabled → TODAY is re-armed and TONIGHT is explicitly cancelled.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ScheduleRefreshReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as ClothesCastApplication
    private val alarmManager: AlarmManager = context.getSystemService()!!

    @Before
    fun resetAlarms() {
        // ClothesCastApplication.onCreate also schedules these alarms on a
        // background coroutine, racing each test. Wait for that pass to settle
        // (default prefs arm both slots), then cancel them so the assertions
        // below see only what the receiver-under-test armed.
        waitForAlarms(2)
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TODAY))
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TONIGHT))
    }

    @Test
    fun `tonight enabled re-arms both alarm slots`() {
        runBlocking { app.settingsRepository.setTonightEnabled(true) }

        ScheduleRefreshReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        waitForAlarms(2)
        val actions = shadowOf(alarmManager).scheduledAlarms
            .map { shadowOf(it.operation).savedIntent.action }
            .toSet()
        actions shouldBe setOf(AlarmReceiver.ACTION_FIRE, AlarmReceiver.ACTION_FIRE_TONIGHT)
    }

    @Test
    fun `tonight disabled re-arms today and skips tonight`() {
        runBlocking { app.settingsRepository.setTonightEnabled(false) }

        ScheduleRefreshReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        waitForAlarms(1)
        val alarms = shadowOf(alarmManager).scheduledAlarms
        alarms shouldHaveSize 1
        shadowOf(alarms.single().operation).savedIntent.action shouldBe AlarmReceiver.ACTION_FIRE
    }

    // The receiver finishes asynchronously via `goAsync()` + a Dispatchers.Default
    // coroutine. There's no Looper to idle — wait for the shadow alarm list to
    // hit the expected count or time out. 5 s is generous; the schedule path is
    // sub-100 ms on every run we've seen.
    private fun waitForAlarms(expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (shadowOf(alarmManager).scheduledAlarms.size >= expected) return
            Thread.sleep(25)
        }
    }
}
