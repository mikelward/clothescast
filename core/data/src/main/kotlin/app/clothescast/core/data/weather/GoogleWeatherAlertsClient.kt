package app.clothescast.core.data.weather

import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.diag.ApiEndpoints
import app.clothescast.core.data.diag.NoOpApiCallLogger
import app.clothescast.core.data.diag.instrument
import app.clothescast.core.domain.model.AlertArea
import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.GeoPoint
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.WeatherAlert
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** What a public-alerts lookup found. */
sealed interface AlertsLookup {
    /** The lookup worked; [alerts] may be empty (no alerts here right now). */
    data class Found(val alerts: List<WeatherAlert>) : AlertsLookup

    /**
     * Google has no alerts for this location at all ("Information is not
     * supported for this location"): a coverage gap, not "no alerts", so the
     * app must not read it as all clear.
     */
    data object Unsupported : AlertsLookup

    /** The lookup failed; [httpStatus] carries the HTTP code when there was one. */
    data class Failed(val httpStatus: Int?) : AlertsLookup
}

/**
 * Google's public weather alerts (`publicAlerts:lookup` on the Weather API,
 * the same API and key as [GoogleWeatherModelClient]), by the user's own key
 * or through the `weatherAlerts` proxy (docs/ALERTS.md).
 *
 * Unlike the forecast path, a failure is never collapsed into an empty list:
 * the last alerts feature hid a 404 that way for months. Callers get
 * [AlertsLookup.Failed] and keep what they last knew.
 *
 * The response shape is parsed leniently — fields Google documents but we
 * couldn't check against a live response (`instruction`, `description`) are
 * read whether they arrive as text, a list of lines, or localized text.
 */
