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
    fun `consensusSunshineHours sums across dates without filtering`() {
        // Tonight's slice straddles midnight: 30m of late-evening sun on
        // [today] + 45m of pre-alarm sun on [tomorrow] per model.
        // consensusSunshineHoursFor(today) would only see the 30m portion;
        // the window-total variant sees both.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    entry(today, 19, 1800.0),
                    entry(tomorrow, 5, 2700.0),
                ),
                "gfs_seamless" to listOf(
                    entry(today, 19, 1800.0),
                    entry(tomorrow, 5, 2700.0),
                ),
            ),
        )

        val dateFiltered = data.consensusSunshineHoursFor(today).shouldNotBeNull()
        dateFiltered shouldBe (0.5 plusOrMinus 0.0001)

        val windowed = data.consensusSunshineHours().shouldNotBeNull()
        windowed shouldBe (1.25 plusOrMinus 0.0001)
    }

    @Test
    fun `a model's missing hour drops out of that hour's mean, not counted as zero`() {
        // Hour 10: mean(1h, 2h) = 1.5h. Hour 12: ecmwf didn't report, so
        // gfs's 1h stands alone. Total = 2.5h. A per-model-totals mean would
        // give (1h + 3h) / 2 = 2h — arithmetically the same as counting
        // ecmwf's missing hour as zero sunshine, which is exactly the
        // partial-series bias the hour-wise contract exists to avoid.
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
        hours shouldBe (2.5 plusOrMinus 0.0001)
    }

    private fun daylight(date: LocalDate, hour: Int, radiation: Double, sunshineSec: Double = 0.0) = PerModelHour(
        time = LocalDateTime.of(date, LocalTime.of(hour, 0)),
        apparentTemperatureC = 10.0,
        temperatureC = 11.0,
        precipitationProbabilityPct = 0.0,
        shortwaveRadiationWm2 = radiation,
        sunshineDurationSec = sunshineSec,
    )

    @Test
    fun `sunshine fraction is sunny hours over hours with any solar radiation`() {
        // Four daylight hours (radiation > 0, cloudy or not) and two dark ones.
        val data = PerModelHourly(
            byModel = mapOf(
                "a" to listOf(6, 9, 12, 15, 18, 21).map { h -> daylight(today, h, if (h in 9..18) 200.0 else 0.0) },
            ),
        )
        data.sunshineFraction(sunshineHours = 2.0, date = today).shouldNotBeNull() shouldBe (0.5 plusOrMinus 1e-9)
    }

    @Test
    fun `an hour any model sees light counts once as daylight`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "a" to listOf(daylight(today, 9, 100.0), daylight(today, 10, 0.0)),
                // Model b sees light at 10 too, and only reports sunshine there.
                "b" to listOf(daylight(today, 9, 120.0), daylight(today, 10, 0.0, sunshineSec = 600.0)),
            ),
        )
        data.sunshineFraction(sunshineHours = 1.0, date = today).shouldNotBeNull() shouldBe (0.5 plusOrMinus 1e-9)
    }

    @Test
    fun `sunshine fraction is capped at full and ignores other dates`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "a" to listOf(daylight(today, 12, 300.0), daylight(tomorrow, 12, 300.0)),
            ),
        )
        data.sunshineFraction(sunshineHours = 3.0, date = today).shouldNotBeNull() shouldBe (1.0 plusOrMinus 1e-9)
        data.sunshineFraction(sunshineHours = 1.0).shouldNotBeNull() shouldBe (0.5 plusOrMinus 1e-9)
    }

    @Test
    fun `sunshine fraction is null with no daylight in the window`() {
        val data = PerModelHourly(byModel = mapOf("a" to listOf(daylight(today, 23, 0.0))))
        data.sunshineFraction(sunshineHours = 0.0, date = today).shouldBeNull()
    }
}
