package app.clothescast.ui.today

import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RangeBandTest {
    private fun hour(time: LocalDateTime, value: Double) =
        PerModelHour(
            time = time,
            apparentTemperatureC = value,
            temperatureC = value,
            precipitationProbabilityPct = null,
        )

    private fun hourlyAt(time: LocalTime) =
        HourlyForecast(
            time = time,
            temperatureC = 0.0,
            feelsLikeC = 0.0,
            precipitationProbabilityPct = 0.0,
            condition = WeatherCondition.UNKNOWN,
        )

    private val startDate = LocalDate.of(2026, 6, 8)
    private val t0 = LocalDateTime.of(2026, 6, 8, 0, 0)
    private val t1 = t0.plusHours(1)
    private val t2 = t0.plusHours(2)
    // The chart's hourly window: t0..t2 at list positions 0..2 — the consensus
    // main line's x grid. Per-model entries are resolved against it by
    // timestamp lookup, exactly as the charts do via [hourlyTimestampIndices].
    private val window = hourlyTimestampIndices(
        hourly = (0..2).map { hourlyAt(LocalTime.of(it, 0)) },
        startDate = startDate,
    )

    @Test
    fun `envelope keys by time, so a model's dropped hour doesn't merge mismatched hours`() {
        val perModel = PerModelHourly(
            byModel = mapOf(
                // Model A carries all three hours.
                "ecmwf_ifs025" to listOf(hour(t0, 10.0), hour(t1, 20.0), hour(t2, 30.0)),
                // Model B dropped the interior hour t1 — its list compacts to [t0, t2].
                "gfs_seamless" to listOf(hour(t0, 12.0), hour(t2, 32.0)),
            ),
        )
        val (minSeries, maxSeries) = perModelEnvelope(perModel, window) { it.apparentTemperatureC }

        // t0 (index 0) has both models; t1 (index 1) has only A, so it's dropped
        // (< 2 models); t2 (index 2) pairs A's 30 with B's 32. Position-keying
        // would instead have paired A's t1 (20) with B's t2 (32) at index 1 —
        // merging mismatched wall-clock hours, which this test guards against.
        minSeries shouldContainExactly listOf(0 to 10.0, 2 to 30.0)
        maxSeries shouldContainExactly listOf(0 to 12.0, 2 to 32.0)
    }

    @Test
    fun `a leading hour every model dropped stays an empty gap, not a left shift`() {
        val perModel = PerModelHourly(
            byModel = mapOf(
                // Both models lack t0 (00:00) — but the window still has it at
                // index 0 (e.g. best_match covered it). The band must leave x=0
                // empty and place 01:00/02:00 at indices 1/2, not slide left.
                "ecmwf_ifs025" to listOf(hour(t1, 20.0), hour(t2, 30.0)),
                "gfs_seamless" to listOf(hour(t1, 22.0), hour(t2, 32.0)),
            ),
        )
        val (minSeries, maxSeries) = perModelEnvelope(perModel, window) { it.apparentTemperatureC }

        minSeries shouldContainExactly listOf(1 to 20.0, 2 to 30.0)
        maxSeries shouldContainExactly listOf(1 to 22.0, 2 to 32.0)
    }

    @Test
    fun `contiguousRuns splits at index gaps so the band doesn't bridge them`() {
        contiguousRuns(listOf(0, 1, 2, 3)) shouldContainExactly listOf(0..3)
        contiguousRuns(listOf(0, 2)) shouldContainExactly listOf(0..0, 1..1)
        contiguousRuns(listOf(0, 1, 3, 4)) shouldContainExactly listOf(0..1, 2..3)
        contiguousRuns(emptyList()) shouldContainExactly emptyList()
    }

    @Test
    fun `best_match is excluded from the envelope`() {
        val perModel = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs025" to listOf(hour(t0, 10.0)),
                "gfs_seamless" to listOf(hour(t0, 20.0)),
                PerModelHourly.BEST_MATCH_MODEL_ID to listOf(hour(t0, 99.0)),
            ),
        )
        val (minSeries, maxSeries) = perModelEnvelope(perModel, window) { it.apparentTemperatureC }

        // best_match's outlier 99 must not widen the band.
        minSeries shouldContainExactly listOf(0 to 10.0)
        maxSeries shouldContainExactly listOf(0 to 20.0)
    }

    @Test
    fun `timestamp indices wrap midnight by advancing the date, not aliasing hours`() {
        // A tonight window: 22:00, 23:00, then 00:00/01:00 the next day. The
        // backwards hour-of-day step (23 -> 0) advances the date, so the
        // post-midnight entries key under June 9 — not a second June 8 00:00.
        val hourly = listOf(22, 23, 0, 1).map { hourlyAt(LocalTime.of(it, 0)) }

        hourlyTimestampIndices(hourly, startDate) shouldContainExactly mapOf(
            LocalDateTime.of(2026, 6, 8, 22, 0) to 0,
            LocalDateTime.of(2026, 6, 8, 23, 0) to 1,
            LocalDateTime.of(2026, 6, 9, 0, 0) to 2,
            LocalDateTime.of(2026, 6, 9, 1, 0) to 3,
        )
    }

    @Test
    fun `a DST spring-forward day keeps band indices aligned with list positions`() {
        // 2026-03-08 springs forward in US zones: the window's wall-clock
        // hours skip 02:00, so the day contributes 23 entries and 03:00 sits
        // at list position 2. Naive hours-since-window-start arithmetic would
        // key 03:00 to index 3 — an hour right of where the position-plotted
        // main line draws it. Walking the actual entries keeps them aligned.
        val springForward = LocalDate.of(2026, 3, 8)
        val hourly = listOf(0, 1, 3, 4).map { hourlyAt(LocalTime.of(it, 0)) }
        val dstWindow = hourlyTimestampIndices(hourly, springForward)
        fun at(h: Int) = LocalDateTime.of(springForward, LocalTime.of(h, 0))

        dstWindow shouldContainExactly mapOf(at(0) to 0, at(1) to 1, at(3) to 2, at(4) to 3)

        // Both models carry the same 23-hour wall-clock series the window has,
        // so the envelope must land on list positions 0..3 — band under line.
        val perModel = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs025" to listOf(0, 1, 3, 4).map { hour(at(it), 10.0 + it) },
                "gfs_seamless" to listOf(0, 1, 3, 4).map { hour(at(it), 20.0 + it) },
            ),
        )
        val (minSeries, maxSeries) = perModelEnvelope(perModel, dstWindow) { it.apparentTemperatureC }

        minSeries shouldContainExactly listOf(0 to 10.0, 1 to 11.0, 2 to 13.0, 3 to 14.0)
        maxSeries shouldContainExactly listOf(0 to 20.0, 1 to 21.0, 2 to 23.0, 3 to 24.0)
    }
}
