package app.clothescast.alerts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.clothescast.R
import app.clothescast.ui.settings.openUrl

/**
 * ClothesCast's disclaimer for weather alerts, on every alert card and in
 * Settings → Alerts. One short line until asked (maintainer, 2026-10-23:
 * "No guarantees, no liability", the rest behind a button, so it doesn't
 * clutter the card); the full text and, given [sourceDisclaimerUrl], a link
 * to the source's own disclaimer show when expanded, named for its author
 * ([sourceDisclaimerName]: "MeteoAlarm disclaimer") where the index gives one,
 * after its wording ([sourceDisclaimerText], verbatim) where the source's
 * terms require it shown (MeteoAlarm's §5).
 */
@Composable
internal fun AlertDisclaimer(
    sourceDisclaimerUrl: String?,
    modifier: Modifier = Modifier,
    sourceDisclaimerName: String? = null,
    sourceDisclaimerText: String? = null,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val context = LocalContext.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The card's and the page's body size (maintainer, 2026-10-23);
            // only the full text below is fine print.
            Text(
                text = stringResource(R.string.weather_alert_disclaimer_short),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = { expanded = !expanded }) {
                val label = if (expanded) {
                    R.string.weather_alert_disclaimer_hide
                } else {
                    R.string.weather_alert_disclaimer_show
                }
                Text(stringResource(label))
            }
        }
        if (expanded) {
            Text(
                text = stringResource(R.string.weather_alert_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sourceDisclaimerText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            sourceDisclaimerUrl?.let { url ->
                TextButton(onClick = { openUrl(context, url) }) {
                    Text(
                        sourceDisclaimerName?.let { stringResource(R.string.weather_alert_named_disclaimer, it) }
                            ?: stringResource(R.string.weather_alert_source_disclaimer),
                    )
                }
            }
        }
    }
}
