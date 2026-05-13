package app.clothescast.core.data.weather

import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.diag.ApiEndpoints
import app.clothescast.core.data.diag.NoOpApiCallLogger
import app.clothescast.core.data.diag.instrument
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.URLProtocol
import io.ktor.http.path
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime

/**
 * Fetches today's apparent-max temperature and peak precipitation probability from
 * several Open-Meteo models in a single request, computes the cross-model spread,
 * and maps to a [ConfidenceInfo]. When the major models agree, we surface "High
 * confidence"; when they disagree, "Low confidence — forecasts disagree".
 *
 * The same call also pulls the per-model hourly apparent-temperature and
 * precipitation-probability series so the Today screen's "show model spread"
 * overlay has the curves it needs to render. Bundled into one request rather
 * than a follow-up call so the additional bytes (~3 KB) ride the existing
 * 7am-ish round-trip and we don't burn another rate-limit slot.
 *
 * Open-Meteo lets us pass a comma-separated `models=` list and returns each
 * requested field once per model, suffixed with the model name (e.g.
 * `apparent_temperature_max_ecmwf_ifs04`, `apparent_temperature_ecmwf_ifs04`).
 *
 * Best-effort: any failure (network, parse error) falls through to null. Per-model
 * failures (server returns nulls or omits a model's fields) drop just that model;
 * we still return a [ConfidenceInfo] as long as at least two models reported
 * usable daily values, and a [PerModelHourly] when at least one model reported
 * usable hourly values. Every drop and every failure is reported through
 * [logger] so the caller can surface why the chip didn't render.
 */
