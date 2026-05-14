package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.time.LocalDateTime

class MultiModelConfidenceFetcherTest {
    private val london = Location(latitude = 51.5074, longitude = -0.1278, displayName = "London")

    private data class LogEntry(val message: String, val throwable: Throwable?)

    private class CapturingLogger : ConfidenceFetchLogger {
        val entries = mutableListOf<LogEntry>()
        override fun log(message: String, throwable: Throwable?) {
            entries += LogEntry(message, throwable)
        }
    }

    private fun fetcherWith(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: ((HttpRequestData) -> Unit)? = null,
        logger: ConfidenceFetchLogger? = null,
    ): MultiModelConfidenceFetcher {
        val engine = MockEngine { request ->
            capture?.invoke(request)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return if (logger != null) {
            MultiModelConfidenceFetcher(client, logger = logger)
        } else {
            MultiModelConfidenceFetcher(client)
        }
    }

    @Test
    fun `issues exactly one batched request listing every model`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val fetcher = fetcherWith(body = THREE_MODEL_AGREEMENT, capture = { captured += it })

        fetcher.fetch(london)

        captured.size shouldBe 1
        val req = captured.single()
        req.url.encodedPath shouldBe "/v1/forecast"
        req.url.parameters["models"] shouldBe "ecmwf_ifs04,gfs_seamless,icon_seamless"
        req.url.parameters["daily"] shouldBe "apparent_temperature_max,precipitation_probability_max"
        req.url.parameters["hourly"] shouldBe
            "apparent_temperature,temperature_2m,precipitation_probability," +
            "wind_speed_10m,relative_humidity_2m,cloud_cover_low," +
            "shortwave_radiation,sunshine_duration,uv_index,weather_code"
        // forecast_days=2 keeps the wrap-past-midnight evening tie-in able to
        // see tomorrow's pre-dawn rain that one model spots but the base
        // forecast under-calls.
        req.url.parameters["forecast_days"] shouldBe "2"
        req.url.parameters["past_days"].shouldBeNull()
    }

    @Test
    fun `tight spread across all three models returns HIGH confidence`() = runTest {
        val info = fetcherWith(THREE_MODEL_AGREEMENT).fetch(london)?.confidence.shouldNotBeNull()

        info.level shouldBe ForecastConfidence.HIGH
        info.tempSpreadC shouldBe (1.0 plusOrMinus 0.0001)
        info.precipSpreadPp shouldBe (10.0 plusOrMinus 0.0001)
        info.modelsConsulted shouldContainExactlyInAnyOrder
            listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless")
    }

    @Test
    fun `wide temp spread drops confidence to LOW`() = runTest {
        val info = fetcherWith(THREE_MODEL_DISAGREEMENT).fetch(london)?.confidence.shouldNotBeNull()

        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (5.0 plusOrMinus 0.0001)
    }

    @Test
    fun `falls back to two-model spread when one model is missing from response`() = runTest {
        val info = fetcherWith(ONE_MODEL_OMITTED).fetch(london)?.confidence.shouldNotBeNull()

        info.modelsConsulted shouldContainExactlyInAnyOrder listOf("ecmwf_ifs04", "gfs_seamless")
        info.tempSpreadC shouldBe (0.5 plusOrMinus 0.0001)
    }

    @Test
    fun `returns null when only one model reports usable values and no hourly is present`() = runTest {
        fetcherWith(TWO_MODELS_OMITTED).fetch(london).shouldBeNull()
    }

    @Test
    fun `returns null when the request fails`() = runTest {
        fetcherWith(body = "boom", status = HttpStatusCode.InternalServerError).fetch(london).shouldBeNull()
    }

    @Test
    fun `null entries from a model are treated as missing`() = runTest {
        // Open-Meteo can return [null] for a model whose run hasn't finished yet.
        val info = fetcherWith(ONE_MODEL_NULL_VALUES).fetch(london)?.confidence.shouldNotBeNull()

        info.modelsConsulted shouldContainExactlyInAnyOrder listOf("gfs_seamless", "icon_seamless")
    }

