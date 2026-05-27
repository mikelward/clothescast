package app.clothescast.location

import app.clothescast.core.data.location.OpenMeteoGeocodingClient
import app.clothescast.core.domain.model.Location
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SuburbCentroidResolver]. Drives the resolver against a
 * stub [OpenMeteoGeocodingClient] backed by a Ktor [MockEngine] that can
 * return different fixtures per query string.
 */
class SuburbCentroidResolverTest {

    /** Inner Melbourne — close enough to Brunswick's centroid to be in-radius. */
    private val brunswickDeviceFix = Location(latitude = -37.77, longitude = 144.96)

    @Test
    fun `nearest in-radius candidate wins from addressDetail query`() = runTest {
        val resolver = resolverWith(
            "Brunswick, VIC 3056, Australia" to results(
                // ~700 m from the device fix — well inside 10 km.
                result("Brunswick", -37.7667, 144.9610, country = "Australia", admin1 = "Victoria", countryCode = "AU"),
                // A far-away namesake, e.g. Brunswick GA: must be filtered out.
                result("Brunswick", 31.15, -81.49, country = "United States", admin1 = "Georgia", countryCode = "US"),
            ),
        )

        val centroid = resolver.resolve(
            deviceFix = brunswickDeviceFix,
            addressDetail = "Brunswick, VIC 3056, Australia",
            city = "Brunswick",
        )

        centroid?.latitude shouldBe -37.77
        centroid?.longitude shouldBe 144.96
        centroid?.displayName shouldBe "Brunswick, Victoria, Australia"
    }

    @Test
    fun `does not broaden to the city query when addressDetail returns no in-radius hit`() = runTest {
        // NYC reproducer: addressDetail "Brooklyn, NY, USA" is the raw locality
        // from the address lines, while `city` is "New York" because
        // CityNamePicker collapses the five boroughs. Open-Meteo doesn't
        // know "Brooklyn, NY, USA" as a single search → no in-radius hit. We
        // must NOT then snap to "New York", whose Manhattan centroid is
        // well within the 10 km radius and would relocate the user across
        // the East River. Resolver should return null; caller falls back
        // to coarsening the raw device fix at 2dp.
        val brooklynDeviceFix = Location(latitude = 40.69, longitude = -73.98)
        val seen = mutableListOf<String>()
        val resolver = resolverWith(
            "Brooklyn, NY, USA" to empty(),
            // Manhattan centroid — inside 10 km of the Brooklyn fix, would
            // otherwise win. Present in the mock to prove we never query it.
            "New York" to results(
                result("New York", 40.7128, -74.0060, country = "United States", admin1 = "New York", countryCode = "US"),
            ),
            seen = seen,
        )

        val centroid = resolver.resolve(
            deviceFix = brooklynDeviceFix,
            addressDetail = "Brooklyn, NY, USA",
            city = "New York",
        )

        centroid.shouldBeNull()
        seen shouldContainExactly listOf("Brooklyn, NY, USA")
    }

    @Test
    fun `uses the city query as a last resort when addressDetail is missing`() = runTest {
        // Some Geocoder backends populate `Address.locality` but return no
        // `getAddressLine` outputs, so `deriveAddressDetail` produces null.
        // In that case `city` is the only signal we have and we should use
        // it — the regression-protection above only fires when an
        // addressDetail was tried and missed.
        val seen = mutableListOf<String>()
        val resolver = resolverWith(
            "Brunswick" to results(
                result("Brunswick", -37.7667, 144.9610, country = "Australia", admin1 = "Victoria", countryCode = "AU"),
            ),
            seen = seen,
        )

        val centroid = resolver.resolve(
            deviceFix = brunswickDeviceFix,
            addressDetail = null,
            city = "Brunswick",
        )

        centroid.shouldNotBeNull()
        seen shouldContainExactly listOf("Brunswick")
    }

    @Test
    fun `returns null when every candidate is outside the radius`() = runTest {
        val resolver = resolverWith(
            "Cambridge" to results(
                // Cambridge UK (52.20, 0.12) and Cambridge MA (42.37, -71.11) —
                // both thousands of km from a Melbourne device fix.
                result("Cambridge", 52.20, 0.12, country = "United Kingdom", admin1 = "England", countryCode = "GB"),
                result("Cambridge", 42.37, -71.11, country = "United States", admin1 = "Massachusetts", countryCode = "US"),
            ),
        )

        val centroid = resolver.resolve(
            deviceFix = brunswickDeviceFix,
            addressDetail = null,
            city = "Cambridge",
        )

        centroid.shouldBeNull()
    }

    @Test
    fun `returns null when both query fields are null or blank without making a request`() = runTest {
        var requested = false
        val resolver = SuburbCentroidResolver(
            OpenMeteoGeocodingClient(
                HttpClient(MockEngine { _ ->
                    requested = true
                    respond(ByteReadChannel("""{"results":[]}"""), HttpStatusCode.OK, jsonHeaders)
                }) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                },
            ),
        )

        resolver.resolve(brunswickDeviceFix, addressDetail = null, city = null).shouldBeNull()
        resolver.resolve(brunswickDeviceFix, addressDetail = "   ", city = "  ").shouldBeNull()

        requested shouldBe false
    }

    @Test
    fun `network failure on the addressDetail query does not broaden to city`() = runTest {
        // Same rationale as the no-in-radius-hit case: a failure on the
        // addressDetail query is a signal to give up, not a signal to
        // broaden. Caller falls back to coarsening the raw device fix.
        val seen = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val query = request.url.parameters["name"].orEmpty()
            seen += query
            respond(ByteReadChannel("server boom"), HttpStatusCode.InternalServerError, jsonHeaders)
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val resolver = SuburbCentroidResolver(OpenMeteoGeocodingClient(client))

        val centroid = resolver.resolve(
            deviceFix = brunswickDeviceFix,
            addressDetail = "Brunswick, VIC 3056, Australia",
            city = "Brunswick",
        )

        centroid.shouldBeNull()
        seen shouldContainExactly listOf("Brunswick, VIC 3056, Australia")
    }

    @Test
    fun `returns null when network fails for every strategy`() = runTest {
        val client = HttpClient(MockEngine { _ ->
            respond(ByteReadChannel("nope"), HttpStatusCode.ServiceUnavailable, jsonHeaders)
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val resolver = SuburbCentroidResolver(OpenMeteoGeocodingClient(client))

        resolver.resolve(
            deviceFix = brunswickDeviceFix,
            addressDetail = "Brunswick, VIC 3056, Australia",
            city = "Brunswick",
        ).shouldBeNull()
    }

    private fun resolverWith(
        vararg responses: Pair<String, String>,
        seen: MutableList<String>? = null,
    ): SuburbCentroidResolver {
        val byQuery = responses.toMap()
        val client = HttpClient(MockEngine { request ->
            val query = request.url.parameters["name"].orEmpty()
            seen?.add(query)
            val body = byQuery[query] ?: empty()
            respond(ByteReadChannel(body), HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return SuburbCentroidResolver(OpenMeteoGeocodingClient(client))
    }

    private fun empty(): String = """{"results":[]}"""

    private fun results(vararg rows: String): String =
        """{"results":[${rows.joinToString(",")}]}"""

    private fun result(
        name: String,
        latitude: Double,
        longitude: Double,
        country: String,
        admin1: String,
        countryCode: String,
    ): String = """
        {
          "id": 0,
          "name": "$name",
          "latitude": $latitude,
          "longitude": $longitude,
          "country_code": "$countryCode",
          "country": "$country",
          "admin1": "$admin1"
        }
    """.trimIndent()

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
}
