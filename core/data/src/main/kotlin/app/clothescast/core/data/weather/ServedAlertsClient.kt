package app.clothescast.core.data.weather

import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.diag.ApiEndpoints
import app.clothescast.core.data.diag.NoOpApiCallLogger
import app.clothescast.core.data.diag.instrument
import app.clothescast.core.domain.model.AlertArea
import app.clothescast.core.domain.model.AlertAttribution
import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.GeoPoint
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.usecase.WeatherAlertRules
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.floor

/**
 * The alerts ClothesCast's own ingester publishes to a public bucket
 * (docs/ALERTS.md → The published files): the index, then the file of the
 * 1° tile holding the point. No coordinate leaves the device, only the
 * tile's name; containment is checked here afterwards
 * ([app.clothescast.core.domain.usecase.WeatherAlertRules]).
 *
 * The answer is trusted only where a source covers the place's country. A
 * covering source whose last check was incomplete, or an index that has
 * gone stale, is [AlertsLookup.Failed], never "no alerts" — except a source
 * whose feed alone has failed for under [MAX_SOURCE_STALENESS] while the
 * ingester still serves its last complete check, whose alerts are shown. A place no source
 * covers is [AlertsLookup.Unsupported], unless an alert in its tile reaches
 * it anyway (an area over a border), which is shown.
 */