    @Test
    fun `parses per-model hourly series when present`() = runTest {
        val hourly = fetcherWith(THREE_MODEL_WITH_HOURLY).fetch(london)?.hourly.shouldNotBeNull()

        hourly.byModel.keys shouldContainExactlyInAnyOrder
            listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless")
        val ecmwf = hourly.byModel.getValue("ecmwf_ifs04")
        ecmwf.size shouldBe 3
        ecmwf[0].time shouldBe LocalDateTime.parse("2026-05-12T00:00")
        ecmwf[0].apparentTemperatureC shouldBe (12.0 plusOrMinus 0.0001)
        ecmwf[0].temperatureC shouldBe (14.0 plusOrMinus 0.0001)
        ecmwf[0].precipitationProbabilityPct shouldBe (10.0 plusOrMinus 0.0001)
        ecmwf[0].windSpeedKmh shouldBe (8.0 plusOrMinus 0.0001)
        ecmwf[0].relativeHumidityPct shouldBe (78.0 plusOrMinus 0.0001)
        // Domain field is still named cloudCoverPct, but it carries the
        // low-deck value now (cloud_cover_low) — see PerModelHour kdoc.
        ecmwf[0].cloudCoverPct shouldBe (60.0 plusOrMinus 0.0001)
        ecmwf[0].shortwaveRadiationWm2 shouldBe (0.0 plusOrMinus 0.0001)
        ecmwf[1].shortwaveRadiationWm2 shouldBe (50.0 plusOrMinus 0.0001)
        ecmwf[0].sunshineDurationSec shouldBe (0.0 plusOrMinus 0.0001)
        ecmwf[2].sunshineDurationSec shouldBe (1800.0 plusOrMinus 0.0001)
        ecmwf[1].uvIndex shouldBe (0.5 plusOrMinus 0.0001)
        // WMO 3 → CLOUDY; ensures weather_code_<model> is being parsed
        // through [WmoCodeMapper] alongside the numeric fields.
        ecmwf[0].condition shouldBe WeatherCondition.CLOUDY
        ecmwf[2].time shouldBe LocalDateTime.parse("2026-05-12T02:00")
    }

    @Test
    fun `diagnostic fields stay null when the response omits them`() = runTest {
        // Backwards-compat: the temp / precip overlay still works on responses
        // that don't include wind / humidity / cloud (legacy fixtures, or a
        // model run that didn't return those fields yet). The hour survives
        // because the required temp + precip values are present; the
        // diagnostic fields just come back null.
        val hourly = fetcherWith(THREE_MODEL_WITH_HOURLY_NO_DIAGNOSTICS).fetch(london)?.hourly
            .shouldNotBeNull()

        val ecmwf = hourly.byModel.getValue("ecmwf_ifs04")
        ecmwf[0].apparentTemperatureC shouldBe (12.0 plusOrMinus 0.0001)
        ecmwf[0].windSpeedKmh.shouldBeNull()
        ecmwf[0].relativeHumidityPct.shouldBeNull()
        ecmwf[0].cloudCoverPct.shouldBeNull()
        ecmwf[0].shortwaveRadiationWm2.shouldBeNull()
        ecmwf[0].sunshineDurationSec.shouldBeNull()
        ecmwf[0].uvIndex.shouldBeNull()
    }

    @Test
    fun `hourly is null when the response carries only daily fields`() = runTest {
        fetcherWith(THREE_MODEL_AGREEMENT).fetch(london)?.hourly.shouldBeNull()
    }

    @Test
    fun `drops a model's hourly entry when a single hour reports nulls`() = runTest {
        val hourly = fetcherWith(HOURLY_WITH_ONE_NULL_HOUR).fetch(london)?.hourly.shouldNotBeNull()

        val ecmwf = hourly.byModel.getValue("ecmwf_ifs04")
        // Hour 1 had a null apparent_temperature for ecmwf; the entry is dropped,
        // but the other two hours survive.
        ecmwf.map { it.time } shouldContainExactlyInAnyOrder
            listOf(LocalDateTime.parse("2026-05-12T00:00"), LocalDateTime.parse("2026-05-12T02:00"))
    }

    @Test
    fun `dropping a model logs which fields were missing`() = runTest {
        val logger = CapturingLogger()
        fetcherWith(ONE_MODEL_NULL_VALUES, logger = logger).fetch(london)

        val message = logger.entries.singleOrNull { "ecmwf_ifs04" in it.message }
            .shouldNotBeNull()
            .message
        message shouldContain "dropped"
        message shouldContain "apparent_temperature_max"
        message shouldContain "precipitation_probability_max"
    }

