package app.clothescast.ui.today

import app.clothescast.core.domain.model.HourlyForecast
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Maps a wall-clock time to an x-position on the 0..n-1 hour-index axis
 * the chart uses. Returns null when [now] sits outside the hourly
 * window, so the caller can omit the indicator on future-day pages or
 * before the morning slice begins.
 *
 * Handles the tonight slice (e.g. 21,22,23,0..5) by advancing the date
 * on each hour-of-day backwards step rather than requiring callers to
 * pass the wrap point in. [startDate] is the date of `hourly[0]` — for
 * the TODAY insight that's `insight.forDate`; for TONIGHT it's also
 * `insight.forDate` because the window starts on that date and rolls
 * into the next.
 *
 * Fractional: a 14:30 lookup against an hourly list with hours 14 and
 * 15 returns 0.5 past index 14's position, so the resulting line slides
 * smoothly across the chart as the minute hand moves rather than
 * jumping in hour steps.
 *
 * Inverted by [chartXToTime] in `ChartScrubber.kt` when the user drags
 * on a chart and we need to publish the pointer's wall-clock time back
 * to the shared scrub controller.
 */
internal fun currentTimeChartX(
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    now: LocalDateTime,
): Double? {
    if (hourly.isEmpty()) return null
    val timestamps = ArrayList<LocalDateTime>(hourly.size)
    var date = startDate
    var prevHour: Int? = null
    for (h in hourly) {
        val hour = h.time.hour
        if (prevHour != null && hour < prevHour) date = date.plusDays(1)
        timestamps += LocalDateTime.of(date, h.time)
        prevHour = hour
    }
    // Trailing slack already works via `end = last + 1h`: each sample
    // is treated as a 1-hour bin, so a "now" of 18:30 against a 07..18
    // window maps to chartX 11.5 (inside the last bin). The leading
    // edge gets an explicit half-cell of slack here — Vico's start
    // padding measures at ~half a cell on this card, so a 06:30
    // "now" against a 07..18 window can render in the visual padding
    // (chartX = -0.5).
    val start = timestamps.first().minusMinutes(30)
    val end = timestamps.last().plusHours(1)
    if (now.isBefore(start) || !now.isBefore(end)) return null
    if (now.isBefore(timestamps.first())) {
        // Pre-window: chartX is negative, scaled the same way as the
        // inter-sample fractions below (always over a 1-hour span,
        // since each sample represents one hour).
        val off = Duration.between(timestamps.first(), now).toMillis().toDouble()
        return off / Duration.ofHours(1).toMillis().toDouble()
    }
    for (i in timestamps.indices) {
        val sampleStart = timestamps[i]
        val next = if (i + 1 < timestamps.size) timestamps[i + 1] else end
        if (!now.isBefore(sampleStart) && now.isBefore(next)) {
            val span = Duration.between(sampleStart, next).toMillis().toDouble()
            val off = Duration.between(sampleStart, now).toMillis().toDouble()
            return i + off / span
        }
    }
    return null
}
