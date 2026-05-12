package app.clothescast.core.data.weather

import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.diag.ApiEndpoints
import app.clothescast.core.data.diag.NoOpApiCallLogger
import app.clothescast.core.data.diag.instrument
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.WeatherAlert
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
        // Primary forecast and the side-band fetches all kick off in parallel — confidence
        // adds three more model calls, so doing them concurrently with the alerts call
        // hides their latency behind the primary fetch.
        val primary = async { fetchPrimary(location) }
        val alerts = async { fetchAlerts(location) }
        val confidence = async { confidenceFetcher.fetch(location) }

        val bundle = OpenMeteoMapper.toBundle(primary.await())
        bundle.copy(
            alerts = alerts.await(),
            confidence = confidence.await(),
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