    @Test
    fun `returning null because too few models reported logs the ratio`() = runTest {
        val logger = CapturingLogger()
        fetcherWith(TWO_MODELS_OMITTED, logger = logger).fetch(london).shouldBeNull()

        val giveUp = logger.entries.lastOrNull().shouldNotBeNull()
        giveUp.message shouldContain "1 of 3"
        giveUp.message shouldContain "returning null"
    }

    @Test
    fun `request failure logs the exception`() = runTest {
        val logger = CapturingLogger()
        fetcherWith(
            body = "boom",
            status = HttpStatusCode.InternalServerError,
            logger = logger,
        ).fetch(london).shouldBeNull()

        val entry = logger.entries.singleOrNull { it.throwable != null }.shouldNotBeNull()
        entry.message shouldContain "confidence fetch failed"
        entry.throwable.shouldNotBeNull()
    }

    @Test
    fun `successful fetch logs nothing`() = runTest {
        val logger = CapturingLogger()
        fetcherWith(THREE_MODEL_AGREEMENT, logger = logger).fetch(london).shouldNotBeNull()

        logger.entries shouldBe emptyList()
    }

    companion object {
        // Tight cluster: temps 21.0/21.5/22.0 (spread 1.0°C), precips 10/15/20 (spread 10pp).
        private val THREE_MODEL_AGREEMENT = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs04": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "apparent_temperature_max_icon_seamless": [22.0],
                "precipitation_probability_max_ecmwf_ifs04": [10],
                "precipitation_probability_max_gfs_seamless": [15],
                "precipitation_probability_max_icon_seamless": [20]
              }
            }
        """.trimIndent()

        // Spread of 5°C across temps; well past the LOW threshold.
        private val THREE_MODEL_DISAGREEMENT = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs04": [18.0],
                "apparent_temperature_max_gfs_seamless": [21.0],
                "apparent_temperature_max_icon_seamless": [23.0],
                "precipitation_probability_max_ecmwf_ifs04": [10],
                "precipitation_probability_max_gfs_seamless": [15],
                "precipitation_probability_max_icon_seamless": [20]
              }
            }
        """.trimIndent()

        // Only ecmwf + gfs reported; icon entirely absent from the daily block.
        private val ONE_MODEL_OMITTED = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs04": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "precipitation_probability_max_ecmwf_ifs04": [10],
                "precipitation_probability_max_gfs_seamless": [15]
              }
            }
        """.trimIndent()

        private val TWO_MODELS_OMITTED = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs04": [21.0],
                "precipitation_probability_max_ecmwf_ifs04": [10]
              }
            }
        """.trimIndent()

        // ecmwf array entries are null (model run pending); gfs + icon are usable.
        private val ONE_MODEL_NULL_VALUES = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs04": [null],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "apparent_temperature_max_icon_seamless": [22.0],
                "precipitation_probability_max_ecmwf_ifs04": [null],
                "precipitation_probability_max_gfs_seamless": [15],
                "precipitation_probability_max_icon_seamless": [20]
              }
            }
        """.trimIndent()

        // Same agreement payload, plus a 3-hour per-model hourly block to
        // exercise the overlay-parsing path. Times are local (`auto` timezone)
        // ISO strings without trailing Z, matching real Open-Meteo output.
        private val THREE_MODEL_WITH_HOURLY = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs04": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "apparent_temperature_max_icon_seamless": [22.0],
                "precipitation_probability_max_ecmwf_ifs04": [10],
                "precipitation_probability_max_gfs_seamless": [15],
                "precipitation_probability_max_icon_seamless": [20]
              },
              "hourly": {
                "time": ["2026-05-12T00:00", "2026-05-12T01:00", "2026-05-12T02:00"],
                "apparent_temperature_ecmwf_ifs04": [12.0, 11.5, 11.0],
                "apparent_temperature_gfs_seamless": [12.2, 11.8, 11.4],
                "apparent_temperature_icon_seamless": [13.0, 12.6, 12.0],
                "temperature_2m_ecmwf_ifs04": [14.0, 13.5, 13.0],
                "temperature_2m_gfs_seamless": [14.2, 13.8, 13.4],
                "temperature_2m_icon_seamless": [15.0, 14.6, 14.0],
                "precipitation_probability_ecmwf_ifs04": [10, 15, 20],
                "precipitation_probability_gfs_seamless": [12, 18, 22],
                "precipitation_probability_icon_seamless": [18, 22, 28],
                "wind_speed_10m_ecmwf_ifs04": [8.0, 9.5, 11.0],
                "wind_speed_10m_gfs_seamless": [7.5, 9.0, 10.5],
                "wind_speed_10m_icon_seamless": [10.0, 12.0, 13.5],
                "relative_humidity_2m_ecmwf_ifs04": [78, 80, 82],
                "relative_humidity_2m_gfs_seamless": [76, 78, 80],
                "relative_humidity_2m_icon_seamless": [82, 84, 85],
                "cloud_cover_low_ecmwf_ifs04": [60, 70, 80],
                "cloud_cover_low_gfs_seamless": [65, 72, 78],
                "cloud_cover_low_icon_seamless": [40, 55, 70],
                "shortwave_radiation_ecmwf_ifs04": [0, 50, 120],
                "shortwave_radiation_gfs_seamless": [0, 45, 110],
                "shortwave_radiation_icon_seamless": [0, 60, 140],
                "sunshine_duration_ecmwf_ifs04": [0, 600, 1800],
                "sunshine_duration_gfs_seamless": [0, 500, 1500],
                "sunshine_duration_icon_seamless": [0, 700, 2100],
                "uv_index_ecmwf_ifs04": [0.0, 0.5, 1.5],
                "uv_index_gfs_seamless": [0.0, 0.4, 1.3],
                "uv_index_icon_seamless": [0.0, 0.6, 1.8],
                "weather_code_ecmwf_ifs04": [3, 61, 61],
                "weather_code_gfs_seamless": [2, 61, 61],
                "weather_code_icon_seamless": [3, 51, 51]
              }
            }
        """.trimIndent()

        // Same daily + temp/precip hourly payload but with no wind / humidity
        // / cloud fields — exercises the backwards-compat path where the
        // diagnostic fields come back null rather than dropping the hour.
        private val THREE_MODEL_WITH_HOURLY_NO_DIAGNOSTICS = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs04": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "apparent_temperature_max_icon_seamless": [22.0],
                "precipitation_probability_max_ecmwf_ifs04": [10],
                "precipitation_probability_max_gfs_seamless": [15],
                "precipitation_probability_max_icon_seamless": [20]
              },
              "hourly": {
                "time": ["2026-05-12T00:00", "2026-05-12T01:00", "2026-05-12T02:00"],
                "apparent_temperature_ecmwf_ifs04": [12.0, 11.5, 11.0],
                "apparent_temperature_gfs_seamless": [12.2, 11.8, 11.4],
                "apparent_temperature_icon_seamless": [13.0, 12.6, 12.0],
                "temperature_2m_ecmwf_ifs04": [14.0, 13.5, 13.0],
                "temperature_2m_gfs_seamless": [14.2, 13.8, 13.4],
                "temperature_2m_icon_seamless": [15.0, 14.6, 14.0],
                "precipitation_probability_ecmwf_ifs04": [10, 15, 20],
                "precipitation_probability_gfs_seamless": [12, 18, 22],
                "precipitation_probability_icon_seamless": [18, 22, 28]
              }
            }
        """.trimIndent()

        // ecmwf is missing its T+1 hour: the entry should be dropped, not the
        // whole model. gfs + icon are fully populated.
        private val HOURLY_WITH_ONE_NULL_HOUR = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs04": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "apparent_temperature_max_icon_seamless": [22.0],
                "precipitation_probability_max_ecmwf_ifs04": [10],
                "precipitation_probability_max_gfs_seamless": [15],
                "precipitation_probability_max_icon_seamless": [20]
              },
              "hourly": {
                "time": ["2026-05-12T00:00", "2026-05-12T01:00", "2026-05-12T02:00"],
                "apparent_temperature_ecmwf_ifs04": [12.0, null, 11.0],
                "apparent_temperature_gfs_seamless": [12.2, 11.8, 11.4],
                "apparent_temperature_icon_seamless": [13.0, 12.6, 12.0],
                "temperature_2m_ecmwf_ifs04": [14.0, 13.5, 13.0],
                "temperature_2m_gfs_seamless": [14.2, 13.8, 13.4],
                "temperature_2m_icon_seamless": [15.0, 14.6, 14.0],
                "precipitation_probability_ecmwf_ifs04": [10, 15, 20],
                "precipitation_probability_gfs_seamless": [12, 18, 22],
                "precipitation_probability_icon_seamless": [18, 22, 28]
              }
            }
        """.trimIndent()
    }
}
