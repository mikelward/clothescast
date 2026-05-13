package app.clothescast.core.data.weather

import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.diag.ApiEndpoints
import app.clothescast.core.data.diag.NoOpApiCallLogger
import app.clothescast.core.data.diag.instrument
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.model.blendConsensusHourly
import app.clothescast.core.domain.model.withAggregatesFrom
import app.clothescast.core.domain.repository.ForecastBundle
import app.clothescast.core.domain.repository.WeatherRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.URLProtocol
import io.ktor.http.path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDateTime
import java.time.LocalDate

internal const val OPEN_METEO_HOST = "api.open-meteo.com"

/**
 * Open-Meteo `forecast` endpoint with past_days=1&forecast_days=2, returning yesterday's
 * actuals plus today's and tomorrow's forecast in one call. Free, key-less. Free-tier
 * soft cap is 10k requests/day; this app makes one call per device per day.
 *
 * `forecast_days=2` (not 1) is to expose tomorrow's pre-dawn hourly entries via
 * [ForecastBundle.tomorrowHourly] so the tonight insight can wrap from 19:00 today
 * through 07:00 next morning, and its overnight low / pre-dawn rain reflect what the
 * user will actually walk into. Today's hourly is always complete regardless of
 * `forecast_days` — Open-Meteo anchors its hourly window to local 00:00 — so the
 * count doesn't matter for the today chart.
 *
 * The mapper splits today's vs tomorrow's hourly entries by date and keeps the daily
 * fields (yesterday + today only) untouched.
 *
 * Severe-weather alerts come from a second `/v1/warnings` call. The warnings endpoint
 * is best-effort: if it fails (4xx, 5xx, network), we return the forecast with an empty
 * alerts list rather than failing the whole fetch — a missing alerts feed must not
 * suppress the daily insight.
 *
 * Cross-model confidence is computed by a third path that fires several model-specific
 * forecast calls in parallel. Same best-effort policy: confidence is null when the
 * extra calls fail.
 */
