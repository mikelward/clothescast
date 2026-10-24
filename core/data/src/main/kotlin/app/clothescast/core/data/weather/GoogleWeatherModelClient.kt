package app.clothescast.core.data.weather

import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.diag.ApiEndpoints
import app.clothescast.core.data.diag.NoOpApiCallLogger
import app.clothescast.core.data.diag.instrument
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.WeatherCondition
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

internal const val GOOGLE_WEATHER_HOST = "weather.googleapis.com"

/**
 * How a Google Weather request is authorized: with a Maps Platform key of
 * the user's own, sent straight to Google, or through the ClothesCast
 * `weather` proxy for a signed-in user, which holds the developer's key
 * (SPEC.md → "Accounts and the developer-funded paths").
 */
sealed interface GoogleWeatherAccess {
    /** The user's own key, as `X-Goog-Api-Key` to [GOOGLE_WEATHER_HOST]. */
    data class OwnKey(val apiKey: String) : GoogleWeatherAccess

    /**
     * The proxy at `https://[host]/[path]`, which forwards the same fields,
     * posted as a form body so the coordinate stays out of request logs.
     * [authorize] adds its headers (App Check, the signed-in ID token).
     */
    class Proxy(
        val host: String,
        val path: String,
        val authorize: suspend (HttpRequestBuilder) -> Unit,
    ) : GoogleWeatherAccess
}

/**
 * Outcome of [GoogleWeatherModelClient.probe] — a user-facing connectivity
 * check for the Google forecaster. Unlike the forecast path's null-on-failure
 * contract, this keeps enough detail for the Forecasters settings page to tell
 * the user whether Google will actually contribute to their charts, and what to
 * do if it won't.
 */
sealed interface GoogleWeatherProbe {
    /** The key reached the Weather API and it returned [hours] usable hours. */
    data class Reachable(val hours: Int) : GoogleWeatherProbe

    /** No Gemini key configured — there's nothing to probe with. */
    data object NoKey : GoogleWeatherProbe

    /**
     * The API rejected the user's own key with 403 PERMISSION_DENIED. The single most
     * common Google-on-the-charts failure, and the one with a concrete fix:
     * enable the Weather API + billing for the key's Google Cloud project, and
     * make sure the key's API restrictions don't exclude it.
     */
    data object Forbidden : GoogleWeatherProbe

    /**
     * Any other failure — a network drop, quota rejection (429), 5xx, a parse
     * error, or a 200 with no usable hours. [httpStatus] carries the HTTP code
     * when the failure was an HTTP error, else null.
     */
    data class Failed(val httpStatus: Int?) : GoogleWeatherProbe
}

/**
 * Fetches Google's Maps-Platform Weather forecast for a location and maps it to
 * a [PerModelHour] series so [OpenMeteoClient] can fold it into the consensus
 * blend as **one more equal-weight peer** alongside the Open-Meteo models
 * (UKMO / ECMWF / ICON / …). Google returns a single AI-blended forecast rather
 * than a raw model, but for the consensus mean it counts as one vote like any
 * other — that's the "use Google as a peer, equal weight" behaviour.
 *
 * Reuses the same Ktor [HttpClient] and [ApiCallLogger] as the Open-Meteo path.
 * The Google key is the user's existing Gemini `AIza*` key (Google's keys are
 * project-scoped Cloud keys, so a key that authorizes the Generative Language
 * API also calls `weather.googleapis.com` **when** the Weather API is enabled
 * and billing is on for that project); the `:app` wiring only invokes this when
 * a Gemini key is configured, and passes it through here.
 *
 * Best-effort, like [MultiModelConfidenceFetcher]: any failure — a 403 because
 * the key's project hasn't enabled the Weather API / lacks billing, a network
 * drop, a parse error, a quota rejection — is logged and returns null so the
 * blend proceeds on the Open-Meteo models alone. The fetch never fails the
 * surrounding forecast.
 *
 * [fetchHourly] walks Google's pagination across the [EXTENDED_HOURS]-hour
 * (7-day) horizon — Google caps each page at [PAGE_SIZE] hours and hands back a
 * `nextPageToken`, so the series is assembled across up to [MAX_PAGES] round-trips,
 * so Google votes in the consensus across the week ahead rather than just today +
 * tomorrow. The walk is expensive, so the
 * :app layer rate-limits it behind `GoogleForecastCache` (a ~12 h TTL) rather than
 * gating it per caller. A page that fails partway keeps the hours already gathered
 * (so a late network blip still contributes the near days) rather than dropping
 * Google entirely. [probe], the cold connectivity check, stays a single round-trip
 * ([NEAR_HOURS]) — it only needs to know the key reaches the API and returns usable
 * hours, not the whole horizon.
 */
