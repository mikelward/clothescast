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
 * (which schedule boundary the chain arms for next — daily, ignoring day-of-week
 * sets) and [deliveryCoversWidgetRefresh] (when a boundary fire defers to the
 * delivery alarm instead of double-fetching alongside it). The alarm plumbing is
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
