package app.clothescast.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.ClothesCastApplication
import app.clothescast.backup.ClothesCastBackupAgent
import app.clothescast.core.domain.model.ForecastPeriod
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A restore leaves a schedule with no alarms behind it; the backup agent's
 * one-shot alarm hands off to [ScheduleRefreshReceiver], which arms them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RestoreRearmTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as ClothesCastApplication
    private val alarmManager: AlarmManager = context.getSystemService()!!

    @Before
    fun resetAlarms() {
        awaitInitialScheduling()
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TODAY))
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TONIGHT))
    }

    @Test
    fun `a finished restore arms the restored morning schedule`() {
        runBlocking {
            app.settingsRepository.setDailyEnabled(true)
            app.settingsRepository.setTonightEnabled(false)
        }

        ClothesCastBackupAgent.scheduleRearmAfterRestore(context)
        val handoff = shadowOf(alarmManager).scheduledAlarms
            .map { shadowOf(it.operation).savedIntent }
            .single { it.action == ScheduleRefreshReceiver.ACTION_RESTORED }
        handoff.component?.className shouldBe ScheduleRefreshReceiver::class.java.name

        ScheduleRefreshReceiver().onReceive(context, handoff)

        awaitBroadcasts()
        shadowOf(alarmManager).scheduledAlarms
            .map { shadowOf(it.operation).savedIntent.action }
            .filter { it == AlarmReceiver.ACTION_FIRE || it == AlarmReceiver.ACTION_FIRE_TONIGHT } shouldBe
            listOf(AlarmReceiver.ACTION_FIRE)
    }
}
