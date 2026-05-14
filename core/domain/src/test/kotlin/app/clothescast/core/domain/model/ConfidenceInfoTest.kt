package app.clothescast.core.domain.model

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ConfidenceInfoTest {

    private val today: LocalDate = LocalDate.of(2026, 5, 14)

    private fun hour(h: Int, apparent: Double, precip: Double = 0.0) = PerModelHour(
        time = LocalDateTime.of(today, LocalTime.of(h, 0)),
        apparentTemperatureC = apparent,
        temperatureC = apparent,
        precipitationProbabilityPct = precip,
    )

    @Test
    fun `null when fewer than two consulted models have data`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(hour(12, 20.0)),
            ),
        )
        ConfidenceInfo.computeFrom(data).shouldBeNull()
    }

    @Test
    fun `excludes best_match from the consulted set`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(hour(12, 20.0)),
                PerModelHourly.BEST_MATCH_MODEL_ID to listOf(hour(12, 99.0)),
            ),
        )
        // Only one model remains after dropping best_match — not enough
        // to compute a spread.
        ConfidenceInfo.computeFrom(data).shouldBeNull()
    }

    @Test
    fun `tight spread emits HIGH tier`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(hour(12, 20.0, 5.0)),
                "gfs_seamless" to listOf(hour(12, 20.5, 10.0)),
                "icon_seamless" to listOf(hour(12, 19.8, 8.0)),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.level shouldBe ForecastConfidence.HIGH
        info.tempSpreadC shouldBe (0.7 plusOrMinus 0.0001)
        info.precipSpreadPp shouldBe (5.0 plusOrMinus 0.0001)
        info.modelsConsulted shouldContainExactlyInAnyOrder
            listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless")
    }

    @Test
    fun `temp spread alone can drive LOW`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(hour(12, 18.0)),
                "gfs_seamless" to listOf(hour(12, 24.5)),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (6.5 plusOrMinus 0.0001)
    }

    @Test
    fun `precip spread alone can drive LOW`() {
        // Tight temps, wide rain.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(hour(12, 20.0, 10.0)),
                "gfs_seamless" to listOf(hour(12, 20.5, 55.0)),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (0.5 plusOrMinus 0.0001)
        info.precipSpreadPp shouldBe (45.0 plusOrMinus 0.0001)
    }

    @Test
    fun `spread is computed over the hours present, so slicing changes the tier`() {
        // Both models peak around 20°C during the day. ECMWF then sees an
        // unusual 25°C late-evening warm front that GFS doesn't pick up
        // (GFS cools normally to 16°C). Full-day per-model max becomes
        // 25 / 20.5 — a 4.5°C spread, LOW tier. Daytime-only slice drops
        // the 22:00 hour, so per-model max is 20.5 / 20.5 — HIGH tier.
        // Validates the whole point of windowed compute: the chip's
        // title reflects the interval the user is actually shown, not
        // the full calendar day.
        val fullDay = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    hour(12, 20.0), hour(13, 20.5), hour(14, 20.0),
                    hour(22, 25.0), // late-evening outlier
                ),
                "gfs_seamless" to listOf(
                    hour(12, 20.5), hour(13, 20.0), hour(14, 20.5),
                    hour(22, 16.0), // normal evening cooling
                ),
            ),
        )
        val fullDayInfo = ConfidenceInfo.computeFrom(fullDay).shouldNotBeNull()
        fullDayInfo.level shouldBe ForecastConfidence.LOW
        fullDayInfo.tempSpreadC shouldBe (4.5 plusOrMinus 0.0001)

        // Daytime-only slice — drops the 22:00 evening hours.
        val daytime = PerModelHourly(
            byModel = fullDay.byModel.mapValues { (_, hours) ->
                hours.filter { it.time.toLocalTime() != LocalTime.of(22, 0) }
            },
        )
        val daytimeInfo = ConfidenceInfo.computeFrom(daytime).shouldNotBeNull()
        daytimeInfo.level shouldBe ForecastConfidence.HIGH
    }

    @Test
    fun `models with empty hour lists are skipped`() {
        // A model whose slice yields no hours (e.g. dropped during the
        // tonight-window slice when the run started after midnight) is
        // ignored rather than crashing on maxOf over an empty list.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to emptyList(),
                "gfs_seamless" to listOf(hour(12, 20.0)),
                "icon_seamless" to listOf(hour(12, 21.0)),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.tempSpreadC shouldBe (1.0 plusOrMinus 0.0001)
        info.modelsConsulted shouldContainExactlyInAnyOrder
            listOf("gfs_seamless", "icon_seamless")
    }
}
