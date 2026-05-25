package app.clothescast.core.data.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the Open-Meteo `forecast` endpoint with `past_days=1&forecast_days=1`.
 * Daily index 0 is yesterday, index 1 is today. Hourly spans both days.
 *
 * Open-Meteo emits null inside the array when a value is unavailable for a given hour;
 * the parser must tolerate this.
 */
@Serializable
internal data class OpenMeteoResponse(
    @SerialName("timezone") val timezone: String,
    @SerialName("daily") val daily: DailyData,
    @SerialName("hourly") val hourly: HourlyData,
)

@Serializable
internal data class DailyData(
    @SerialName("time") val time: List<String>,
    @SerialName("temperature_2m_min") val temperatureMin: List<Double?>,
    @SerialName("temperature_2m_max") val temperatureMax: List<Double?>,
    @SerialName("apparent_temperature_min") val feelsLikeMin: List<Double?>,
    @SerialName("apparent_temperature_max") val feelsLikeMax: List<Double?>,
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int?>,
    @SerialName("precipitation_sum") val precipitationSum: List<Double?>,
    @SerialName("weather_code") val weatherCode: List<Int?>,
)

@Serializable
internal data class HourlyData(
    @SerialName("time") val time: List<String>,
    @SerialName("temperature_2m") val temperature: List<Double?>,
    @SerialName("apparent_temperature") val feelsLike: List<Double?>,
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?>,
    @SerialName("weather_code") val weatherCode: List<Int?>,
    @SerialName("precipitation") val precipitation: List<Double?> = emptyList(),
)
