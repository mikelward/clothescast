package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.PerModelHourly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
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

class OpenMeteoClientTest {
    private val london = Location(latitude = 51.5074, longitude = -0.1278, displayName = "London")

    private fun fixtureBytes(name: String): ByteReadChannel {
        val text = checkNotNull(javaClass.getResourceAsStream(name)) {
            "fixture $name missing"
        }.bufferedReader().readText()
        return ByteReadChannel(text)
    }

    private fun mockClient(captureRequest: (HttpRequestData) -> Unit = {}): HttpClient {
        val engine = MockEngine { request ->
            captureRequest(request)
            when (request.url.encodedPath) {
                "/v1/forecast" -> respond(
                    content = fixtureBytes("/openmeteo_london.json"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    @Test
    fun `request hits open-meteo with required parameters`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = OpenMeteoClient(mockClient { captured += it })

        client.fetchForecast(london)

        // The confidence fetcher also hits /v1/forecast (one batched call across
        // all configured models) with a different param shape (no past_days).
        // Pick out the primary call by looking for past_days=1 so this test isn't
        // sensitive to async ordering.
        val forecastReq = captured.first {
            it.url.encodedPath == "/v1/forecast" && it.url.parameters["past_days"] == "1"
        }
        forecastReq.url.host shouldBe OPEN_METEO_HOST

        val params = forecastReq.url.parameters
        params["latitude"] shouldBe "51.5074"
        params["longitude"] shouldBe "-0.1278"
        params["past_days"] shouldBe "1"
        params["forecast_days"] shouldBe "14"
        params["timezone"] shouldBe "auto"

        val daily = checkNotNull(params["daily"]).split(",")
        daily.shouldContainAll(
            listOf(
                "temperature_2m_min",
                "temperature_2m_max",
                "precipitation_probability_max",
                "precipitation_sum",
                "weather_code",
            ),
        )

        val hourly = checkNotNull(params["hourly"]).split(",")
        hourly.shouldContain("temperature_2m")
        hourly.shouldContain("precipitation_probability")
        hourly.shouldContain("weather_code")
    }

    @Test
    fun `parses fixture into a forecast bundle`() = runTest {
        val client = OpenMeteoClient(mockClient())

        val bundle = client.fetchForecast(london)

        bundle.yesterday.temperatureMaxC shouldBe 18.0
        bundle.today.temperatureMaxC shouldBe 24.0
        bundle.today.hourly.size shouldBe 8
    }

    @Test
    fun `null best_match hours do not vote synthetic zeros into the consensus blend`() = runTest {
        // The horizon edge of the 14-day window (and a model run still warming
        // up) returns null hourly values for best_match. The mapper turns those
        // into 0.0 °C / 0 % for the chart; the consensus blend must not see
        // them as real votes or a fake cold-and-dry hour drags the mean (and
        // the recomputed daily extremes) down.
        val primaryJson = """
            {
              "timezone": "Europe/London",
              "daily": {
                "time": ["2026-04-24", "2026-04-25"],
                "temperature_2m_min": [12.0, 16.0],
                "temperature_2m_max": [18.0, 24.0],
                "apparent_temperature_min": [10.0, 15.0],
                "apparent_temperature_max": [17.0, 23.0],
                "precipitation_probability_max": [5, 60],
                "precipitation_sum": [0.0, 4.5],
                "weather_code": [2, 63]
              },
              "hourly": {
                "time": ["2026-04-25T12:00", "2026-04-25T13:00"],
                "temperature_2m": [20.0, null],
                "apparent_temperature": [20.0, null],
                "precipitation_probability": [40, null],
                "weather_code": [2, null],
                "wind_speed_10m": [10.0, null],
                "uv_index": [4.0, null]
              }
            }
        """.trimIndent()
        val confidenceJson = """
            {
              "daily": {"time": ["2026-04-25"]},
              "hourly": {
                "time": ["2026-04-25T12:00", "2026-04-25T13:00"],
                "temperature_2m_gfs_seamless": [21.0, 10.0],
                "apparent_temperature_gfs_seamless": [21.0, 10.0],
                "precipitation_probability_gfs_seamless": [50, 60],
                "wind_speed_10m_gfs_seamless": [20.0, 20.0],
                "temperature_2m_icon_seamless": [23.0, 20.0],
                "apparent_temperature_icon_seamless": [23.0, 20.0],
                "precipitation_probability_icon_seamless": [70, 80],
                "wind_speed_10m_icon_seamless": [30.0, 30.0]
              }
            }
        """.trimIndent()
        val engine = MockEngine { request ->
            val isPrimary = request.url.parameters["past_days"] == "1"
            respond(
                content = if (isPrimary) primaryJson else confidenceJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenMeteoClient(
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
        )

        val bundle = client.fetchForecast(london)

        // 12:00 — all three vote: best_match 20, GFS 21, ICON 23. Wind rides
        // the primary call, so best_match votes there too: (10 + 20 + 30) / 3.
        bundle.today.hourly[0].temperatureC shouldBe ((20.0 + 21.0 + 23.0) / 3 plusOrMinus 1e-6)
        bundle.today.hourly[0].windSpeedKmh!! shouldBe ((10.0 + 20.0 + 30.0) / 3 plusOrMinus 1e-6)
        // 13:00 — best_match's hour was null upstream: it must sit the hour
        // out, not vote the mapper's synthetic 0 °C / 0 %.
        bundle.today.hourly[1].temperatureC shouldBe ((10.0 + 20.0) / 2 plusOrMinus 1e-6)
        bundle.today.hourly[1].precipitationProbabilityPct shouldBe ((60.0 + 80.0) / 2 plusOrMinus 1e-6)

        // The exposed per-model series reflects the same policy: the null hour
        // is absent from best_match's series rather than zero-filled, and the
        // primary call's wind / UV ride along.
        val bestMatch = checkNotNull(bundle.perModelHourly)
            .byModel.getValue(PerModelHourly.BEST_MATCH_MODEL_ID)
        bestMatch.map { it.time.hour } shouldBe listOf(12)
        bestMatch[0].windSpeedKmh shouldBe 10.0
        bestMatch[0].uvIndex shouldBe 4.0
    }

    @Test
    fun `5xx with html body surfaces as ResponseException, not a deserialization error`() = runTest {
        // Open-Meteo's gateway occasionally returns 502 with text/html on
        // upstream blips. Without expectSuccess=true the JSON deserializer
        // bites first and throws NoTransformationFoundException, which the
        // worker wouldn't recognise as 5xx and so wouldn't retry.
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/forecast" -> respond(
                    content = "<html><body>502 Bad Gateway</body></html>",
                    status = HttpStatusCode.BadGateway,
                    headers = headersOf(HttpHeaders.ContentType, "text/html"),
                )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }
        val client = OpenMeteoClient(
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
        )

        shouldThrow<ServerResponseException> { client.fetchForecast(london) }
    }

    @Test
    fun `429 from the primary forecast surfaces as ClientRequestException with the status`() = runTest {
        // When several devices on the same home IP wake at 07:00 the primary
        // fetch can come back with "Too many concurrent requests". FetchAndNotifyWorker
        // distinguishes 429 from other 4xx via response.status so it can retry —
        // verify the client surfaces the exception type and status the worker
        // expects rather than swallowing it as a deserialization error.
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/forecast" -> respond(
                    content = """{"error":true,"reason":"Too many concurrent requests"}""",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }
        val client = OpenMeteoClient(
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
        )

        val ex = shouldThrow<ClientRequestException> { client.fetchForecast(london) }
        ex.response.status shouldBe HttpStatusCode.TooManyRequests
    }
}
