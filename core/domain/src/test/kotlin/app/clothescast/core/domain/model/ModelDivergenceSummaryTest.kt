package app.clothescast.core.domain.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ModelDivergenceSummaryTest {

    private val today: LocalDate = LocalDate.of(2026, 5, 13)

    private fun entry(
        hour: Int,
        apparent: Double,
        air: Double,
        precip: Double = 0.0,
        wind: Double? = null,
        humidity: Double? = null,
        cloud: Double? = null,
    ) = PerModelHour(
        time = LocalDateTime.of(today, LocalTime.of(hour, 0)),
        apparentTemperatureC = apparent,
        temperatureC = air,
        precipitationProbabilityPct = precip,
        windSpeedKmh = wind,
        relativeHumidityPct = humidity,
        cloudCoverPct = cloud,
    )

    @Test
    fun `returns null when there is fewer than two models`() {
        val data = PerModelHourly(
            byModel = mapOf("gfs_seamless" to listOf(entry(12, 10.0, 11.0))),
        )

        ModelDivergenceSummary.computeFrom(data).shouldBeNull()
    }

    @Test
    fun `returns null when the peak feels-like spread is below the threshold`() {
        // 0.5 °C spread is well below MIN_FEELS_LIKE_SPREAD_C = 1.5 °C.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(entry(12, 10.0, 11.0, cloud = 20.0)),
                "icon_seamless" to listOf(entry(12, 10.5, 11.0, cloud = 80.0)),
            ),
        )

        ModelDivergenceSummary.computeFrom(data).shouldBeNull()
    }

    @Test
    fun `picks the peak feels-like-spread hour across the day`() {
        // At 12:00 the spread is 1.0 °C; at 15:00 it's 4.0 °C — the larger
        // hour should win regardless of the order the entries appear in.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    entry(12, 10.0, 11.0, cloud = 30.0),
                    entry(15, 12.0, 13.0, cloud = 40.0),
                ),
                "icon_seamless" to listOf(
                    entry(12, 11.0, 12.0, cloud = 35.0),
                    entry(15, 16.0, 17.0, cloud = 85.0),
                ),
            ),
        )

        val summary = ModelDivergenceSummary.computeFrom(data).shouldNotBeNull()

        summary.peakHour shouldBe LocalTime.of(15, 0)
        summary.feelsLikeSpreadC shouldBe (4.0 plusOrMinus 0.0001)
    }

    @Test
    fun `picks the factor with the widest normalised spread`() {
        // At the peak hour:
        //   air-temp spread = 1 °C   →  normalised 1.0 / 1.5  = 0.67
        //   wind  spread    = 6 km/h →  normalised 6.0 / 10.0 = 0.6
        //   cloud spread    = 50 pp  →  normalised 50  / 30   = 1.67  <-- wins
        //   humidity spread = 10 pp  →  normalised 10  / 20   = 0.5
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    entry(13, 7.0, 9.0, wind = 5.0, humidity = 70.0, cloud = 20.0),
                ),
                "icon_seamless" to listOf(
                    entry(13, 10.0, 10.0, wind = 11.0, humidity = 80.0, cloud = 70.0),
                ),
            ),
        )

        val summary = ModelDivergenceSummary.computeFrom(data).shouldNotBeNull()

        summary.topFactor shouldBe ModelDivergenceSummary.Factor.CLOUD_COVER
        summary.topFactorMin shouldBe (20.0 plusOrMinus 0.0001)
        summary.topFactorMax shouldBe (70.0 plusOrMinus 0.0001)
    }

    @Test
    fun `excludes a factor reported by only one model`() {
        // Cloud cover would otherwise dominate, but only one model reported
        // it — a single-model "spread" is meaningless. Wind has two models
        // and should win instead.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    entry(13, 7.0, 9.0, wind = 5.0, cloud = 10.0),
                ),
                "icon_seamless" to listOf(
                    entry(13, 10.0, 10.0, wind = 25.0, cloud = null),
                ),
            ),
        )

        val summary = ModelDivergenceSummary.computeFrom(data).shouldNotBeNull()

        summary.topFactor shouldBe ModelDivergenceSummary.Factor.WIND_SPEED
        summary.topFactorMin shouldBe (5.0 plusOrMinus 0.0001)
        summary.topFactorMax shouldBe (25.0 plusOrMinus 0.0001)
    }

    @Test
    fun `still returns a summary when only air temperature is available`() {
        // Wind / cloud / humidity all null — the temp comparison still
        // works because it's a required field on PerModelHour.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(entry(13, 7.0, 9.0)),
                "icon_seamless" to listOf(entry(13, 10.0, 12.5)),
            ),
        )

        val summary = ModelDivergenceSummary.computeFrom(data).shouldNotBeNull()

        summary.topFactor shouldBe ModelDivergenceSummary.Factor.AIR_TEMPERATURE
        summary.peakHour shouldBe LocalTime.of(13, 0)
    }

    @Test
    fun `includes the best-match overlay in the spread calc`() {
        // best_match is treated as a regular model here, matching the
        // consensus blend's posture: if it's the outlier on a given hour
        // the divergence summary surfaces that disagreement rather than
        // hiding it. GFS / ICON agree at ~7 °C feels-like; best_match is
        // a wild outlier at 20 °C; including all three gives a 13 °C
        // spread which clears the MIN_FEELS_LIKE_SPREAD_C threshold.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(entry(13, 7.0, 9.0)),
                "icon_seamless" to listOf(entry(13, 8.0, 10.0)),
                PerModelHourly.BEST_MATCH_MODEL_ID to listOf(entry(13, 20.0, 22.0)),
            ),
        )

        val summary = ModelDivergenceSummary.computeFrom(data).shouldNotBeNull()

        summary.feelsLikeSpreadC shouldBe (13.0 plusOrMinus 0.0001)
    }

    @Test
    fun `returns null when every available factor has zero spread`() {
        // Feels-like spread of 2.0 °C clears the threshold, but air
        // temperature is the only available factor and both models report
        // the same value — picking it would produce a misleading
        // "mostly air temperature, 11–11 °C" attribution.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(entry(13, 9.0, 11.0)),
                "icon_seamless" to listOf(entry(13, 11.0, 11.0)),
            ),
        )

        ModelDivergenceSummary.computeFrom(data).shouldBeNull()
    }

    @Test
    fun `skips zero-spread factors in favour of a non-zero one`() {
        // Air temp agrees exactly across models, but wind disagrees —
        // we should attribute the feels-like spread to wind rather than
        // pick the alphabetically-first zero-spread factor.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(entry(13, 7.0, 10.0, wind = 5.0)),
                "icon_seamless" to listOf(entry(13, 10.0, 10.0, wind = 25.0)),
            ),
        )

        val summary = ModelDivergenceSummary.computeFrom(data).shouldNotBeNull()

        summary.topFactor shouldBe ModelDivergenceSummary.Factor.WIND_SPEED
        summary.topFactorMin shouldBe (5.0 plusOrMinus 0.0001)
        summary.topFactorMax shouldBe (25.0 plusOrMinus 0.0001)
    }

    @Test
    fun `ignores hours that only one model reported`() {
        // 12:00 has a 5 °C spread but only one model is present — skipped.
        // 15:00 has a 2 °C spread across both — this is the real peak.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    entry(12, 5.0, 7.0),
                    entry(15, 11.0, 13.0),
                ),
                "icon_seamless" to listOf(
                    entry(15, 13.0, 15.0),
                ),
            ),
        )

        val summary = ModelDivergenceSummary.computeFrom(data).shouldNotBeNull()

        summary.peakHour shouldBe LocalTime.of(15, 0)
        summary.feelsLikeSpreadC shouldBe (2.0 plusOrMinus 0.0001)
    }
}
