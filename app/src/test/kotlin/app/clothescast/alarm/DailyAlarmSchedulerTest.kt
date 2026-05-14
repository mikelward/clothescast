package app.clothescast.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Behaviour of [DailyAlarmScheduler]:
 *  - arms `setExactAndAllowWhileIdle` at the next [Schedule] occurrence;
 *  - keeps the TODAY and TONIGHT slots independent (each has its own request
 *    code and receiver action so AlarmManager can hold both at once);
 *  - cancels the slot the caller asks for without disturbing the other.
 *
 * Note: the inexact-alarm fallback path (when the user has revoked the
 * `SCHEDULE_EXACT_ALARM` permission) isn't covered here — Robolectric 4.14.1's
 * `ShadowAlarmManager` doesn't yet expose a hook for flipping
 * `canScheduleExactAlarms()` to false. Add coverage when that surfaces.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DailyAlarmSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager: AlarmManager = context.getSystemService()!!

    // Fixed clock at midday UTC so we don't have to reason about "the test ran
    // at 07:00:00.001 and the same-day fire was missed by a millisecond".
    private val fixedNow: Instant = Instant.parse("2026-05-14T12:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    @Before
    fun clearStrayAlarms() {
        // ClothesCastApplication.onCreate schedules its own alarms on a
        // background coroutine; they land in `shadowOf(am).scheduledAlarms` at
        // an unpredictable point and break single-alarm assertions. Wait long
        // enough for both default-prefs slots to land (only the very first @Before
        // typically waits — later tests find the count already settled), then
        // cancel both so the test-under-test starts from an empty list.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            shadowOf(alarmManager).scheduledAlarms.size < 2
        ) {
            Thread.sleep(25)
        }
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TODAY))
        alarmManager.cancel(DailyAlarmScheduler.pendingIntent(context, ForecastPeriod.TONIGHT))
    }

    private val morning = Schedule(
        time = LocalTime.of(7, 0),
        days = DayOfWeek.entries.toSet(),
        zoneId = ZoneOffset.UTC,
    )
    private val evening = Schedule(
        time = LocalTime.of(19, 0),
        days = DayOfWeek.entries.toSet(),
        zoneId = ZoneOffset.UTC,
    )

    @Test
    fun `schedule arms an exact alarm at the next occurrence`() {
        DailyAlarmScheduler(context, clock).schedule(morning, ForecastPeriod.TODAY)

        val scheduled = shadowOf(alarmManager).scheduledAlarms.single()
        scheduled.type shouldBe AlarmManager.RTC_WAKEUP
        // Next 07:00 UTC strictly after 12:00 UTC on 2026-05-14 is 2026-05-15 07:00 UTC.
        scheduled.triggerAtTime shouldBe Instant.parse("2026-05-15T07:00:00Z").toEpochMilli()
    }

    @Test
    fun `schedule stamps the trigger time on the PendingIntent extras`() {
        DailyAlarmScheduler(context, clock).schedule(evening, ForecastPeriod.TONIGHT)

        val scheduled = shadowOf(alarmManager).scheduledAlarms.single()
        val savedIntent = shadowOf(scheduled.operation).savedIntent
        savedIntent.action shouldBe AlarmReceiver.ACTION_FIRE_TONIGHT
        savedIntent.getLongExtra(DailyAlarmScheduler.EXTRA_SCHEDULED_AT_MS, -1L) shouldBe
            Instant.parse("2026-05-14T19:00:00Z").toEpochMilli()
    }

    @Test
    fun `TODAY and TONIGHT live in independent slots`() {
        val scheduler = DailyAlarmScheduler(context, clock)
        scheduler.schedule(morning, ForecastPeriod.TODAY)
        scheduler.schedule(evening, ForecastPeriod.TONIGHT)

        val alarms = shadowOf(alarmManager).scheduledAlarms
        alarms shouldHaveSize 2
        val actions = alarms.map { shadowOf(it.operation).savedIntent.action }.toSet()
        actions shouldBe setOf(AlarmReceiver.ACTION_FIRE, AlarmReceiver.ACTION_FIRE_TONIGHT)
    }

    @Test
    fun `cancel removes only the requested slot`() {
        val scheduler = DailyAlarmScheduler(context, clock)
        scheduler.schedule(morning, ForecastPeriod.TODAY)
        scheduler.schedule(evening, ForecastPeriod.TONIGHT)

        scheduler.cancel(ForecastPeriod.TONIGHT)

        val remaining = shadowOf(alarmManager).scheduledAlarms.single()
        shadowOf(remaining.operation).savedIntent.action shouldBe AlarmReceiver.ACTION_FIRE
    }

    @Test
    fun `nextOccurrence resolves through the schedule's own zone`() {
        // Schedule pinned to a fixed offset rather than systemDefault() to keep
        // the assertion independent of the test host's TZ.
        val sydney = Schedule(
            time = LocalTime.of(7, 0),
            days = DayOfWeek.entries.toSet(),
            zoneId = ZoneId.of("Australia/Sydney"),
        )
        DailyAlarmScheduler(context, clock).schedule(sydney, ForecastPeriod.TODAY)

        val scheduled = shadowOf(alarmManager).scheduledAlarms.single()
        // 12:00 UTC on 2026-05-14 is 22:00 Sydney (AEST = UTC+10 in May).
        // Next 07:00 Sydney after that is 2026-05-15 07:00 +10:00 → 2026-05-14 21:00 UTC.
        scheduled.triggerAtTime shouldBe Instant.parse("2026-05-14T21:00:00Z").toEpochMilli()
    }
}
