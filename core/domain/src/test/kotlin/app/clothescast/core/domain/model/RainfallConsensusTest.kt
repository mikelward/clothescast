package app.clothescast.core.domain.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RainfallConsensusTest {

    private val today: LocalDate = LocalDate.of(2026, 5, 13)
    private val tomorrow: LocalDate = today.plusDays(1)

    private fun entry(date: LocalDate, hour: Int, precipMm: Double?) = PerModelHour(
        time = LocalDateTime.of(date, LocalTime.of(hour, 0)),
        apparentTemperatureC = 10.0,
        temperatureC = 11.0,
        precipitationProbabilityPct = 0.0,
        precipitationMm = precipMm,
    )

    @Test
    fun `averages per-model daily totals in millimetres`() {
        // Model A: 1.0 + 0.5 = 1.5 mm. Model B: 4.0 + 1.0 = 5.0 mm. Mean = 3.25 mm.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    entry(today, 10, 1.0),
                    entry(today, 12, 0.5),
                ),
                "gfs_seamless" to listOf(
                    entry(today, 10, 4.0),
                    entry(today, 12, 1.0),
                ),
            ),
        )

        val mm = data.consensusRainfallMmFor(today).shouldNotBeNull()
        mm shouldBe (3.25 plusOrMinus 0.0001)
    }

    @Test
    fun `ignores hours outside the requested date`() {
        // Each model has 2 mm today + 10 mm tomorrow; the request for [today]
        // should only see 2 mm per model, not 12 mm.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    entry(today, 12, 2.0),
                    entry(tomorrow, 12, 10.0),
                ),
                "gfs_seamless" to listOf(
                    entry(today, 12, 2.0),
                    entry(tomorrow, 12, 10.0),
                ),
            ),
        )

        val mm = data.consensusRainfallMmFor(today).shouldNotBeNull()
        mm shouldBe (2.0 plusOrMinus 0.0001)
    }

    @Test
    fun `returns null when fewer than two models reported precipitation`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(entry(today, 12, 2.0)),
                // gfs has only nulls for precipitationMm; it doesn't contribute
                // to the consensus and we fall back to one model = no consensus.
                "gfs_seamless" to listOf(entry(today, 12, null)),
            ),
        )

        data.consensusRainfallMmFor(today).shouldBeNull()
    }

    @Test
    fun `consensusRainfallMm sums across dates without filtering`() {
        // Tonight's slice straddles midnight: 1 mm of late-evening rain on
        // [today] + 3 mm of pre-alarm rain on [tomorrow] per model.
        // consensusRainfallMmFor(today) would only see the 1 mm portion;
        // the window-total variant sees both.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    entry(today, 19, 1.0),
                    entry(tomorrow, 5, 3.0),
                ),
                "gfs_seamless" to listOf(
                    entry(today, 19, 1.0),
                    entry(tomorrow, 5, 3.0),
                ),
            ),
        )

        val dateFiltered = data.consensusRainfallMmFor(today).shouldNotBeNull()
        dateFiltered shouldBe (1.0 plusOrMinus 0.0001)

        val windowed = data.consensusRainfallMm().shouldNotBeNull()
        windowed shouldBe (4.0 plusOrMinus 0.0001)
    }

    @Test
    fun `DST fall-back duplicate timestamps sum within a model, not double-vote the mean`() {
        // On the 25-hour fall-back day Open-Meteo's local-time array repeats
        // a wall-clock hour; both physical hours carry the same LocalDateTime.
        // Each model's duplicates sum into that hour's value (1.0 + 2.0 = 3.0
        // for ecmwf), keeping the physical total, and then the hour's mean is
        // one vote per model: mean(3.0, 1.0) = 2.0 — not a 3-way mean where
        // ecmwf votes twice.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    entry(today, 2, 1.0),
                    entry(today, 2, 2.0),
                ),
                "gfs_seamless" to listOf(
                    entry(today, 2, 1.0),
                ),
            ),
        )

        val mm = data.consensusRainfallMmFor(today).shouldNotBeNull()
        mm shouldBe (2.0 plusOrMinus 0.0001)
    }

    @Test
    fun `a model's missing hour drops out of that hour's mean, not counted as zero`() {
        // Hour 10: mean(1.0, 4.0) = 2.5 mm. Hour 12: ecmwf didn't report, so
        // gfs's 1.0 mm stands alone. Total = 3.5 mm. A per-model-totals mean
        // would give (1.0 + 5.0) / 2 = 3.0 mm — arithmetically the same as
        // counting ecmwf's missing hour as 0 mm, which is exactly the
        // partial-series bias the hour-wise contract exists to avoid.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    entry(today, 10, 1.0),
                    entry(today, 12, null),
                ),
                "gfs_seamless" to listOf(
                    entry(today, 10, 4.0),
                    entry(today, 12, 1.0),
                ),
            ),
        )

        val mm = data.consensusRainfallMmFor(today).shouldNotBeNull()
        mm shouldBe (3.5 plusOrMinus 0.0001)
    }
}
