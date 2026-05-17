package app.clothescast.core.data.location

import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.diag.ApiEndpoints
import app.clothescast.core.data.diag.NoOpApiCallLogger
import app.clothescast.core.data.diag.instrument
import app.clothescast.core.domain.model.Location
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.URLProtocol
import io.ktor.http.path

internal const val GEOCODING_HOST = "geocoding-api.open-meteo.com"

/**
 * Resolves a free-text place name to one or more candidate [Location]s. Free, key-less
 * Open-Meteo service, separate host from the forecast API.
 *
 * Returns at most [limit] candidates ordered by Open-Meteo's relevance ranking. The
 * displayName is built as "<name>, <admin1>, <country>" with missing parts elided so
 * a city like "London" surfaces as "London, England, United Kingdom" while a less
 * granular match shows just what's available.
 */
class OpenMeteoGeocodingClient(
    private val httpClient: HttpClient,
    private val apiCallLogger: ApiCallLogger = NoOpApiCallLogger,
) {
    suspend fun search(query: String, limit: Int = 5, languageTag: String = "en"): List<Location> {
        if (query.isBlank()) return emptyList()

        val response: OpenMeteoGeocodingResponse = apiCallLogger.instrument(ApiEndpoints.OPEN_METEO_GEOCODING) {
            httpClient.get {
                expectSuccess = true
                url {
                    protocol = URLProtocol.HTTPS
                    host = GEOCODING_HOST
                    path("v1", "search")
                }
                parameter("name", query.trim())
                parameter("count", limit)
                parameter("language", languageTag)
                parameter("format", "json")
            }.body()
        }

        return response.results.orEmpty().map { it.toDomain() }
    }

    private fun GeocodingResult.toDomain(): Location {
        val displayName = listOfNotNull(name, admin1, country).joinToString(", ")
        return Location(
            latitude = latitude,
            longitude = longitude,
            displayName = displayName,
            countryCode = countryCode?.takeIf { it.isNotBlank() }?.uppercase(),
        )
    }
}
