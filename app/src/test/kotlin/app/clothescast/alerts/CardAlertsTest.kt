package app.clothescast.alerts

import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.usecase.RelevantAlert
import app.clothescast.data.AlertsCheckOutcome
import app.clothescast.data.WeatherAlertsState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class CardAlertsTest {
    private val here = Location(latitude = 1.48, longitude = -1.9, displayName = "City A")
    private val now = Instant.parse("2026-10-22T18:00:00Z")
    private val tonight = Instant.parse("2026-10-22T19:00:00Z")..Instant.parse("2026-10-23T07:00:00Z")
    private val tomorrow = Instant.parse("2026-10-23T07:00:00Z")..Instant.parse("2026-10-23T19:00:00Z")

    private fun alert(severity: AlertSeverity, start: String?, end: String?) = RelevantAlert(
        alert = WeatherAlert(
            id = "$severity-$start-$end",
            issuerTitle = "Warning",
            eventType = "WIND",
            severity = severity,
            areaName = null,
            description = null,
            instruction = null,
            startTime = start?.let(Instant::parse),
            endTime = end?.let(Instant::parse),
            issuer = null,
            issuerUrl = null,
            area = null,
        ),
        notifiable = true,
    )

    private fun stored(vararg alerts: RelevantAlert) = WeatherAlertsState(
        checkedAt = now,
        outcome = AlertsCheckOutcome.OK,
        point = WeatherAlertsRefresher.alertPoint(here),
        alerts = alerts.toList(),
    )

    @Test
    fun `a card shows the warnings that overlap its own window`() {
        val endsTonight = alert(AlertSeverity.EXTREME, start = null, end = "2026-10-22T23:00:00Z")
        val startsTomorrow = alert(AlertSeverity.SEVERE, start = "2026-10-23T06:00:00Z", end = "2026-10-23T20:00:00Z")
        val state = stored(endsTonight, startsTomorrow)

        cardAlertLevel(state, enabled = true, location = here, window = tonight, now = now) shouldBe AlertLevel.RED
        // The evening's card for tomorrow: not the red one ending tonight.
        cardAlertLevel(state, enabled = true, location = here, window = tomorrow, now = now) shouldBe AlertLevel.ORANGE
    }

    @Test
    fun `an insight carries the yellow-and-up warnings over its window, most severe first`() {
        val red = alert(AlertSeverity.EXTREME, start = null, end = "2026-10-23T20:00:00Z")
        val yellow = alert(AlertSeverity.MODERATE, start = null, end = "2026-10-23T20:00:00Z")
        val minor = alert(AlertSeverity.MINOR, start = null, end = "2026-10-23T20:00:00Z")
        val endsTonight = alert(AlertSeverity.SEVERE, start = null, end = "2026-10-22T23:00:00Z")
        insightAlerts(listOf(red, yellow, minor, endsTonight), tomorrow) shouldBe listOf(red, yellow)
    }

    @Test
    fun `nothing heads the card when alerts are off, elsewhere, or minor`() {
        val severe = alert(AlertSeverity.SEVERE, start = null, end = "2026-10-23T20:00:00Z")
        val elsewhere = Location(latitude = 10.0, longitude = 10.0, displayName = "City B")
        cardAlertLevel(stored(severe), enabled = false, location = here, window = tonight, now = now) shouldBe null
        cardAlertLevel(stored(severe), enabled = true, location = elsewhere, window = tonight, now = now) shouldBe null
        val minor = alert(AlertSeverity.MINOR, start = null, end = "2026-10-23T20:00:00Z")
        cardAlertLevel(stored(minor), enabled = true, location = here, window = tonight, now = now) shouldBe null
    }

    @Test
    fun `without a window, what's in force now`() {
        val severe = alert(AlertSeverity.SEVERE, start = "2026-10-23T06:00:00Z", end = "2026-10-23T20:00:00Z")
        cardAlertLevel(stored(severe), enabled = true, location = here, window = null, now = now) shouldBe AlertLevel.ORANGE
    }
}
