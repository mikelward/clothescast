package app.clothescast.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.ClothesCastApplication
import app.clothescast.R
import kotlinx.coroutines.launch

/**
 * One-time non-blocking notice that telemetry (Firebase Analytics + Crashlytics)
 * is on by default. The toggle itself lives in Settings → Privacy; this banner
 * exists so the default-on choice isn't hidden — the user gets a single
 * surface that says "you're sending crash data, here's how to turn it off",
 * dismisses on tap, and never returns. Tapping "Settings" deep-links to the
 * Privacy sub-page; tapping the X just acks the notice.
 *
 * Hides itself once the user has acked it (banner dismissed OR Privacy opened
 * from the banner). Kept separate from the [telemetryEnabled] preference so a
 * user who flips telemetry off and on again doesn't see this one-time notice
 * a second time.
 */
@Composable
internal fun TelemetryNoticeBanner(
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The telemetry-notice pref is read via a lifecycle-scoped flow that can't
    // run in @Preview / snapshot composition — no-op so a full-screen preview
    // (the scaffold's banner stack) renders cleanly.
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val settings = (context.applicationContext as ClothesCastApplication).settingsRepository
    val coroutineScope = rememberCoroutineScope()

    val prefs by settings.preferences.collectAsStateWithLifecycle(initialValue = null)
    val current = prefs ?: return
    if (current.telemetryNoticeAcked) return

    fun ack() {
        coroutineScope.launch { settings.setTelemetryNoticeAcked(true) }
    }

    TelemetryNoticeBannerCard(
        modifier = modifier,
        onOpenSettings = {
            ack()
            onOpenPrivacy()
        },
        onDismiss = { ack() },
    )
}

@Composable
internal fun TelemetryNoticeBannerCard(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    text = stringResource(R.string.today_telemetry_notice_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.today_telemetry_notice_dismiss),
                    )
                }
            }
            Text(
                text = stringResource(R.string.today_telemetry_notice_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.today_telemetry_notice_open_settings))
                }
            }
        }
    }
}
