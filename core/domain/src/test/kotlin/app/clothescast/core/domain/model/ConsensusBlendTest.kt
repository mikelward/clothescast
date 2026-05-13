package app.clothescast.core.domain.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalTime

class ConsensusBlendTest {

    private fun hour(
        h: Int,
        temp: Double,
        feels: Double = temp - 1.0,
        precip: Double = 0.0,
    ) = HourlyForecast(
        time = LocalTime.of(h, 0),
        temperatureC = temp,
        feelsLikeC = feels,
        precipitationProbabilityPct = precip,
        condition = WeatherCondition.CLEAR,
    )

    private fun perModel(
        h: Int,
        apparent: Double,
        air: Double,
        precip: Double = 0.0,
    ) = PerModelHour(
        time = LocalTime.of(h, 0),
        apparentTemperatureC = apparent,
        temperatureC = air,
        precipitationProbabilityPct = precip,
    )

    @Test
    fun `returns null when per-model data is null`() {
        val best = listOf(hour(12, temp = 10.0))

        blendConsensusHourly(best, perModel = null).shouldBeNull()
    }

    @Test
    fun `returns null when fewer than two models reported`() {
        // Only one model present — single-model "consensus" is meaningless,
        // fall back to best_match.
        val perModel = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(perModel(12, apparent = 14.0, air = 15.0, precip = 80.0)),
            ),
        )

        blendConsensusHourly(listOf(hour(12, temp = 10.0)), perModel).shouldBeNull()
    }

    @Test
    fun `includes the best-match overlay in the consensus mean`() {
        // best_match is one of the models in [byModel] and gets folded into
        // the mean alongside the consulted models. The user picked this
        // posture deliberately: Open-Meteo's auto-pick is presumably
        // location-tuned and excluding it would dilute that signal, even at
        // the cost of implicitly double-weighting whichever underlying
        // model best_match resolved to.
        val best = listOf(hour(12, temp = 10.0, feels = 9.0, precip = 30.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(perModel(12, apparent = 14.0, air = 15.0, precip = 80.0)),
                PerModelHourly.BEST_MATCH_MODEL_ID to listOf(
                    perModel(12, apparent = 9.0, air = 10.0, precip = 30.0),
                ),
            ),
        )

        val blended = blendConsensusHourly(best, perModel).shouldNotBeNull()

        blended[0].temperatureC shouldBe (12.5 plusOrMinus 0.0001) // (15 + 10) / 2
        blended[0].feelsLikeC shouldBe (11.5 plusOrMinus 0.0001)   // (14 + 9) / 2
        blended[0].precipitationProbabilityPct shouldBe (55.0 plusOrMinus 0.0001) // (80 + 30) / 2
    }

    @Test
    fun `averages all models when two or more report a given hour`() {
        // Today's pattern: best_match predicts 30% rain at 12:00 but two
        // consulted models both predict 80%+. The consensus should beat the
        // outlier even with best_match included in the mean.
        val best = listOf(hour(12, temp = 10.0, feels = 9.0, precip = 30.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(perModel(12, apparent = 11.0, air = 12.0, precip = 80.0)),
                "icon_seamless" to listOf(perModel(12, apparent = 13.0, air = 14.0, precip = 90.0)),
            ),
        )

        val blended = blendConsensusHourly(best, perModel).shouldNotBeNull()

        blended.size shouldBe 1
        blended[0].time shouldBe LocalTime.of(12, 0)
        blended[0].temperatureC shouldBe (13.0 plusOrMinus 0.0001)  // (12 + 14) / 2
        blended[0].feelsLikeC shouldBe (12.0 plusOrMinus 0.0001)    // (11 + 13) / 2
        blended[0].precipitationProbabilityPct shouldBe (85.0 plusOrMinus 0.0001) // (80 + 90) / 2
        // Condition stays from best_match for now (modal aggregation is a follow-up).
        blended[0].condition shouldBe WeatherCondition.CLEAR
    }

    @Test
    fun `falls back to best-match for an hour only one model covered`() {
        // 12:00 has two models → averaged.
        // 15:00 has just one model → keeps best_match.
        val best = listOf(
            hour(12, temp = 10.0, feels = 9.0, precip = 30.0),
            hour(15, temp = 11.0, feels = 10.0, precip = 40.0),
        )
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    perModel(12, apparent = 11.0, air = 12.0, precip = 80.0),
                    perModel(15, apparent = 14.0, air = 15.0, precip = 90.0),
                ),
                "icon_seamless" to listOf(
                    perModel(12, apparent = 13.0, air = 14.0, precip = 90.0),
                    // 15:00 missing from icon.
                ),
            ),
        )

        val blended = blendConsensusHourly(best, perModel).shouldNotBeNull()

        blended[0].temperatureC shouldBe (13.0 plusOrMinus 0.0001)  // consensus
        blended[1] shouldBe best[1]                                   // fell back
    }

    @Test
    fun `returns null when no hour ended up blended`() {
        // Two consulted models present but covering different hours — no
        // single hour has 2+ models. Should signal "nothing blended" so the
        // caller leaves the upstream daily aggregates untouched.
        val best = listOf(
            hour(12, temp = 10.0, feels = 9.0, precip = 30.0),
            hour(15, temp = 11.0, feels = 10.0, precip = 40.0),
        )
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    perModel(12, apparent = 11.0, air = 12.0, precip = 80.0),
                    // 15:00 missing.
                ),
                "icon_seamless" to listOf(
                    // 12:00 missing.
                    perModel(15, apparent = 13.0, air = 14.0, precip = 90.0),
                ),
            ),
        )

        blendConsensusHourly(best, perModel).shouldBeNull()
    }

    @Test
    fun `withAggregatesFrom recomputes daily extremes from blended hourly`() {
        val daily = DailyForecast(
            date = java.time.LocalDate.of(2026, 5, 13),
            temperatureMinC = 5.0,
            temperatureMaxC = 15.0,
            feelsLikeMinC = 3.0,
            feelsLikeMaxC = 12.0,
            precipitationProbabilityMaxPct = 30.0,
            precipitationMmTotal = 2.0,
            condition = WeatherCondition.CLEAR,
        )
        val blended = listOf(
            hour(12, temp = 12.0, feels = 10.0, precip = 80.0),
            hour(15, temp = 14.0, feels = 12.0, precip = 90.0),
            hour(18, temp = 10.0, feels = 8.0, precip = 60.0),
        )

        val out = daily.withAggregatesFrom(blended)

        out.temperatureMinC shouldBe (10.0 plusOrMinus 0.0001)
        out.temperatureMaxC shouldBe (14.0 plusOrMinus 0.0001)
        out.feelsLikeMinC shouldBe (8.0 plusOrMinus 0.0001)
        out.feelsLikeMaxC shouldBe (12.0 plusOrMinus 0.0001)
        // The peak rain probability now reflects the consensus — the daily
        // umbrella rule keys off this, so without recomputation the chart
        // would show "100% rain" while the umbrella stayed unrecommended.
        out.precipitationProbabilityMaxPct shouldBe (90.0 plusOrMinus 0.0001)
        // Precip mm and condition stay from best_match (no consensus available).
        out.precipitationMmTotal shouldBe daily.precipitationMmTotal
        out.condition shouldBe daily.condition
    }

    @Test
    fun `withAggregatesFrom leaves daily unchanged when blended hourly is empty`() {
        val daily = DailyForecast(
            date = java.time.LocalDate.of(2026, 5, 13),
            temperatureMinC = 5.0,
            temperatureMaxC = 15.0,
            feelsLikeMinC = 3.0,
            feelsLikeMaxC = 12.0,
            precipitationProbabilityMaxPct = 30.0,
            precipitationMmTotal = 2.0,
            condition = WeatherCondition.CLEAR,
        )

        daily.withAggregatesFrom(emptyList()) shouldBe daily
    }
}
