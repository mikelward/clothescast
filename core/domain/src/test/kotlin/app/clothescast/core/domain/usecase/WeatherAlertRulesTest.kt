package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.AlertArea
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.GeoPoint
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

class WeatherAlertRulesTest {
    private val now: Instant = Instant.parse("2026-10-22T12:00:00Z")
    private val here = GeoPoint(51.0, -1.0)

    /** A square [halfDeg] either side of [center]. */
    private fun square(center: GeoPoint, halfDeg: Double) = AlertArea(
        listOf(
            listOf(
                listOf(
                    GeoPoint(center.latitude - halfDeg, center.longitude - halfDeg),
                    GeoPoint(center.latitude - halfDeg, center.longitude + halfDeg),
                    GeoPoint(center.latitude + halfDeg, center.longitude + halfDeg),
                    GeoPoint(center.latitude + halfDeg, center.longitude - halfDeg),
                ),
            ),
        ),
    )

    private fun alert(
        id: String,
        severity: AlertSeverity = AlertSeverity.SEVERE,
        area: AlertArea? = square(here, 1.0),
        eventType: String? = "WIND",
        endTime: Instant? = now.plus(Duration.ofHours(6)),
    ) = WeatherAlert(
        id = id,
        issuerTitle = "Amber warning of wind",
        eventType = eventType,
        severity = severity,
        areaName = null,
        description = null,
        instruction = null,
        startTime = null,
        endTime = endTime,
        issuer = "Met Office",
        issuerUrl = null,
        area = area,
    )

    @Test
    fun `keeps alerts whose area contains the point and drops the rest`() {
        val result = WeatherAlertRules.relevant(
            listOf(
                alert("here"),
                alert("elsewhere", area = square(GeoPoint(53.0, -1.0), 0.5)),
                alert("no-area", area = null),
            ),
            here,
            now,
        )
        result.map { it.alert.id } shouldBe listOf("here")
    }

    @Test
    fun `allows about a kilometre of slack at the boundary`() {
        // The square's southern edge sits 0.005° (≈0.56 km) north of the point.
        val nearEdge = square(GeoPoint(here.latitude + 0.505, here.longitude), 0.5)
        val result = WeatherAlertRules.relevant(listOf(alert("edge", area = nearEdge)), here, now)
        result.map { it.alert.id } shouldBe listOf("edge")

        // 0.02° (≈2.2 km) away is out.
        val tooFar = square(GeoPoint(here.latitude + 0.52, here.longitude), 0.5)
        WeatherAlertRules.relevant(listOf(alert("far", area = tooFar)), here, now).shouldBeEmpty()
    }

    @Test
    fun `drops expired and non-weather alerts`() {
        val result = WeatherAlertRules.relevant(
            listOf(
                alert("expired", endTime = now.minusSeconds(1)),
                alert("quake", eventType = "EARTHQUAKE"),
                alert("open-ended", endTime = null),
            ),
            here,
            now,
        )
        result.map { it.alert.id } shouldBe listOf("open-ended")
    }

    @Test
    fun `a continent-sized area shows but never notifies`() {
        val typhoon = alert("typhoon", severity = AlertSeverity.EXTREME, area = square(here, 12.0))
        val result = WeatherAlertRules.relevant(listOf(typhoon), here, now).single()
        result.notifiable shouldBe false
        result.level shouldBe AlertLevel.INFO
    }

    @Test
    fun `sorts most severe first, then soonest to end, without duplicates`() {
        val result = WeatherAlertRules.relevant(
            listOf(
                alert("yellow", severity = AlertSeverity.MODERATE),
                alert("red-late", severity = AlertSeverity.EXTREME, endTime = now.plus(Duration.ofHours(9))),
                alert("red-soon", severity = AlertSeverity.EXTREME, endTime = now.plus(Duration.ofHours(2))),
                alert("yellow"),
            ),
            here,
            now,
        )
        result.map { it.alert.id } shouldBe listOf("red-soon", "red-late", "yellow")
    }

    private fun hour(
        feelsLikeC: Double = 12.0,
        precipitationMm: Double = 0.0,
        windSpeedKmh: Double? = 15.0,
        condition: WeatherCondition = WeatherCondition.CLOUDY,
    ) = HourlyForecast(
        time = LocalTime.NOON,
        temperatureC = feelsLikeC,
        feelsLikeC = feelsLikeC,
        precipitationProbabilityPct = 0.0,
        condition = condition,
        precipitationMm = precipitationMm,
        windSpeedKmh = windSpeedKmh,
    )

    @Test
    fun `a quiet forecast doesn't look severe`() {
        WeatherAlertRules.forecastLooksSevere(List(48) { hour() }) shouldBe false
        WeatherAlertRules.forecastLooksSevere(emptyList()) shouldBe false
    }

    @Test
    fun `gales, heavy rain, snow, thunder and extreme feels-like look severe`() {
        val quiet = List(47) { hour() }
        WeatherAlertRules.forecastLooksSevere(quiet + hour(windSpeedKmh = 55.0)) shouldBe true
        WeatherAlertRules.forecastLooksSevere(quiet + hour(precipitationMm = 12.0)) shouldBe true
        WeatherAlertRules.forecastLooksSevere(List(48) { hour(precipitationMm = 0.7) }) shouldBe true
        WeatherAlertRules.forecastLooksSevere(quiet + hour(condition = WeatherCondition.SNOW)) shouldBe true
        WeatherAlertRules.forecastLooksSevere(quiet + hour(condition = WeatherCondition.THUNDERSTORM)) shouldBe true
        WeatherAlertRules.forecastLooksSevere(quiet + hour(feelsLikeC = 36.0)) shouldBe true
        WeatherAlertRules.forecastLooksSevere(quiet + hour(feelsLikeC = -16.0)) shouldBe true
    }

    @Test
    fun `the check is due every 3 hours, or hourly while elevated`() {
        WeatherAlertRules.checkDue(null, now, elevated = false) shouldBe true
        val twoHoursAgo = now.minus(Duration.ofHours(2))
        WeatherAlertRules.checkDue(twoHoursAgo, now, elevated = false) shouldBe false
        WeatherAlertRules.checkDue(twoHoursAgo, now, elevated = true) shouldBe true
        WeatherAlertRules.checkDue(now.minus(Duration.ofHours(3)), now, elevated = false) shouldBe true
        WeatherAlertRules.checkDue(now.minus(Duration.ofMinutes(30)), now, elevated = true) shouldBe false
        // A clock set backwards doesn't silence the check.
        WeatherAlertRules.checkDue(now.plus(Duration.ofHours(5)), now, elevated = false) shouldBe true
    }
}
