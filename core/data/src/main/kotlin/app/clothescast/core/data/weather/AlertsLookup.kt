package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.WeatherAlert

/** What an alerts lookup for one location found (docs/ALERTS.md). */
sealed interface AlertsLookup {
    /** The lookup worked; [alerts] may be empty (no alerts here right now). */
    data class Found(val alerts: List<WeatherAlert>) : AlertsLookup

    /**
     * No source has alerts for this location at all: a coverage gap, not
     * "no alerts", so the app must not read it as all clear.
     */
    data object Unsupported : AlertsLookup

    /** The lookup failed; [httpStatus] carries the HTTP code when there was one. */
    data class Failed(val httpStatus: Int?) : AlertsLookup
}
