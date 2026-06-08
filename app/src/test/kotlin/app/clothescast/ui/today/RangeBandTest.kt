package app.clothescast.ui.today

import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class RangeBandTest {
    private fun hour(time: LocalDateTime, value: Double) =
        PerModelHour(
            time = time,
            apparentTemperatureC = value,
            temperatureC = value,
            precipitationProbabilityPct = null,
        )

    private val t0 = LocalDateTime.of(2026, 6, 8, 0, 0)
    private val t1 = t0.plusHours(1)
    private val t2 = t0.plusHours(2)
    // The chart's hourly window — the canonical x grid the consensus main line
    // is plotted against. Matched by time-of-day (date-agnostic).
    private val window = listOf(t0, t1, t2).map { it.toLocalTime() }

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
}
