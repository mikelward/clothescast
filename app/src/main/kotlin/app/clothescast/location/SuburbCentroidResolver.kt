package app.clothescast.location

import app.clothescast.core.data.location.OpenMeteoGeocodingClient
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.util.coRunCatching
import app.clothescast.core.domain.util.distanceMetersTo
import app.clothescast.diag.DiagLog

/**
 * Resolves a device fix to the *centroid of its suburb*. The persisted
 * location's lat/lon then comes from Open-Meteo's canonical coord for
 * the suburb (a stable, recognisable point that lands in the middle of
 * the place the user thinks of as "here") rather than from coarsening
 * the device fix to a 2dp / ~1 km grid (where the snapped point can
 * land anywhere in the cell — often outside the recognisable suburb).
 *
 * Strategy:
 *
 *  1. If an `addressDetail` like "Brunswick, VIC 3056, Australia" is
 *     available, search Open-Meteo for it and pick the candidate
 *     nearest to [deviceFix] within [maxRadiusMeters].
 *  2. Only when `addressDetail` was *unavailable* (geocoder returned
 *     a locality but no address lines), fall back to the bare `city`
 *     name. We deliberately skip this fallback when an addressDetail
 *     was tried and missed: `city` comes from [pickCityName], which
 *     intentionally collapses regions to a broader name (NYC's five
 *     boroughs all return "New York"; Cairo governorate suffixes are
 *     stripped). For a Brooklyn user that would let "New York"'s
 *     Manhattan centroid score within the 10 km radius and silently
 *     relocate the persisted location across the East River. The
 *     addressDetail query, in contrast, sees the raw locality token
 *     ("Brooklyn, NY 11201, USA"), so a missed addressDetail is a
 *     signal Open-Meteo doesn't know the suburb — not a signal to
 *     broaden the search.
 *  3. On any failure — neither field available, network error, no
 *     candidate within the radius — return null. Callers fall back
 *     to coarsening [deviceFix] to the 2dp privacy grid.
 *
 * Privacy: only the search query (`addressDetail` / `city`) and the
 * returned suburb centroid (re-coarsened by [OpenMeteoGeocodingClient])
 * cross the device boundary from this class. [deviceFix] itself is the
 * input to the distance filter only — it's not transmitted.
 */
class SuburbCentroidResolver(
    private val geocodingClient: OpenMeteoGeocodingClient,
    private val maxRadiusMeters: Double = DEFAULT_RADIUS_METERS,
) {
    /**
     * Returns the nearest suburb-centroid `Location` to [deviceFix], or
     * null when no usable candidate is within [maxRadiusMeters] (or when
     * neither query field is available, or the geocoding call fails).
     *
     * The returned Location carries only the centroid lat/lon (and the
     * client's coarsening). Display name / country code / address detail
     * are the caller's responsibility — those come from the reverse-geocode
     * pass that produced [addressDetail] and [city].
     */
    suspend fun resolve(
        deviceFix: Location,
        addressDetail: String?,
        city: String?,
    ): Location? {
        val detail = addressDetail?.takeIf { it.isNotBlank() }
        val cityName = city?.takeIf { it.isNotBlank() }
        if (detail == null && cityName == null) return null

        if (detail != null) {
            // addressDetail tried and missed → don't broaden to `city`;
            // see the class doc for the NYC-borough rationale.
            return nearest(deviceFix, detail)
        }
        return nearest(deviceFix, cityName!!)
    }

    private suspend fun nearest(reference: Location, query: String): Location? {
        val candidates = coRunCatching { geocodingClient.search(query, limit = SEARCH_LIMIT) }
            .onFailure { DiagLog.w(TAG, "Forward geocode for \"$query\" failed: ${it.javaClass.simpleName}", it) }
            .getOrNull()
            ?: return null
        if (candidates.isEmpty()) {
            DiagLog.v(TAG, "Forward geocode for \"$query\" returned no candidates.")
            return null
        }
        val inRadius = candidates
            .map { it to it.distanceMetersTo(reference) }
            .filter { (_, distance) -> distance <= maxRadiusMeters }
            .minByOrNull { (_, distance) -> distance }
        if (inRadius == null) {
            val nearestMeters = candidates.minOf { it.distanceMetersTo(reference) }
            DiagLog.i(
                TAG,
                "Forward geocode for \"$query\" had ${candidates.size} candidates, " +
                    "nearest ${nearestMeters.toLong()}m away (> ${maxRadiusMeters.toLong()}m); ignoring.",
            )
            return null
        }
        return inRadius.first
    }

    companion object {
        private const val TAG = "SuburbCentroidResolver"
        private const val SEARCH_LIMIT = 5

        /**
         * 10 km is roomy enough to cover sprawling outer suburbs (Berwick,
         * outer Melbourne, the further reaches of Greater London) without
         * letting a "Cambridge" query in Boston snap to Cambridge, UK.
         */
        const val DEFAULT_RADIUS_METERS: Double = 10_000.0
    }
}
