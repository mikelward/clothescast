package app.clothescast.alarm

import app.clothescast.core.domain.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure-logic coverage for the widget-only refresh chain: [nextWidgetRefreshAfter]
 * (which schedule boundary the boundary slot arms for next — daily, ignoring
 * day-of-week sets), [nextHourlyWidgetRefreshAfter] (the repaint slot), and
 * [deliveryCoversWidgetRefresh] (when a boundary fire defers to the delivery
 * alarm instead of double-fetching alongside it). The two slots are independent
 * by design, so they are computed independently here too. The alarm plumbing is
 * covered by [WidgetRefreshReceiverTest]. Default 07:00 / 19:00 cutoffs match
 * the app's default morning / tonight schedule.
 */
class WidgetRefreshSchedulerTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val morningTime: LocalTime = LocalTime.of(7, 0)
    private val tonightTime: LocalTime = LocalTime.of(19, 0)

    private fun at(hour: Int, minute: Int, day: Int = 18): Instant =
        LocalDateTime.of(2026, 6, day, hour, minute).atZone(zone).toInstant()

    @Test
    fun `before the morning boundary arms for this morning`() {
        assertEquals(at(7, 0), nextWidgetRefreshAfter(at(5, 59), morningTime, tonightTime, zone))
    }

    @Test
    fun `between the boundaries arms for tonight`() {
        assertEquals(at(19, 0), nextWidgetRefreshAfter(at(12, 30), morningTime, tonightTime, zone))
    }

    @Test
    fun `after the tonight boundary arms for tomorrow morning`() {
        assertEquals(at(7, 0, day = 19), nextWidgetRefreshAfter(at(22, 15), morningTime, tonightTime, zone))
    }

    @Test
    fun `exactly at a boundary arms strictly after it`() {
        // A fire lands at (or just after) its own boundary; re-arming must pick
        // the *next* one, not re-arm the instant that just fired.
        assertEquals(at(19, 0), nextWidgetRefreshAfter(at(7, 0), morningTime, tonightTime, zone))
    }

    @Test
    fun `crossed schedule still yields the earliest upcoming boundary`() {
        // Degenerate config (tonight earlier than morning) — the chain doesn't
        // care which window is which, only when the next flip happens.
        val next = nextWidgetRefreshAfter(at(5, 0), LocalTime.of(21, 0), LocalTime.of(6, 0), zone)
        assertEquals(at(6, 0), next)
    }

    @Test
    fun `mid-hour arms the hourly slot for the top of the next hour`() {
        // The stale-widget case: a 07:00 snapshot rendered at 07:00, glanced at
        // half past two. Nothing flips until 19:00, so without the hourly slot
        // the chart's now-line would still read 07:00.
        assertEquals(at(15, 0), nextHourlyWidgetRefreshAfter(at(14, 33), zone))
    }

    @Test
    fun `on the hour arms the following hour, not the one that just fired`() {
        assertEquals(at(16, 0), nextHourlyWidgetRefreshAfter(at(15, 0), zone))
    }

    @Test
    fun `the hourly slot follows the zone's local hour, not the instant's`() {
        // Kathmandu is UTC+05:45, so truncating the *instant* to the hour would
        // arm :15 past the local hour. Users read the launcher in local time.
        val kathmandu = ZoneId.of("Asia/Kathmandu")
        val now = LocalDateTime.of(2026, 6, 18, 14, 33).atZone(kathmandu).toInstant()
        val expected = LocalDateTime.of(2026, 6, 18, 15, 0).atZone(kathmandu).toInstant()
        assertEquals(expected, nextHourlyWidgetRefreshAfter(now, kathmandu))
    }

    @Test
    fun `a late hourly tick does not carry the boundary past its time`() {
        // The regression the two-slot split exists to prevent. The hourly alarm
        // is non-wakeup, so a 06:00 tick on a sleeping device can be delivered
        // at, say, 09:12. Because each slot re-arms only itself, that late fire
        // moves the *hourly* slot to 10:00 and leaves the boundary slot exactly
        // where it was — still 07:00, armed as a wakeup alarm, which is what
        // fetches the morning window. Sharing one slot would have re-armed the
        // boundary to 19:00 and skipped the morning refresh entirely, blanking
        // the widget on the day's first glance.
        val lateFire = at(9, 12)
        assertEquals(at(10, 0), nextHourlyWidgetRefreshAfter(lateFire, zone))
        // The boundary armed back at 05:00 — before the device went to sleep —
        // is untouched by that fire and is still the morning one.
        assertEquals(at(7, 0), nextWidgetRefreshAfter(at(5, 0), morningTime, tonightTime, zone))
    }

    @Test
    fun `the two slots coincide when a boundary sits on the hour`() {
        // Default schedules put both boundaries on the hour, so at 18:30 both
        // slots arm for 19:00. They are separate PendingIntents, so both fire;
        // the silent-refresh queue's REPLACE dedupe collapses any double fetch.
        assertEquals(at(19, 0), nextWidgetRefreshAfter(at(18, 30), morningTime, tonightTime, zone))
        assertEquals(at(19, 0), nextHourlyWidgetRefreshAfter(at(18, 30), zone))
    }

    @Test
    fun `an off-the-hour boundary keeps its own time`() {
        // A 07:30 morning cutoff: the boundary slot arms for 07:30 and the
        // hourly slot for 08:00, each on its own schedule.
        assertEquals(at(7, 30), nextWidgetRefreshAfter(at(7, 10), LocalTime.of(7, 30), tonightTime, zone))
        assertEquals(at(8, 0), nextHourlyWidgetRefreshAfter(at(7, 10), zone))
    }

    private fun schedule(time: LocalTime, days: Set<DayOfWeek> = Schedule.EVERY_DAY) =
        Schedule(time = time, days = days, zoneId = zone)

    // 2026-06-18 is a Thursday.
    private val thursdayMorningFire: LocalDateTime = LocalDateTime.of(2026, 6, 18, 7, 0)
    private val thursdayTonightFire: LocalDateTime = LocalDateTime.of(2026, 6, 18, 19, 1)

    @Test
    fun `disabled slots never cover a boundary`() {
        assertFalse(
            deliveryCoversWidgetRefresh(
                dailyEnabled = false,
                tonightEnabled = false,
                morning = schedule(morningTime),
                tonight = schedule(tonightTime),
                now = thursdayMorningFire,
            ),
        )
    }

    @Test
    fun `enabled daily slot covers the morning boundary on an active day`() {
        assertTrue(
            deliveryCoversWidgetRefresh(
                dailyEnabled = true,
                tonightEnabled = false,
                morning = schedule(morningTime),
                tonight = schedule(tonightTime),
                now = thursdayMorningFire,
            ),
        )
    }

    @Test
    fun `enabled daily slot does not cover the morning boundary outside its day set`() {
        // Weekend-only morning cast: Thursday's boundary isn't delivery-covered,
        // so the widget chain must refresh it itself.
        assertFalse(
            deliveryCoversWidgetRefresh(
                dailyEnabled = true,
                tonightEnabled = false,
                morning = schedule(morningTime, days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
                tonight = schedule(tonightTime),
                now = thursdayMorningFire,
            ),
        )
    }

    @Test
    fun `the daily toggle does not cover the tonight boundary`() {
        assertFalse(
            deliveryCoversWidgetRefresh(
                dailyEnabled = true,
                tonightEnabled = false,
                morning = schedule(morningTime),
                tonight = schedule(tonightTime),
                now = thursdayTonightFire,
            ),
        )
    }

    @Test
    fun `enabled tonight slot covers the tonight boundary on an active day`() {
        assertTrue(
            deliveryCoversWidgetRefresh(
                dailyEnabled = false,
                tonightEnabled = true,
                morning = schedule(morningTime),
                tonight = schedule(tonightTime),
                now = thursdayTonightFire,
            ),
        )
    }
}
