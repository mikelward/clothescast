package app.clothescast.alerts

import app.clothescast.core.data.weather.AlertsLookup
import app.clothescast.core.data.weather.GoogleWeatherAccess
import app.clothescast.core.data.weather.GoogleWeatherAlertsClient
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.GeoPoint
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.usecase.WeatherAlertRules
import app.clothescast.data.AlertsCheckOutcome
import app.clothescast.data.WeatherAlertsState
import app.clothescast.data.WeatherAlertsStore
import app.clothescast.diag.DiagLog
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.round

/**
 * Runs the alerts check for the forecast's location and keeps the store in
 * step with it (docs/ALERTS.md → Location, Cadence). It never touches the
 * shade: [WeatherAlertShade] settles that from the store, run through
 * [onChanged] after every write.
 *
 * - Alerts off: everything is forgotten.
 * - A different place from the last check: the old place's alerts go first.
 * - A failed check keeps what was known (alerts past their end time drop out
 *   on their own) and is recorded as failed, never as all clear.
 */
class WeatherAlertsRefresher(
    private val store: WeatherAlertsStore,
    private val client: GoogleWeatherAlertsClient,
    /** The route to Google right now: own key, else the proxy while signed in, else null. */
    private val access: suspend () -> GoogleWeatherAccess?,
    private val clock: Clock = Clock.systemUTC(),
    private val locale: () -> Locale = { Locale.getDefault() },
    /** Runs after every stored change: the shade and the widgets follow the store. */
    private val onChanged: suspend () -> Unit = {},
    /**
     * Whether alerts are on right now, read again once a lookup returns: the
     * switch can go off while one is in flight, and its result must not be
     * stored after [turnedOff] has cleared up.
     */
    private val stillEnabled: suspend () -> Boolean = { true },
) {
    // One check or clean-up at a time, so a lookup that returns after the
    // switch went off can't write back what [turnedOff] just cleared.
    private val mutex = Mutex()

    /** Why a check runs: the on-screen rule or the background cadence. */
    enum class Trigger { ON_SCREEN, BACKGROUND }

    /**
     * Checks alerts for [location] if one is due for [trigger], given the
     * stored state and [forecast] (the hours ahead, for the adaptive cadence).
     * Returns true when a lookup ran.
     */
    suspend fun refreshIfDue(
        prefs: UserPreferences,
        location: Location?,
        forecast: List<HourlyForecast>,
        trigger: Trigger,
    ): Boolean = mutex.withLock { refreshLocked(prefs, location, forecast, trigger) }

    private suspend fun refreshLocked(
        prefs: UserPreferences,
        location: Location?,
        forecast: List<HourlyForecast>,
        trigger: Trigger,
    ): Boolean {
        if (!prefs.weatherAlerts) {
            clear()
            return false
        }
        val point = location?.let(::alertPoint) ?: return false
        val state = store.current()
        val now = clock.instant()
        val samePlace = state.point == point
        // A check that found no route costs no network to repeat, and the
        // user may have signed in since.
        val due = !samePlace || state.outcome == AlertsCheckOutcome.NEVER ||
            state.outcome == AlertsCheckOutcome.NO_ACCESS || when (trigger) {
            // The forecast's own on-screen rule: an hour.
            Trigger.ON_SCREEN -> state.checkedAt == null ||
                Duration.between(state.checkedAt, now) >= ON_SCREEN_MAX_AGE ||
                state.checkedAt.isAfter(now)
            Trigger.BACKGROUND -> WeatherAlertRules.checkDue(
                lastCheck = state.checkedAt,
                now = now,
                elevated = WeatherAlertRules.forecastLooksSevere(forecast) || state.active(now).isNotEmpty(),
            )
        }
        if (!due) return false
        if (refresh(location, point, state, now)) onChanged()
        return true
    }

    /** Returns false when the result was dropped because alerts went off meanwhile. */
    private suspend fun refresh(
        location: Location,
        point: GeoPoint,
        before: WeatherAlertsState,
        now: Instant,
    ): Boolean {
        // A new place: the old place's alerts (and what was notified of
        // them) no longer apply here.
        val start = if (before.point != null && before.point != point) {
            WeatherAlertsState(point = point)
        } else {
            before.copy(point = point)
        }
        val route = access()
        if (route == null) {
            store.update { WeatherAlertsState(checkedAt = now, outcome = AlertsCheckOutcome.NO_ACCESS, point = point) }
            DiagLog.i(TAG, "Alerts check skipped: no route to Google (signed out, no key).")
            return true
        }
        val alertsLocation = location.copy(latitude = point.latitude, longitude = point.longitude)
        val result = client.lookup(alertsLocation, route, locale().toLanguageTag())
        if (!stillEnabled()) {
            // Turned off mid-lookup: the clean-up that follows owns the store.
            DiagLog.i(TAG, "Alerts turned off during a check; dropping its result.")
            return false
        }
        // Built on the store as it is now, not as it was before the lookup:
        // the shade may have recorded notifications meanwhile (an alert
        // starting, a permission granted), and they carry over for the same
        // place. The shade settles the rest.
        fun samePlaceOr(current: WeatherAlertsState) = if (current.point == point) current else start
        when (result) {
            is AlertsLookup.Found -> {
                val relevant = WeatherAlertRules.relevant(result.alerts, point, now)
                val ids = relevant.map { it.alert.id }.toSet()
                store.update { current ->
                    val kept = samePlaceOr(current)
                    kept.copy(
                        checkedAt = now,
                        outcome = AlertsCheckOutcome.OK,
                        alerts = relevant,
                        // A dismissal outlives no alert it was for.
                        dismissed = kept.dismissed.filterKeys { it in ids },
                    )
                }
                DiagLog.i(TAG, "Alerts check: %s returned, %s apply here.", result.alerts.size, relevant.size)
            }
            AlertsLookup.Unsupported -> store.update {
                WeatherAlertsState(checkedAt = now, outcome = AlertsCheckOutcome.UNSUPPORTED, point = point)
            }
            is AlertsLookup.Failed -> {
                // Keep what was known; it ages out by end time on its own.
                store.update { current -> samePlaceOr(current).copy(checkedAt = now, outcome = AlertsCheckOutcome.FAILED) }
                DiagLog.w(TAG, "Alerts check failed (HTTP %s); keeping the last known alerts.", result.httpStatus)
            }
        }
        return true
    }

    /** Alerts turned off: forget them, after any check in flight. */
    suspend fun turnedOff() {
        mutex.withLock { clear() }
    }

    private suspend fun clear() {
        if (store.current() == WeatherAlertsState()) return
        store.clear()
        onChanged()
    }

    companion object {
        private const val TAG = "WeatherAlerts"

        /** Today never shows alerts staler than the forecast's own hour. */
        val ON_SCREEN_MAX_AGE: Duration = Duration.ofHours(1)

        /**
         * The point alerts are checked for: the forecast's location rounded to
         * 2 decimals (about 1 km), the precision the forecast already sends.
         */
        fun alertPoint(location: Location): GeoPoint =
            GeoPoint(round(location.latitude * 100) / 100, round(location.longitude * 100) / 100)

        /** The hours ahead in [snapshot] (today, then tomorrow) for the adaptive cadence. */
        fun hoursAhead(snapshot: ForecastSnapshot?): List<HourlyForecast> =
            snapshot?.bundle?.let { it.today.hourly + (it.tomorrow?.hourly ?: it.tomorrowHourly) }.orEmpty()
    }
}
