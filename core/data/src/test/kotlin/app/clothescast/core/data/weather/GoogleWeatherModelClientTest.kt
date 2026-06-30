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
          "timeZone": {"id":"Europe/London"},
          "nextPageToken": "abc"
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