class ServedAlertsClient(
    private val httpClient: HttpClient,
    /** The bucket's address, without a trailing slash (`https://storage.googleapis.com/<project>-alerts`). */
    private val baseUrl: String,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: ConfidenceFetchLogger = NoOpConfidenceFetchLogger,
    private val apiCallLogger: ApiCallLogger = NoOpApiCallLogger,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** The alerts in force around [location], with text in [languageTag] where the issuer has it. */
    suspend fun lookup(location: Location, languageTag: String): AlertsLookup {
        try {
            val index = json.decodeFromString<IndexDto>(fetch(INDEX_PATH))
            if (index.v != FORMAT_VERSION || index.tileDegrees != 1.0) {
                logger.log("Served alerts: unknown format v${index.v}, ${index.tileDegrees}° tiles")
                return AlertsLookup.Failed(null)
            }
            val generatedAt = parseInstant(index.generatedAt)
            if (generatedAt == null || Duration.between(generatedAt, clock.instant()) > MAX_INDEX_AGE) {
                logger.log("Served alerts: index is stale (generated ${index.generatedAt})")
                return AlertsLookup.Failed(null)
            }
            val country = location.countryCode?.uppercase(Locale.ROOT)
            val covering = index.sources.filter { country != null && country in it.countries }
            val staleBefore = clock.instant().minus(MAX_SOURCE_STALENESS)
            covering.firstOrNull { source ->
                // Only a source still serving its last complete check's alerts
                // (its feed failing, not its documents) keeps them for a while:
                // after an incomplete check, an alert may be missing.
                source.error != null &&
                    (!source.servingLastSuccess || parseInstant(source.lastSuccessAt)?.isBefore(staleBefore) != false)
            }?.let {
                logger.log("Served alerts: ${it.id}'s checks have been incomplete since ${it.lastSuccessAt} (${it.error})")
                return AlertsLookup.Failed(null)
            }
            val key = tileKey(location.latitude, location.longitude)
            val hash = index.tiles[key]
            if (hash == null) {
                return if (covering.isEmpty()) AlertsLookup.Unsupported else AlertsLookup.Found(emptyList())
            }
            val tile = json.decodeFromString<TileDto>(fetch("v$FORMAT_VERSION/tiles/$key/$hash.json"))
            val sources = index.sources.associateBy { it.id }
            val alerts = tile.alerts.mapNotNull { it.toDomain(languageTag, sources[it.source]) }
            if (covering.isEmpty()) {
                // A neighbor's alert elsewhere in the tile says nothing about here.
                val here = GeoPoint(location.latitude, location.longitude)
                val reachesHere = WeatherAlertRules.relevant(alerts, here, clock.instant()).isNotEmpty()
                if (!reachesHere) return AlertsLookup.Unsupported
            }
            return AlertsLookup.Found(alerts)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: ResponseException) {
            val status = e.response.status.value
            logger.log("Served alerts lookup failed: HTTP $status", e)
            return AlertsLookup.Failed(status)
        } catch (e: SerializationException) {
            logger.log("Served alerts: unreadable file", e)
            return AlertsLookup.Failed(null)
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization reports a missing or mistyped field this way.
            logger.log("Served alerts: unreadable file", e)
            return AlertsLookup.Failed(null)
        } catch (e: Exception) {
            logger.log("Served alerts lookup failed", e)
            return AlertsLookup.Failed(null)
        }
    }

    /**
     * The countries whose source answered in full at the index's last run,
     * read from the index alone: no location is involved. A source whose
     * check failed or was incomplete is left out until it answers again.
     * Null when the index can't be had or read.
     */
    suspend fun coveredCountries(): AlertsCoverage? {
        try {
            val index = json.decodeFromString<IndexDto>(fetch(INDEX_PATH))
            if (index.v != FORMAT_VERSION) {
                logger.log("Served alerts: unknown format v${index.v}")
                return null
            }
            return AlertsCoverage(
                asOf = parseInstant(index.generatedAt),
                countries = index.sources.filter { it.error == null }.flatMap { it.countries }.map { it.uppercase(Locale.ROOT) }.distinct().sorted(),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // HTTP, network or unreadable JSON alike: the caller says the list couldn't load.
            logger.log("Served alerts: covered countries unavailable", e)
            return null
        }
    }

    private suspend fun fetch(path: String): String =
        apiCallLogger.instrument(ApiEndpoints.SERVED_ALERTS) {
            httpClient.get("$baseUrl/$path") { expectSuccess = true }.bodyAsText()
        }

    private fun AlertDto.toDomain(languageTag: String, source: SourceDto?): WeatherAlert? {
        if (id.isBlank()) return null
        val text = pickText(texts, languageTag) ?: return null
        fun ringOf(raw: List<List<Double>>): List<GeoPoint>? = raw.mapNotNull { point ->
            if (point.size < 2) return@mapNotNull null
            val (lat, lon) = point[0] to point[1]
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
        }.takeIf { it.size >= 3 }
        // A polygon whose outer ring is unreadable is dropped whole, so a
        // hole never stands in for it.
        val polygons = polygons.mapNotNull { rings ->
            val outer = rings.firstOrNull()?.let(::ringOf) ?: return@mapNotNull null
            listOf(outer) + rings.drop(1).mapNotNull(::ringOf)
        }
        return WeatherAlert(
            id = id,
            issuerTitle = text.title.orEmpty(),
            eventType = eventType?.takeIf { it.isNotBlank() },
            severity = AlertSeverity.parse(severity),
            areaName = text.areaName?.takeIf { it.isNotBlank() },
            description = text.description?.takeIf { it.isNotBlank() },
            instruction = text.instruction?.takeIf { it.isNotBlank() },
            startTime = parseInstant(onset),
            endTime = parseInstant(expires),
            issuer = issuer?.takeIf { it.isNotBlank() },
            issuerUrl = text.web?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            area = polygons.takeIf { it.isNotEmpty() }?.let(::AlertArea),
            issuedAt = parseInstant(sent),
            attribution = source?.attribution?.takeIf { it.name.isNotBlank() && it.url.startsWith("https://") }
                ?.let { credit ->
                    val disclaimer = source.disclaimer?.takeIf { it.url.startsWith("https://") }
                    AlertAttribution(
                        name = credit.name,
                        url = credit.url,
                        disclaimerUrl = disclaimer?.url,
                        disclaimerName = disclaimer?.name?.takeIf { it.isNotBlank() },
                        disclaimerText = disclaimer?.text?.takeIf { it.isNotBlank() },
                    )
                },
        )
    }

    /** An ISO 8601 time with an offset, or null (logged) when it doesn't parse. */
    private fun parseInstant(raw: String?): Instant? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (e: DateTimeParseException) {
            logger.log("Served alerts: unreadable timestamp; treating it as absent", e)
            null
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
        private const val INDEX_PATH = "v$FORMAT_VERSION/index.json"

        /**
         * An index older than this means the ingester has stopped: six
         * missed 5-minute runs. Its silence then reads as unknown, not quiet.
         */
        val MAX_INDEX_AGE: Duration = Duration.ofMinutes(30)

        /**
         * How long a covering source's last complete check still stands once
         * its checks start failing (six missed 5-minute runs, as for
         * [MAX_INDEX_AGE]): one failed fetch shouldn't blank the card.
         */
        val MAX_SOURCE_STALENESS: Duration = Duration.ofMinutes(30)

        /** The 1° tile holding a point, as the ingester names it: its south-west corner ("51/-1"). */
        fun tileKey(latitude: Double, longitude: Double): String {
            val lon = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            return "${floor(latitude).toInt()}/${floor(lon).toInt()}"
        }

        /**
         * The issuer's text in [languageTag]: the exact tag, else the same
         * language ("de" for "de-AT"), else English, else whatever came first.
         */
        internal fun pickText(texts: List<TextDto>, languageTag: String): TextDto? {
            val wanted = languageTag.lowercase(Locale.ROOT)
            val language = wanted.substringBefore('-')
            fun TextDto.tag() = this.language.lowercase(Locale.ROOT)
            return texts.firstOrNull { it.tag() == wanted }
                ?: texts.firstOrNull { it.tag().substringBefore('-') == language }
                ?: texts.firstOrNull { it.tag().substringBefore('-') == "en" }
                ?: texts.firstOrNull()
        }
    }
}

/** The countries whose alert sources answered the ingester's last run, at [asOf]. */
data class AlertsCoverage(
    /** When the index was written; null if it didn't say. */
    val asOf: Instant?,
    /** ISO 3166-1 alpha-2 codes, uppercase and sorted. */
    val countries: List<String>,
)

@Serializable
private data class IndexDto(
    val v: Int,
    val generatedAt: String? = null,
    val tileDegrees: Double = 0.0,
    val sources: List<SourceDto> = emptyList(),
    val tiles: Map<String, String> = emptyMap(),
)

@Serializable
private data class SourceDto(
    val id: String,
    val countries: List<String> = emptyList(),
    val attribution: AttributionDto? = null,
    val disclaimer: DisclaimerDto? = null,
    val lastSuccessAt: String? = null,
    val error: String? = null,
    /** Absent from an index written before it existed: read as false, so no grace. */
    val servingLastSuccess: Boolean = false,
)

@Serializable
private data class AttributionDto(val name: String = "", val url: String = "")

@Serializable
private data class DisclaimerDto(val name: String = "", val url: String = "", val text: String? = null)

@Serializable
private data class TileDto(val alerts: List<AlertDto> = emptyList())

@Serializable
private data class AlertDto(
    val id: String = "",
    val source: String = "",
    val sent: String? = null,
    val onset: String? = null,
    val expires: String? = null,
    val severity: String? = null,
    val eventType: String? = null,
    val issuer: String? = null,
    /** Each polygon's outer ring then any holes, `[lat, lon]`; the area is their union. */
    val polygons: List<List<List<List<Double>>>> = emptyList(),
    val texts: List<TextDto> = emptyList(),
)

@Serializable
internal data class TextDto(
    val language: String = "",
    val title: String? = null,
    val headline: String? = null,
    val description: String? = null,
    val instruction: String? = null,
    val areaName: String? = null,
    val web: String? = null,
)
