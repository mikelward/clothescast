package app.clothescast.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.clothescast.core.domain.model.AlertAttribution
import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.usecase.RelevantAlert
import java.time.Instant
import java.time.ZoneOffset

// Fixed instants and UTC so the "Until" lines don't move with the runner's
// clock or zone.
private val PREVIEW_NOW: Instant = Instant.parse("2026-10-22T10:00:00Z")

private fun sampleAlert(
    id: String,
    title: String,
    eventType: String,
    severity: AlertSeverity,
    end: String,
    notifiable: Boolean = true,
    description: String = "Very strong winds are expected to cause disruption to travel and power supplies.",
    instruction: String = "Secure loose objects and take care on exposed routes.",
    issuedAt: Instant? = null,
    attribution: AlertAttribution? = null,
) = RelevantAlert(
    alert = WeatherAlert(
        id = id,
        issuerTitle = title,
        eventType = eventType,
        severity = severity,
        areaName = "Region A | Region B",
        description = description,
        instruction = instruction,
        startTime = null,
        endTime = Instant.parse(end),
        issuer = "Met Office",
        issuerUrl = "https://example.com/warnings",
        area = null,
        issuedAt = issuedAt,
        attribution = attribution,
    ),
    notifiable = notifiable,
)

private val SAMPLE_ALERTS = listOf(
    sampleAlert("red", "Red warning of wind", "WIND", AlertSeverity.EXTREME, "2026-10-22T21:00:00Z"),
    sampleAlert(
        "amber",
        "Amber warning of rain",
        "RAIN",
        AlertSeverity.SEVERE,
        "2026-10-23T18:00:00Z",
        description = "Persistent heavy rain may bring flooding and travel disruption. " +
            "Flood updates at www.example.com/floods.",
        instruction = "Avoid driving through flood water.",
    ),
    sampleAlert("yellow", "Yellow warning of fog", "FOG", AlertSeverity.MODERATE, "2026-10-23T09:00:00Z"),
)

// Four in force: three cards, the fourth behind "1 more alert".
@Preview(name = "Alerts · cards", widthDp = 360)
@Composable
internal fun WeatherAlertCardsPreview() {
    Frame {
        WeatherAlertCards(
            alerts = SAMPLE_ALERTS + sampleAlert(
                "yellow-ice",
                "Yellow warning of ice",
                "ICE",
                AlertSeverity.MODERATE,
                "2026-10-23T11:00:00Z",
            ),
            now = PREVIEW_NOW,
            onDismiss = {},
            zone = ZoneOffset.UTC,
        )
    }
}

@Preview(name = "Alerts · cards dark", widthDp = 360)
@Composable
internal fun WeatherAlertCardsDarkPreview() {
    Frame(darkTheme = true) {
        WeatherAlertCards(alerts = SAMPLE_ALERTS, now = PREVIEW_NOW, onDismiss = {}, zone = ZoneOffset.UTC)
    }
}

// The levels side by side, collapsed, plus a broad-area alert shown at the
// neutral level.
@Preview(name = "Alerts · every level", widthDp = 360)
@Composable
internal fun WeatherAlertLevelsPreview() {
    Frame {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (
                SAMPLE_ALERTS + sampleAlert(
                    "broad",
                    "Typhoon information",
                    "TYPHOON",
                    AlertSeverity.UNKNOWN,
                    "2026-10-24T00:00:00Z",
                    notifiable = false,
                )
                ).forEach { WeatherAlertCard(it, PREVIEW_NOW, onDismiss = {}, zone = ZoneOffset.UTC) }
        }
    }
}

@Preview(name = "Alerts · card expanded", widthDp = 360)
@Composable
internal fun WeatherAlertCardExpandedPreview() {
    Frame {
        WeatherAlertCard(
            SAMPLE_ALERTS[1],
            PREVIEW_NOW,
            onDismiss = {},
            zone = ZoneOffset.UTC,
            initiallyExpanded = true,
        )
    }
}

// From ClothesCast's own source: the issue time and the source's credit,
// which its terms ask for, under the issuer's text.
@Preview(name = "Alerts · card expanded with credit", widthDp = 360)
@Composable
internal fun WeatherAlertCardCreditedPreview() {
    Frame {
        WeatherAlertCard(
            sampleAlert(
                "amber-credited",
                "Amber wind warning",
                "WIND",
                AlertSeverity.SEVERE,
                "2026-10-23T18:00:00Z",
                issuedAt = Instant.parse("2026-10-22T09:02:00Z"),
                attribution = AlertAttribution("Met Office via MeteoAlarm", "https://example.com/credit"),
            ),
            PREVIEW_NOW,
            onDismiss = {},
            zone = ZoneOffset.UTC,
            initiallyExpanded = true,
        )
    }
}
