package app.clothescast.core.domain.model

/**
 * Which slice of the day the insight is talking about.
 *
 *  - [TODAY]    — the morning forecast that fires at the user's scheduled wake-up time
 *    (default 07:00). Covers the daytime window from the wake-up time onward.
 *  - [TONIGHT]  — the evening forecast that fires at the user's evening time (default
 *    19:00). Spans the overnight window from the evening time today through to the
 *    next morning's wake-up time (default 07:00 next day) — Open-Meteo is fetched
 *    with `forecast_days=2` so the bundle carries tomorrow's pre-dawn hourly slice.
 *    The summary leads with "Tonight will be …" and the clothes / outfit reflect
 *    the actual overnight low.
 */
enum class ForecastPeriod {
    TODAY, TONIGHT;

    /**
     * The other period kind. Used by the Today screen's pager to pick the
     * placeholder copy for page 2 when its cache slot is empty.
     */
    fun opposite(): ForecastPeriod = when (this) {
        TODAY -> TONIGHT
        TONIGHT -> TODAY
    }
}
