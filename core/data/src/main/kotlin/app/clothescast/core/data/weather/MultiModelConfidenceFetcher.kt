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
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLProtocol
import io.ktor.http.path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.LocalDateTime

/**
 * Fetches today's apparent high and low temperature and peak precipitation
 * probability from several Open-Meteo models in a single request, computes the
 * cross-model spread (the wider of the high- and low-temperature disagreement,
 * plus precip), and maps to a [ConfidenceInfo]. When the major models agree, we
 * surface "High confidence"; when they disagree, "Low confidence — forecasts
 * disagree".
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
 * Best-effort: any failure (network, parse error) falls through to null. A model
 * is dropped only when its required daily field — `apparent_temperature_max`, the
 * temperature vote — is missing or null; a model that omits the soft fields
 * (`apparent_temperature_min`, `precipitation_probability_max`, which Open-Meteo
 * leaves out for several models) still contributes, sitting out just the
 * spread term it lacks. We still return a [ConfidenceInfo] as long as at least
 * two models reported a usable daily high, and a [PerModelHourly] when at least
 * one model reported usable hourly values. Every drop and every soft skip is
 * reported through [logger] so the caller can surface why the chip didn't render.
 *
 * Invalid-model resilience: Open-Meteo rejects the *entire* batched request with
 * HTTP 400 if any one `models=` id is invalid or withdrawn (it names the bad one
 * in the body). Rather than let a single bad id wipe the chip and every chart,
 * [fetch] drops the rejected model(s) and retries with the rest — so a typo'd or
 * retired id costs only its own line, not the whole feature.
 */
