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
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }

    /**
     * Returns this location rounded to a ~1 km grid (2 decimal places of
     * latitude / longitude — at the equator, 0.01° ≈ 1.1 km). Applied at
     * every entry point that brings a [Location] into the app
     * ([app.clothescast.location.LocationResolver] device fix,
     * [app.clothescast.core.data.location.OpenMeteoGeocodingClient]
     * forward geocode, [app.clothescast.data.SettingsRepository] persisted
     * read) so downstream consumers — including the Open-Meteo weather
     * request, the bug report payload, and anything else that may leave
     * the device — only ever see neighbourhood-level precision.
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
