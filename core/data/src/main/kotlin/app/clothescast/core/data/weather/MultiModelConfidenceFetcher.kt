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
    private val logger: ConfidenceFetchLogger = NoOpConfidenceFetchLogger,
    private val apiCallLogger: ApiCallLogger = NoOpApiCallLogger,
) {
    suspend fun fetch(
        location: Location,
        models: List<String> = DEFAULT_MODELS,
    ): MultiModelData? = try {
        // Tolerate a degenerate user selection by falling back to defaults rather
        // than firing a `models=` parameter that Open-Meteo would reject. The
        // settings UI keeps the picker pinned to ≥ 2 enabled, so this branch is
        // a hand-edited-DataStore safety net more than a user-reachable path.
        val effectiveModels = models.takeIf { it.isNotEmpty() } ?: DEFAULT_MODELS
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
                // forecast_days=14 keeps the per-model series aligned with the
                // primary call's 14-day window so both Today-screen week pages
                // ("Next 7 days" and "Following 7 days") can render their
                // per-model diagnostic cards (wind, humidity, cloud, solar, UV,
                // sunshine) and model-spread overlays. Not every model runs the
                // full 14 days (ICON stops at day 7; ECMWF coarsens past ~day 6),
                // so the second week's spread is naturally sparser — the page's
                // coverage gate auto-hides diagnostics for any day a model
                // doesn't reach. The tonight insight's evening tie-in still
                // reads tomorrow's pre-dawn hours out of the same series.
                // Confidence aggregates read `daily[0]` (today's value), so
                // widening the window doesn't disturb the tier calculation
                // downstream.
                parameter("forecast_days", 14)
                parameter("timezone", "auto")
                parameter("daily", "apparent_temperature_max,precipitation_probability_max")
                // cloud_cover_low (the deck below ~2 km) rather than total
                // column: total picks up cirrus that looks cloudy on satellite
                // but barely dims the sun, and the user-facing question
                // ("will it feel gloomy / overcast?") is answered by the low
                // deck. shortwave_radiation / sunshine_duration / uv_index
                // ride the same call for free and feed the new solar /
                // sunshine / UV diagnostic cards.
                parameter(
                    "hourly",
                    "apparent_temperature,temperature_2m,precipitation_probability,precipitation," +
                        "wind_speed_10m,relative_humidity_2m,cloud_cover_low," +
                        "shortwave_radiation,sunshine_duration,uv_index,weather_code",
                )
                parameter("models", effectiveModels.joinToString(","))
            }.body<MultiModelResponse>()
        }

        val confidence = computeConfidence(response.daily, effectiveModels)
        val hourly = parseHourly(response.hourly, effectiveModels)
        if (confidence == null && hourly == null) null else MultiModelData(confidence, hourly)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        logger.log("confidence fetch failed", t)
        null
    }

    private fun computeConfidence(daily: JsonObject, models: List<String>): ConfidenceInfo? {
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

    private fun parseHourly(hourly: JsonObject?, models: List<String>): PerModelHourly? {
        val obj = hourly ?: return null
        val times = (obj["time"] as? JsonArray) ?: return null
        val byModel = buildMap<String, List<PerModelHour>> {
            for (model in models) {
                val apparentTemps = obj["apparent_temperature_$model"] as? JsonArray
                val airTemps = obj["temperature_2m_$model"] as? JsonArray
                val precips = obj["precipitation_probability_$model"] as? JsonArray
                val precipMm = obj["precipitation_$model"] as? JsonArray
                val winds = obj["wind_speed_10m_$model"] as? JsonArray
                val humidities = obj["relative_humidity_2m_$model"] as? JsonArray
                // We request cloud_cover_low but keep the domain field named
                // `cloudCoverPct` — the value is still a percent, just for the
                // low deck. See [PerModelHour.cloudCoverPct] for the rationale.
                val clouds = obj["cloud_cover_low_$model"] as? JsonArray
                val solar = obj["shortwave_radiation_$model"] as? JsonArray
                val sunshine = obj["sunshine_duration_$model"] as? JsonArray
                // UV index per-model: Open-Meteo exposes `uv_index_$model` on
                // most models. When a particular model omits it the field is
                // simply absent for that model and the UV card hides its line.
                val uv = obj["uv_index_$model"] as? JsonArray
                val weatherCodes = obj["weather_code_$model"] as? JsonArray
                // Hard requirement: temperature_2m. Without it the chart has
                // nothing to plot for this model and the consensus blend
                // can't average it in. Open-Meteo silently omits per-model
                // variables when a model doesn't expose them — so log the
                // drop with the reason rather than dropping silently like
                // the previous code did. Mirrors readModelDaily above.
                if (airTemps == null) {
                    logger.log("model $model dropped from hourly: temperature_2m missing")
                    continue
                }
                // Soft requirements: log per-model when apparent_temperature
                // or precipitation_probability is wholesale missing so the
                // user can see why a chart looks flat (precip) or follows
                // raw air (apparent). The model still renders — `apparent`
                // falls back to `air`, `precip` falls back to 0% per hour
                // below — but the cause is logged so a Diagnostics scrape
                // explains the surprise.
                if (apparentTemps == null) {
                    logger.log("model $model hourly: apparent_temperature missing, falling back to temperature_2m")
                }
                if (precips == null) {
                    logger.log("model $model hourly: precipitation_probability missing, precip-specific aggregates will skip this model")
                }
                if (precipMm == null) {
                    logger.log("model $model hourly: precipitation missing, hourly-rainfall chart will skip this model")
                }
                // Diagnostic fields — each chart hides this model's line on
                // its own card when the field is wholesale absent, so the
                // user-visible failure mode is "model X has no UV line" with
                // no explanation. Log each missing diagnostic series so a
                // Diagnostics scrape carries the per-model per-variable
                // coverage map. Open-Meteo's per-model coverage varies
                // widely (GFS exposes uv_index, ICON doesn't; ECMWF exposes
                // sunshine_duration, GFS doesn't; etc.) and surfacing the
                // gaps explicitly turns a silent-empty chart into an
                // observable contract miss.
                if (winds == null) {
                    logger.log("model $model hourly: wind_speed_10m missing, wind chart will skip this model")
                }
                if (humidities == null) {
                    logger.log("model $model hourly: relative_humidity_2m missing, humidity chart will skip this model")
                }
                if (clouds == null) {
                    logger.log("model $model hourly: cloud_cover_low missing, cloud chart will skip this model")
                }
                if (solar == null) {
                    logger.log("model $model hourly: shortwave_radiation missing, solar chart will skip this model")
                }
                if (sunshine == null) {
                    logger.log("model $model hourly: sunshine_duration missing, sunshine chart will skip this model")
                }
                if (uv == null) {
                    logger.log("model $model hourly: uv_index missing, UV chart will skip this model")
                }
                if (weatherCodes == null) {
                    logger.log("model $model hourly: weather_code missing, consensus condition will skip this model's vote")
                }
                val entries = buildList {
                    for (i in 0 until times.size) {
                        val time = parseHour(times.getOrNull(i)) ?: continue
                        // Required per-hour: time and air temp. Apparent
                        // falls back to air temp when missing (the
                        // feels-like curve degrades to raw 2 m air for
                        // that model). Precip stays null when missing —
                        // do NOT fall back to 0 because the precipitation
                        // chart, consensus blend, and insight rain prose
                        // all need to distinguish "model predicts no rain"
                        // from "model didn't provide a precip forecast"
                        // and a synthetic zero silently downgrades all of
                        // them. Diagnostic fields (wind, humidity, cloud,
                        // condition) survive per-field nulls.
                        val air = numberAt(airTemps, i)?.toDouble() ?: continue
                        val apparent = numberAt(apparentTemps, i)?.toDouble() ?: air
                        val precip = numberAt(precips, i)?.toDouble()
                        add(
                            PerModelHour(
                                time = time,
                                apparentTemperatureC = apparent,
                                temperatureC = air,
                                precipitationProbabilityPct = precip,
                                precipitationMm = numberAt(precipMm, i)?.toDouble(),
                                windSpeedKmh = numberAt(winds, i)?.toDouble(),
                                relativeHumidityPct = numberAt(humidities, i)?.toDouble(),
                                cloudCoverPct = numberAt(clouds, i)?.toDouble(),
                                shortwaveRadiationWm2 = numberAt(solar, i)?.toDouble(),
                                sunshineDurationSec = numberAt(sunshine, i)?.toDouble(),
                                uvIndex = numberAt(uv, i)?.toDouble(),
                                condition = numberAt(weatherCodes, i)?.toInt()
                                    ?.let { WmoCodeMapper.map(it) },
                            ),
                        )
                    }
                }
                if (entries.isEmpty()) {
                    logger.log("model $model dropped from hourly: no usable hours after parsing")
                } else {
                    put(model, entries)
                }
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
            tempSpread <= ConfidenceInfo.TEMP_HIGH_AGREEMENT_C &&
                precipSpread <= ConfidenceInfo.PRECIP_HIGH_AGREEMENT_PP ->
                ForecastConfidence.HIGH
            tempSpread <= ConfidenceInfo.TEMP_MEDIUM_AGREEMENT_C &&
                precipSpread <= ConfidenceInfo.PRECIP_MEDIUM_AGREEMENT_PP ->
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

