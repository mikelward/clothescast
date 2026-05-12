package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.Location
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
        req.url.parameters["forecast_days"] shouldBe "1"
        req.url.parameters["past_days"].shouldBeNull()
    }

    @Test
    fun `tight spread across all three models returns HIGH confidence`() = runTest {
        val info = fetcherWith(THREE_MODEL_AGREEMENT).fetch(london).shouldNotBeNull()

        info.level shouldBe ForecastConfidence.HIGH
        info.tempSpreadC shouldBe (1.0 plusOrMinus 0.0001)
        info.precipSpreadPp shouldBe (10.0 plusOrMinus 0.0001)
        info.modelsConsulted shouldContainExactlyInAnyOrder
            listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless")
    }

    @Test
    fun `wide temp spread drops confidence to LOW`() = runTest {
        val info = fetcherWith(THREE_MODEL_DISAGREEMENT).fetch(london).shouldNotBeNull()

        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (5.0 plusOrMinus 0.0001)
    }

    @Test
    fun `falls back to two-model spread when one model is missing from response`() = runTest {
        val info = fetcherWith(ONE_MODEL_OMITTED).fetch(london).shouldNotBeNull()

        info.modelsConsulted shouldContainExactlyInAnyOrder listOf("ecmwf_ifs04", "gfs_seamless")
        info.tempSpreadC shouldBe (0.5 plusOrMinus 0.0001)
    }

    @Test
    fun `returns null when only one model reports usable values`() = runTest {
        fetcherWith(TWO_MODELS_OMITTED).fetch(london).shouldBeNull()
    }

    @Test
    fun `returns null when the request fails`() = runTest {
        fetcherWith(body = "boom", status = HttpStatusCode.InternalServerError).fetch(london).shouldBeNull()
    }

    @Test
    fun `null entries from a model are treated as missing`() = runTest {
        // Open-Meteo can return [null] for a model whose run hasn't finished yet.
        val info = fetcherWith(ONE_MODEL_NULL_VALUES).fetch(london).shouldNotBeNull()

        info.modelsConsulted shouldContainExactlyInAnyOrder listOf("gfs_seamless", "icon_seamless")
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
    }
}