class GoogleWeatherModelClient(
    private val httpClient: HttpClient,
    private val logger: ConfidenceFetchLogger = NoOpConfidenceFetchLogger,
    private val apiCallLogger: ApiCallLogger = NoOpApiCallLogger,
) {
    /**
     * Fetches the [EXTENDED_HOURS]-hour (7-day) Google forecast, walking
     * pagination as needed (~7 round-trips). The expensive walk is rate-limited
     * by the :app layer's `GoogleForecastCache` (a ~12 h TTL), so it runs at most
     * a couple of times a day regardless of how often the forecast refreshes —
     * which is why every caller can ask for the whole horizon here without a
     * cheaper "near" variant.
     */
    suspend fun fetchHourly(location: Location, apiKey: String): ZonedHourlySeries? =
        if (apiKey.isBlank()) null else fetchHourly(location, GoogleWeatherAccess.OwnKey(apiKey))

    /** [fetchHourly] through either route; see [GoogleWeatherAccess]. */
    suspend fun fetchHourly(location: Location, access: GoogleWeatherAccess): ZonedHourlySeries? {
        val (hours, zoneId) = try {
            requestAllPages(location, access)
        } catch (ce: CancellationException) {
            // Narrow the catch and rethrow cancellation so a cancelled fetch
            // doesn't get masked as "Google unavailable" and break structured
            // concurrency.
            throw ce
        } catch (t: Throwable) {
            logger.log("Google Weather fetch failed; dropping Google from the blend", t)
            return null
        }
        return parse(hours)?.let { ZonedHourlySeries(it, zoneId) }
    }

    /**
     * One-shot connectivity check for the Forecasters settings page: hits the
     * same endpoint as [fetchHourly] but keeps the failure detail instead of
     * collapsing it to null, so the UI can tell the user *why* Google would sit
     * out of the blend the moment they enable it — rather than leaving them to
     * notice a missing line on the chart and dig through the diag log.
     *
     * The split is deliberate: [fetchHourly] is the hot forecast path and stays
     * "any failure → null, never break the surrounding forecast"; [probe] is the
     * cold, user-initiated path where the distinction between "the key is blocked
     * (fixable)" and "the network blipped (transient)" is exactly what the user
     * needs to act. Both share [request]/[parse], so they can't drift apart.
     */
    suspend fun probe(location: Location, apiKey: String): GoogleWeatherProbe =
        if (apiKey.isBlank()) GoogleWeatherProbe.NoKey else probe(location, GoogleWeatherAccess.OwnKey(apiKey))

    /** [probe] through either route; see [GoogleWeatherAccess]. */
    suspend fun probe(location: Location, access: GoogleWeatherAccess): GoogleWeatherProbe {
        val response = try {
            // Single page is enough for a connectivity check — no need to walk
            // the full horizon just to learn the key reaches the API.
            request(location, access, maxHours = NEAR_HOURS, pageToken = null)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logger.log("Google Weather probe failed", t)
            // A 403 PERMISSION_DENIED is the common, actionable case — the
            // Weather API isn't enabled for the key's project, billing is off,
            // or the key's API restrictions exclude it. Surface it distinctly
            // from a transient network / quota / 5xx failure so the UI can point
            // at the Cloud Console fix rather than "try again later". Only for a
            // key of the user's own: a 403 through the proxy is the developer's
            // key or the proxy refusing the caller, which the user can't fix.
            val status = (t as? ResponseException)?.response?.status?.value
            return if (status == HttpStatusCode.Forbidden.value && access is GoogleWeatherAccess.OwnKey) {
                GoogleWeatherProbe.Forbidden
            } else {
                GoogleWeatherProbe.Failed(status)
            }
        }
        val hours = parse(response.forecastHours)?.size ?: 0
        // A 200 with no usable hours is treated as a failure: the key reached the
        // API but there's nothing to fold into the blend, so "Google is
        // contributing" would be a lie. Rare for a valid key, but honest.
        return if (hours > 0) GoogleWeatherProbe.Reachable(hours) else GoogleWeatherProbe.Failed(null)
    }

    /**
     * Walks Google's pagination to gather up to [EXTENDED_HOURS] hours, following
     * each page's `nextPageToken` until it runs dry, the hour budget is met, or
     * [MAX_PAGES] is hit (a backstop so a misbehaving token can't loop forever).
     *
     * The first page's failure propagates so [fetchHourly]'s catch can drop
     * Google to null. A *later* page's failure is caught and the walk stops with
     * what it already has — a network blip on page 7 shouldn't throw away the six
     * days already in hand.
     */
    private suspend fun requestAllPages(
        location: Location,
        access: GoogleWeatherAccess,
    ): Pair<List<GoogleForecastHour>, String?> {
        val all = mutableListOf<GoogleForecastHour>()
        // The zone Google reports for the location; the hours' display times
        // are in it. Taken from the first page that carries it.
        var zoneId: String? = null
        var pageToken: String? = null
        var pages = 0
        do {
            val response = if (all.isEmpty()) {
                request(location, access, EXTENDED_HOURS, pageToken)
            } else {
                try {
                    request(location, access, EXTENDED_HOURS, pageToken)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    logger.log("Google Weather page ${pages + 1} failed; keeping ${all.size} hours gathered so far", t)
                    break
                }
            }
            all += response.forecastHours
            zoneId = zoneId ?: response.timeZone?.id?.takeIf { it.isNotBlank() }
            pages++
            pageToken = response.nextPageToken?.takeIf { it.isNotBlank() }
        } while (pageToken != null && all.size < EXTENDED_HOURS && pages < MAX_PAGES)
        return all to zoneId
    }

    private suspend fun request(
        location: Location,
        access: GoogleWeatherAccess,
        maxHours: Int,
        pageToken: String?,
    ): GoogleHourlyResponse =
        apiCallLogger.instrument(ApiEndpoints.GOOGLE_WEATHER) {
            val query = Parameters.build {
                append("location.latitude", location.latitude.toString())
                append("location.longitude", location.longitude.toString())
                append("hours", maxHours.toString())
                // Google caps the hourly page at PAGE_SIZE; the rest of the
                // requested window arrives via nextPageToken (see
                // [requestAllPages]).
                append("pageSize", PAGE_SIZE.toString())
                // METRIC so temperatures come back in °C and wind in km/h, the
                // units PerModelHour / the domain already speak — no conversion.
                append("unitsSystem", "METRIC")
                // Subsequent pages: hand back the token from the prior page.
                if (pageToken != null) append("pageToken", pageToken)
            }
            when (access) {
                is GoogleWeatherAccess.OwnKey -> httpClient.get {
                    expectSuccess = true
                    url {
                        protocol = URLProtocol.HTTPS
                        host = GOOGLE_WEATHER_HOST
                        // `hours:lookup` must keep its literal colon. Plain
                        // `path(...)` would too — Ktor's segment encoder
                        // preserves ':' (GeminiTtsClient relies on that for
                        // `<model>:generateContent`) — so pre-encoded segments
                        // here are explicitness, not a workaround.
                        encodedPathSegments = listOf("v1", "forecast", "hours:lookup")
                        parameters.appendAll(query)
                    }
                    // Send the key as a header, NOT a `key=` query param: on an HTTP
                    // error Ktor's ResponseException message embeds the request URL,
                    // which we log via the [logger] below — a `key=` in the URL would
                    // put the user's key into the recent-logs buffer that bug
                    // reports attach. Google documents X-Goog-Api-Key precisely to
                    // keep the key out of URLs.
                    header("X-Goog-Api-Key", access.apiKey)
                }
                // The proxy takes the same fields as a form body, not a query:
                // Cloud Run's request log records the full URL, and the
                // coordinate must not land in it (PRIVACY.md).
                is GoogleWeatherAccess.Proxy -> httpClient.post {
                    expectSuccess = true
                    url {
                        protocol = URLProtocol.HTTPS
                        host = access.host
                        encodedPathSegments = listOf(access.path)
                    }
                    access.authorize(this)
                    setBody(FormDataContent(query))
                }
            }.body()
        }

    private fun parse(forecastHours: List<GoogleForecastHour>): List<PerModelHour>? {
        val hours = forecastHours.mapNotNull { hour ->
            val time = hour.displayDateTime?.toLocalDateTime() ?: return@mapNotNull null
            // Air temperature is the hard requirement, mirroring the Open-Meteo
            // per-model parse: without it there's nothing to average into the
            // consensus, so the hour sits out rather than voting a synthetic 0.
            val air = hour.temperature?.degrees ?: return@mapNotNull null
            val rawType = hour.weatherCondition?.type
            val condition = GoogleConditionMapper.map(rawType)
            if (condition == WeatherCondition.UNKNOWN && !rawType.isNullOrBlank()) {
                logger.log("Google weatherCondition.type \"$rawType\" not mapped; condition vote sits out")
            }
            PerModelHour(
                time = time,
                apparentTemperatureC = hour.feelsLikeTemperature?.degrees ?: air,
                temperatureC = air,
                // Null (not 0) when absent, like the Open-Meteo per-model parse:
                // "Google gave no precip figure" must stay distinct from "Google
                // predicts a dry hour" so a missing value doesn't downgrade the
                // blended rain probability.
                precipitationProbabilityPct = hour.precipitation?.probability?.percent?.toDouble(),
                precipitationMm = hour.precipitation?.qpf?.quantity,
                windSpeedKmh = hour.wind?.speed?.value,
                windGustKmh = hour.wind?.gust?.value,
                relativeHumidityPct = hour.relativeHumidity?.toDouble(),
                cloudCoverPct = hour.cloudCover?.toDouble(),
                uvIndex = hour.uvIndex?.toDouble(),
                condition = condition,
            )
        }
        return hours.ifEmpty { null }
    }

    companion object {
        // 168 hours / 7 days, so Google votes in the consensus across the week
        // ahead, not just today + tomorrow. Google serves up to 240 hours, but
        // every page is a billed call, and days 8–14 are left to the free
        // Open-Meteo models. The forecast path ([fetchHourly]) always walks this;
        // the :app cache rate-limits it.
        const val EXTENDED_HOURS = 168

        // A single 24-hour page — all [probe] needs to confirm the key reaches the
        // Weather API and returns usable hours, without walking the full horizon.
        const val NEAR_HOURS = 24

        // Google caps each hourly page at 24 hours; the rest of the horizon
        // arrives by following nextPageToken (see [requestAllPages]).
        private const val PAGE_SIZE = 24

        // ceil(EXTENDED_HOURS / PAGE_SIZE) pages cover the whole horizon — a
        // backstop so a misbehaving token can't loop forever.
        private const val MAX_PAGES = EXTENDED_HOURS / PAGE_SIZE
    }
}

