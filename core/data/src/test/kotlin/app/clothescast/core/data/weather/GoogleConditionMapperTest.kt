package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GoogleConditionMapperTest {
    @Test
    fun `clear and cloudy families map to their buckets`() {
        GoogleConditionMapper.map("CLEAR") shouldBe WeatherCondition.CLEAR
        GoogleConditionMapper.map("MOSTLY_CLEAR") shouldBe WeatherCondition.CLEAR
        GoogleConditionMapper.map("PARTLY_CLOUDY") shouldBe WeatherCondition.PARTLY_CLOUDY
        GoogleConditionMapper.map("MOSTLY_CLOUDY") shouldBe WeatherCondition.CLOUDY
        GoogleConditionMapper.map("CLOUDY") shouldBe WeatherCondition.CLOUDY
    }

    @Test
    fun `rain family maps to RAIN`() {
        listOf(
            "RAIN",
            "LIGHT_RAIN",
            "HEAVY_RAIN",
            "LIGHT_RAIN_SHOWERS",
            "RAIN_SHOWERS",
            "SCATTERED_SHOWERS",
            "CHANCE_OF_SHOWERS",
            "LIGHT_TO_MODERATE_RAIN",
            "MODERATE_TO_HEAVY_RAIN",
            "RAIN_PERIODICALLY_HEAVY",
            "WIND_AND_RAIN",
        ).forEach { GoogleConditionMapper.map(it) shouldBe WeatherCondition.RAIN }
    }

    @Test
    fun `snow family and rain-and-snow map to SNOW so the frozen gate fires`() {
        listOf(
            "SNOW",
            "LIGHT_SNOW",
            "HEAVY_SNOW",
            "SNOW_SHOWERS",
            "BLOWING_SNOW",
            "SNOWSTORM",
            "RAIN_AND_SNOW",
        ).forEach { GoogleConditionMapper.map(it) shouldBe WeatherCondition.SNOW }
    }

    @Test
    fun `thunderstorm and hail families map to THUNDERSTORM`() {
        listOf(
            "THUNDERSTORM",
            "THUNDERSHOWER",
            "SCATTERED_THUNDERSTORMS",
            "HEAVY_THUNDERSTORM",
            "LIGHT_THUNDERSTORM_RAIN",
            "HAIL",
            "HAIL_SHOWERS",
        ).forEach { GoogleConditionMapper.map(it) shouldBe WeatherCondition.THUNDERSTORM }
    }

    @Test
    fun `windy, unspecified, unknown, and null map to UNKNOWN`() {
        GoogleConditionMapper.map("WINDY") shouldBe WeatherCondition.UNKNOWN
        GoogleConditionMapper.map("TYPE_UNSPECIFIED") shouldBe WeatherCondition.UNKNOWN
        GoogleConditionMapper.map("SOME_FUTURE_TYPE") shouldBe WeatherCondition.UNKNOWN
        GoogleConditionMapper.map(null) shouldBe WeatherCondition.UNKNOWN
    }
}
