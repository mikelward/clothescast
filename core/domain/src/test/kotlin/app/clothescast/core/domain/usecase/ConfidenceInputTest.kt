package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ConfidenceInputTest {
    private val date = LocalDate.of(2026, 6, 29)
    private val window = listOf(10, 11, 12).map { LocalDateTime.of(date, LocalTime.of(it, 0)) }
    private val google = ForecastModel.GOOGLE_WEATHER.openMeteoId

    private fun hour(h: Int, t: Double) = PerModelHour(
        time = LocalDateTime.of(date, LocalTime.of(h, 0)),
        apparentTemperatureC = t,
        temperatureC = t,
        precipitationProbabilityPct = null,
    )

    @Test
    fun `google missing the window start is dropped when two other models remain`() {
        val series = PerModelHourly(
            mapOf(
                "ecmwf_ifs025" to listOf(hour(10, 12.0), hour(11, 14.0), hour(12, 16.0)),
                "gfs_seamless" to listOf(hour(10, 11.0), hour(11, 13.0), hour(12, 15.0)),
                // Front-truncated: starts at 11:00, missing the 10:00 window start.
                google to listOf(hour(11, 20.0), hour(12, 22.0)),
            ),
        )
        val result = series.confidenceInput(window)
        result.byModel shouldNotContainKey google
        result.byModel shouldContainKey "ecmwf_ifs025"
    }

    @Test
    fun `google covering the window start is kept`() {
        val series = PerModelHourly(
            mapOf(
                "ecmwf_ifs025" to listOf(hour(10, 12.0), hour(11, 14.0)),
                google to listOf(hour(10, 20.0), hour(11, 22.0)),
            ),
        )
        series.confidenceInput(window).byModel shouldContainKey google
    }

    @Test
    fun `front-truncated google is kept when it is the only second consulted model`() {
        // Google + one Open-Meteo: dropping front-truncated Google would leave a
        // single consulted model and computeFrom would return null (no chip), so
        // Google is kept despite its partial coverage.
        val series = PerModelHourly(
            mapOf(
                PerModelHourly.BEST_MATCH_MODEL_ID to listOf(hour(10, 11.0), hour(11, 13.0)),
                "ecmwf_ifs025" to listOf(hour(10, 12.0), hour(11, 14.0)),
                google to listOf(hour(11, 20.0)), // missing the 10:00 window start
            ),
        )
        series.confidenceInput(window).byModel shouldContainKey google
    }

    @Test
    fun `with no google the series is unchanged`() {
        val series = PerModelHourly(
            mapOf(
                "ecmwf_ifs025" to listOf(hour(11, 14.0)),
                "gfs_seamless" to listOf(hour(11, 15.0)),
            ),
        )
        series.confidenceInput(window).byModel.keys shouldBe setOf("ecmwf_ifs025", "gfs_seamless")
    }
}
