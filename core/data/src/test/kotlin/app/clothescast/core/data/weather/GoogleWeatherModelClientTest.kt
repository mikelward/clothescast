package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
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
import java.time.LocalDateTime

class GoogleWeatherModelClientTest {
    private val london = Location(latitude = 51.5074, longitude = -0.1278, displayName = "London")

    private val sampleJson = """
        {
          "forecastHours": [
            {
              "displayDateTime": {"year":2026,"month":6,"day":29,"hours":11,"minutes":0,"utcOffset":"3600s"},
              "weatherCondition": {"type":"RAIN"},
              "temperature": {"degrees":17.5,"unit":"CELSIUS"},
              "feelsLikeTemperature": {"degrees":16.0,"unit":"CELSIUS"},
              "relativeHumidity": 72,
              "uvIndex": 2,
              "precipitation": {"probability":{"percent":65,"type":"RAIN"},"qpf":{"quantity":1.2,"unit":"MILLIMETERS"}},
              "wind": {"speed":{"value":14.0,"unit":"KILOMETERS_PER_HOUR"}},
              "cloudCover": 90
            },
            {
              "displayDateTime": {"year":2026,"month":6,"day":29,"hours":12,"minutes":0,"utcOffset":"3600s"},
              "weatherCondition": {"type":"CLOUDY"},
              "temperature": {"degrees":18.0},
              "precipitation": {"probability":{"percent":20}},
              "wind": {"speed":{"value":12.0}}
            }
          ],
          "timeZone": {"id":"Europe/London"}
        }
    """.trimIndent()

