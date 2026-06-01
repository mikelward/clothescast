package app.clothescast.core.data.weather

import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.diag.ApiEndpoints
import app.clothescast.core.data.diag.NoOpApiCallLogger
import app.clothescast.core.data.diag.instrument
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDateTime
import java.time.LocalDate

internal const val OPEN_METEO_HOST = "api.open-meteo.com"

/**
 * Open-Meteo `forecast` endpoint with past_days=1&forecast_days=7, returning yesterday's
 * actuals plus a 7-day forecast in one call. Free, key-less. Free-tier soft cap is
 * 10k requests/day; this app makes one call per device per day.
 *
 * `forecast_days=7` covers the Today screen's 7-day pager page. Tomorrow's
 * pre-dawn hourly entries — read by the tonight insight via
 * [ForecastBundle.tomorrowHourly] so its overnight low / pre-dawn rain reflect
 * what the user will actually walk into — come along for free in the same call.
 * Today's hourly is always complete regardless of `forecast_days` — Open-Meteo
 * anchors its hourly window to local 00:00 — so the count doesn't matter for
 * the today chart.
 *
 * The mapper splits the hourly stream by date so each [DailyForecast.hourly]
 * carries only that day's entries; daily aggregates flow through unchanged.
 *
 * Cross-model confidence is computed by a second path that fires several model-specific
 * forecast calls in parallel. Best-effort policy: confidence is null when the
 * extra calls fail.
 */
class OpenMeteoClient(
    private val httpClient: HttpClient,
    confidenceLogger: ConfidenceFetchLogger = NoOpConfidenceFetchLogger,
    private val apiCallLogger: ApiCallLogger = NoOpApiCallLogger,
    /**
     * Snapshot of which Open-Meteo model IDs the multi-model confidence
     * fetcher should consult on the next call. Read fresh on every
     * [fetchForecast] so a Forecasters-settings change takes effect on the
     * next refresh without rebuilding the client. Takes the request's
     * [Location] because an Auto-mode Forecasters selection resolves to a
     * different model trio depending on which region the user is in (UKMO
     * trio over the British Isles, GFS trio over North America, etc.);
     * passing it through lets the `:app` wiring run the [Location]-aware
     * resolver. Suspending so the `:app`-side wiring can
     * `settingsRepository.preferences.first()` directly without staging
     * through a StateFlow snapshot. DataStore caches the latest emission,
     * so on the warm path the read is a memory hit and runs inside the
     * same coroutine that's about to launch the parallel fetches — no
     * measurable added latency. Defaults to the three-model trio,
     * ignoring location, for tests and any caller that doesn't wire a
     * settings-backed provider.
     */
    private val confidenceModelsProvider: suspend (Location) -> List<String> =
        { _ -> MultiModelConfidenceFetcher.DEFAULT_MODELS },
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
        // Primary forecast and the side-band fetch kick off in parallel — the
        // multi-model call pulls both confidence aggregates and per-model
        // hourly series in one go, so doing it concurrently with the primary
        // fetch hides its latency behind it.
        val primary = async { fetchPrimary(location) }
        val models = confidenceModelsProvider(location)
        val multiModel = async { confidenceFetcher.fetch(location, models) }

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
        // Include best_match hours for every forward day, not just today. The
        // consulted models (ECMWF / GFS / ICON) carry the full forecast_days=7
        // window, so best_match has to span it too or the consensus silently
        // changes character partway through the week: with best_match present
        // only for today + tomorrow, days 3–7 would average the consulted
        // models *without* Open-Meteo's auto-pick, breaking the equal-weight
        // policy [blendConsensusHourly] documents and making days 1–2 and 3–7
        // blend differently (Codex caught it). It also keeps best_match a real
        // vote in [RenderInsightSummary.pickPerModelPeak]'s `majorityNeeded`
        // bar on both sides of the midnight boundary.
        //
        // Sources overlap — `tomorrowHourly` is tomorrow's pre-dawn slice and
        // `upcomingDays[0]` is tomorrow's full day — so dedupe by timestamp
        // (first wins) to avoid double-weighting best_match on the overlapping
        // hours. Today comes only from best_match's own hourly.
        val tomorrowDate = bundle.today.date.plusDays(1)
        val bestMatchPerModel =
            (bestMatchHourly.asPerModelHours(bundle.today.date) +
                bundle.tomorrowHourly.asPerModelHours(tomorrowDate) +
                bundle.upcomingDays.flatMap { it.hourly.asPerModelHours(it.date) })
                .distinctBy { it.time }
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
        //
        // Blend every forward-looking series, not just today: the consulted
        // models are fetched at forecast_days=7, so the whole week can follow
        // the consensus. This keeps the 7-day chart's combined line, the
        // week-ahead headline, and the conditions strip's week-wide peaks all
        // reading the same blended numbers — and lets the strip read wind / UV
        // straight off [HourlyForecast] (now consensus) instead of recomputing
        // the per-model mean itself. Yesterday is historical, with no per-model
        // coverage, so it stays best_match.
        fun blendDay(day: DailyForecast): DailyForecast =
            blendConsensusHourly(day.date, day.hourly, perModelWithBestMatch)
                ?.let { day.withAggregatesFrom(it) }
                ?: day

        bundle.copy(
            today = blendDay(bundle.today),
            tomorrow = bundle.tomorrow?.let { blendDay(it) },
            upcomingDays = bundle.upcomingDays.map { blendDay(it) },
            tomorrowHourly = blendConsensusHourly(tomorrowDate, bundle.tomorrowHourly, perModelWithBestMatch)
                ?: bundle.tomorrowHourly,
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
            // Both probability and amount come from the primary `/v1/forecast`
            // call for best_match, so the amount chart's best_match overlay
            // has real data on it — unlike the diagnostic fields above.
            precipitationMm = it.precipitationMm,
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
                // forecast_days=7 covers the Today screen's 7-day forecast
                // pager page. Tomorrow's pre-dawn hourly entries (which the
                // tonight insight reads via [ForecastBundle.tomorrowHourly] to
                // wrap past midnight) come along for free in the same call,
                // so we don't issue a second fetch.
                parameter("forecast_days", 7)
                parameter("timezone", "auto")
                parameter(
                    "daily",
                    "temperature_2m_min,temperature_2m_max,apparent_temperature_min,apparent_temperature_max," +
                        "precipitation_probability_max,precipitation_sum,weather_code",
                )
                parameter(
                    "hourly",
                    "temperature_2m,apparent_temperature,precipitation_probability," +
                        "precipitation,weather_code,wind_speed_10m,uv_index",
                )
            }.body()
        }
}
