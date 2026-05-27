package app.clothescast.core.domain.model

import kotlin.math.round

data class Location(
    val latitude: Double,
    val longitude: Double,
    val displayName: String? = null,
    /**
     * ISO 3166-1 alpha-2 country code (e.g. "AU", "GB") when the location's
     * country is known — populated by reverse geocoding from the platform
     * [android.location.Geocoder] (`Address.countryCode`) and by Open-Meteo
     * forward-geocode results (`GeocodingResult.countryCode`). Null for
     * legacy persisted locations that predate the field; the holiday
     * country filter falls back to locale country in that case.
     */
    val countryCode: String? = null,
    /**
     * The reverse-geocoded street address with the leading "house number /
     * street" component dropped, for display on the Location settings page
     * (e.g. "Cambridge, MA 02139, USA" from "1 Vassar St, Cambridge, MA
     * 02139, USA"). Null when no address line was available or it had no
     * second component. Populated only by the device-location path via
     * `ReverseGeocoder`; manual / forward-geocoded picks leave it null.
     *
     * Privacy: dropping the leading address component is a belt-and-braces
     * step so we never surface a precise-looking street / house number on
     * the Location page, even if the platform Geocoder returned one for
     * the network-provider fix we sent it. The persisted lat/lon next to
     * this string is the Open-Meteo suburb centroid (or a 2dp coarsening
     * of the network-provider fix on round-trip miss) — not the precise
     * device fix that was used to look the address up.
     */
    val addressDetail: String? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }

    /**
     * Returns this location rounded to a ~1 km grid (2 decimal places of
     * latitude / longitude — at the equator, 0.01° ≈ 1.1 km). Applied at
     * the entry points that bring a [Location] into the app at finer
     * precision: [app.clothescast.core.data.location.OpenMeteoGeocodingClient]
     * coarsens forward-geocode results, [app.clothescast.data.SettingsRepository]
     * re-applies on persisted reads as a belt-and-braces for legacy values,
     * and the worker calls this explicitly when the suburb-centroid
     * round-trip can't snap to a known suburb. Downstream consumers —
     * Open-Meteo weather request, bug-report payload, anything that may
     * leave the device — only ever see neighbourhood-level precision.
     *
     * Note: the device fix that
     * [app.clothescast.location.LocationResolver] returns is *not*
     * coarsened by this — the worker pipeline reverse-geocodes the raw
     * network-provider fix (~50–300 m, the precision
     * `ACCESS_COARSE_LOCATION` already gives) so the suburb name comes
     * back correct even when the user is near a boundary. The persisted
     * coord is the suburb centroid that the round-trip returns (already
     * 2dp from `OpenMeteoGeocodingClient`) or this function applied to
     * the raw fix on miss.
     *
     * The point is privacy: a 4-decimal coordinate (~11 m) pinpoints a
     * specific house; 2 decimals doesn't. The forecast doesn't care
     * either — Open-Meteo's grid is several km already, and the caching
     * layer was already bucketing requests at this same granularity.
     */
    fun coarsened(): Location = copy(
        latitude = round(latitude * COARSEN_FACTOR) / COARSEN_FACTOR,
        longitude = round(longitude * COARSEN_FACTOR) / COARSEN_FACTOR,
    )

    private companion object {
        /** 10^2 — keeps two decimal places, dropping anything finer. */
        const val COARSEN_FACTOR: Double = 100.0
    }
}
