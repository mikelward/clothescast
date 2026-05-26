package app.clothescast.core.data.location

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class OpenMeteoGeocodingClientTest {
    private fun fixture(): ByteReadChannel {
        val text = checkNotNull(javaClass.getResourceAsStream("/geocoding_london.json")) {
            "fixture missing"
        }.bufferedReader().readText()
        return ByteReadChannel(text)
    }

    private fun mockClient(
        body: ByteReadChannel = fixture(),
        capture: (HttpRequestData) -> Unit = {},
    ): HttpClient {
        val engine = MockEngine { request ->
            capture(request)
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Test
    fun `request hits the geocoding host with name + count + language`() = runTest {
        var captured: HttpRequestData? = null
        val client = OpenMeteoGeocodingClient(mockClient(capture = { captured = it }))

        client.search("London", limit = 5, languageTag = "en-AU")

        val req = checkNotNull(captured)
        req.url.host shouldBe GEOCODING_HOST
        req.url.encodedPath shouldBe "/v1/search"
        req.url.parameters["name"] shouldBe "London"
        req.url.parameters["count"] shouldBe "5"
        req.url.parameters["language"] shouldBe "en-AU"
        req.url.parameters["format"] shouldBe "json"
    }

    @Test
    fun `parses fixture into domain Locations with admin and country in displayName`() = runTest {
        val client = OpenMeteoGeocodingClient(mockClient())

        val results = client.search("London")

        results shouldHaveSize 2
        results[0].displayName shouldBe "London, England, United Kingdom"
        // Fixture lat/lon (51.50853, -0.12574) get rounded to a 2dp / ~1km
        // grid at the geocoder boundary — see Location.coarsened().
        results[0].latitude shouldBe 51.51
        results[0].longitude shouldBe -0.13

        results[1].displayName shouldBe "London, Ontario, Canada"
    }

    @Test
    fun `empty query returns empty list without making the request`() = runTest {
        var requested = false
        val client = OpenMeteoGeocodingClient(mockClient(capture = { requested = true }))

        client.search("   ").shouldBeEmpty()

        requested shouldBe false
    }

    @Test
    fun `null results field maps to empty list`() = runTest {
        val emptyBody = ByteReadChannel("""{"generationtime_ms":0.1}""")
        val client = OpenMeteoGeocodingClient(mockClient(body = emptyBody))

        client.search("Atlantis").shouldBeEmpty()
    }
}