class GoogleWeatherAlertsClient(
    private val httpClient: HttpClient,
    private val logger: ConfidenceFetchLogger = NoOpConfidenceFetchLogger,
    private val apiCallLogger: ApiCallLogger = NoOpApiCallLogger,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * The alerts Google has for [location], in [languageCode] where the issuer
     * provides it (a BCP 47 tag such as "en-GB"; issuers that don't translate
     * answer in their own language).
     */
    suspend fun lookup(
        location: Location,
        access: GoogleWeatherAccess,
        languageCode: String?,
    ): AlertsLookup {
        val alerts = mutableListOf<WeatherAlert>()
        var pageToken: String? = null
        var pages = 0
        try {
            do {
                val page = request(location, access, languageCode, pageToken)
                page.weatherAlerts.mapNotNullTo(alerts) { it.toDomain() }
                pages++
                pageToken = page.nextPageToken?.takeIf { it.isNotBlank() }
            } while (pageToken != null && pages < MAX_PAGES)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: ResponseException) {
            val status = e.response.status.value
            // Melbourne and Seoul answer 404 "not supported for this location":
            // a coverage gap. Only on the first page; a later page's 404 is a
            // broken token, a failure like any other.
            if (status == HttpStatusCode.NotFound.value && pages == 0) {
                logger.log("Google alerts: location not supported")
                return AlertsLookup.Unsupported
            }
            logger.log("Google alerts lookup failed: HTTP $status", e)
            return AlertsLookup.Failed(status)
        } catch (t: Throwable) {
            logger.log("Google alerts lookup failed", t)
            return AlertsLookup.Failed(null)
        }
        return AlertsLookup.Found(alerts)
    }

    private suspend fun request(
        location: Location,
        access: GoogleWeatherAccess,
        languageCode: String?,
        pageToken: String?,
    ): PublicAlertsResponse = apiCallLogger.instrument(ApiEndpoints.GOOGLE_WEATHER_ALERTS) {
        val query = Parameters.build {
            append("location.latitude", location.latitude.toString())
            append("location.longitude", location.longitude.toString())
            if (!languageCode.isNullOrBlank()) append("languageCode", languageCode)
            append("pageSize", PAGE_SIZE.toString())
            if (pageToken != null) append("pageToken", pageToken)
        }
        when (access) {
            is GoogleWeatherAccess.OwnKey -> httpClient.get {
                expectSuccess = true
                url {
                    protocol = URLProtocol.HTTPS
                    host = GOOGLE_WEATHER_HOST
                    // `publicAlerts:lookup` keeps its literal colon, as
                    // `hours:lookup` does in GoogleWeatherModelClient.
                    encodedPathSegments = listOf("v1", "publicAlerts:lookup")
                    parameters.appendAll(query)
                }
                // A header, never `key=`: an error's message embeds the URL,
                // and that reaches the log.
                header("X-Goog-Api-Key", access.apiKey)
            }
            // Form body, not a query string, so the coordinate stays out of
            // Cloud Run's request log (PRIVACY.md).
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

    private fun PublicAlertDto.toDomain(): WeatherAlert? {
        val id = alertId?.takeIf { it.isNotBlank() } ?: return null
        return WeatherAlert(
            id = id,
            issuerTitle = alertTitle.text().orEmpty(),
            eventType = eventType?.takeIf { it.isNotBlank() },
            severity = AlertSeverity.parse(severity),
            areaName = areaName?.takeIf { it.isNotBlank() },
            description = description.text(),
            instruction = instruction.text(),
            startTime = parseInstant(startTime),
            endTime = parseInstant(expirationTime),
            issuer = dataSource?.name?.takeIf { it.isNotBlank() },
            issuerUrl = dataSource?.authorityUri?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            area = polygon?.let { parseArea(it) },
        )
    }

    /** An RFC 3339 timestamp, or null (logged) when it doesn't parse. */
    private fun parseInstant(raw: String?): Instant? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (e: DateTimeParseException) {
            logger.log("Google alerts: unreadable timestamp; treating it as absent", e)
            null
        }
    }

    /**
     * Reads the area from Google's `polygon`, a GeoJSON string: every array of
     * `[longitude, latitude]` positions, wherever it sits (Polygon,
     * MultiPolygon, or a Feature wrapping either). Null when it isn't GeoJSON
     * or holds no ring, and the alert is then dropped as unplaceable.
     */
    private fun parseArea(polygon: JsonElement): AlertArea? {
        // Documented as a string of GeoJSON; read an inline object too.
        val root = if (polygon is JsonPrimitive && polygon.isString) {
            try {
                json.parseToJsonElement(polygon.content)
            } catch (e: SerializationException) {
                logger.log("Google alerts: polygon isn't GeoJSON; dropping the area", e)
                return null
            }
        } else {
            polygon
        }
        val rings = mutableListOf<List<GeoPoint>>()
        collectRings(root, rings)
        return if (rings.isEmpty()) null else AlertArea(rings)
    }

    private fun collectRings(element: JsonElement, into: MutableList<List<GeoPoint>>) {
        when (element) {
            is JsonArray -> {
                val positions = element.mapNotNull { it.position() }
                if (positions.size >= 3 && positions.size == element.size) {
                    into += positions
                } else {
                    element.forEach { collectRings(it, into) }
                }
            }
            is JsonObject -> element.values.forEach { collectRings(it, into) }
            else -> Unit
        }
    }

    /** A GeoJSON position, `[longitude, latitude]`, or null if [this] isn't one. */
    private fun JsonElement.position(): GeoPoint? {
        val array = this as? JsonArray ?: return null
        if (array.size < 2) return null
        val lon = (array[0] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lat = (array[1] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(latitude = lat, longitude = lon)
    }

    companion object {
        /** The proxy function's name, beside `weather` (functions/src/index.ts). */
        const val PROXY_FUNCTION = "weatherAlerts"

        // One page holds every alert for a point in practice; a few more
        // pages are a backstop, not an expectation.
        private const val PAGE_SIZE = 20
        private const val MAX_PAGES = 3
    }
}

/**
 * Text from a field Google may send as a string, localized text
 * (`{"text": …}`) or a list of either (lines, joined).
 */
private fun JsonElement?.text(): String? = when (this) {
    null -> null
    is JsonPrimitive -> if (isString) content else null
    is JsonObject -> (this["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    is JsonArray -> mapNotNull { it.text() }.filter { it.isNotBlank() }.joinToString("\n").ifEmpty { null }
}?.trim()?.takeIf { it.isNotEmpty() }

@Serializable
private data class PublicAlertsResponse(
    val weatherAlerts: List<PublicAlertDto> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
private data class PublicAlertDto(
    val alertId: String? = null,
    val alertTitle: JsonElement? = null,
    val eventType: String? = null,
    val areaName: String? = null,
    val description: JsonElement? = null,
    val instruction: JsonElement? = null,
    val startTime: String? = null,
    val expirationTime: String? = null,
    val dataSource: PublicAlertSourceDto? = null,
    val polygon: JsonElement? = null,
    val severity: String? = null,
)

@Serializable
private data class PublicAlertSourceDto(
    val publisher: String? = null,
    val name: String? = null,
    val authorityUri: String? = null,
)
