package app.clothescast.ui.today

import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for the [currentTimeChartX] helper that maps a wall-clock
 * time to the chart's 0..n-1 hour-index axis. Pins the fractional positioning
 * (now between two hourly entries), the tonight midnight-wrap detection, and
 * the out-of-window null return.
 */
class CurrentTimeIndicatorTest {

    private fun h(time: LocalTime) = HourlyForecast(
        time = time,
        temperatureC = 10.0,
        feelsLikeC = 10.0,
        precipitationProbabilityPct = 0.0,
        condition = WeatherCondition.CLEAR,
    )

    @Test
    fun `today window — fractional position between two hours`() {
        // Daytime slice 06..21, now at 14:30 should land at index 8.5.
        val hourly = (6..21).map { h(LocalTime.of(it, 0)) }
        val x = currentTimeChartX(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            now = LocalDateTime.of(2026, 5, 23, 14, 30),
        )
        x!! shouldBe (8.5 plusOrMinus 1e-6)
    }

    @Test
    fun `today window — on the hour returns integer index`() {
        val hourly = (6..21).map { h(LocalTime.of(it, 0)) }
        val x = currentTimeChartX(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            now = LocalDateTime.of(2026, 5, 23, 6, 0),
        )
        x!! shouldBe (0.0 plusOrMinus 1e-6)
    }

    @Test
    fun `today window — more than one hour before window returns null`() {
        // The valid range extends an hour either side of the data
        // window so the indicator can land in Vico's leading / trailing
        // padding (see the comment in `currentTimeChartX`). Only times
        // past that slack — here 04:59 against a 06..21 window —
        // hide the indicator entirely.
        val hourly = (6..21).map { h(LocalTime.of(it, 0)) }
        val x = currentTimeChartX(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            now = LocalDateTime.of(2026, 5, 23, 4, 59),
        )
        x.shouldBeNull()
    }

    @Test
    fun `today window — within the leading hour returns a small negative`() {
        // 05:30 against a 06..21 window — 30 min before the first
        // data point. The indicator should land midway through the
        // leading padding (chartX = -0.5).
        val hourly = (6..21).map { h(LocalTime.of(it, 0)) }
        val x = currentTimeChartX(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            now = LocalDateTime.of(2026, 5, 23, 5, 30),
        )
        x!! shouldBe (-0.5 plusOrMinus 1e-6)
    }

    @Test
    fun `today window — after window returns null`() {
        val hourly = (6..21).map { h(LocalTime.of(it, 0)) }
        val x = currentTimeChartX(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            now = LocalDateTime.of(2026, 5, 23, 22, 0),
        )
        x.shouldBeNull()
    }

    @Test
    fun `tonight window — wraps midnight, position on next day`() {
        // Tonight slice 21..23 then 0..5. startDate is the bedtime date.
        val hours = listOf(21, 22, 23, 0, 1, 2, 3, 4, 5)
        val hourly = hours.map { h(LocalTime.of(it, 0)) }
        // 03:15 on the next morning sits in the index-6 slot (hour 03) plus
        // a quarter of the way into it.
        val x = currentTimeChartX(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            now = LocalDateTime.of(2026, 5, 24, 3, 15),
        )
        x!! shouldBe (6.25 plusOrMinus 1e-6)
    }

    @Test
    fun `tonight window — pre-midnight on the start date`() {
        val hours = listOf(21, 22, 23, 0, 1, 2, 3, 4, 5)
        val hourly = hours.map { h(LocalTime.of(it, 0)) }
        val x = currentTimeChartX(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            now = LocalDateTime.of(2026, 5, 23, 22, 30),
        )
        x!! shouldBe (1.5 plusOrMinus 1e-6)
    }

    @Test
    fun `tomorrow's forecast — current time before window returns null`() {
        // Future-day insight (forDate is tomorrow). The line should hide.
        val hourly = (6..21).map { h(LocalTime.of(it, 0)) }
        val x = currentTimeChartX(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 24),
            now = LocalDateTime.of(2026, 5, 23, 14, 30),
        )
        x.shouldBeNull()
    }

    @Test
    fun `empty hourly returns null`() {
        val x = currentTimeChartX(
            hourly = emptyList(),
            startDate = LocalDate.of(2026, 5, 23),
            now = LocalDateTime.of(2026, 5, 23, 14, 30),
        )
        x.shouldBeNull()
    }

    @Test
    fun `last hour spans through end of its hour`() {
        // Window ends with 21:00 — now at 21:45 still falls inside the last
        // slot (21:00..22:00), at fractional position 15 + 0.75.
        val hourly = (6..21).map { h(LocalTime.of(it, 0)) }
        val x = currentTimeChartX(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            now = LocalDateTime.of(2026, 5, 23, 21, 45),
        )
        x!! shouldBe (15.75 plusOrMinus 1e-6)
    }
}
