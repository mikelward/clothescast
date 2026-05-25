package app.clothescast.ui.today

import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.jupiter.api.Test

/**
 * Pins the [chartXToTime] clamp: a tap inside the *trailing* unit of
 * x-axis space (past the rightmost data point's index) resolves to a
 * fractional time inside the last hour rather than snapping back to
 * the data point's start. This is the inverse of the visual extension
 * the charts apply when they set `xMax = hourly.size` — without this
 * clamp, scrubbing right at 06:40 on a TONIGHT chart whose last data
 * point is 06:00 would jump back to 06:00.
 */
class ChartScrubberTest {

    private fun h(hour: Int) = HourlyForecast(
        time = LocalTime.of(hour, 0),
        temperatureC = 10.0,
        feelsLikeC = 10.0,
        precipitationProbabilityPct = 0.0,
        condition = WeatherCondition.CLEAR,
    )

    @Test
    fun `scrub at the trailing edge resolves to half an hour past the last data point`() {
        // Tonight slice 21..06 (last data point 06:00 next day). The
        // clamp gives half a cell of trailing slack — taps at or past
        // the visible right edge land 30 min past the last data point.
        val hours = listOf(21, 22, 23, 0, 1, 2, 3, 4, 5, 6)
        val hourly = hours.map { h(LocalTime.of(it, 0).hour) }
        val time = chartXToTime(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            chartX = hourly.lastIndex + 0.5,
        )
        time.shouldNotBeNull()
        val expected = LocalDateTime.of(2026, 5, 24, 6, 30, 0)
        val diff = Duration.between(expected, time).abs()
        (diff <= Duration.ofSeconds(1)) shouldBe true
    }

    @Test
    fun `scrub past the trailing slack clamps to the half-hour boundary`() {
        // Out-of-grid drags (chartX beyond the half-cell trailing
        // slack) clamp to last + 30 min — they don't run off into
        // arbitrary times.
        val hourly = (7..21).map { h(it) }
        val time = chartXToTime(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            chartX = hourly.size.toDouble() + 5.0,
        )
        time.shouldNotBeNull()
        val expected = LocalDateTime.of(2026, 5, 23, 21, 30, 0)
        val diff = Duration.between(expected, time).abs()
        (diff <= Duration.ofSeconds(1)) shouldBe true
    }

    @Test
    fun `scrub at an integer index inside the window still returns that hour exactly`() {
        // Pre-existing contract: tapping right on a data point's index
        // returns the on-the-hour time. Make sure the trailing-bin
        // clamp didn't shift integer mappings.
        val hourly = (7..21).map { h(it) }
        val time = chartXToTime(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            chartX = 0.0,
        )
        time shouldBe LocalDateTime.of(2026, 5, 23, 7, 0)
    }

    @Test
    fun `scrub before the first hour resolves into the leading slack`() {
        // Pre-period scrub: tap in Vico's leading padding (chartX
        // negative) maps to a time a few minutes before the first
        // data point. Mirrors the trailing-bin case at the other end.
        val hourly = (7..21).map { h(it) }
        val time = chartXToTime(
            hourly = hourly,
            startDate = LocalDate.of(2026, 5, 23),
            chartX = -0.5,
        )
        time.shouldNotBeNull()
        val expected = LocalDateTime.of(2026, 5, 23, 6, 30, 0)
        val diff = Duration.between(expected, time).abs()
        (diff <= Duration.ofSeconds(1)) shouldBe true
    }

    @Test
    fun `empty hourly returns null`() {
        val time = chartXToTime(
            hourly = emptyList(),
            startDate = LocalDate.of(2026, 5, 23),
            chartX = 0.0,
        )
        time.shouldBeNull()
    }
}
