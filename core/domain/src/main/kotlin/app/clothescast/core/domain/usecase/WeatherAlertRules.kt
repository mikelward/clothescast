package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.AlertHazards
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.GeoPoint
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.model.WeatherCondition
import java.time.Duration
import java.time.Instant

/** An alert that applies here, and whether it may post a notification. */
data class RelevantAlert(
    val alert: WeatherAlert,
    /**
     * False when the area is too broad to be about this place
     * ([WeatherAlertRules.MAX_NOTIFY_SPAN_KM]): such an alert still shows on
     * Today, with the neutral icon, but never notifies.
     */
    val notifiable: Boolean,
) {
    /** The level the app draws: the alert's own, or neutral when the area is too broad. */
    val level: AlertLevel get() = if (notifiable) alert.level else AlertLevel.INFO
}

/**
 * Which alerts apply to the user's location, and when to check again
 * (docs/ALERTS.md → Location, Cadence). An alert for somewhere 50 miles away is
 * wrong, not just noisy, so these rules drop anything they can't tie to the
 * point.
 */
object WeatherAlertRules {
    /**
     * Slack around the boundary for the 2-decimal rounding of the user's
     * coordinate (about 1 km).
     */
    const val CONTAINMENT_TOLERANCE_KM = 1.0

    /**
     * Wider than this and an area can't be about one place: Japan's typhoon
     * alerts spanned about 2,900 km (docs/ALERTS.md → Coverage). Met Office
     * warnings, a few hundred km across, stay well inside.
     */
    const val MAX_NOTIFY_SPAN_KM = 1_000.0

    /** The background check's resting cadence. */
    val BASE_CHECK_INTERVAL: Duration = Duration.ofHours(3)

    /** The cadence while the forecast looks severe or an alert is active. */
    val ELEVATED_CHECK_INTERVAL: Duration = Duration.ofHours(1)

    /**
     * The alerts that apply at [point] at [now], most severe first, then
     * soonest to end: unexpired, about the weather, with an area that contains
     * the point (within [CONTAINMENT_TOLERANCE_KM]). An alert without an area
     * can't be checked and is dropped.
     */
    fun relevant(alerts: List<WeatherAlert>, point: GeoPoint, now: Instant): List<RelevantAlert> =
        alerts
            .asSequence()
            .filter { it.endTime == null || it.endTime.isAfter(now) }
            .filterNot { AlertHazards.isExcluded(it.eventType) }
            .mapNotNull { alert ->
                val area = alert.area ?: return@mapNotNull null
                val covers = area.contains(point) ||
                    area.distanceToBoundaryKm(point) <= CONTAINMENT_TOLERANCE_KM
                if (!covers) return@mapNotNull null
                RelevantAlert(alert, notifiable = area.spanKm() <= MAX_NOTIFY_SPAN_KM)
            }
            .distinctBy { it.alert.id }
            .sortedWith(
                compareByDescending<RelevantAlert> { it.level }
                    .thenBy { it.alert.endTime ?: Instant.MAX },
            )
            .toList()

    /**
     * Whether the coming hours look like warning weather: gale-force wind,
     * heavy rain, snow, thunderstorms, or extreme feels-like temperatures.
     * Warnings mostly follow the forecast, so the background check runs hourly
     * while this holds and every 3 hours otherwise.
     */
    fun forecastLooksSevere(hours: List<HourlyForecast>): Boolean {
        if (hours.isEmpty()) return false
        val heavyHour = hours.any { it.precipitationMm >= HEAVY_RAIN_MM_PER_HOUR }
        val heavyTotal = hours.sumOf { it.precipitationMm } >= HEAVY_RAIN_MM_TOTAL
        val gale = hours.any { (it.windSpeedKmh ?: 0.0) >= GALE_KMH }
        val stormOrSnow = hours.any {
            it.condition == WeatherCondition.THUNDERSTORM || it.condition == WeatherCondition.SNOW
        }
        val extremeFeel = hours.any { it.feelsLikeC >= EXTREME_HEAT_C || it.feelsLikeC <= EXTREME_COLD_C }
        return heavyHour || heavyTotal || gale || stormOrSnow || extremeFeel
    }

    /**
     * Whether the background check is due: [BASE_CHECK_INTERVAL] after the
     * last one, or [ELEVATED_CHECK_INTERVAL] while [elevated] (the forecast
     * looks severe or an alert is active). Never checked → due.
     */
    fun checkDue(lastCheck: Instant?, now: Instant, elevated: Boolean): Boolean {
        if (lastCheck == null) return true
        val interval = if (elevated) ELEVATED_CHECK_INTERVAL else BASE_CHECK_INTERVAL
        // A clock set backwards (lastCheck in the future) counts as due rather
        // than silencing the check until the clock catches up.
        return lastCheck.isAfter(now) || Duration.between(lastCheck, now) >= interval
    }

    // Thresholds for "looks severe" (autopilot, TODO.md → Decisions needing
    // review): Beaufort 7 (near gale) sustained, a heavy-rain hour, a wet day,
    // and feels-like extremes.
    private const val GALE_KMH = 50.0
    private const val HEAVY_RAIN_MM_PER_HOUR = 10.0
    private const val HEAVY_RAIN_MM_TOTAL = 30.0
    private const val EXTREME_HEAT_C = 35.0
    private const val EXTREME_COLD_C = -15.0
}
