package app.clothescast.core.domain.model

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
}