@Serializable
private data class GoogleHourlyResponse(
    val forecastHours: List<GoogleForecastHour> = emptyList(),
    val nextPageToken: String? = null,
    val timeZone: GoogleTimeZone? = null,
)

@Serializable
private data class GoogleTimeZone(
    val id: String? = null,
)

@Serializable
private data class GoogleForecastHour(
    val displayDateTime: GoogleDateTime? = null,
    val weatherCondition: GoogleWeatherCondition? = null,
    val temperature: GoogleMeasure? = null,
    val feelsLikeTemperature: GoogleMeasure? = null,
    val relativeHumidity: Int? = null,
    val uvIndex: Int? = null,
    val precipitation: GooglePrecipitation? = null,
    val wind: GoogleWind? = null,
    val cloudCover: Int? = null,
)

@Serializable
private data class GoogleDateTime(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val hours: Int? = null,
    val minutes: Int? = null,
) {
    /**
     * The local wall-clock time for the hour, matching the local timestamps the
     * Open-Meteo series carries (`timezone=auto`) so the consensus blend keys
     * the two against the same hour. `displayDateTime` is already in the
     * location's local zone; `hours` defaults to 0 only as a guard (Google
     * always populates it). Returns null when the date is incomplete.
     */
    fun toLocalDateTime(): LocalDateTime? {
        val y = year ?: return null
        val mo = month ?: return null
        val d = day ?: return null
        return runCatching {
            LocalDateTime.of(y, mo, d, hours ?: 0, minutes ?: 0)
        }.getOrNull()
    }
}

@Serializable
private data class GoogleWeatherCondition(val type: String? = null)

/** A Google measure object; temperatures expose `degrees`, wind speed `value`. */
@Serializable
private data class GoogleMeasure(
    val degrees: Double? = null,
    @SerialName("value") val value: Double? = null,
)

@Serializable
private data class GooglePrecipitation(
    val probability: GoogleProbability? = null,
    val qpf: GoogleQpf? = null,
)

@Serializable
private data class GoogleProbability(val percent: Int? = null, val type: String? = null)

@Serializable
private data class GoogleQpf(val quantity: Double? = null, val unit: String? = null)

@Serializable
private data class GoogleWind(val speed: GoogleMeasure? = null, val gust: GoogleMeasure? = null)
