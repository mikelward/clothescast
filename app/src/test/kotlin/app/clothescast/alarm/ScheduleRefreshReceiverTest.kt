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
        // ClothesCastApplication.onCreate reconciles both slots on a background
        // coroutine off the persisted prefs. Both are off by default, so that
        // pass *cancels* both — and a cancel that ran late would wipe the alarm
        // the receiver under test just armed, zeroing the assertions below.
        // Join it, then clear both slots so the test arms onto an empty list.
        awaitInitialScheduling()
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TODAY))
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TONIGHT))
    }

    @Test
    fun `tonight enabled re-arms both alarm slots`() {
        runBlocking {
            app.settingsRepository.setDailyEnabled(true)
            app.settingsRepository.setTonightEnabled(true)
        }

        ScheduleRefreshReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        awaitBroadcasts()
        val alarms = shadowOf(alarmManager).scheduledAlarms
        alarms shouldHaveSize 2
        alarms.map { shadowOf(it.operation).savedIntent.action }.toSet() shouldBe
            setOf(AlarmReceiver.ACTION_FIRE, AlarmReceiver.ACTION_FIRE_TONIGHT)
    }

    @Test
    fun `tonight disabled re-arms today and skips tonight`() {
        runBlocking {
            app.settingsRepository.setDailyEnabled(true)
            app.settingsRepository.setTonightEnabled(false)
        }

        ScheduleRefreshReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        awaitBroadcasts()
        val alarms = shadowOf(alarmManager).scheduledAlarms
        alarms shouldHaveSize 1
        shadowOf(alarms.single().operation).savedIntent.action shouldBe AlarmReceiver.ACTION_FIRE
    }
}
