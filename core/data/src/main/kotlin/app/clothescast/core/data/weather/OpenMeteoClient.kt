package app.clothescast.core.data.weather

import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.diag.ApiEndpoints
import app.clothescast.core.data.diag.NoOpApiCallLogger
import app.clothescast.core.data.diag.instrument
import app.clothescast.core.domain.model.DailyForecast
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

// Per-model map key for Google's forecast. Matches ForecastModel.GOOGLE_WEATHER's
// openMeteoId so the chart's MODEL_DRAW_ORDER, the palette, and the legend all
// reach it like any other forecaster. Distinct from every Open-Meteo id and from
// best_match, so it's one more equal-weight vote in [blendConsensusHourly] and a
// regular entry for ConfidenceInfo.computeFrom / ModelDivergenceSummary.
internal const val GOOGLE_MODEL_ID = "google"

/**
 * Open-Meteo `forecast` endpoint with past_days=1&forecast_days=14, returning yesterday's
 * actuals plus a 14-day forecast in one call. Free, key-less. Free-tier soft cap is
 * 10k requests/day; this app makes one call per device per day.
 *
 * `forecast_days=14` covers the Today screen's two week pages — "Next 7 days"
 * (days 1-7) and "Following 7 days" (days 8-14). Tomorrow's
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
    /**
     * Optional supplier of an extra per-model hourly series to fold in as **just
     * another forecaster** — used to add Google's Maps-Platform Weather forecast
     * alongside the Open-Meteo models when the user has selected the Google
     * forecaster *and* has a Gemini key configured (the `:app` wiring checks both
     * and resolves the key; see [GoogleWeatherModelClient]). Takes the request's
     * [Location] and returns the Google series, or null when Google shouldn't /
     * couldn't contribute (not selected, no key, or the best-effort fetch
     * failed). Defaults to null so tests and any caller without it behave exactly
     * as before.
     *
     * The returned series is stored under the "google" id in
     * [ForecastBundle.perModelHourly], so it's a full peer everywhere downstream:
     * it draws on the charts, votes in the confidence chip + divergence hint
     * (via ConfidenceInfo.computeFrom / ModelDivergenceSummary), and votes in the
     * consensus blend.
     */
    private val extraModelHourly: (suspend (Location) -> List<PerModelHour>?)? = null,
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
        // Google's forecast (when a key is configured) rides alongside the
        // Open-Meteo calls so its latency hides behind the primary fetch.
        val googleModel = extraModelHourly?.let { provider -> async { provider(location) } }

        val response = primary.await()
        val bundle = OpenMeteoMapper.toBundle(response)
        val multi = multiModel.await()
        val googleHours = googleModel?.await()

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
        // Stash best_match alongside the consulted models in [PerModelHourly]
        // before passing into the consensus blender so the blender sees it
        // as a regular model — equal-weight inclusion of best_match is the
        // posture documented on [blendConsensusHourly], and an earlier
        // ordering of these two blocks regressed that by passing the
        // pre-injection map (Codex caught it on PR review).
        //
        // Built from the wire payload, not the mapped [HourlyForecast]s: the
        // mapper drops hours whose temperature_2m is null but still folds
        // null precip to 0 % for the chart, and feeding that synthetic zero
        // into [blendConsensusHourly] would let a horizon-edge null cast a
        // fake dry vote into the consensus mean — exactly what
        // MultiModelConfidenceFetcher.parseHourly avoids for the consulted
        // models by keeping precip null.
        //
        // The raw hourly stream spans yesterday through day 14, so best_match
        // covers the same forecast_days=14 window the consulted models carry —
        // the equal-weight policy [blendConsensusHourly] documents holds on
        // every forward day (an earlier today-plus-tomorrow-only version made
        // near and later days blend differently; Codex caught it), so
        // best_match stays a real vote in the consensus blend on both sides of
        // the midnight boundary.
        val tomorrowDate = bundle.today.date.plusDays(1)
        val bestMatchPerModel = response.hourly.asBestMatchPerModelHours()

        // Fold best_match and (when the user enabled it) Google into the
        // per-model map. Google is stored alongside the Open-Meteo source models
        // — not split into a blend-only input — so it's treated as just another
        // forecaster everywhere downstream: it draws on the charts
        // (MODEL_DRAW_ORDER reaches it via its "google" id), votes in the
        // confidence chip and divergence hint (ConfidenceInfo.computeFrom /
        // ModelDivergenceSummary read this map), and votes in the consensus
        // blend. When the Open-Meteo side-band fetch failed but Google returned,
        // best_match + Google still clear the blender's two-model bar, so Google
        // can carry the consensus on its own.
        val extraSeries: Map<String, List<PerModelHour>> = buildMap {
            put(PerModelHourly.BEST_MATCH_MODEL_ID, bestMatchPerModel)
            googleHours?.takeIf { it.isNotEmpty() }?.let { put(GOOGLE_MODEL_ID, it) }
        }
        val perModelWithBestMatch: PerModelHourly? = when {
            multi?.hourly != null -> multi.hourly.copy(byModel = multi.hourly.byModel + extraSeries)
            // No consulted Open-Meteo series. With Google present, best_match +
            // Google still blend; without it, best_match alone isn't a consensus,
            // so leave the map null exactly as before.
            googleHours.isNullOrEmpty() -> null
            else -> PerModelHourly(byModel = extraSeries)
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
        // models are fetched at forecast_days=14, so both weeks can follow
        // the consensus. This keeps the week charts' combined line, the
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

    // Mirrors MultiModelConfidenceFetcher.parseHourly's null policy: an hour
    // without temperature_2m is dropped (no synthetic 0 °C vote), apparent
    // falls back to air, and the precipitation fields stay null when absent so
    // precip-specific aggregates skip them instead of counting a fake dry hour.
    private fun HourlyData.asBestMatchPerModelHours(): List<PerModelHour> =
        buildList {
            for (i in time.indices) {
                val ts = runCatching { LocalDateTime.parse(time[i]) }.getOrNull() ?: continue
                // Yesterday's hours stay in: both this call and the side-band
                // confidence call fetch past_days=1, so the consulted models'
                // series reach back to yesterday 00:00 — filtering best_match
                // to today made the "Auto" line alone start at midnight on the
                // Overnight chart's pre-midnight hours. Day-keyed consumers
                // (the consensus blend) never look yesterday up, so the extra
                // entries are inert outside the charts.
                val air = temperature.getOrNull(i) ?: continue
                add(
                    PerModelHour(
                        time = ts,
                        apparentTemperatureC = feelsLike.getOrNull(i) ?: air,
                        temperatureC = air,
                        precipitationProbabilityPct = precipitationProbability.getOrNull(i)?.toDouble(),
                        precipitationMm = precipitation.getOrNull(i),
                        // Wind and UV ride the primary call too, so best_match
                        // votes in the wind / UV consensus and draws on those
                        // diagnostic charts like any consulted model. Humidity,
                        // cloud, solar, and sunshine ride only the side-band
                        // multi-model call, so they stay null — "no data for
                        // this metric" rather than a synthetic zero.
                        windSpeedKmh = windSpeed.getOrNull(i),
                        uvIndex = uvIndex.getOrNull(i),
                        condition = weatherCode.getOrNull(i)?.let { WmoCodeMapper.map(it) },
                    ),
                )
            }
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
                // forecast_days=14 covers the Today screen's two week pages —
                // "Next 7 days" (days 1-7) and "Following 7 days" (days 8-14).
                // Open-Meteo's /v1/forecast accepts up to 16 days at the same
                // resolution and JSON shape, so the second week comes back in
                // the same fields the mapper already parses; it just reads as a
                // daily trend (per-model agreement thins past ~day 7). Tomorrow's
                // pre-dawn hourly entries (which the tonight insight reads via
                // [ForecastBundle.tomorrowHourly] to wrap past midnight) come
                // along for free in the same call, so we don't issue a second
                // fetch.
                parameter("forecast_days", 14)
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