internal class MultiModelConfidenceFetcher(
    private val httpClient: HttpClient,
    private val logger: ConfidenceFetchLogger = NoOpConfidenceFetchLogger,
    private val apiCallLogger: ApiCallLogger = NoOpApiCallLogger,
) {
    suspend fun fetch(
        location: Location,
        models: List<String> = DEFAULT_MODELS,
    ): MultiModelData? {
        // Tolerate a degenerate user selection by falling back to defaults rather
        // than firing a `models=` parameter that Open-Meteo would reject. The
        // settings UI keeps the picker pinned to ≥ 2 enabled, so this branch is
        // a hand-edited-DataStore safety net more than a user-reachable path.
        var remaining = models.takeIf { it.isNotEmpty() } ?: DEFAULT_MODELS
        var attempts = 0
        var transientAttempts = 0
        while (true) {
            attempts++
            val response = try {
                requestModels(location, remaining)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: ResponseException) {
                // Open-Meteo answers the whole batched request with HTTP 400 when
                // any single `models=` entry is invalid or withdrawn, naming the
                // offending model in the error body. Because `expectSuccess` turns
                // that into a throw, one bad id would otherwise take the confidence
                // chip *and* every per-model chart down with it (the catch-all
                // below returns null). Instead, drop the model(s) the server
                // complained about and retry with the rest, so the feature
                // degrades to the surviving models rather than vanishing. Each
                // retry strictly shrinks the list, so the loop terminates; if we
                // can't identify a model to drop we fall through to null like any
                // other failure.
                val status = e.response.status.value
                // Read the error body to find which model(s) the server named.
                // Don't use runCatching here: reading the body suspends, and a
                // blanket catch would swallow a CancellationException (cancelled
                // while suspended) and mask it as an empty body — breaking
                // structured concurrency. Rethrow cancellation; treat any other
                // read failure as "no body to parse".
                val body = try {
                    e.response.bodyAsText()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (readError: Throwable) {
                    logger.log("could not read error body for status $status", readError)
                    ""
                }
                // Match whole ids only: a bare substring check would also
                // "match" a requested id that happens to be a prefix of the
                // one the server named (Open-Meteo has such pairs, e.g.
                // icon_d2 / icon_d2_15min) and prune a perfectly valid model
                // alongside the bad one. Ids are word characters throughout
                // ([a-z0-9_]), so \b anchors exactly at their edges.
                val rejected = remaining.filter {
                    it.isNotBlank() && Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(body)
                }
                if (status != HTTP_BAD_REQUEST || rejected.isEmpty() || attempts > MAX_FETCH_ATTEMPTS) {
                    logger.log("confidence fetch failed (status $status)", e)
                    return null
                }
                val pruned = remaining - rejected.toSet()
                if (pruned.isEmpty()) {
                    logger.log("confidence fetch failed: Open-Meteo rejected every requested model ($rejected)", e)
                    return null
                }
                logger.log("Open-Meteo rejected model(s) $rejected; retrying with $pruned")
                remaining = pruned
                continue
            } catch (t: Throwable) {
                // A transient network failure — a socket / connect / request
                // timeout or a mid-flight IO drop — shouldn't cost the whole
                // per-model feature for the period. The primary forecast
                // retries these via WorkManager, but this side-band fetch is
                // deliberately best-effort: its failure is swallowed (returns
                // null) so it never reaches the worker's retry path. A single
                // timeout on the 7am round-trip would otherwise drop the
                // confidence card *and* every per-model chart until the next
                // scheduled run. Retry a couple of times with a short backoff
                // here instead. Parse errors and the like aren't IOExceptions,
                // so they fall straight through to null — a malformed body
                // won't fix itself on a retry.
                if (transientAttempts < MAX_TRANSIENT_RETRIES && t.isTransientNetworkFailure()) {
                    transientAttempts++
                    logger.log(
                        "confidence fetch transient failure " +
                            "(retry $transientAttempts/$MAX_TRANSIENT_RETRIES)",
                        t,
                    )
                    delay(TRANSIENT_RETRY_BACKOFF_MS * transientAttempts)
                    continue
                }
                logger.log("confidence fetch failed", t)
                return null
            }

            val confidence = computeConfidence(response.daily, remaining)
            val hourly = parseHourly(response.hourly, remaining)
            return if (confidence == null && hourly == null) {
                null
            } else {
                MultiModelData(confidence, hourly)
            }
        }
    }

    /**
     * Issues one batched Open-Meteo request for [models] and parses the body.
     * Split out of [fetch] so it can be re-issued with a pruned model list when
     * the server rejects an invalid id. `expectSuccess = true` makes a non-2xx
     * status throw a [ResponseException], which [instrument] records with the
     * real HTTP status before rethrowing.
     */
    private suspend fun requestModels(
        location: Location,
        models: List<String>,
    ): MultiModelResponse =
        apiCallLogger.instrument(ApiEndpoints.OPEN_METEO_CONFIDENCE) {
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
                // doesn't reach. The tonight insight's evening extras still
                // reads tomorrow's pre-dawn hours out of the same series.
                // With past_days=1 below the daily arrays lead with yesterday,
                // so the confidence aggregate reads today at index 1 (see
                // todayDailyIndex / readModelDaily) — not index 0, which is now
                // yesterday.
                parameter("forecast_days", 14)
                // past_days=1 mirrors the primary OpenMeteoClient call so the
                // per-model hourly series reaches back into yesterday evening.
                // The Overnight view's window starts at ~19:00 the previous
                // day; without yesterday's hours the per-model lines only have
                // data from today 00:00 onward, so the chart shows just the
                // blended line before midnight and the per-model lines only
                // diverge after it.
                parameter("past_days", 1)
                parameter("timezone", "auto")
                parameter("daily", "apparent_temperature_max,apparent_temperature_min,precipitation_probability_max")
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
                parameter("models", models.joinToString(","))
            }.body<MultiModelResponse>()
        }

    private fun computeConfidence(daily: JsonObject, models: List<String>): ConfidenceInfo? {
        val todayIndex = todayDailyIndex(daily)
        val soleModel = models.size == 1
        val results = buildList {
            for (model in models) {
                when (val outcome = readModelDaily(daily, model, todayIndex, soleModel)) {
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
        // Open-Meteo only suffixes variables with the model id when two or
        // more models are requested; a single-entry `models=` list comes back
        // with plain names (`temperature_2m`, not `temperature_2m_<model>`).
        // See [seriesFor].
        val soleModel = models.size == 1
        val byModel = buildMap<String, List<PerModelHour>> {
            for (model in models) {
                val apparentTemps = seriesFor(obj, "apparent_temperature", model, soleModel)
                val airTemps = seriesFor(obj, "temperature_2m", model, soleModel)
                val precips = seriesFor(obj, "precipitation_probability", model, soleModel)
                val precipMm = seriesFor(obj, "precipitation", model, soleModel)
                val winds = seriesFor(obj, "wind_speed_10m", model, soleModel)
                val humidities = seriesFor(obj, "relative_humidity_2m", model, soleModel)
                // We request cloud_cover_low but keep the domain field named
                // `cloudCoverPct` — the value is still a percent, just for the
                // low deck. See [PerModelHour.cloudCoverPct] for the rationale.
                val clouds = seriesFor(obj, "cloud_cover_low", model, soleModel)
                val solar = seriesFor(obj, "shortwave_radiation", model, soleModel)
                val sunshine = seriesFor(obj, "sunshine_duration", model, soleModel)
                // UV index per-model: Open-Meteo exposes `uv_index_$model` on
                // most models. When a particular model omits it the field is
                // simply absent for that model and the UV card hides its line.
                val uv = seriesFor(obj, "uv_index", model, soleModel)
                val weatherCodes = seriesFor(obj, "weather_code", model, soleModel)
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

    /**
     * Index of today's entry within the daily arrays. The confidence call uses
     * `past_days=1`, so Open-Meteo leads the daily arrays with yesterday (index
     * 0) and today sits at index 1 — mirror [OpenMeteoMapper], which reads today
     * the same way. A degenerate response with a single daily entry (no past
     * day) falls back to index 0 so the aggregate still describes the only day
     * present rather than dropping every model on an out-of-bounds read.
     */
    private fun todayDailyIndex(daily: JsonObject): Int {
        val times = daily["time"] as? JsonArray ?: return 0
        return if (times.size >= 2) 1 else 0
    }

    /**
     * The per-model series for [field], honoring Open-Meteo's suffix rule:
     * with two or more `models=` entries every variable is suffixed with the
     * model id (`temperature_2m_gfs_seamless`), but a single-model request
     * comes back with plain, unsuffixed names. Without the fallback a
     * one-model fetch — reachable when the picker's selection filters down
     * to a single Open-Meteo id, or when the invalid-model prune-and-retry
     * leaves one survivor — parses to nothing and silently drops the whole
     * per-model feature.
     */
    private fun seriesFor(
        obj: JsonObject,
        field: String,
        model: String,
        soleModel: Boolean,
    ): JsonArray? =
        (obj["${field}_$model"] as? JsonArray)
            ?: if (soleModel) obj[field] as? JsonArray else null

    private fun readModelDaily(
        daily: JsonObject,
        model: String,
        todayIndex: Int,
        soleModel: Boolean,
    ): ReadOutcome {
        val tempMax = numberAt(seriesFor(daily, "apparent_temperature_max", model, soleModel), todayIndex)?.toDouble()
        // Soft field: the daily low feeds the low-disagreement half of the
        // temp spread, but a model that reports a high without a low still
        // contributes its high. When missing it simply drops out of the
        // low-spread calculation (see [compute]) rather than dropping the
        // whole model.
        val tempMin = numberAt(seriesFor(daily, "apparent_temperature_min", model, soleModel), todayIndex)?.toDouble()
        // Soft field: Open-Meteo omits daily precipitation_probability_max for
        // several models (verified 2026-06-09 against the live API — among
        // DEFAULT_MODELS, ecmwf_aifs025_single returns null at all three of
        // lat 51.5/lon -0.12, lat 40.7/lon -74.0, lat 35.7/lon 139.7; the
        // ukmo_seamless / jma_seamless / meteofrance_seamless / arpege_seamless
        // selections omit it everywhere too). A model that reports a high
        // without a precip max still contributes its high (and low). When
        // missing it drops out of the precip-spread calculation (see
        // [compute]) rather than dropping the whole model — mirrors the hourly
        // path's soft precip handling and the daily-low handling above.
        // apparent_temperature_max stays the only hard requirement: without it
        // the model has no temperature vote to cast.
        val precipMax = numberAt(seriesFor(daily, "precipitation_probability_max", model, soleModel), todayIndex)?.toDouble()
        if (precipMax == null) {
            logger.log("model $model daily: precipitation_probability_max missing, precip-spread will skip this model")
        }
        return when (tempMax) {
            null ->
                ReadOutcome.Dropped("apparent_temperature_max missing or null")
            else ->
                ReadOutcome.Usable(ModelDailyValues(tempMax, tempMin, precipMax))
        }
    }

    private fun numberAt(array: JsonArray?, index: Int): Number? {
        val element = array?.getOrNull(index) ?: return null
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        return primitive.doubleOrNull ?: primitive.intOrNull
    }

    private fun compute(results: List<Pair<String, ModelDailyValues>>): ConfidenceInfo {
        val highs = results.map { it.second.tempMaxC }
        // Models that didn't report a daily low (older payloads, or a model
        // that omits apparent_temperature_min) sit out the low-spread term.
        // With fewer than two lows we can't measure low disagreement, so it
        // contributes nothing — mirrors the precip dimension's guard.
        val lows = results.mapNotNull { it.second.tempMinC }
        // Models that didn't report a daily precip max (Open-Meteo omits the
        // per-model field for several models — see [readModelDaily]) sit out
        // the precip-spread term. With fewer than two reporters we can't
        // measure precip disagreement, so it contributes nothing — including a
        // model with a fake zero would silently inflate divergence on a calm
        // day and downgrade confidence. Mirrors the low-spread guard and
        // [ConfidenceInfo.computeFrom]'s hourly precip handling.
        val precips = results.mapNotNull { it.second.precipMaxPp }
        val highSpread = highs.max() - highs.min()
        val lowSpread = if (lows.size >= 2) lows.max() - lows.min() else 0.0
        // Disagreement on the day's high *or* its low downgrades confidence.
        val tempSpread = maxOf(highSpread, lowSpread)
        val precipSpread = if (precips.size >= 2) precips.max() - precips.min() else 0.0

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

    private data class ModelDailyValues(
        val tempMaxC: Double,
        val tempMinC: Double?,
        val precipMaxPp: Double?,
    )

    private sealed class ReadOutcome {
        data class Usable(val values: ModelDailyValues) : ReadOutcome()
        data class Dropped(val reason: String) : ReadOutcome()
    }

    companion object {
        // Safety-net default when the caller passes an empty model list (a
        // hand-edited DataStore could). Mirrors ForecastModel.DEFAULTS: the
        // ECMWF (0.25°) / GFS / ICON trio for the high-res first week, plus
        // GEM and AIFS so two-plus models keep reporting through the second
        // week once ICON drops out at ~day 7. The real selection flows in via
        // the `models` parameter (SettingsRepository → defaultsFor).
        val DEFAULT_MODELS =
            listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless", "gem_seamless", "ecmwf_aifs025_single")

        // Open-Meteo's status for an invalid/withdrawn `models=` entry.
        private const val HTTP_BAD_REQUEST = 400

        // Hard cap on prune-and-retry rounds. Each round drops at least one
        // model, so the loop already terminates on its own; this is a
        // belt-and-suspenders bound against a pathological model list (and
        // keeps a worst case of a handful of requests, not an unbounded storm).
        private const val MAX_FETCH_ATTEMPTS = 6

        // Transient-network retry budget. A socket timeout on this best-effort
        // side-band fetch is swallowed before it can reach the worker's
        // WorkManager retry, so without an internal retry one 7am timeout drops
        // the confidence card and every per-model chart for the whole period.
        // Two extra attempts with a short linear backoff clear the common
        // one-off timeout without stalling the round-trip for long when the
        // network is genuinely down.
        private const val MAX_TRANSIENT_RETRIES = 2
        private const val TRANSIENT_RETRY_BACKOFF_MS = 500L
    }
}

// True when [this] (or any cause in its chain) is an IOException — the socket /
// connect / request timeouts and generic network drops a retry might clear.
// java.net.SocketTimeoutException, Ktor's socket/connect timeout types, and
// HttpRequestTimeoutException all extend IOException; a SerializationException
// or other parse failure does not, so it isn't retried.
private fun Throwable.isTransientNetworkFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is IOException }

internal data class MultiModelData(
    val confidence: ConfidenceInfo?,
    val hourly: PerModelHourly?,
)

@Serializable
private data class MultiModelResponse(
    val daily: JsonObject,
    val hourly: JsonObject? = null,
)

