package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.Location
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
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
        params["forecast_days"] shouldBe "7"
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