class OpenMeteoClient(
    private val httpClient: HttpClient,
    confidenceLogger: ConfidenceFetchLogger = NoOpConfidenceFetchLogger,
    private val apiCallLogger: ApiCallLogger = NoOpApiCallLogger,
) : WeatherRepository {

    // Constructed once per client. Exposing it on the public constructor would
    // leak the internal type; if we ever need a test seam, add an internal-only
    // secondary constructor instead.
    private val confidenceFetcher = MultiModelConfidenceFetcher(
        httpClient,
        logger = confidenceLogger,
        apiCallLogger = apiCallLogger,
    )

    override suspend fun fetchForecast(location: Location): ForecastBundle = coroutineScope {
        // Primary forecast and the side-band fetches all kick off in parallel — the
        // multi-model call now pulls both confidence aggregates and per-model
        // hourly series in one go, so doing it concurrently with alerts hides its
        // latency behind the primary fetch.
        val primary = async { fetchPrimary(location) }
        val alerts = async { fetchAlerts(location) }
        val multiModel = async { confidenceFetcher.fetch(location) }

        val bundle = OpenMeteoMapper.toBundle(primary.await())
        val multi = multiModel.await()

        // Replace today's hourly + the derived daily extremes with the
        // consensus mean across the per-model series (ECMWF / GFS / ICON
        // plus best_match itself, all weighted equally — see
        // [blendConsensusHourly]). The previous behaviour piped
        // best_match straight through; on the diverging days the user
        // keeps catching, that single auto-selected line was the wrong
        // call. Falling back to best_match per-hour when fewer than two
        // models reported keeps a sane backstop in regions where the
        // side-band fetch is sparse, and unconditionally when the
        // multi-model fetch failed entirely.
        val bestMatchHourly = bundle.today.hourly

        // Stash best_match alongside the consulted models in [PerModelHourly]
        // before passing into the consensus blender so the blender sees it
        // as a regular model — equal-weight inclusion of best_match is the
        // posture documented on [blendConsensusHourly], and an earlier
        // ordering of these two blocks regressed that by passing the
        // pre-injection map (Codex caught it on PR review).
        //
        // Include tomorrow's best_match hours too. The multi-model fetcher
        // pulls today + tomorrow's pre-dawn (forecast_days=2) to feed the
        // evening tie-in's wrap past midnight, so the consulted models
        // (ECMWF / GFS / ICON) have tomorrow entries. If best_match's
        // entries stopped at today's 23:00, [RenderInsightSummary.pickPerModelPeak]'s
        // `majorityNeeded = models.size / 2 + 1` would compute the bar from
        // all 4 models but only 3 readings would actually exist for any
        // tomorrow hour, silently downgrading a clean 2-of-3-models-agree
        // tomorrow peak to POSSIBLE (Codex caught it). Pulling
        // [ForecastBundle.tomorrowHourly] through keeps best_match a real
        // 4th vote on both sides of the midnight boundary.
        val tomorrowDate = bundle.today.date.plusDays(1)
        val bestMatchPerModel =
            bestMatchHourly.asPerModelHours(bundle.today.date) +
                bundle.tomorrowHourly.asPerModelHours(tomorrowDate)
        val perModelWithBestMatch = multi?.hourly?.let { existing ->
            existing.copy(
                byModel = existing.byModel +
                    (PerModelHourly.BEST_MATCH_MODEL_ID to bestMatchPerModel),
            )
        }

        // Recompute daily aggregates only when at least one hour actually got
        // blended — `blendConsensusHourly` returns null when nothing changed
        // (no per-model data, fewer than two models, or no hour had ≥2 of
        // them). Without this guard we'd swap the upstream daily
        // temperatureMax (derived by Open-Meteo from its own internal model
        // steps) for a max computed from the hourly *samples*, which can
        // differ by a fraction of a degree even when the consensus didn't
        // apply anywhere.
        val blendedToday = blendConsensusHourly(bundle.today.date, bestMatchHourly, perModelWithBestMatch)
            ?.let { bundle.today.withAggregatesFrom(it) }
            ?: bundle.today

        bundle.copy(
            today = blendedToday,
            alerts = alerts.await(),
            confidence = multi?.confidence,
            perModelHourly = perModelWithBestMatch,
        )
    }

    private fun List<HourlyForecast>.asPerModelHours(date: LocalDate): List<PerModelHour> = map {
        // best_match's hourly comes from the primary `/v1/forecast` call which
        // doesn't include the diagnostic fields (wind, humidity, cloud); those
        // ride only the side-band multi-model call and aren't fetched per
        // best_match. Surface as null so the diagnostic charts treat
        // best_match as "no data for this metric" instead of dropping it.
        // The weather code *is* available on the primary call (already
        // mapped to a [WeatherCondition] in [OpenMeteoMapper]), so we pass
        // it through — the consensus blend's modal aggregation gets a 4th
        // vote from best_match this way.
        PerModelHour(
            time = LocalDateTime.of(date, it.time),
            apparentTemperatureC = it.feelsLikeC,
            temperatureC = it.temperatureC,
            precipitationProbabilityPct = it.precipitationProbabilityPct,
            condition = it.condition,
        )
    }

    private suspend fun fetchPrimary(location: Location): OpenMeteoResponse =
        apiCallLogger.instrument(ApiEndpoints.OPEN_METEO_FORECAST) {
            httpClient.get {
                // Without this, a 5xx that returns an HTML error page (Open-Meteo's
                // upstream gateway occasionally serves text/html on 502s) hits the
                // JSON deserializer first and surfaces as NoTransformationFoundException
                // — bypassing the worker's ResponseException → retry path.
                expectSuccess = true
                url {
                    protocol = URLProtocol.HTTPS
                    host = OPEN_METEO_HOST
                    path("v1", "forecast")
                }
                parameter("latitude", location.latitude)
                parameter("longitude", location.longitude)
                parameter("past_days", 1)
                parameter("forecast_days", 2)
                parameter("timezone", "auto")
                parameter(
                    "daily",
                    "temperature_2m_min,temperature_2m_max,apparent_temperature_min,apparent_temperature_max," +
                        "precipitation_probability_max,precipitation_sum,weather_code",
                )
                parameter(
                    "hourly",
                    "temperature_2m,apparent_temperature,precipitation_probability,weather_code",
                )
            }.body()
        }

    private suspend fun fetchAlerts(location: Location): List<WeatherAlert> = try {
        apiCallLogger.instrument(ApiEndpoints.OPEN_METEO_WARNINGS) {
            val response: OpenMeteoWarningsResponse = httpClient.get {
                expectSuccess = true
                url {
                    protocol = URLProtocol.HTTPS
                    host = OPEN_METEO_HOST
                    path("v1", "warnings")
                }
                parameter("latitude", location.latitude)
                parameter("longitude", location.longitude)
                parameter("timezone", "auto")
            }.body()
            OpenMeteoWarningsMapper.toAlerts(response)
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        emptyList()
    }
}
