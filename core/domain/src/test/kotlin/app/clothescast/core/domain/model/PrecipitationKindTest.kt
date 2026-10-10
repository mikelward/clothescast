package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalTime

class PrecipitationKindTest {

    private fun hour(h: Int, pct: Double, condition: WeatherCondition, mm: Double = 0.0) = HourlyForecast(
        time = LocalTime.of(h, 0),
        temperatureC = 0.0,
        feelsLikeC = -2.0,
        precipitationProbabilityPct = pct,
        condition = condition,
        precipitationMm = mm,
    )

    private val byChance: (HourlyForecast) -> Boolean = { it.precipitationProbabilityPct >= 5.0 }
    private val byAmount: (HourlyForecast) -> Boolean = { it.precipitationMm > 0.0 }

    @Test
    fun `a dry window keeps the rain labels`() {
        precipitationKind(listOf(hour(9, 0.0, WeatherCondition.CLEAR)), byChance) shouldBe PrecipitationKind.RAIN
        precipitationKind(emptyList(), byChance) shouldBe PrecipitationKind.RAIN
    }

    @Test
    fun `snow-only wet hours read as snow`() {
        precipitationKind(
            listOf(
                hour(9, 60.0, WeatherCondition.SNOW),
                hour(10, 0.0, WeatherCondition.CLOUDY),
            ),
            byChance,
        ) shouldBe PrecipitationKind.SNOW
    }

    @Test
    fun `snow alongside rain reads as mixed`() {
        precipitationKind(
            listOf(
                hour(9, 60.0, WeatherCondition.SNOW),
                hour(15, 40.0, WeatherCondition.RAIN),
            ),
            byChance,
        ) shouldBe PrecipitationKind.MIXED
    }

    @Test
    fun `a snow code on an hour the test calls dry does not relabel a rainy window`() {
        precipitationKind(
            listOf(
                hour(9, 2.0, WeatherCondition.SNOW, mm = 0.3),
                hour(15, 70.0, WeatherCondition.RAIN),
            ),
            byChance,
        ) shouldBe PrecipitationKind.RAIN
    }

    @Test
    fun `each caller's wet test decides which hours count`() {
        val hours = listOf(hour(9, 2.0, WeatherCondition.SNOW, mm = 0.4))
        precipitationKind(hours, byChance) shouldBe PrecipitationKind.RAIN
        precipitationKind(hours, byAmount) shouldBe PrecipitationKind.SNOW
    }
}
