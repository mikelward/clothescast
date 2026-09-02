package app.clothescast.widget

import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.widget.WidgetCacheAction.REFRESH
import app.clothescast.widget.WidgetCacheAction.RENDER
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit coverage for [widgetCacheAction] — the gate that stops the home-screen
 * widgets plotting a previous window as the current one (the "Feels like
 * 17 – 22°C" bug, where a day-stale daytime snapshot drew yesterday's curve
 * under today's axis). It renders the cache when it's the current window —
 * including the ongoing post-midnight overnight, matched on its overnight flag
 * since that window's insight dates to yesterday while its bundle stays
 * today-anchored — and [REFRESH]es (empty + kick) otherwise. A snapshot younger
 * than the silent-refresh threshold is always rendered, so it can't settle into
 * a loop. The render path itself needs a real display and is verified on-device;
 * this locks the pure period/date logic. The default 07:00 / 19:00 cutoffs match
 * the app's default morning / tonight schedule.
 */
class WidgetInsightFreshnessTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val morning: LocalTime = LocalTime.of(7, 0)
    private val tonight: LocalTime = LocalTime.of(19, 0)

    @Test
    fun `daytime snapshot renders during its own daytime`() {
        assertEquals(RENDER, action(dayInsight(LocalDate.of(2026, 6, 18)), at(2026, 6, 18, 17, 3)))
    }

    @Test
    fun `daytime snapshot refreshes once the user crosses into the tonight window`() {
        // Past 19:00 a refresh would target tonight (reachable, dated today).
        assertEquals(REFRESH, action(dayInsight(LocalDate.of(2026, 6, 18)), at(2026, 6, 18, 19, 30)))
    }

    @Test
    fun `day-old daytime snapshot refreshes - the reported case`() {
        // Bug report: snapshot for 2026-06-17 still on screen at 2026-06-18 17:03.
        assertEquals(REFRESH, action(dayInsight(LocalDate.of(2026, 6, 17)), at(2026, 6, 18, 17, 3)))
    }

    @Test
    fun `ongoing tonight snapshot renders in the small hours after midnight`() {
        // Captured post-midnight (00:30, how an overnight snapshot really arises)
        // and dated the 18th; at 02:00 on the 19th the current window is still
        // that ongoing overnight, recognised by its overnight flag + the 18th
        // date. generatedAt is >1h old so the age gate doesn't mask the match.
        val overnight = nightInsight(
            LocalDate.of(2026, 6, 18),
            generatedAt = at(2026, 6, 19, 0, 30),
            overnight = true,
        )
        assertEquals(RENDER, action(overnight, at(2026, 6, 19, 2, 0)))
    }

    @Test
    fun `a day-old overnight snapshot refreshes instead of rendering stale`() {
        // An overnight from the night of the 17th, still cached at 02:00 on the
        // 19th: its flag is set but its date (the 17th) isn't the night now in
        // progress (the 18th), so it must refresh, not render two-day-old data.
        val staleOvernight = nightInsight(
            LocalDate.of(2026, 6, 17),
            generatedAt = at(2026, 6, 18, 0, 30),
            overnight = true,
        )
        assertEquals(REFRESH, action(staleOvernight, at(2026, 6, 19, 2, 0)))
    }

    @Test
    fun `tonight snapshot refreshes once the next morning's cutoff passes`() {
        assertEquals(REFRESH, action(nightInsight(LocalDate.of(2026, 6, 18)), at(2026, 6, 19, 7, 30)))
    }

    @Test
    fun `post-midnight, a stale daytime snapshot refreshes toward the ongoing overnight`() {
        // Between midnight and the wake cutoff the current window is the ongoing
        // overnight, which is today-anchored (since "Show Overnight and Today
        // before dawn") and so reachable by a dayOffset-0 refresh. A stale
        // daytime snapshot therefore refreshes toward it rather than being kept.
        assertEquals(REFRESH, action(dayInsight(LocalDate.of(2026, 6, 18)), at(2026, 6, 19, 2, 0)))
    }

    @Test
    fun `tonight snapshot honors a later-than-default wake time`() {
        // Wake set to 09:00: the ongoing overnight stays current until then.
        // Captured post-midnight (01:00) and >1h old at both clocks below.
        val lateMorning = LocalTime.of(9, 0)
        val overnight = nightInsight(
            LocalDate.of(2026, 6, 18),
            generatedAt = at(2026, 6, 19, 1, 0),
            overnight = true,
        )
        assertEquals(
            RENDER,
            widgetCacheAction(overnight, at(2026, 6, 19, 8, 0), lateMorning, tonight, zone),
        )
        assertEquals(
            REFRESH,
            widgetCacheAction(overnight, at(2026, 6, 19, 9, 30), lateMorning, tonight, zone),
        )
    }

    @Test
    fun `daytime snapshot honors a later-than-default evening cutoff`() {
        // Evening cutoff set to 21:00: the daytime widget stays current until then.
        val lateEvening = LocalTime.of(21, 0)
        assertEquals(
            RENDER,
            widgetCacheAction(dayInsight(LocalDate.of(2026, 6, 18)), at(2026, 6, 18, 20, 0), morning, lateEvening, zone),
        )
    }

    @Test
    fun `a just-written snapshot renders even when its date does not match`() {
        // Loop-breaker: a cross-zone refresh can land a snapshot whose
        // forecast-zone forDate (the 19th) differs from the device-clock target
        // date (the 18th). Generated seconds ago, it's rendered so the kicked
        // refresh ends the staleness instead of churning.
        val justNow = at(2026, 6, 18, 20, 30)
        val fresh = nightInsight(forDate = LocalDate.of(2026, 6, 19), generatedAt = justNow.minusSeconds(30))
        assertEquals(RENDER, action(fresh, justNow))
        // Same period/date mismatch, but hours old and in a reachable window →
        // the age gate no longer protects it and a refresh is due.
        val old = nightInsight(forDate = LocalDate.of(2026, 6, 19), generatedAt = justNow.minusSeconds(7200))
        assertEquals(REFRESH, action(old, justNow))
    }

    @Test
    fun `a young snapshot from the previous window refreshes at the boundary`() {
        // A glance at the 07:00 boundary. Last night's window was refreshed at
        // 06:30, so the cached snapshot is TONIGHT and only 30 minutes old. The
        // age loop-breaker used to let that render — drawing last night as if it
        // were the day ahead, for up to an hour. The period has flipped, so it
        // refreshes instead: empty state, fetch, then the real 12 hours.
        val boundary = at(2026, 6, 18, 7, 0)
        val lastNight = nightInsight(
            forDate = LocalDate.of(2026, 6, 17),
            generatedAt = boundary.minusSeconds(1800),
        )
        assertEquals(REFRESH, action(lastNight, boundary))
    }

    @Test
    fun `the loop-breaker still covers a young snapshot of the right period`() {
        // The disagreement it was written for is a *date* one, and that still
        // renders: same period, cross-zone forDate, seconds old. Narrowing the
        // gate to period mismatches must not reopen the refresh→reject loop.
        val justNow = at(2026, 6, 18, 12, 0)
        val fresh = dayInsight(
            forDate = LocalDate.of(2026, 6, 19),
            generatedAt = justNow.minusSeconds(30),
        )
        assertEquals(RENDER, action(fresh, justNow))
    }

    private fun action(insight: Insight, now: Instant): WidgetCacheAction =
        widgetCacheAction(insight, now, morning, tonight, zone)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()

    // generatedAt defaults to the snapshot date's 07:00 — comfortably older than
    // the silent-refresh threshold for any same-or-later test clock, so the age
    // gate doesn't mask the period/date logic under test.
    private fun dayInsight(forDate: LocalDate, generatedAt: Instant = forDate.atTime(7, 0).atZone(zone).toInstant()): Insight =
        insight(ForecastPeriod.TODAY, forDate, generatedAt)

    // overnight = true models the ongoing post-midnight night: its bundle is
    // anchored on today but its derived insight dates to yesterday (see
    // InsightSummary.overnight). The widget recognises that window by the flag,
    // not the date.
    private fun nightInsight(
        forDate: LocalDate,
        generatedAt: Instant = forDate.atTime(19, 0).atZone(zone).toInstant(),
        overnight: Boolean = false,
    ): Insight =
        insight(ForecastPeriod.TONIGHT, forDate, generatedAt, overnight)

    private fun insight(
        period: ForecastPeriod,
        forDate: LocalDate,
        generatedAt: Instant,
        overnight: Boolean = false,
    ): Insight =
        Insight(
            summary = InsightSummary(
                period = period,
                band = BandClause(TemperatureBand.MILD, TemperatureBand.MILD),
                overnight = overnight,
            ),
            recommendedItems = emptyList(),
            generatedAt = generatedAt,
            forDate = forDate,
            period = period,
        )
}
