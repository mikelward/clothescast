package app.clothescast.ui.today

import app.clothescast.alerts.WeatherAlertsRefresher
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.usecase.RelevantAlert
import app.clothescast.data.AlertsCheckOutcome
import app.clothescast.data.WeatherAlertsState
import app.clothescast.data.dismissKey
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `a dismissed card stays hidden until the alert changes`() {
        val wind = relevant(AlertSeverity.SEVERE)
        val fog = relevant(AlertSeverity.MODERATE)
        val state = stored(wind, fog).let { it.copy(dismissed = mapOf(wind.alert.id to wind.dismissKey())) }
        state.undismissed(listOf(wind, fog)) shouldBe listOf(fog)

        // Any change the issuer makes brings the card back: a later end, new
        // text, a higher level.
        val later = wind.copy(alert = wind.alert.copy(endTime = now.plus(Duration.ofHours(9))))
        val reworded = wind.copy(alert = wind.alert.copy(description = "Gusts now up to 80 mph."))
        val raised = wind.copy(alert = wind.alert.copy(severity = AlertSeverity.EXTREME))
        listOf(later, reworded, raised).forEach { changed ->
            state.undismissed(listOf(changed, fog)) shouldBe listOf(changed, fog)
        }
    }

    @Test
    fun `a card dismissed at the neutral level returns when its area narrows`() {
        // Too broad to notify, so drawn neutral; then the issuer narrows the
        // area and the same text is drawn at its own level.
        val broad = relevant(AlertSeverity.SEVERE, notifiable = false)
        val narrowed = broad.copy(notifiable = true)
        val state = stored(broad).copy(dismissed = mapOf(broad.alert.id to broad.dismissKey()))
        state.undismissed(listOf(broad)).shouldBeEmpty()
        state.undismissed(listOf(narrowed)) shouldBe listOf(narrowed)
    }

    @Test
    fun `dismissing a card leaves the strip's level`() {
        val wind = relevant(AlertSeverity.SEVERE)
        TodayAlertsState(alerts = listOf(wind), cards = emptyList()).stripLevel shouldBe AlertLevel.ORANGE
    }

    @Test
    fun `the alerts promo invites only where a source covers the country`() {
        val gb = here.copy(countryCode = "gb")
        val covered = setOf("GB", "US")
        alertsPromoVisible(alertsOn = false, dismissed = false, location = gb, covered = covered) shouldBe true
        // Not covered, not yet read, or the country unknown: nobody is invited.
        alertsPromoVisible(false, false, here.copy(countryCode = "AU"), covered) shouldBe false
        alertsPromoVisible(false, false, gb, covered = null) shouldBe false
        alertsPromoVisible(false, false, here, covered) shouldBe false
        alertsPromoVisible(false, false, location = null, covered = covered) shouldBe false
        // Already on, or dismissed.
        alertsPromoVisible(alertsOn = true, dismissed = false, location = gb, covered = covered) shouldBe false
        alertsPromoVisible(alertsOn = false, dismissed = true, location = gb, covered = covered) shouldBe false
    }

    @Test
    fun `coverage is read once, and only while the promo could show`() = runTest(UnconfinedTestDispatcher()) {
        var reads = 0
        val couldShow = MutableStateFlow(false)
        val seen = mutableListOf<Set<String>?>()
        backgroundScope.coverageWhile(couldShow) { reads++; setOf("GB") }
            .onEach { seen += it }
            .launchIn(backgroundScope)
        reads shouldBe 0
        seen shouldBe listOf(null)

        couldShow.value = true
        reads shouldBe 1
        seen.last() shouldBe setOf("GB")

        // Alerts turned on, then off again: the same answer, not a second read.
        couldShow.value = false
        seen.last() shouldBe null
        couldShow.value = true
        reads shouldBe 1
        seen.last() shouldBe setOf("GB")
    }
}
