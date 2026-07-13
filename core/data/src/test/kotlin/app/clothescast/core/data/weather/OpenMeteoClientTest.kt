package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import java.time.LocalDateTime

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
        // all configured models). Both calls now carry past_days=1, so pick out
        // the primary call by the absence of `models` (only the confidence call
        // lists them) so this test isn't sensitive to async ordering.
        val forecastReq = captured.first {
            it.url.encodedPath == "/v1/forecast" && it.url.parameters["models"] == null
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
            // Both calls now carry past_days=1 (the per-model confidence call
            // reaches back into yesterday so the Overnight chart's per-model
            // lines have pre-midnight data). The confidence call is the one
            // that lists `models`; the primary forecast doesn't.
            val isPrimary = request.url.parameters["models"] == null
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
    fun `best_match per-model series keeps yesterday's hours for the overnight chart`() = runTest {
        // Both the primary call and the confidence side-band fetch past_days=1,
        // so the consulted models' series reach back to yesterday evening for
        // the Overnight chart's pre-midnight hours. best_match must too — a
        // today-only filter made the "Auto" line alone start at midnight.
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
                "time": ["2026-04-24T21:00", "2026-04-25T12:00"],
                "temperature_2m": [14.0, 20.0],
                "apparent_temperature": [13.0, 20.0],
                "precipitation_probability": [10, 40],
                "weather_code": [2, 2],
                "wind_speed_10m": [8.0, 10.0],
                "uv_index": [0.0, 4.0]
              }
            }
        """.trimIndent()
        val confidenceJson = """
            {
              "daily": {"time": ["2026-04-25"]},
              "hourly": {
                "time": ["2026-04-24T21:00", "2026-04-25T12:00"],
                "temperature_2m_gfs_seamless": [15.0, 21.0],
                "apparent_temperature_gfs_seamless": [15.0, 21.0],
                "temperature_2m_icon_seamless": [13.0, 23.0],
                "apparent_temperature_icon_seamless": [13.0, 23.0]
              }
            }
        """.trimIndent()
        val engine = MockEngine { request ->
            val isPrimary = request.url.parameters["models"] == null
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

        val bestMatch = checkNotNull(bundle.perModelHourly)
            .byModel.getValue(PerModelHourly.BEST_MATCH_MODEL_ID)
        bestMatch.map { it.time } shouldBe listOf(
            java.time.LocalDateTime.parse("2026-04-24T21:00"),
            java.time.LocalDateTime.parse("2026-04-25T12:00"),
        )
    }

    @Test
    fun `google is treated as just another model - votes in the blend and lands in the stored per-model map`() = runTest {
        // Same two-model side-band (GFS + ICON) and best_match primary as the
        // synthetic-zero test above. At 12:00 best_match=20, GFS=21, ICON=23.
        // Folding Google in (temp 27, precip 90) must shift the blended mean to
        // a four-way average — Google is one more equal-weight vote — and it must
        // also land in the stored per-model map under "google" so it draws on
        // the charts and votes in the confidence chip + divergence hint.
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
                "time": ["2026-04-25T12:00"],
                "temperature_2m": [20.0],
                "apparent_temperature": [20.0],
                "precipitation_probability": [40],
                "weather_code": [2],
                "wind_speed_10m": [10.0],
                "uv_index": [4.0]
              }
            }
        """.trimIndent()
        val confidenceJson = """
            {
              "daily": {"time": ["2026-04-25"]},
              "hourly": {
                "time": ["2026-04-25T12:00"],
                "temperature_2m_gfs_seamless": [21.0],
                "apparent_temperature_gfs_seamless": [21.0],
                "precipitation_probability_gfs_seamless": [50],
                "temperature_2m_icon_seamless": [23.0],
                "apparent_temperature_icon_seamless": [23.0],
                "precipitation_probability_icon_seamless": [70]
              }
            }
        """.trimIndent()
        val engine = MockEngine { request ->
            val isPrimary = request.url.parameters["models"] == null
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
            extraModelHourly = {
                listOf(
                    PerModelHour(
                        time = LocalDateTime.of(2026, 4, 25, 12, 0),
                        apparentTemperatureC = 27.0,
                        temperatureC = 27.0,
                        precipitationProbabilityPct = 90.0,
                        condition = WeatherCondition.RAIN,
                    ),
                )
            },
        )

        val bundle = client.fetchForecast(london)

        // Four equal-weight votes: best_match 20, GFS 21, ICON 23, Google 27.
        bundle.today.hourly[0].temperatureC shouldBe ((20.0 + 21.0 + 23.0 + 27.0) / 4 plusOrMinus 1e-6)
        bundle.today.hourly[0].precipitationProbabilityPct shouldBe ((40.0 + 50.0 + 70.0 + 90.0) / 4 plusOrMinus 1e-6)

        // Google sits in the stored per-model series alongside the source models
        // and best_match, so the chart overlay, confidence chip, and divergence
        // hint all see it as a peer.
        val byModel = checkNotNull(bundle.perModelHourly).byModel
        byModel.keys shouldContainAll
            listOf(GOOGLE_MODEL_ID, "gfs_seamless", "icon_seamless", PerModelHourly.BEST_MATCH_MODEL_ID)
        byModel.getValue(GOOGLE_MODEL_ID).single().temperatureC shouldBe 27.0
    }

    @Test
    fun `google can rescue the blend when the open-meteo side-band fails`() = runTest {
        // The multi-model side-band 500s, so there are no consulted Open-Meteo
        // models — normally best_match alone can't blend (one model isn't a
        // consensus). With Google present, best_match + Google clear the
        // two-model bar and the blend still applies.
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
                "time": ["2026-04-25T12:00"],
                "temperature_2m": [20.0],
                "apparent_temperature": [20.0],
                "precipitation_probability": [40],
                "weather_code": [2],
                "wind_speed_10m": [10.0],
                "uv_index": [4.0]
              }
            }
        """.trimIndent()
        val engine = MockEngine { request ->
            val isPrimary = request.url.parameters["models"] == null
            if (isPrimary) {
                respond(
                    content = primaryJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    content = "<html>500</html>",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }
        }
        val client = OpenMeteoClient(
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
            extraModelHourly = {
                listOf(
                    PerModelHour(
                        time = LocalDateTime.of(2026, 4, 25, 12, 0),
                        apparentTemperatureC = 26.0,
                        temperatureC = 26.0,
                        precipitationProbabilityPct = 80.0,
                        condition = WeatherCondition.RAIN,
                    ),
                )
            },
        )

        val bundle = client.fetchForecast(london)

        // best_match 20 + Google 26 → 23.0.
        bundle.today.hourly[0].temperatureC shouldBe ((20.0 + 26.0) / 2 plusOrMinus 1e-6)
        // The side-band failed, so the stored map is just best_match + Google.
        val byModel = checkNotNull(bundle.perModelHourly).byModel
        byModel.keys shouldContainExactlyInAnyOrder listOf(PerModelHourly.BEST_MATCH_MODEL_ID, GOOGLE_MODEL_ID)
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
