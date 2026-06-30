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
        air: Double = apparent,
    ) = PerModelHour(
        time = LocalDateTime.of(today, LocalTime.of(hour, 0)),
        apparentTemperatureC = apparent,
        temperatureC = air,
        precipitationProbabilityPct = 0.0,
    )

    @Test
    fun `returns null when there is fewer than two models`() {
        val data = PerModelHourly(
            byModel = mapOf("gfs_seamless" to listOf(entry(12, 10.0))),
        )

        ModelDivergenceSummary.computeFrom(data).shouldBeNull()
    }

    @Test
    fun `returns null when both extremes agree within the threshold`() {
        // High spread 0.5 °C, low spread 0.5 °C — both well below
        // MIN_SPREAD_C = 1.5 °C, so there's nothing worth surfacing.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(entry(6, 10.0), entry(14, 20.0)),
                "icon_seamless" to listOf(entry(6, 10.5), entry(14, 20.5)),
            ),
        )

        ModelDivergenceSummary.computeFrom(data).shouldBeNull()
    }

    @Test
    fun `surfaces the high when models split more on the daily high`() {
        // Highs 20 vs 24 → spread 4; lows 10 vs 10.5 → spread 0.5. The high
        // is the wider disagreement, so it's what the summary describes.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(entry(6, 10.0), entry(14, 20.0)),
                "icon_seamless" to listOf(entry(6, 10.5), entry(14, 24.0)),
            ),
        )

        val summary = ModelDivergenceSummary.computeFrom(data).shouldNotBeNull()

        summary.extreme shouldBe ModelDivergenceSummary.Extreme.HIGH
        summary.spreadC shouldBe (4.0 plusOrMinus 0.0001)
        summary.minC shouldBe (20.0 plusOrMinus 0.0001)
        summary.maxC shouldBe (24.0 plusOrMinus 0.0001)
    }

    @Test
    fun `surfaces the low when models split more on the daily low`() {
        // Highs 20 vs 21 → spread 1; lows 8 vs 12 → spread 4. The low wins,
        // and the reported range is the models' lows (8–12), not their highs.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(entry(6, 8.0), entry(14, 20.0)),
                "icon_seamless" to listOf(entry(6, 12.0), entry(14, 21.0)),
            ),
        )

        val summary = ModelDivergenceSummary.computeFrom(data).shouldNotBeNull()

        summary.extreme shouldBe ModelDivergenceSummary.Extreme.LOW
        summary.spreadC shouldBe (4.0 plusOrMinus 0.0001)
        summary.minC shouldBe (8.0 plusOrMinus 0.0001)
        summary.maxC shouldBe (12.0 plusOrMinus 0.0001)
    }

    @Test
    fun `a differently-shaped diurnal curve does not register as disagreement`() {
        // Both models reach a high of 20 and a low of 12, but the second warms
        // up faster — at 12:00 it's already at 20 while the first is still at
        // 16, a 4 °C mid-curve gap. Because the summary keys off the maxima and
        // minima only, that timing artifact is ignored: the highs match, the
        // lows match, so there's nothing to surface. (Hours are 3 h apart, so
        // the spike filter leaves them untouched and can't be what hides it.)
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    entry(9, 12.0), entry(12, 16.0), entry(15, 20.0), entry(18, 14.0),
                ),
                "icon_seamless" to listOf(
                    entry(9, 12.0), entry(12, 20.0), entry(15, 20.0), entry(18, 14.0),
                ),
            ),
        )

        ModelDivergenceSummary.computeFrom(data).shouldBeNull()
    }

    @Test
    fun `a lone spike hour is filtered and cannot fake a high disagreement`() {
        // The second model has a single 16 °C spike at 13:00 between two 10 °C
        // hours; everything else sits at 10 °C. The spike filter (shared with
        // the confidence tier) replaces it with the median of its neighbors, so
        // its true high is 10 °C — matching the first model — and no
        // disagreement is surfaced. Without the filter the spike would read as a
        // 6 °C high spread.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(entry(12, 10.0), entry(13, 10.0), entry(14, 10.0)),
                "icon_seamless" to listOf(entry(12, 10.0), entry(13, 16.0), entry(14, 10.0)),
            ),
        )

        ModelDivergenceSummary.computeFrom(data).shouldBeNull()
    }

    @Test
    fun `includes the best-match overlay in the spread calc`() {
        // best_match is treated as a regular model here, matching the consensus
        // blend's posture: if it's the outlier on the day's extreme the summary
        // surfaces that rather than hiding it. GFS / ICON agree at ~7 °C;
        // best_match is a wild outlier at 20 °C, giving a 13 °C spread.
        val data = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(entry(13, 7.0)),
                "icon_seamless" to listOf(entry(13, 8.0)),
                PerModelHourly.BEST_MATCH_MODEL_ID to listOf(entry(13, 20.0)),
            ),
        )

        val summary = ModelDivergenceSummary.computeFrom(data).shouldNotBeNull()

        summary.spreadC shouldBe (13.0 plusOrMinus 0.0001)
        summary.minC shouldBe (7.0 plusOrMinus 0.0001)
        summary.maxC shouldBe (20.0 plusOrMinus 0.0001)
    }
}
