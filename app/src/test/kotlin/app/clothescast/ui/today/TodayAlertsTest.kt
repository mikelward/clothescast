package app.clothescast.ui.today

import app.clothescast.alerts.WeatherAlertsRefresher
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.usecase.RelevantAlert
import app.clothescast.data.AlertsCheckOutcome
import app.clothescast.data.WeatherAlertsState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TodayAlertsTest {
    private val here = Location(latitude = 1.48, longitude = -1.9, displayName = "City A")
    private val elsewhere = Location(latitude = 10.0, longitude = 10.0, displayName = "City B")
    private val now = Instant.parse("2026-10-22T10:00:00Z")

    private fun relevant(
        severity: AlertSeverity,
        end: Instant? = now.plus(Duration.ofHours(6)),
        notifiable: Boolean = true,
    ) = RelevantAlert(
        alert = WeatherAlert(
            id = "$severity-$end",
            issuerTitle = "Warning",
            eventType = "WIND",
            severity = severity,
            areaName = null,
            description = null,
            instruction = null,
            startTime = null,
            endTime = end,
            issuer = null,
            issuerUrl = null,
            area = null,
        ),
        notifiable = notifiable,
    )

    private fun stored(vararg alerts: RelevantAlert) = WeatherAlertsState(
        checkedAt = now,
        outcome = AlertsCheckOutcome.OK,
        point = WeatherAlertsRefresher.alertPoint(here),
        alerts = alerts.toList(),
    )

    @Test
    fun `alerts show for the place they were checked for, while on and in force`() {
        val severe = relevant(AlertSeverity.SEVERE)
        val ended = relevant(AlertSeverity.EXTREME, end = now.minusSeconds(1))
        stored(severe, ended).onScreen(enabled = true, location = here, now = now) shouldBe listOf(severe)
    }

    @Test
    fun `another place's alerts never show, nor any while alerts are off`() {
        val state = stored(relevant(AlertSeverity.SEVERE))
        state.onScreen(enabled = true, location = elsewhere, now = now).shouldBeEmpty()
        state.onScreen(enabled = false, location = here, now = now).shouldBeEmpty()
        state.onScreen(enabled = true, location = null, now = now).shouldBeEmpty()
    }

    @Test
    fun `the strip shows yellow and up, never a minor or too-broad alert`() {
        TodayAlertsState(alerts = listOf(relevant(AlertSeverity.MINOR))).stripLevel shouldBe null
        TodayAlertsState(alerts = listOf(relevant(AlertSeverity.EXTREME, notifiable = false))).stripLevel shouldBe null
        TodayAlertsState(
            alerts = listOf(relevant(AlertSeverity.MODERATE), relevant(AlertSeverity.SEVERE)),
        ).stripLevel shouldBe AlertLevel.ORANGE
        TodayAlertsState().stripLevel shouldBe null
    }
}
