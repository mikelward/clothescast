package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.GeoPoint
import app.clothescast.core.domain.model.Location
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant

class GoogleWeatherAlertsClientTest {
    private val place = Location(latitude = 1.48, longitude = -1.9, displayName = "City A")

    private val square =
        """{"type":"Polygon","coordinates":[[[-3.0,0.5],[0.0,0.5],[0.0,2.5],[-3.0,2.5],[-3.0,0.5]]]}"""

    /**
     * A page with one wind warning over [polygon] (sent as a GeoJSON string,
     * the way Google documents it; null for none), an instruction as a list of
     * lines, and an alert without an id that must be dropped.
     */
    private fun page(
        polygon: String? = square,
        id: String = "alert-1",
        nextPageToken: String? = null,
    ): String {
        val polygonField = polygon?.let { "\"polygon\": ${JsonPrimitive(it)}," } ?: ""
        val tokenField = nextPageToken?.let { ",\"nextPageToken\": \"$it\"" } ?: ""
        return """
            {
              "weatherAlerts": [
                {
                  "alertId": "$id",
                  "alertTitle": {"text": "Yellow warning of wind", "languageCode": "en"},
                  "eventType": "WIND",
                  "areaName": "Region A | Region B",
                  "description": "Strong winds may cause disruption.",
                  "instruction": ["Secure loose objects.", "Take care on exposed routes."],
                  "startTime": "2026-10-22T09:03:00Z",
                  "expirationTime": "2026-10-23T20:00:00Z",
                  "dataSource": {"publisher": "SOME_PUBLISHER", "name": "Met Office", "authorityUri": "https://www.metoffice.gov.uk/"},
                  $polygonField
                  "severity": "MODERATE"
                },
                {
                  "alertTitle": {"text": "No id, so dropped"},
                  "severity": "SEVERE"
                }
              ],
              "regionCode": "GB"$tokenField
            }
        """.trimIndent()
    }

    private fun client(
        status: HttpStatusCode = HttpStatusCode.OK,
        bodies: List<String> = listOf(page()),
        captured: MutableList<HttpRequestData> = mutableListOf(),
    ): GoogleWeatherAlertsClient {
        val engine = MockEngine { request ->
            captured += request
            if (status == HttpStatusCode.OK) {
                respond(
                    content = ByteReadChannel(bodies[(captured.size - 1).coerceAtMost(bodies.lastIndex)]),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respondError(status)
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return GoogleWeatherAlertsClient(http)
    }

    @Test
    fun `own key calls Google directly with the key as a header`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val result = client(captured = captured)
            .lookup(place, GoogleWeatherAccess.OwnKey("test-key"), languageCode = "en-GB")

        val request = captured.single()
        request.method shouldBe HttpMethod.Get
        request.url.host shouldBe "weather.googleapis.com"
        request.url.encodedPath shouldBe "/v1/publicAlerts:lookup"
        request.url.parameters["location.latitude"] shouldBe "1.48"
        request.url.parameters["languageCode"] shouldBe "en-GB"
        request.url.parameters["key"].shouldBeNull()
        request.headers["X-Goog-Api-Key"] shouldBe "test-key"
        result.shouldBeInstanceOf<AlertsLookup.Found>()
    }

    @Test
    fun `the proxy gets the same fields as a form body, never in the URL`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val access = GoogleWeatherAccess.Proxy(
            host = "us-central1-demo.cloudfunctions.net",
            path = GoogleWeatherAlertsClient.PROXY_FUNCTION,
            authorize = {},
        )
        client(captured = captured).lookup(place, access, languageCode = "en")

        val request = captured.single()
        request.method shouldBe HttpMethod.Post
        request.url.host shouldBe "us-central1-demo.cloudfunctions.net"
        request.url.encodedPath shouldBe "/weatherAlerts"
        request.url.parameters.isEmpty() shouldBe true
        val form = (request.body as FormDataContent).formData
        form["location.latitude"] shouldBe "1.48"
        form["location.longitude"] shouldBe "-1.9"
        form["languageCode"] shouldBe "en"
    }

    @Test
    fun `parses an alert and drops one without an id`() = runTest {
        val result = client().lookup(place, GoogleWeatherAccess.OwnKey("k"), "en")
        val alert = (result as AlertsLookup.Found).alerts.single()
        alert.id shouldBe "alert-1"
        alert.issuerTitle shouldBe "Yellow warning of wind"
        alert.eventType shouldBe "WIND"
        alert.severity shouldBe AlertSeverity.MODERATE
        alert.areaName shouldBe "Region A | Region B"
        alert.description shouldBe "Strong winds may cause disruption."
        alert.instruction shouldBe "Secure loose objects.\nTake care on exposed routes."
        alert.startTime shouldBe Instant.parse("2026-10-22T09:03:00Z")
        alert.endTime shouldBe Instant.parse("2026-10-23T20:00:00Z")
        alert.issuer shouldBe "Met Office"
        alert.issuerUrl shouldBe "https://www.metoffice.gov.uk/"
        val area = alert.area.shouldNotBeNull()
        area.contains(GeoPoint(1.48, -1.9)) shouldBe true
        area.contains(GeoPoint(3.0, -1.9)) shouldBe false
    }

    @Test
    fun `an unreadable polygon leaves the alert without an area`() = runTest {
        val alert = (client(bodies = listOf(page(polygon = "not geojson"))).lookup(place, GoogleWeatherAccess.OwnKey("k"), "en") as AlertsLookup.Found)
            .alerts.single()
        alert.area.shouldBeNull()
    }

    @Test
    fun `a multipolygon keeps every ring`() = runTest {
        val multi = """{"type":"MultiPolygon","coordinates":[""" +
            "[[[-3.0,0.5],[0.0,0.5],[0.0,2.5],[-3.0,0.5]]]," +
            "[[[10.0,10.0],[11.0,10.0],[11.0,11.0],[10.0,10.0]]]]}"
        val alert = (client(bodies = listOf(page(polygon = multi))).lookup(place, GoogleWeatherAccess.OwnKey("k"), "en") as AlertsLookup.Found)
            .alerts.single()
        alert.area.shouldNotBeNull().rings.size shouldBe 2
    }

    @Test
    fun `follows the next-page token`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val first = page(nextPageToken = "page2")
        val second = page(id = "alert-2")
        val result = client(bodies = listOf(first, second), captured = captured)
            .lookup(place, GoogleWeatherAccess.OwnKey("k"), "en")
        captured.size shouldBe 2
        captured[1].url.parameters["pageToken"] shouldBe "page2"
        (result as AlertsLookup.Found).alerts.map { it.id } shouldBe listOf("alert-1", "alert-2")
    }

    @Test
    fun `404 means the location isn't covered, not that it's clear`() = runTest {
        client(status = HttpStatusCode.NotFound)
            .lookup(place, GoogleWeatherAccess.OwnKey("k"), "en") shouldBe AlertsLookup.Unsupported
    }

    @Test
    fun `any other error is a failure, never an empty list`() = runTest {
        client(status = HttpStatusCode.InternalServerError)
            .lookup(place, GoogleWeatherAccess.OwnKey("k"), "en") shouldBe AlertsLookup.Failed(500)
        client(status = HttpStatusCode.Forbidden)
            .lookup(place, GoogleWeatherAccess.OwnKey("k"), "en") shouldBe AlertsLookup.Failed(403)
        client(bodies = listOf("not json"))
            .lookup(place, GoogleWeatherAccess.OwnKey("k"), "en") shouldBe AlertsLookup.Failed(null)
    }
}
