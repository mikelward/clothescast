package app.clothescast.core.domain.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SunshineConsensusTest {

    private val today: LocalDate = LocalDate.of(2026, 5, 13)
    private val tomorrow: LocalDate = today.plusDays(1)

    private fun entry(date: LocalDate, hour: Int, sunshineSec: Double?) = PerModelHour(
        time = LocalDateTime.of(date, LocalTime.of(hour, 0)),
        apparentTemperatureC = 10.0,
        temperatureC = 11.0,
        precipitationProbabilityPct = 0.0,
        sunshineDurationSec = sunshineSec,
    )

    @Test
    fun `averages per-model daily totals into hours`() {
        // Model A: 1h + 0.5h = 1.5h.  Model B: 2h + 1h = 3h.  Mean = 2.25h.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    entry(today, 10, 3600.0),
                    entry(today, 12, 1800.0),
                ),
                "gfs_seamless" to listOf(
                    entry(today, 10, 7200.0),
                    entry(today, 12, 3600.0),
                ),
            ),
        )

        val hours = data.consensusSunshineHoursFor(today).shouldNotBeNull()
        hours shouldBe (2.25 plusOrMinus 0.0001)
    }

    @Test
    fun `ignores hours outside the requested date`() {
        // Each model has 1h today + 5h tomorrow; the request for [today]
        // should only see 1h per model, not 6h.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    entry(today, 12, 3600.0),
                    entry(tomorrow, 12, 18_000.0),
                ),
                "gfs_seamless" to listOf(
                    entry(today, 12, 3600.0),
                    entry(tomorrow, 12, 18_000.0),
                ),
            ),
        )

        val hours = data.consensusSunshineHoursFor(today).shouldNotBeNull()
        hours shouldBe (1.0 plusOrMinus 0.0001)
    }

    @Test
    fun `returns null when fewer than two models reported sunshine`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(entry(today, 12, 3600.0)),
                // gfs has only nulls for sunshine; it doesn't contribute to
                // the consensus and we fall back to one model = no consensus.
                "gfs_seamless" to listOf(entry(today, 12, null)),
            ),
        )

        data.consensusSunshineHoursFor(today).shouldBeNull()
    }

    @Test
    fun `partial null hours within a model still count toward its total`() {
        // ecmwf has 1h + null = 1h total; gfs has 2h + 1h = 3h. Mean = 2h.
        // A partial-null hour shouldn't drag the model's total to zero or
        // disqualify it from the consensus.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    entry(today, 10, 3600.0),
                    entry(today, 12, null),
                ),
                "gfs_seamless" to listOf(
                    entry(today, 10, 7200.0),
                    entry(today, 12, 3600.0),
                ),
            ),
        )

        val hours = data.consensusSunshineHoursFor(today).shouldNotBeNull()
        hours shouldBe (2.0 plusOrMinus 0.0001)
    }
}
