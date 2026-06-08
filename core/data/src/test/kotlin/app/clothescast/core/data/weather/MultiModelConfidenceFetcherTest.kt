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

    // The three models these mechanics fixtures are keyed on. Passed
    // explicitly so the parse / confidence / logging assertions don't depend
    // on the production default set (now five models — see
    // ForecastModel.DEFAULTS / MultiModelConfidenceFetcher.DEFAULT_MODELS),
    // which would otherwise log drops for the fixture-omitted models and
    // change the consulted-model counts.
    private val fixtureModels = listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless")

    // The default set the fetcher falls back to when the caller passes no
    // (or an empty) model list — mirrors MultiModelConfidenceFetcher
    // .DEFAULT_MODELS. Joined into the request's models= parameter.
    private val defaultModelsParam =
        "ecmwf_ifs025,gfs_seamless,icon_seamless,gem_seamless,ecmwf_aifs025_single"

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

    /**
     * Overload for tests that need a stateful engine (e.g. 400 on the first
     * model list, 200 on the pruned retry) rather than one fixed response.
     */
    private fun fetcherWith(
        engine: MockEngine,
        logger: ConfidenceFetchLogger? = null,
    ): MultiModelConfidenceFetcher {
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
        req.url.parameters["models"] shouldBe defaultModelsParam
        req.url.parameters["daily"] shouldBe
            "apparent_temperature_max,apparent_temperature_min,precipitation_probability_max"
        req.url.parameters["hourly"] shouldBe
            "apparent_temperature,temperature_2m,precipitation_probability,precipitation," +
            "wind_speed_10m,relative_humidity_2m,cloud_cover_low," +
            "shortwave_radiation,sunshine_duration,uv_index,weather_code"
        // forecast_days=14 covers the Today screen's two week pages ("Next 7
        // days" and "Following 7 days") so the per-model diagnostic cards
        // (wind / humidity / cloud / solar / UV / sunshine) and model-spread
        // overlays render across both weeks, and keeps the wrap-past-midnight
        // evening tie-in able to see tomorrow's pre-dawn rain that one model
        // spots but the base forecast under-calls.
        req.url.parameters["forecast_days"] shouldBe "14"
        req.url.parameters["past_days"].shouldBeNull()
    }

    @Test
    fun `caller-supplied models override the default trio in the request URL`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val fetcher = fetcherWith(body = THREE_MODEL_AGREEMENT, capture = { captured += it })

        fetcher.fetch(london, listOf("ukmo_seamless", "meteofrance_seamless"))

        captured.single().url.parameters["models"] shouldBe "ukmo_seamless,meteofrance_seamless"
    }

    @Test
    fun `empty caller-supplied models falls back to the default set`() = runTest {
        // The Forecasters settings UI keeps the picker pinned to at least
        // two checked entries, so this path is a hand-edited-DataStore
        // safety net. The fetcher recovers rather than firing an empty
        // models= parameter that Open-Meteo would reject.
        val captured = mutableListOf<HttpRequestData>()
        val fetcher = fetcherWith(body = THREE_MODEL_AGREEMENT, capture = { captured += it })

        fetcher.fetch(london, emptyList())

        captured.single().url.parameters["models"] shouldBe defaultModelsParam
    }

    @Test
    fun `tight spread across all three models returns HIGH confidence`() = runTest {
        val info = fetcherWith(THREE_MODEL_AGREEMENT).fetch(london, fixtureModels)?.confidence.shouldNotBeNull()

        info.level shouldBe ForecastConfidence.HIGH
        info.tempSpreadC shouldBe (1.0 plusOrMinus 0.0001)
        info.precipSpreadPp shouldBe (10.0 plusOrMinus 0.0001)
        info.modelsConsulted shouldContainExactlyInAnyOrder
            listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless")
    }

    @Test
    fun `wide temp spread drops confidence to LOW`() = runTest {
        val info = fetcherWith(THREE_MODEL_DISAGREEMENT).fetch(london, fixtureModels)?.confidence.shouldNotBeNull()

        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (5.0 plusOrMinus 0.0001)
    }

    @Test
    fun `wide overnight-low spread drops confidence even when the highs agree`() = runTest {
        // Highs cluster tightly (21.0 / 21.2 / 21.4 → 0.4°C) but the
        // overnight lows split wide (8 / 11 / 14 → 6°C). The tier reads the
        // wider of the two temp spreads, so the low disagreement drags it
        // to LOW even though the peaks line up.
        val info = fetcherWith(THREE_MODEL_LOW_SPLIT).fetch(london, fixtureModels)?.confidence.shouldNotBeNull()

        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (6.0 plusOrMinus 0.0001)
    }

    @Test
    fun `falls back to two-model spread when one model is missing from response`() = runTest {
        val info = fetcherWith(ONE_MODEL_OMITTED).fetch(london, fixtureModels)?.confidence.shouldNotBeNull()

        info.modelsConsulted shouldContainExactlyInAnyOrder listOf("ecmwf_ifs025", "gfs_seamless")
        info.tempSpreadC shouldBe (0.5 plusOrMinus 0.0001)
    }

    @Test
    fun `returns null when only one model reports usable values and no hourly is present`() = runTest {
        fetcherWith(TWO_MODELS_OMITTED).fetch(london, fixtureModels).shouldBeNull()
    }

    @Test
    fun `returns null when the request fails`() = runTest {
        fetcherWith(body = "boom", status = HttpStatusCode.InternalServerError).fetch(london, fixtureModels).shouldBeNull()
    }

    @Test
    fun `null entries from a model are treated as missing`() = runTest {
        // Open-Meteo can return [null] for a model whose run hasn't finished yet.
        val info = fetcherWith(ONE_MODEL_NULL_VALUES).fetch(london, fixtureModels)?.confidence.shouldNotBeNull()

        info.modelsConsulted shouldContainExactlyInAnyOrder listOf("gfs_seamless", "icon_seamless")
    }

    @Test
    fun `parses per-model hourly series when present`() = runTest {
        val hourly = fetcherWith(THREE_MODEL_WITH_HOURLY).fetch(london, fixtureModels)?.hourly.shouldNotBeNull()

        hourly.byModel.keys shouldContainExactlyInAnyOrder
            listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless")
        val ecmwf = hourly.byModel.getValue("ecmwf_ifs025")
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
        val hourly = fetcherWith(THREE_MODEL_WITH_HOURLY_NO_DIAGNOSTICS).fetch(london, fixtureModels)?.hourly
            .shouldNotBeNull()

        val ecmwf = hourly.byModel.getValue("ecmwf_ifs025")
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
        fetcherWith(THREE_MODEL_AGREEMENT).fetch(london, fixtureModels)?.hourly.shouldBeNull()
    }

    @Test
    fun `drops a model's hourly entry when temperature_2m is null for that hour`() = runTest {
        val hourly = fetcherWith(HOURLY_WITH_ONE_NULL_HOUR).fetch(london, fixtureModels)?.hourly.shouldNotBeNull()

        val ecmwf = hourly.byModel.getValue("ecmwf_ifs025")
        // Hour 1 had a null temperature_2m for ecmwf; the entry is dropped,
        // but the other two hours survive. apparent_temperature and
        // precipitation_probability are now optional per-hour — temperature_2m
        // is the only required field — so a per-hour drop only happens when
        // the air-temp value itself is null.
        ecmwf.map { it.time } shouldContainExactlyInAnyOrder
            listOf(LocalDateTime.parse("2026-05-12T00:00"), LocalDateTime.parse("2026-05-12T02:00"))
    }

    @Test
    fun `model still renders when only apparent_temperature is missing`() = runTest {
        // Open-Meteo silently omits per-model variables that a particular
        // model doesn't expose. Pre-fix, a model missing apparent_temperature
        // entirely would drop from the chart because every hour's `apparent`
        // value was null and the parser `?: continue`'d each one. The fix
        // falls back to temperature_2m for the apparent series, so the model
        // still renders — just with the raw air-temp curve in feels-like
        // mode rather than a wind-chill-adjusted one.
        val logger = CapturingLogger()
        val hourly = fetcherWith(HOURLY_MISSING_APPARENT_TEMPERATURE, logger = logger)
            .fetch(london, fixtureModels)?.hourly.shouldNotBeNull()

        val ecmwf = hourly.byModel.getValue("ecmwf_ifs025")
        ecmwf.size shouldBe 3
        // apparent_temperature falls back to temperature_2m per hour
        ecmwf[0].apparentTemperatureC shouldBe (14.0 plusOrMinus 0.0001)
        ecmwf[0].temperatureC shouldBe (14.0 plusOrMinus 0.0001)
        // Log entry surfaces the substitution so a Diagnostics scrape
        // explains why the apparent series mirrors air temp.
        logger.entries.any { "ecmwf_ifs025" in it.message && "apparent_temperature missing" in it.message } shouldBe true
    }

    @Test
    fun `model still renders when only precipitation_probability is missing`() = runTest {
        // The case some models hit (UKMO / JMA / GEM / ARPEGE): Open-Meteo
        // doesn't expose `precipitation_probability_<model>` for them. We keep
        // the model in the chart on the strength of its temperature series,
        // leave precip null per hour so precip-specific aggregates (consensus
        // blend, insight rain prose, precipitation chart) can skip the model
        // rather than treating it as a 0% rain vote, and log the substitution
        // so the cause is observable from Diagnostics.
        val logger = CapturingLogger()
        val hourly = fetcherWith(HOURLY_MISSING_PRECIP_PROBABILITY, logger = logger)
            .fetch(london, fixtureModels)?.hourly.shouldNotBeNull()

        val ecmwf = hourly.byModel.getValue("ecmwf_ifs025")
        ecmwf.size shouldBe 3
        ecmwf.all { it.precipitationProbabilityPct == null } shouldBe true
        logger.entries.any { "ecmwf_ifs025" in it.message && "precipitation_probability missing" in it.message } shouldBe true
    }

    @Test
    fun `model drops entirely when temperature_2m is wholesale missing`() = runTest {
        // Without an air-temperature series the chart has nothing to draw,
        // and the consensus blend has no temp signal to fold in. Log + drop.
        val logger = CapturingLogger()
        val hourly = fetcherWith(HOURLY_MISSING_AIR_TEMPERATURE, logger = logger)
            .fetch(london, fixtureModels)?.hourly

        // ecmwf is the only model in this fixture; with it dropped, byModel
        // is empty and parseHourly returns null.
        hourly shouldBe null
        logger.entries.any {
            "ecmwf_ifs025" in it.message && "temperature_2m missing" in it.message
        } shouldBe true
    }

    @Test
    fun `dropping a model logs which fields were missing`() = runTest {
        val logger = CapturingLogger()
        fetcherWith(ONE_MODEL_NULL_VALUES, logger = logger).fetch(london, fixtureModels)

        val message = logger.entries.singleOrNull { "ecmwf_ifs025" in it.message }
            .shouldNotBeNull()
            .message
        message shouldContain "dropped"
        message shouldContain "apparent_temperature_max"
        message shouldContain "precipitation_probability_max"
    }

    @Test
    fun `returning null because too few models reported logs the ratio`() = runTest {
        val logger = CapturingLogger()
        fetcherWith(TWO_MODELS_OMITTED, logger = logger).fetch(london, fixtureModels).shouldBeNull()

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
        ).fetch(london, fixtureModels).shouldBeNull()

        val entry = logger.entries.singleOrNull { it.throwable != null }.shouldNotBeNull()
        entry.message shouldContain "confidence fetch failed"
        entry.throwable.shouldNotBeNull()
    }

    @Test
    fun `successful fetch logs nothing`() = runTest {
        val logger = CapturingLogger()
        fetcherWith(THREE_MODEL_AGREEMENT, logger = logger).fetch(london, fixtureModels).shouldNotBeNull()

        logger.entries shouldBe emptyList()
    }

    @Test
    fun `drops a model Open-Meteo rejects and retries with the rest`() = runTest {
        // Open-Meteo 400s the whole batched request when any models= entry is
        // invalid, naming the offender in the body. The fetcher must drop it and
        // retry so a single bad id costs only its own line, not the chip + every
        // chart. Engine: 400 while the list still contains icon_seamless, 200
        // once it's pruned.
        val requestedModels = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val models = request.url.parameters["models"]
            requestedModels += models
            if (models != null && "icon_seamless" in models) {
                respond(
                    content = ByteReadChannel(
                        """{"error":true,"reason":"Cannot initialize WeatherModel from String \"icon_seamless\""}""",
                    ),
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    content = ByteReadChannel(THREE_MODEL_AGREEMENT),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val logger = CapturingLogger()
        val data = fetcherWith(engine, logger = logger)
            .fetch(london, listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless"))
            .shouldNotBeNull()

        // First request lists all three; the retry drops the rejected model.
        requestedModels shouldBe listOf(
            "ecmwf_ifs025,gfs_seamless,icon_seamless",
            "ecmwf_ifs025,gfs_seamless",
        )
        // Confidence still computed from the surviving models.
        data.confidence.shouldNotBeNull()
            .modelsConsulted shouldContainExactlyInAnyOrder listOf("ecmwf_ifs025", "gfs_seamless")
        logger.entries.any { "icon_seamless" in it.message && "retrying" in it.message } shouldBe true
    }

    @Test
    fun `does not retry when the 400 names no requested model`() = runTest {
        // A 400 that isn't about a model id (bad coordinates, etc.) shouldn't
        // trigger a prune-and-retry — there's nothing to drop. Fail fast to null.
        val requestedModels = mutableListOf<String?>()
        val engine = MockEngine { request ->
            requestedModels += request.url.parameters["models"]
            respond(
                content = ByteReadChannel("""{"error":true,"reason":"Latitude must be in range -90 to 90"}"""),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        fetcherWith(engine).fetch(london, listOf("ecmwf_ifs025", "gfs_seamless")).shouldBeNull()
        requestedModels.size shouldBe 1
    }

    @Test
    fun `returns null when Open-Meteo rejects every requested model`() = runTest {
        // If pruning the rejected models leaves nothing, give up rather than
        // fire an empty models= request.
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(
                    """{"error":true,"reason":"invalid models: ecmwf_ifs025, gfs_seamless"}""",
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        fetcherWith(engine).fetch(london, listOf("ecmwf_ifs025", "gfs_seamless")).shouldBeNull()
    }

    companion object {
        // Tight cluster: temps 21.0/21.5/22.0 (spread 1.0°C), precips 10/15/20 (spread 10pp).
        private val THREE_MODEL_AGREEMENT = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs025": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "apparent_temperature_max_icon_seamless": [22.0],
                "precipitation_probability_max_ecmwf_ifs025": [10],
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
                "apparent_temperature_max_ecmwf_ifs025": [18.0],
                "apparent_temperature_max_gfs_seamless": [21.0],
                "apparent_temperature_max_icon_seamless": [23.0],
                "precipitation_probability_max_ecmwf_ifs025": [10],
                "precipitation_probability_max_gfs_seamless": [15],
                "precipitation_probability_max_icon_seamless": [20]
              }
            }
        """.trimIndent()

        // Highs agree (21.0 / 21.2 / 21.4) but the daily lows split wide
        // (8.0 / 11.0 / 14.0). Exercises the low-disagreement half of the
        // temp spread.
        private val THREE_MODEL_LOW_SPLIT = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs025": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.2],
                "apparent_temperature_max_icon_seamless": [21.4],
                "apparent_temperature_min_ecmwf_ifs025": [8.0],
                "apparent_temperature_min_gfs_seamless": [11.0],
                "apparent_temperature_min_icon_seamless": [14.0],
                "precipitation_probability_max_ecmwf_ifs025": [10],
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
                "apparent_temperature_max_ecmwf_ifs025": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "precipitation_probability_max_ecmwf_ifs025": [10],
                "precipitation_probability_max_gfs_seamless": [15]
              }
            }
        """.trimIndent()

        private val TWO_MODELS_OMITTED = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs025": [21.0],
                "precipitation_probability_max_ecmwf_ifs025": [10]
              }
            }
        """.trimIndent()

        // ecmwf array entries are null (model run pending); gfs + icon are usable.
        private val ONE_MODEL_NULL_VALUES = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs025": [null],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "apparent_temperature_max_icon_seamless": [22.0],
                "precipitation_probability_max_ecmwf_ifs025": [null],
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
                "apparent_temperature_max_ecmwf_ifs025": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "apparent_temperature_max_icon_seamless": [22.0],
                "precipitation_probability_max_ecmwf_ifs025": [10],
                "precipitation_probability_max_gfs_seamless": [15],
                "precipitation_probability_max_icon_seamless": [20]
              },
              "hourly": {
                "time": ["2026-05-12T00:00", "2026-05-12T01:00", "2026-05-12T02:00"],
                "apparent_temperature_ecmwf_ifs025": [12.0, 11.5, 11.0],
                "apparent_temperature_gfs_seamless": [12.2, 11.8, 11.4],
                "apparent_temperature_icon_seamless": [13.0, 12.6, 12.0],
                "temperature_2m_ecmwf_ifs025": [14.0, 13.5, 13.0],
                "temperature_2m_gfs_seamless": [14.2, 13.8, 13.4],
                "temperature_2m_icon_seamless": [15.0, 14.6, 14.0],
                "precipitation_probability_ecmwf_ifs025": [10, 15, 20],
                "precipitation_probability_gfs_seamless": [12, 18, 22],
                "precipitation_probability_icon_seamless": [18, 22, 28],
                "wind_speed_10m_ecmwf_ifs025": [8.0, 9.5, 11.0],
                "wind_speed_10m_gfs_seamless": [7.5, 9.0, 10.5],
                "wind_speed_10m_icon_seamless": [10.0, 12.0, 13.5],
                "relative_humidity_2m_ecmwf_ifs025": [78, 80, 82],
                "relative_humidity_2m_gfs_seamless": [76, 78, 80],
                "relative_humidity_2m_icon_seamless": [82, 84, 85],
                "cloud_cover_low_ecmwf_ifs025": [60, 70, 80],
                "cloud_cover_low_gfs_seamless": [65, 72, 78],
                "cloud_cover_low_icon_seamless": [40, 55, 70],
                "shortwave_radiation_ecmwf_ifs025": [0, 50, 120],
                "shortwave_radiation_gfs_seamless": [0, 45, 110],
                "shortwave_radiation_icon_seamless": [0, 60, 140],
                "sunshine_duration_ecmwf_ifs025": [0, 600, 1800],
                "sunshine_duration_gfs_seamless": [0, 500, 1500],
                "sunshine_duration_icon_seamless": [0, 700, 2100],
                "uv_index_ecmwf_ifs025": [0.0, 0.5, 1.5],
                "uv_index_gfs_seamless": [0.0, 0.4, 1.3],
                "uv_index_icon_seamless": [0.0, 0.6, 1.8],
                "weather_code_ecmwf_ifs025": [3, 61, 61],
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
                "apparent_temperature_max_ecmwf_ifs025": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "apparent_temperature_max_icon_seamless": [22.0],
                "precipitation_probability_max_ecmwf_ifs025": [10],
                "precipitation_probability_max_gfs_seamless": [15],
                "precipitation_probability_max_icon_seamless": [20]
              },
              "hourly": {
                "time": ["2026-05-12T00:00", "2026-05-12T01:00", "2026-05-12T02:00"],
                "apparent_temperature_ecmwf_ifs025": [12.0, 11.5, 11.0],
                "apparent_temperature_gfs_seamless": [12.2, 11.8, 11.4],
                "apparent_temperature_icon_seamless": [13.0, 12.6, 12.0],
                "temperature_2m_ecmwf_ifs025": [14.0, 13.5, 13.0],
                "temperature_2m_gfs_seamless": [14.2, 13.8, 13.4],
                "temperature_2m_icon_seamless": [15.0, 14.6, 14.0],
                "precipitation_probability_ecmwf_ifs025": [10, 15, 20],
                "precipitation_probability_gfs_seamless": [12, 18, 22],
                "precipitation_probability_icon_seamless": [18, 22, 28]
              }
            }
        """.trimIndent()

        // ecmwf is missing its T+1 temperature_2m: the entry should be
        // dropped, not the whole model. gfs + icon are fully populated.
        // temperature_2m is the only required per-hour field now.
        private val HOURLY_WITH_ONE_NULL_HOUR = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs025": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "apparent_temperature_max_icon_seamless": [22.0],
                "precipitation_probability_max_ecmwf_ifs025": [10],
                "precipitation_probability_max_gfs_seamless": [15],
                "precipitation_probability_max_icon_seamless": [20]
              },
              "hourly": {
                "time": ["2026-05-12T00:00", "2026-05-12T01:00", "2026-05-12T02:00"],
                "apparent_temperature_ecmwf_ifs025": [12.0, 11.5, 11.0],
                "apparent_temperature_gfs_seamless": [12.2, 11.8, 11.4],
                "apparent_temperature_icon_seamless": [13.0, 12.6, 12.0],
                "temperature_2m_ecmwf_ifs025": [14.0, null, 13.0],
                "temperature_2m_gfs_seamless": [14.2, 13.8, 13.4],
                "temperature_2m_icon_seamless": [15.0, 14.6, 14.0],
                "precipitation_probability_ecmwf_ifs025": [10, 15, 20],
                "precipitation_probability_gfs_seamless": [12, 18, 22],
                "precipitation_probability_icon_seamless": [18, 22, 28]
              }
            }
        """.trimIndent()

        // ecmwf's `apparent_temperature_<model>` array is omitted entirely.
        // Pre-fix this would have silently dropped ecmwf from the per-model
        // chart; post-fix the model still renders with apparent falling back
        // to temperature_2m and a log entry explaining the substitution.
        private val HOURLY_MISSING_APPARENT_TEMPERATURE = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs025": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "precipitation_probability_max_ecmwf_ifs025": [10],
                "precipitation_probability_max_gfs_seamless": [15]
              },
              "hourly": {
                "time": ["2026-05-12T00:00", "2026-05-12T01:00", "2026-05-12T02:00"],
                "temperature_2m_ecmwf_ifs025": [14.0, 13.5, 13.0],
                "temperature_2m_gfs_seamless": [14.2, 13.8, 13.4],
                "precipitation_probability_ecmwf_ifs025": [10, 15, 20],
                "precipitation_probability_gfs_seamless": [12, 18, 22]
              }
            }
        """.trimIndent()

        // ecmwf's `precipitation_probability_<model>` array is omitted.
        // Model still renders; per-hour precip defaults to 0% and we log.
        private val HOURLY_MISSING_PRECIP_PROBABILITY = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs025": [21.0],
                "apparent_temperature_max_gfs_seamless": [21.5],
                "precipitation_probability_max_ecmwf_ifs025": [10],
                "precipitation_probability_max_gfs_seamless": [15]
              },
              "hourly": {
                "time": ["2026-05-12T00:00", "2026-05-12T01:00", "2026-05-12T02:00"],
                "apparent_temperature_ecmwf_ifs025": [12.0, 11.5, 11.0],
                "apparent_temperature_gfs_seamless": [12.2, 11.8, 11.4],
                "temperature_2m_ecmwf_ifs025": [14.0, 13.5, 13.0],
                "temperature_2m_gfs_seamless": [14.2, 13.8, 13.4],
                "precipitation_probability_gfs_seamless": [12, 18, 22]
              }
            }
        """.trimIndent()

        // ecmwf has no air temperature at all → chart has nothing to plot
        // → model drops with a logged reason.
        private val HOURLY_MISSING_AIR_TEMPERATURE = """
            {
              "daily": {
                "time": ["2026-05-12"],
                "apparent_temperature_max_ecmwf_ifs025": [21.0],
                "precipitation_probability_max_ecmwf_ifs025": [10]
              },
              "hourly": {
                "time": ["2026-05-12T00:00", "2026-05-12T01:00", "2026-05-12T02:00"],
                "apparent_temperature_ecmwf_ifs025": [12.0, 11.5, 11.0],
                "precipitation_probability_ecmwf_ifs025": [10, 15, 20]
              }
            }
        """.trimIndent()
    }
}
