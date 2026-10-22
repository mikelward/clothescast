package app.clothescast.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.alerts.AlertLevelIcon
import app.clothescast.alerts.WeatherAlertsRefresher
import app.clothescast.alerts.alertTitle
import app.clothescast.alerts.alertUntil
import app.clothescast.alerts.icon
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.usecase.RelevantAlert
import app.clothescast.data.WeatherAlertsState
import app.clothescast.ui.LocalTimeFormat
import app.clothescast.ui.settings.openUrl
import java.time.Instant
import java.time.ZoneId

/** What Today shows of weather alerts (docs/ALERTS.md → What the user sees). */
data class TodayAlertsState(
    /** The alerts in force at the forecast's place, most severe first. */
    val alerts: List<RelevantAlert> = emptyList(),
    /** Signed in with Google, alerts off, and the promo not yet dismissed. */
    val promoVisible: Boolean = false,
    /** Alerts on at a level that posts notifications, so the permission matters. */
    val notifies: Boolean = false,
) {
    /**
     * The level the conditions strip shows: yellow and up. A minor alert, or
     * one too broad to tie to the user's point, stays in the cards only.
     */
    val stripLevel: AlertLevel?
        get() = alerts.maxOfOrNull { it.level }?.takeIf { it >= AlertLevel.YELLOW }
}

/**
 * The alerts that apply on screen: alerts on, checked for the place the
 * forecast is for (an old place's alerts never show for a new one), and still
 * in force at [now].
 */
internal fun WeatherAlertsState.onScreen(
    enabled: Boolean,
    location: Location?,
    now: Instant,
): List<RelevantAlert> {
    if (!enabled || location == null) return emptyList()
    if (point != WeatherAlertsRefresher.alertPoint(location)) return emptyList()
    return active(now)
}

/** Today's alerts plus the actions its cards take, for the pages that draw them. */
internal class TodayAlertsUi(
    val state: TodayAlertsState,
    val onOpenSettings: () -> Unit,
    val onDismissPromo: () -> Unit,
) {
    /** The same, minus the alerts: for pages past the current period. */
    fun withoutAlerts(): TodayAlertsUi =
        TodayAlertsUi(state.copy(alerts = emptyList()), onOpenSettings, onDismissPromo)
}

/**
 * Provided once by [TodayScreen] so the banner stack, the alert cards and the
 * conditions strip read one view without threading it through every page.
 */
internal val LocalTodayAlerts = compositionLocalOf { TodayAlertsUi(TodayAlertsState(), {}, {}) }

/** How many alert cards show before the rest fold behind "N more alerts". */
private const val ALERTS_SHOWN = 3

/**
 * The alerts in force, one single-row card each: the level icon, the title
 * and the end time, with the issuer, area, the issuer's own words and a link
 * a tap away. Outlined and neutral, so the icon carries the color (a yellow
 * icon can't be read on a yellow card).
 */
@Composable
internal fun WeatherAlertCards(
    alerts: List<RelevantAlert>,
    now: Instant,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    if (alerts.isEmpty()) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (showAll) alerts else alerts.take(ALERTS_SHOWN)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { WeatherAlertCard(it, now, zone = zone) }
        val hidden = alerts.size - shown.size
        if (hidden > 0) {
            TextButton(onClick = { showAll = true }) {
                Text(pluralStringResource(R.plurals.weather_alert_more, hidden, hidden))
            }
        }
    }
}

@Composable
internal fun WeatherAlertCard(
    relevant: RelevantAlert,
    now: Instant,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    // Previews only: a card starts collapsed on screen.
    initiallyExpanded: Boolean = false,
) {
    val context = LocalContext.current
    val alert = relevant.alert
    var expanded by rememberSaveable(alert.id) { mutableStateOf(initiallyExpanded) }
    val title = alertTitle(context, alert)
    val until = alertUntil(
        context,
        alert,
        now,
        LocalTimeFormat.current,
        LocalConfiguration.current.locales[0],
        zone,
    )
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.weather_alert_collapse else R.string.weather_alert_expand,
                    ),
                ) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlertLevelIcon(relevant.icon)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                until?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
            if (expanded) {
                // The issuer's own words as written; only the title and the
                // "until" line are the app's.
                listOfNotNull(alert.issuer, alert.areaName, alert.description, alert.instruction)
                    .filter { it.isNotBlank() }
                    .forEach { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                alert.issuerUrl?.let { url ->
                    TextButton(onClick = { openUrl(context, url) }) {
                        Text(
                            alert.issuer?.let { stringResource(R.string.weather_alert_open_source, it) }
                                ?: stringResource(R.string.weather_alert_open_source_fallback),
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Get weather alerts": shown once the user is signed in with Google (the
 * proxy's requirement) while alerts are off, until dismissed or followed.
 * The CTA opens Alerts settings, where the switch lives.
 */
@Composable
internal fun WeatherAlertsPromoCard(
    visible: Boolean,
    onOpenAlerts: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.today_alerts_promo_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.today_alerts_promo_dismiss),
                    )
                }
            }
            Text(
                text = stringResource(R.string.today_alerts_promo_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenAlerts) {
                    Text(stringResource(R.string.today_alerts_promo_cta))
                }
            }
        }
    }
}
