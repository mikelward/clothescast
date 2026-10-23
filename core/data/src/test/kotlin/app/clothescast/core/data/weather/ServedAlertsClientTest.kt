package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.AlertAttribution
import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.GeoPoint
import app.clothescast.core.domain.model.Location
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ServedAlertsClientTest {
    private val now = Instant.parse("2026-10-23T12:00:00Z")
    private val base = "https://storage.example.com/test-alerts"
    private val place = Location(latitude = 55.86, longitude = -4.25, displayName = "City A", countryCode = "gb")

    private fun index(
        generatedAt: String = "2026-10-23T11:58:00Z",
        tiles: String = """{"55/-5": "abc123"}""",
        error: String? = null,
        countries: String = """["GB"]""",
    ) = """
        {"v": 1, "generatedAt": "$generatedAt", "tileDegrees": 1,
         "sources": [{"id": "meteoalarm-gb", "countries": $countries,
                      "attribution": {"name": "Met Office via MeteoAlarm", "url": "https://meteoalarm.org"},
                      "lastSuccessAt": "2026-10-23T11:58:00Z", "error": ${error?.let { "\"$it\"" } ?: "null"}}],
         "tiles": $tiles}
    """.trimIndent()

    private val tile = """
        {"v": 1, "tile": "55/-5", "alerts": [{
          "id": "meteoalarm-gb:2.49.0.0.826.0.GB_1", "source": "meteoalarm-gb",
          "sent": "2026-10-23T09:02:39+00:00", "onset": "2026-10-24T20:00:00+00:00",
          "expires": "2026-10-25T21:00:00+00:00", "severity": "SEVERE", "eventType": "WIND",
          "issuer": "UK Met Office",
          "polygons": [[[[55.0, -4.5], [55.0, -2.5], [56.5, -2.5], [56.5, -4.5], [55.0, -4.5]]]],
          "texts": [
            {"language": "cy", "title": "Rhybudd oren", "areaName": "Ardal A"},
            {"language": "en-GB", "title": "Amber wind warning", "description": "Very strong winds.",
             "instruction": "Stay indoors.", "areaName": "Region A",
             "web": "https://example.com/warnings"}
          ]
        }]}
    """.trimIndent()

    private val requested = mutableListOf<String>()

    private fun client(routes: Map<String, String>): ServedAlertsClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/test-alerts/")
            requested += path
            val body = routes[path]
            if (body == null) {
                respondError(HttpStatusCode.NotFound)
            } else {
                respond(ByteReadChannel(body), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        return ServedAlertsClient(HttpClient(engine), base, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `alerts in the place's tile come back with the issuer's text, issue time and credit`() = runTest {
        val result = client(mapOf("v1/index.json" to index(), "v1/tiles/55/-5/abc123.json" to tile))
            .lookup(place, "en-GB")

        val alert = result.shouldBeInstanceOf<AlertsLookup.Found>().alerts.single()
        alert.id shouldBe "meteoalarm-gb:2.49.0.0.826.0.GB_1"
        alert.issuerTitle shouldBe "Amber wind warning"
        alert.severity shouldBe AlertSeverity.SEVERE
        alert.eventType shouldBe "WIND"
        alert.areaName shouldBe "Region A"
        alert.description shouldBe "Very strong winds."
        alert.instruction shouldBe "Stay indoors."
        alert.startTime shouldBe Instant.parse("2026-10-24T20:00:00Z")
        alert.endTime shouldBe Instant.parse("2026-10-25T21:00:00Z")
        alert.issuedAt shouldBe Instant.parse("2026-10-23T09:02:39Z")
        alert.issuer shouldBe "UK Met Office"
        alert.issuerUrl shouldBe "https://example.com/warnings"
        alert.attribution shouldBe AlertAttribution("Met Office via MeteoAlarm", "https://meteoalarm.org")
        alert.area!!.contains(GeoPoint(55.86, -4.25)) shouldBe true
        requested shouldBe listOf("v1/index.json", "v1/tiles/55/-5/abc123.json")
    }

    @Test
    fun `each polygon keeps its holes, and one without a readable outer ring is dropped`() = runTest {
        val square = "[[55.0, -4.5], [55.0, -2.5], [56.5, -2.5], [56.5, -4.5], [55.0, -4.5]]"
        val hole = "[[55.8, -4.3], [55.8, -4.2], [55.9, -4.2], [55.9, -4.3], [55.8, -4.3]]"
        val unreadable = "[[[95.0, 0.0], [0.0, 0.0]], [[10.0, 10.0], [10.0, 11.0], [11.0, 11.0]]]"
        val holed = tile.replace("\"polygons\": [[$square]]", "\"polygons\": [[$square, $hole], $unreadable]")
        val area = client(mapOf("v1/index.json" to index(), "v1/tiles/55/-5/abc123.json" to holed))
            .lookup(place, "en").shouldBeInstanceOf<AlertsLookup.Found>().alerts.single().area!!
        area.polygons.size shouldBe 1
        area.contains(GeoPoint(55.86, -4.25)) shouldBe false
        area.contains(GeoPoint(55.5, -3.0)) shouldBe true
    }

    @Test
    fun `the text is picked by language, then by English, then the first`() {
        val texts = listOf(TextDto(language = "fr"), TextDto(language = "en-GB"), TextDto(language = "de-DE"))
        ServedAlertsClient.pickText(texts, "de-AT")?.language shouldBe "de-DE"
        ServedAlertsClient.pickText(texts, "en-US")?.language shouldBe "en-GB"
        ServedAlertsClient.pickText(texts, "ja-JP")?.language shouldBe "en-GB"
        ServedAlertsClient.pickText(listOf(TextDto(language = "nb")), "ja")?.language shouldBe "nb"
    }

    @Test
    fun `a covered place with no tile listed has no alerts, without fetching one`() = runTest {
        val result = client(mapOf("v1/index.json" to index(tiles = "{}"))).lookup(place, "en-GB")
        result.shouldBeInstanceOf<AlertsLookup.Found>().alerts.shouldBeEmpty()
        requested shouldBe listOf("v1/index.json")
    }

    @Test
    fun `a place no source covers is unsupported, never all clear`() = runTest {
        val elsewhere = place.copy(countryCode = "FR")
        client(mapOf("v1/index.json" to index(tiles = "{}"))).lookup(elsewhere, "fr") shouldBe AlertsLookup.Unsupported
        client(mapOf("v1/index.json" to index(tiles = "{}"))).lookup(place.copy(countryCode = null), "en") shouldBe
            AlertsLookup.Unsupported
    }

    @Test
    fun `an alert reaching over a border still shows where no source covers the place`() = runTest {
        val result = client(mapOf("v1/index.json" to index(), "v1/tiles/55/-5/abc123.json" to tile))
            .lookup(place.copy(countryCode = "IE"), "en")
        result.shouldBeInstanceOf<AlertsLookup.Found>().alerts.single().issuerTitle shouldBe "Amber wind warning"
    }

    @Test
    fun `a neighbor's alert elsewhere in the tile leaves an uncovered place unsupported`() = runTest {
        val outsideTheArea = place.copy(latitude = 55.5, longitude = -4.9, countryCode = "IE")
        client(mapOf("v1/index.json" to index(), "v1/tiles/55/-5/abc123.json" to tile))
            .lookup(outsideTheArea, "en") shouldBe AlertsLookup.Unsupported
    }

    @Test
    fun `an incomplete check or a stale index is unknown, not quiet`() = runTest {
        client(mapOf("v1/index.json" to index(tiles = "{}", error = "1 of 3 documents failed")))
            .lookup(place, "en") shouldBe AlertsLookup.Failed(null)
        client(mapOf("v1/index.json" to index(tiles = "{}", generatedAt = "2026-10-23T11:00:00Z")))
            .lookup(place, "en") shouldBe AlertsLookup.Failed(null)
    }

    @Test
    fun `a missing or unreadable file is a failed check`() = runTest {
        client(emptyMap()).lookup(place, "en") shouldBe AlertsLookup.Failed(404)
        client(mapOf("v1/index.json" to index())).lookup(place, "en") shouldBe AlertsLookup.Failed(404)
        client(mapOf("v1/index.json" to "not json")).lookup(place, "en") shouldBe AlertsLookup.Failed(null)
        client(mapOf("v1/index.json" to """{"v": 2, "tileDegrees": 1}""")).lookup(place, "en") shouldBe
            AlertsLookup.Failed(null)
    }

    @Test
    fun `tiles are named by their south-west corner, as the ingester names them`() {
        ServedAlertsClient.tileKey(51.5, -0.12) shouldBe "51/-1"
        ServedAlertsClient.tileKey(-37.8, 144.9) shouldBe "-38/144"
        ServedAlertsClient.tileKey(0.0, 0.0) shouldBe "0/0"
        ServedAlertsClient.tileKey(-0.0, -0.0) shouldBe "0/0"
        ServedAlertsClient.tileKey(10.0, 180.0) shouldBe "10/-180"
    }
}