internal class MultiModelConfidenceFetcher(
    private val httpClient: HttpClient,
    private val models: List<String> = DEFAULT_MODELS,
    private val logger: ConfidenceFetchLogger = NoOpConfidenceFetchLogger,
    private val apiCallLogger: ApiCallLogger = NoOpApiCallLogger,
) {
    suspend fun fetch(location: Location): MultiModelData? = try {
        val response = apiCallLogger.instrument(ApiEndpoints.OPEN_METEO_CONFIDENCE) {
            httpClient.get {
                expectSuccess = true
                url {
                    protocol = URLProtocol.HTTPS
                    host = OPEN_METEO_HOST
                    path("v1", "forecast")
                }
                parameter("latitude", location.latitude)
                parameter("longitude", location.longitude)
                // forecast_days=2 so the per-model series covers today AND tomorrow's
                // pre-dawn hours. The tonight insight's evening tie-in needs the wrap
                // past midnight to spot rain that one model sees overnight but the
                // base-only fallback misses. Confidence aggregates read `daily[0]`
                // (today's value), so widening the window doesn't disturb the
                // tier calculation downstream.
                parameter("forecast_days", 2)
                parameter("timezone", "auto")
                parameter("daily", "apparent_temperature_max,precipitation_probability_max")
                parameter(
                    "hourly",
                    "apparent_temperature,temperature_2m,precipitation_probability," +
                        "wind_speed_10m,relative_humidity_2m,cloud_cover,weather_code",
                )
                parameter("models", models.joinToString(","))
            }.body<MultiModelResponse>()
        }

        val confidence = computeConfidence(response.daily)
        val hourly = parseHourly(response.hourly)
        if (confidence == null && hourly == null) null else MultiModelData(confidence, hourly)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        logger.log("confidence fetch failed", t)
        null
    }

    private fun computeConfidence(daily: JsonObject): ConfidenceInfo? {
        val results = buildList {
            for (model in models) {
                when (val outcome = readModelDaily(daily, model)) {
                    is ReadOutcome.Usable -> add(model to outcome.values)
                    is ReadOutcome.Dropped ->
                        logger.log("model $model dropped: ${outcome.reason}")
                }
            }
        }
        if (results.size < 2) {
            logger.log(
                "only ${results.size} of ${models.size} models reported usable values; " +
                    "returning null",
            )
            return null
        }
        return compute(results)
    }

    private fun parseHourly(hourly: JsonObject?): PerModelHourly? {
        val obj = hourly ?: return null
        val times = (obj["time"] as? JsonArray) ?: return null
        val byModel = buildMap<String, List<PerModelHour>> {
            for (model in models) {
                val apparentTemps = obj["apparent_temperature_$model"] as? JsonArray
                val airTemps = obj["temperature_2m_$model"] as? JsonArray
                val precips = obj["precipitation_probability_$model"] as? JsonArray
                val winds = obj["wind_speed_10m_$model"] as? JsonArray
                val humidities = obj["relative_humidity_2m_$model"] as? JsonArray
                val clouds = obj["cloud_cover_$model"] as? JsonArray
                val weatherCodes = obj["weather_code_$model"] as? JsonArray
                if (apparentTemps == null && airTemps == null && precips == null) continue
                val entries = buildList {
                    for (i in 0 until times.size) {
                        val time = parseHour(times.getOrNull(i)) ?: continue
                        // Required: time, apparent temp, air temp, precip — drop the hour
                        // when any of these are null. Diagnostic fields (wind, humidity,
                        // cloud, condition) survive per-field nulls; we just carry through
                        // what we got so the diagnostic charts and the consensus blend hide
                        // that model only when *its* field is missing.
                        val apparent = numberAt(apparentTemps, i)?.toDouble() ?: continue
                        val air = numberAt(airTemps, i)?.toDouble() ?: continue
                        val precip = numberAt(precips, i)?.toDouble() ?: continue
                        add(
                            PerModelHour(
                                time = time,
                                apparentTemperatureC = apparent,
                                temperatureC = air,
                                precipitationProbabilityPct = precip,
                                windSpeedKmh = numberAt(winds, i)?.toDouble(),
                                relativeHumidityPct = numberAt(humidities, i)?.toDouble(),
                                cloudCoverPct = numberAt(clouds, i)?.toDouble(),
                                condition = numberAt(weatherCodes, i)?.toInt()
                                    ?.let { WmoCodeMapper.map(it) },
                            ),
                        )
                    }
                }
                if (entries.isNotEmpty()) put(model, entries)
            }
        }
        return if (byModel.isEmpty()) null else PerModelHourly(byModel)
    }

    private fun parseHour(element: JsonElement?): LocalDateTime? {
        val text = (element as? JsonPrimitive)?.contentOrNull ?: return null
        return runCatching { LocalDateTime.parse(text) }.getOrNull()
    }

    private fun readModelDaily(daily: JsonObject, model: String): ReadOutcome {
        val tempMax = numberAt(daily["apparent_temperature_max_$model"] as? JsonArray, 0)?.toDouble()
        val precipMax = numberAt(daily["precipitation_probability_max_$model"] as? JsonArray, 0)?.toDouble()
        return when {
            tempMax == null && precipMax == null ->
                ReadOutcome.Dropped("both apparent_temperature_max and precipitation_probability_max missing or null")
            tempMax == null ->
                ReadOutcome.Dropped("apparent_temperature_max missing or null")
            precipMax == null ->
                ReadOutcome.Dropped("precipitation_probability_max missing or null")
            else ->
                ReadOutcome.Usable(ModelDailyValues(tempMax, precipMax))
        }
    }

    private fun numberAt(array: JsonArray?, index: Int): Number? {
        val element = array?.getOrNull(index) ?: return null
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        return primitive.doubleOrNull ?: primitive.intOrNull
    }

    private fun compute(results: List<Pair<String, ModelDailyValues>>): ConfidenceInfo {
        val temps = results.map { it.second.tempMaxC }
        val precips = results.map { it.second.precipMaxPp }
        val tempSpread = temps.max() - temps.min()
        val precipSpread = precips.max() - precips.min()

        val level = when {
            tempSpread <= TEMP_HIGH_AGREEMENT_C && precipSpread <= PRECIP_HIGH_AGREEMENT_PP ->
                ForecastConfidence.HIGH
            tempSpread <= TEMP_MEDIUM_AGREEMENT_C && precipSpread <= PRECIP_MEDIUM_AGREEMENT_PP ->
                ForecastConfidence.MEDIUM
            else -> ForecastConfidence.LOW
        }

        return ConfidenceInfo(
            level = level,
            tempSpreadC = tempSpread,
            precipSpreadPp = precipSpread,
            modelsConsulted = results.map { it.first },
        )
    }

    private data class ModelDailyValues(val tempMaxC: Double, val precipMaxPp: Double)

    private sealed class ReadOutcome {
        data class Usable(val values: ModelDailyValues) : ReadOutcome()
        data class Dropped(val reason: String) : ReadOutcome()
    }

    companion object {
        // Three models with global coverage so the spread is meaningful regardless of
        // where the user is. Could be made user-tunable later (MODELS.md idea).
        val DEFAULT_MODELS = listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless")

        // Thresholds are deliberate first-pass guesses; refine with real data.
        // Both temp and precip have to clear the bar for HIGH; either dropping
        // moves us down a tier.
        internal const val TEMP_HIGH_AGREEMENT_C = 1.5
        internal const val TEMP_MEDIUM_AGREEMENT_C = 3.0
        internal const val PRECIP_HIGH_AGREEMENT_PP = 15.0
        internal const val PRECIP_MEDIUM_AGREEMENT_PP = 30.0
    }
}

internal data class MultiModelData(
    val confidence: ConfidenceInfo?,
    val hourly: PerModelHourly?,
)

@Serializable
private data class MultiModelResponse(
    val daily: JsonObject,
    val hourly: JsonObject? = null,
)

