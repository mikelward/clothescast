package app.clothescast.core.domain.repository

import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.PerModelHourly
import java.time.ZoneId

/**
 * Source of weather data. Implementations are responsible for combining yesterday's
 * actual weather with today's forecast — Open-Meteo's `forecast?past_days=1` returns
 * both in one response, so a single call satisfies this contract.
 *
 * Cross-model confidence ([ForecastBundle.confidence]) is best-effort: implementations
 * may return null when the multi-model fetch fails or isn't supported.
 */
interface WeatherRepository {
    suspend fun fetchForecast(location: Location): ForecastBundle
}

data class ForecastBundle(
    val today: DailyForecast,
    val yesterday: DailyForecast,
    val confidence: ConfidenceInfo? = null,
    /**
     * Per-model hourly apparent-temperature and precipitation-probability
     * series for today, pulled from the same batched multi-model request that
     * feeds [confidence]. Drives the optional "show model spread" overlay on
     * the Today screen's charts. Null when the side-band fetch failed or the
     * upstream omitted the per-model hourly fields entirely; the chart simply
     * doesn't render the overlay in that case.
     */
    val perModelHourly: PerModelHourly? = null,
    /**
     * Tomorrow's hourly entries, when the underlying API response carried them.
     * Used by the tonight insight to wrap from today's evening hours through to
     * tomorrow morning (so "Tonight will be cold to mild" reflects the actual
     * overnight low, and a 06:00 rain hour can drive the umbrella suggestion).
     *
     * Empty when the fetch only covered today (legacy `forecast_days=1` calls,
     * sparse test fixtures). The tonight slice falls back to today-only in that
     * case rather than failing.
     */
    val tomorrowHourly: List<HourlyForecast> = emptyList(),
    /**
     * Tomorrow's full daily aggregates, when available. Used by the tonight
     * insight to derive a next-day outfit preview ("Tomorrow" card) without
     * burning a second forecast call. Null when the response only covered
     * today (legacy `forecast_days=1`, sparse fixtures); the home screen
     * falls back to a single-card layout in that case.
     */
    val tomorrow: DailyForecast? = null,
    /**
     * Days *after* today, in date order — tomorrow + up to five more
     * (six entries with `forecast_days=7`). Drives the Today screen's
     * 7-day pager page. Tomorrow is intentionally duplicated between
     * [tomorrow] and `upcomingDays[0]` so the existing tonight-insight
     * paths can keep reading [tomorrow] unchanged; new consumers that
     * want a single multi-day list read [upcomingDays] instead. Empty
     * on legacy fixtures and on 2-entry responses; the 7-day page
     * collapses or hides itself when the list is empty.
     */
    val upcomingDays: List<DailyForecast> = emptyList(),
    /**
     * IANA zone the upstream forecast is in — Open-Meteo's `timezone=auto`
     * resolves to the forecast location's zone rather than the device's.
     * Propagated up to [app.clothescast.core.domain.model.Insight.forecastZone]
     * so UI surfaces that compare wall-clock hours against `now()` read
     * `now` in the right zone. Null on legacy fixtures / fetch paths that
     * don't surface the field; consumers fall back to the device default.
     */
    val forecastZone: ZoneId? = null,
) {
    init {
        require(yesterday.date.isBefore(today.date)) {
            "yesterday (${yesterday.date}) must be before today (${today.date})"
        }
    }
}
