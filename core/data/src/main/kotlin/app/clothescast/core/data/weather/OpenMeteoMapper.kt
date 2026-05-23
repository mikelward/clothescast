package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.repository.ForecastBundle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Maps an [OpenMeteoResponse] (queried with past_days=1&forecast_days=2) into a
 * [ForecastBundle]. Index 0 in the daily arrays is yesterday, index 1 is today,
 * index 2 (when present) is tomorrow. Tomorrow's daily aggregates flow through on
 * [ForecastBundle.tomorrow] (so the side-by-side outfit row can render the
 * tomorrow-morning suggestion); tomorrow's hourly entries flow through on
 * [ForecastBundle.tomorrowHourly] so the tonight insight can wrap from 19:00 today
 * into tomorrow's pre-dawn morning.
 *
 * The hourly stream covers all three days. We split it by date: today's hours
 * attach to the today forecast, tomorrow's hours attach to the tomorrow forecast
 * (and are also exposed via `tomorrowHourly` for the tonight slice). Yesterday's
 * hourlies attach to the yesterday forecast so the delta clause can slice both
 * sides to the same daytime window for a symmetric comparison.
 *
 * Tolerates 2-entry responses (forecast_days=1) for backwards compatibility with
 * older fixtures and any caller that downgrades the request — tomorrow comes
 * through null + empty hourly in that case.
 */
internal object OpenMeteoMapper {
    fun toBundle(response: OpenMeteoResponse): ForecastBundle {
        require(response.daily.time.size >= 2) {
            "Open-Meteo response must include 2 daily entries (yesterday + today); got ${response.daily.time.size}"
        }

        val yesterdayDate = LocalDate.parse(response.daily.time[0])
        val todayDate = LocalDate.parse(response.daily.time[1])

        val yesterdayHourly = response.hourly.forDate(yesterdayDate)
        val yesterday = response.daily.toForecast(index = 0, date = yesterdayDate, hourly = yesterdayHourly)
        val todayHourly = response.hourly.forDate(todayDate)
        val today = response.daily.toForecast(index = 1, date = todayDate, hourly = todayHourly)

        val (tomorrowHourly, tomorrow) = if (response.daily.time.size >= 3) {
            val tomorrowDate = LocalDate.parse(response.daily.time[2])
            val hourly = response.hourly.forDate(tomorrowDate)
            hourly to response.daily.toForecast(index = 2, date = tomorrowDate, hourly = hourly)
        } else {
            emptyList<HourlyForecast>() to null
        }

        return ForecastBundle(
            today = today,
            yesterday = yesterday,
            tomorrowHourly = tomorrowHourly,
            tomorrow = tomorrow,
            // Open-Meteo's `timezone=auto` echoes the resolved IANA zone in the
            // response (`America/Los_Angeles`, `Europe/London`, …). Parse
            // defensively — an unparseable / `"GMT+1"`-style value collapses to
            // null so consumers fall back to the device default rather than
            // throwing on a perfectly-good forecast payload.
            forecastZone = runCatching { ZoneId.of(response.timezone) }.getOrNull(),
        )
    }

    // getOrNull on every parallel array so a daily payload with mismatched lengths
    // (Open-Meteo always sends matching arrays, but defensive against transient
    // bugs / proxies / future field-by-field rollout) degrades to the same defaults
    // the per-element nullables already use, instead of throwing.
    private fun DailyData.toForecast(index: Int, date: LocalDate, hourly: List<HourlyForecast>): DailyForecast {
        val rawMin = temperatureMin.getOrNull(index) ?: 0.0
        val rawMax = temperatureMax.getOrNull(index) ?: 0.0
        return DailyForecast(
            date = date,
            temperatureMinC = rawMin,
            temperatureMaxC = rawMax,
            // Fall back to raw temp if Open-Meteo didn't provide apparent — better than 0 °C.
            feelsLikeMinC = feelsLikeMin.getOrNull(index) ?: rawMin,
            feelsLikeMaxC = feelsLikeMax.getOrNull(index) ?: rawMax,
            precipitationProbabilityMaxPct = (precipitationProbabilityMax.getOrNull(index) ?: 0).toDouble(),
            precipitationMmTotal = precipitationSum.getOrNull(index) ?: 0.0,
            condition = WmoCodeMapper.map(weatherCode.getOrNull(index)),
            hourly = hourly,
        )
    }

    private fun HourlyData.forDate(date: LocalDate): List<HourlyForecast> {
        val out = ArrayList<HourlyForecast>(24)
        for (i in time.indices) {
            val ts = LocalDateTime.parse(time[i])
            if (ts.toLocalDate() != date) continue
            val raw = temperature.getOrNull(i) ?: 0.0
            out += HourlyForecast(
                time = LocalTime.of(ts.hour, ts.minute),
                temperatureC = raw,
                feelsLikeC = feelsLike.getOrNull(i) ?: raw,
                precipitationProbabilityPct = (precipitationProbability.getOrNull(i) ?: 0).toDouble(),
                condition = WmoCodeMapper.map(weatherCode.getOrNull(i)),
            )
        }
        return out
    }
}