    private fun client(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = sampleJson,
        captureRequest: (HttpRequestData) -> Unit = {},
    ): GoogleWeatherModelClient {
        val engine = MockEngine { request ->
            captureRequest(request)
            if (status == HttpStatusCode.OK) {
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respondError(status)
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return GoogleWeatherModelClient(http)
    }

    /** A single forecast page carrying one hour at [hour] and an optional [nextPageToken]. */
    private fun page(hour: Int, nextPageToken: String? = null): String {
        val token = nextPageToken?.let { ""","nextPageToken":"$it"""" } ?: ""
        return """
            {
              "forecastHours": [
                {
                  "displayDateTime": {"year":2026,"month":6,"day":29,"hours":$hour,"minutes":0},
                  "temperature": {"degrees":15.0}
                }
              ]$token
            }
        """.trimIndent()
    }

    /**
     * Serves [pages] in order, one per request (capturing each request), then —
     * once the list is exhausted — replays the last page forever. Replaying a page
     * that still carries a token lets a test exercise the MAX_PAGES backstop.
     */
    private fun pagingClient(
        pages: List<String>,
        captured: MutableList<HttpRequestData> = mutableListOf(),
        failOnRequest: Int? = null,
    ): GoogleWeatherModelClient {
        val engine = MockEngine { request ->
            val index = captured.size
            captured.add(request)
            if (failOnRequest != null && index + 1 == failOnRequest) {
                respondError(HttpStatusCode.InternalServerError)
            } else {
                respond(
                    content = ByteReadChannel(pages[index.coerceAtMost(pages.lastIndex)]),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return GoogleWeatherModelClient(http)
    }

    @Test
    fun `maps the forecast hours into per-model hours`() = runTest {
        val result = checkNotNull(client().fetchHourly(london, "AIza-test-key"))

        result.size shouldBe 2

        val first = result[0]
        first.time shouldBe LocalDateTime.of(2026, 6, 29, 11, 0)
        first.temperatureC shouldBe 17.5
        first.apparentTemperatureC shouldBe 16.0
        first.precipitationProbabilityPct shouldBe 65.0
        first.precipitationMm shouldBe 1.2
        first.windSpeedKmh shouldBe 14.0
        first.uvIndex shouldBe 2.0
        first.relativeHumidityPct shouldBe 72.0
        first.cloudCoverPct shouldBe 90.0
        first.condition shouldBe WeatherCondition.RAIN
    }

    @Test
    fun `feels-like falls back to air temp and absent fields stay null`() = runTest {
        val result = checkNotNull(client().fetchHourly(london, "AIza-test-key"))

        val second = result[1]
        second.apparentTemperatureC shouldBe 18.0
        second.precipitationMm.shouldBeNull()
        second.uvIndex.shouldBeNull()
        second.relativeHumidityPct.shouldBeNull()
        second.cloudCoverPct.shouldBeNull()
        second.condition shouldBe WeatherCondition.CLOUDY
    }

    @Test
    fun `request sends the key as a header not a query param, with metric units and the lookup path`() = runTest {
        var captured: HttpRequestData? = null
        client(captureRequest = { captured = it }).fetchHourly(london, "AIza-test-key")

        val req = checkNotNull(captured)
        req.url.host shouldBe GOOGLE_WEATHER_HOST
        req.url.encodedPath shouldBe "/v1/forecast/hours:lookup"
        // The key rides a header so it never lands in a logged URL.
        req.headers["X-Goog-Api-Key"] shouldBe "AIza-test-key"
        val params = req.url.parameters
        params["key"].shouldBeNull()
        params["location.latitude"] shouldBe "51.5074"
        params["location.longitude"] shouldBe "-0.1278"
        params["unitsSystem"] shouldBe "METRIC"
        // The full 10-day horizon, fetched 24 hours at a time via pagination.
        params["hours"] shouldBe "240"
        params["pageSize"] shouldBe "24"
        // No token on the first page.
        params["pageToken"].shouldBeNull()
    }

    @Test
    fun `follows nextPageToken across pages and concatenates the hours`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val result = checkNotNull(
            pagingClient(
                pages = listOf(page(hour = 11, nextPageToken = "p2"), page(hour = 12, nextPageToken = "p3"), page(hour = 13)),
                captured = captured,
            ).fetchHourly(london, "AIza-test-key"),
        )

        // One PerModelHour per page, in order.
        result.map { it.time.hour } shouldBe listOf(11, 12, 13)
        // Stopped after the tokenless third page — exactly three round-trips.
        captured.size shouldBe 3
        // Each follow-up page carried the prior page's token; the first carried none.
        captured.map { it.url.parameters["pageToken"] } shouldBe listOf(null, "p2", "p3")
    }

    @Test
    fun `a page failing partway keeps the hours already gathered`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        // Page 1 succeeds and points at page 2; page 2 errors.
        val result = checkNotNull(
            pagingClient(
                pages = listOf(page(hour = 11, nextPageToken = "p2"), page(hour = 12)),
                captured = captured,
                failOnRequest = 2,
            ).fetchHourly(london, "AIza-test-key"),
        )

        result.map { it.time.hour } shouldBe listOf(11)
        captured.size shouldBe 2
    }

    @Test
    fun `the first page failing drops Google entirely`() = runTest {
        // No partial result to keep — a page-one failure is the fetch failing.
        pagingClient(pages = listOf(page(hour = 11)), failOnRequest = 1)
            .fetchHourly(london, "AIza-test-key")
            .shouldBeNull()
    }

    @Test
    fun `pagination stops at the page cap even when Google keeps handing back a token`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        // Every page carries a token, so only the MAX_PAGES backstop ends the walk.
        val result = checkNotNull(
            pagingClient(pages = listOf(page(hour = 11, nextPageToken = "loop")), captured = captured)
                .fetchHourly(london, "AIza-test-key"),
        )

        // 240 hours / 24 per page = 10 pages, and no more.
        captured.size shouldBe 10
        result.size shouldBe 10
    }

    @Test
    fun `probe stays a single round-trip on the near window even when more pages are available`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        pagingClient(pages = listOf(page(hour = 11, nextPageToken = "p2"), page(hour = 12)), captured = captured)
            .probe(london, "AIza-test-key") shouldBe GoogleWeatherProbe.Reachable(hours = 1)

        // One page only, asking Google for just the 24-hour connectivity window.
        captured.size shouldBe 1
        captured.single().url.parameters["hours"] shouldBe "24"
    }

    @Test
    fun `a 403 returns null so the blend proceeds without Google`() = runTest {
        // The key's project hasn't enabled the Weather API / has no billing.
        client(status = HttpStatusCode.Forbidden)
            .fetchHourly(london, "AIza-test-key")
            .shouldBeNull()
    }

    @Test
    fun `a blank key skips the call entirely`() = runTest {
        var called = false
        val result = client(captureRequest = { called = true }).fetchHourly(london, "")
        result.shouldBeNull()
        called shouldBe false
    }

    @Test
    fun `an empty forecast returns null rather than an empty series`() = runTest {
        client(body = """{"forecastHours":[]}""")
            .fetchHourly(london, "AIza-test-key")
            .shouldBeNull()
    }

    @Test
    fun `probe reports the hour count when the key reaches the API`() = runTest {
        client().probe(london, "AIza-test-key") shouldBe GoogleWeatherProbe.Reachable(hours = 2)
    }

    @Test
    fun `probe reports a blank key distinctly from a fetch failure`() = runTest {
        var called = false
        val result = client(captureRequest = { called = true }).probe(london, "")
        result shouldBe GoogleWeatherProbe.NoKey
        called shouldBe false
    }

    @Test
    fun `probe singles out a 403 so the UI can point at the Cloud Console fix`() = runTest {
        client(status = HttpStatusCode.Forbidden).probe(london, "AIza-test-key") shouldBe
            GoogleWeatherProbe.Forbidden
    }

    @Test
    fun `probe carries the HTTP status for other server failures`() = runTest {
        client(status = HttpStatusCode.TooManyRequests).probe(london, "AIza-test-key") shouldBe
            GoogleWeatherProbe.Failed(httpStatus = 429)
    }

    @Test
    fun `probe treats an empty forecast as a failure rather than reachable`() = runTest {
        client(body = """{"forecastHours":[]}""").probe(london, "AIza-test-key") shouldBe
            GoogleWeatherProbe.Failed(httpStatus = null)
    }
}
