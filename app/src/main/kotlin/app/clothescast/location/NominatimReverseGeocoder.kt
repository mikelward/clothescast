package app.clothescast.location

import app.clothescast.BuildConfig
import app.clothescast.core.domain.util.coRunCatching
import app.clothescast.diag.DiagLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.URLProtocol
import io.ktor.http.path
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Resolves a `(lat, lon)` pair to a city-friendly name and an ISO 3166-1
 * alpha-2 country code using OpenStreetMap's Nominatim service. `zoom=10`
 * collapses suburb-grained coordinates to the recognisable metro name in
 * `address.city`, so a Sydney suburb resolves to "Sydney" rather than
 * "Balmain" and a Manhattan address to "New York" rather than "Manhattan."
 *
 * A leading "Greater " is stripped from the city — Nominatim returns
 * "Greater London" for London addresses where the colloquial name is plain
 * "London" (the same applies to "Greater Manchester", "Greater Glasgow",
 * etc.). The well-known false positive is the Canadian city of Greater
 * Sudbury, Ontario — accepting that trade for the much-more-common London
 * case.
 *
 * Privacy: every call sends the coordinate off-device to
 * `nominatim.openstreetmap.org` — a separate send from the Open-Meteo
 * forecast call. The `User-Agent` identifies the app + repo URL per
 * Nominatim's usage policy. Call rate (one per Today refresh / daily
 * worker run) is well inside the 1 req/sec policy cap.
 *
 * Returns an [Empty][Result.EMPTY] result on every failure path; callers
 * treat blank fields as "no value available" and surface a date-only
 * header / no-country fallback.
 */
class NominatimReverseGeocoder(
    private val httpClient: HttpClient,
    private val timeoutMillis: Long = 5_000L,
) {
    /**
     * Best-effort city name + country code. Either field can be null
     * independently (e.g. Nominatim returned a usable city but the address
     * lacked a country code); callers handle each as missing data without
     * crashing.
     */
    data class Result(val city: String?, val countryCode: String?) {
        companion object {
            val EMPTY = Result(city = null, countryCode = null)
        }
    }

    suspend fun resolve(latitude: Double, longitude: Double): Result = coRunCatching {
        withTimeoutOrNull(timeoutMillis) { fetch(latitude, longitude) } ?: run {
            DiagLog.w(TAG, "Reverse geocode timed out after ${timeoutMillis}ms.")
            Result.EMPTY
        }
    }
        .onFailure { DiagLog.w(TAG, "Unexpected ${it.javaClass.simpleName} from resolve; returning empty.", it) }
        .getOrDefault(Result.EMPTY)

    private suspend fun fetch(latitude: Double, longitude: Double): Result {
        val response: NominatimResponse = httpClient.get {
            expectSuccess = true
            url {
                protocol = URLProtocol.HTTPS
                host = NOMINATIM_HOST
                path("reverse")
            }
            parameter("lat", "%.4f".format(Locale.US, latitude))
            parameter("lon", "%.4f".format(Locale.US, longitude))
            parameter("zoom", "10")
            parameter("format", "jsonv2")
            parameter("addressdetails", "1")
            header("User-Agent", USER_AGENT)
        }.body()
        return toResult(response)
    }

    @Serializable
    internal data class NominatimResponse(
        val address: NominatimAddress? = null,
    )

    @Serializable
    internal data class NominatimAddress(
        val city: String? = null,
        val town: String? = null,
        val village: String? = null,
        val municipality: String? = null,
        @SerialName("country_code") val countryCode: String? = null,
    )

    companion object {
        private const val TAG = "NominatimReverseGeocoder"
        private const val NOMINATIM_HOST = "nominatim.openstreetmap.org"
        private val USER_AGENT =
            "ClothesCast/${BuildConfig.VERSION_NAME} (https://github.com/mikelward/clothescast)"

        /**
         * Pure transform from a Nominatim response to a [Result]. Extracted so
         * the city-fallback chain and "Greater " strip can be unit-tested on
         * a JVM without spinning up an HTTP mock.
         */
        internal fun toResult(response: NominatimResponse): Result {
            val address = response.address
            val raw = address?.city?.takeIf { it.isNotBlank() }
                ?: address?.town?.takeIf { it.isNotBlank() }
                ?: address?.village?.takeIf { it.isNotBlank() }
                ?: address?.municipality?.takeIf { it.isNotBlank() }
            val city = raw?.removePrefix("Greater ")?.takeIf { it.isNotBlank() }
            val cc = address?.countryCode?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
            return Result(city = city, countryCode = cc)
        }
    }
}
