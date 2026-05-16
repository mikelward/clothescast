package app.clothescast.core.domain.util

import app.clothescast.core.domain.model.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance in metres between two [Location]s using the Haversine
 * formula. Correct everywhere on the WGS-84 spheroid (the small-angle
 * equirectangular approximation in FetchAndNotifyWorker breaks down near the
 * poles and across the antimeridian; this one doesn't).
 *
 * Earth radius is the mean spherical radius (6,371,000 m). Accurate to within
 * ~0.5% for any pair of points — well inside the precision floor of the
 * coarse network-provider fixes we feed it from `LocationResolver`.
 */
fun Location.distanceMetersTo(other: Location): Double {
    val lat1 = latitude.toRadians()
    val lat2 = other.latitude.toRadians()
    val dLat = (other.latitude - latitude).toRadians()
    val dLon = (other.longitude - longitude).toRadians()
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}

/** True when [other] is within [meters] of this location, by Haversine distance. */
fun Location.isWithin(meters: Double, of: Location): Boolean =
    distanceMetersTo(of) <= meters

private const val EARTH_RADIUS_METERS = 6_371_000.0

private fun Double.toRadians(): Double = this * Math.PI / 180.0
