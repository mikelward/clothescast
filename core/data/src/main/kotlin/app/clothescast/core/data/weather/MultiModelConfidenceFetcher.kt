package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.Location
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.URLProtocol
import io.ktor.http.path
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches today's apparent-max temperature and peak precipitation probability from
 * several Open-Meteo models in a single request, computes the cross-model spread,
 * and maps to a [ConfidenceInfo]. When the major models agree, we surface "High
 * confidence"; when they disagree, "Low confidence — forecasts disagree".
 *
 * Open-Meteo lets us pass a comma-separated `models=` list and returns each
 * requested daily field once per model, suffixed with the model name (e.g.
 * `apparent_temperature_max_ecmwf_ifs04`). Issuing one request instead of N saves
 * 2 of 5 weather calls per insight cycle and avoids per-model retry storms.
 *
 * Best-effort: any failure (network, parse error) falls through to null. Per-model
 * failures (server returns nulls or omits a model's fields) drop just that model;
 * we still return a [ConfidenceInfo] as long as at least two models reported
 * usable values.
 */
internal class MultiModelConfidenceFetcher(
    private val httpClient: HttpClient,
    private val models: List<String> = DEFAULT_MODELS,
) {
    suspend fun fetch(location: Location): ConfidenceInfo? = try {
        val daily: JsonObject = httpClient.get {
            url {
                protocol = URLProtocol.HTTPS
                host = OPEN_METEO_HOST
                path("v1", "forecast")
            }
            parameter("latitude", location.latitude)
            parameter("longitude", location.longitude)
            parameter("forecast_days", 1)
            parameter("timezone", "auto")
            parameter("daily", "apparent_temperature_max,precipitation_probability_max")
            parameter("models", models.joinToString(","))
        }.body<ConfidenceResponse>().daily

        val results = models.mapNotNull { model -> readModel(daily, model)?.let { model to it } }
        if (results.size < 2) null else compute(results)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    }

    private fun readModel(daily: JsonObject, model: String): ModelDailyValues? {
        val tempMax = firstNumber(daily["apparent_temperature_max_$model"])?.toDouble()
            ?: return null
        val precipMax = firstNumber(daily["precipitation_probability_max_$model"])?.toDouble()
            ?: return null
        return ModelDailyValues(tempMax, precipMax)
    }

    private fun firstNumber(element: JsonElement?): Number? {
        val array = (element as? JsonArray) ?: return null
        val first = array.firstOrNull() ?: return null
        val primitive = runCatching { first.jsonPrimitive }.getOrNull() ?: return null
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

@Serializable
private data class ConfidenceResponse(
    val daily: JsonObject,
)
