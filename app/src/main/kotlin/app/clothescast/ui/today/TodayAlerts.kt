package app.clothescast.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.alerts.AlertLevelIcon
import app.clothescast.alerts.WeatherAlertsRefresher
import app.clothescast.alerts.alertLinks
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
    /** Those with a card on Today: [alerts] less the ones dismissed unchanged. */
    val cards: List<RelevantAlert> = alerts,
    /** Alerts on at a level that posts notifications, so the permission matters. */
    val notifies: Boolean = false,
) {
    /**
     * The level the conditions strip shows: yellow and up. A minor alert, or
     * one too broad to tie to the user's point, stays in the cards only. A
     * dismissed card still counts: the warning is still in force.
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

/** [alerts] less those whose card was dismissed and hasn't changed since. */
internal fun WeatherAlertsState.undismissed(alerts: List<RelevantAlert>): List<RelevantAlert> =
    alerts.filterNot { isDismissed(it) }

/** Today's alerts plus the actions its cards take, for the pages that draw them. */
internal class TodayAlertsUi(
    val state: TodayAlertsState,
    val onDismissAlert: (RelevantAlert) -> Unit,
) {
    /** The same, minus the alerts: for pages past the current period. */
    fun withoutAlerts(): TodayAlertsUi = TodayAlertsUi(
        state.copy(alerts = emptyList(), cards = emptyList()),
        onDismissAlert,
    )
}

/**
 * Provided once by [TodayScreen] so the banner stack, the alert cards and the
 * conditions strip read one view without threading it through every page.
 */
internal val LocalTodayAlerts = compositionLocalOf { TodayAlertsUi(TodayAlertsState(), {}) }

/** How many alert cards show before the rest fold behind "N more alerts". */
private const val ALERTS_SHOWN = 3

/**
 * The alerts in force, one single-row card each: the level icon, the title
 * and the end time, with the issuer, area, the issuer's own words, a link and
 * Dismiss a tap away. Outlined and neutral, so the icon carries the color (a
 * yellow icon can't be read on a yellow card).
 */
@Composable
internal fun WeatherAlertCards(
    alerts: List<RelevantAlert>,
    now: Instant,
    onDismiss: (RelevantAlert) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    if (alerts.isEmpty()) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (showAll) alerts else alerts.take(ALERTS_SHOWN)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { WeatherAlertCard(it, now, onDismiss = onDismiss, zone = zone) }
        val hidden = alerts.size - shown.size
        if (hidden > 0) {
            TextButton(onClick = { showAll = true }) {
                Text(pluralStringResource(R.plurals.weather_alert_more, hidden, hidden))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeatherAlertCard(
    relevant: RelevantAlert,
    now: Instant,
    onDismiss: (RelevantAlert) -> Unit,
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
                // The issuer's own words as written, web addresses tappable;
                // only the title and the "until" line are the app's.
                listOfNotNull(alert.issuer, alert.areaName, alert.description, alert.instruction)
                    .filter { it.isNotBlank() }
                    .forEach { text ->
                        Text(
                            text = linkified(text, MaterialTheme.colorScheme.primary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                // Wraps rather than squeezing Dismiss out beside a long
                // issuer name or at a large font size.
                FlowRow {
                    alert.issuerUrl?.let { url ->
                        TextButton(onClick = { openUrl(context, url) }) {
                            Text(
                                alert.issuer?.let { stringResource(R.string.weather_alert_open_source, it) }
                                    ?: stringResource(R.string.weather_alert_open_source_fallback),
                            )
                        }
                    }
                    // Until the issuer changes anything about it
                    // (WeatherAlertsState.dismissed).
                    TextButton(onClick = { onDismiss(relevant) }) {
                        Text(stringResource(R.string.weather_alert_dismiss))
                    }
                }
            }
        }
    }
}

/** [text] with each web address in it a link, in [linkColor]. */
private fun linkified(text: String, linkColor: Color): AnnotatedString {
    val links = alertLinks(text)
    if (links.isEmpty()) return AnnotatedString(text)
    val style = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0
        links.forEach { link ->
            append(text, at, link.range.first)
            withLink(LinkAnnotation.Url(link.url, style)) { append(text, link.range.first, link.range.last + 1) }
            at = link.range.last + 1
        }
        append(text, at, text.length)
    }
}
