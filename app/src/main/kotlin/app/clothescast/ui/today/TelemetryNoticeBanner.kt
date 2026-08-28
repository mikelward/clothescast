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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.ClothesCastApplication
import app.clothescast.R
import kotlinx.coroutines.launch

/**
 * One-time non-blocking invitation to turn telemetry (Firebase Analytics +
 * Crashlytics) on. It is off until the user does, so this is an offer rather
 * than a disclosure: without it an opt-in toggle buried in Settings → Privacy
 * is one almost nobody finds, and the crash reports that would fix their bugs
 * never arrive. Dismisses on tap and never returns. Tapping "Settings"
 * deep-links to the Privacy sub-page; tapping the X just acks the notice.
 *
 * Hides itself once the user has acked it (banner dismissed OR Privacy opened
 * from the banner). Kept separate from the telemetry preference so a user who
 * flips telemetry on and off again doesn't see this one-time invitation a
 * second time — and so declining it stays a decision they made once.
 */
@Composable
internal fun TelemetryNoticeBanner(
    visible: Boolean,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    // Acking the notice reaches the app's settingsRepository via LocalContext,
    // which isn't a ClothesCastApplication under @Preview / snapshot
    // composition — no-op there so a full-screen banner-stack preview renders
    // cleanly without a live Application.
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val app = context.applicationContext as ClothesCastApplication
    val settings = app.settingsRepository
    val coroutineScope = rememberCoroutineScope()

    // Answering either way stores a choice, so nothing downstream can read the
    // user as simply never having been asked. Declining writes `false` rather
    // than leaving the key absent, which is the difference between "said no"
    // and "not yet asked" — and it is `setTelemetryEnabled` that then owes the
    // discard on a later opt-in.
    fun answer(enabled: Boolean) {
        // One call: `setTelemetryEnabled` acks in the same edit as the choice,
        // so there is no window where the choice is stored and the banner is
        // still asking (Codex, PR #1161).
        coroutineScope.launch { settings.setTelemetryEnabled(enabled) }
    }

    TelemetryNoticeBannerCard(
        modifier = modifier,
        onAccept = { answer(true) },
        onDecline = { answer(false) },
        // The X is not an answer: it stores nothing, so the choice stays
        // absent — which reporting already treats as off — and the next launch
        // puts the question again. The flag lives in `TodayContent`, above the
        // empty-state / forecast branch: held here it was destroyed the moment
        // the first forecast arrived and swapped one `BannerStack` call for the
        // other, and the banner came straight back in the same session (Codex,
        // PR #1161).
        onDismiss = { app.telemetryInviteDismissedForSession.value = true },
    )
}

/**
 * Whether the invitation has been dismissed for this session — the X, which
 * stores nothing.
 *
 * Held on the application, not in the banner: Today swaps one `BannerStack`
 * call site for another the moment the first forecast arrives — and again per
 * pager page — so a `rememberSaveable` under either branch is destroyed when
 * that branch goes, and dismissing before the first forecast brought the
 * banner straight back in the same session (Codex, PR #1161).
 *
 * Read where promo eligibility is decided rather than inside the banner. Only
 * hiding the card there left `TELEMETRY` still holding one of the two promo
 * slots, so a lower-priority setup card stayed hidden behind a row that
 * rendered nothing (Codex, PR #1161).
 *
 * False under `@Preview` / snapshot composition, where the context is not a
 * [ClothesCastApplication].
 */
@Composable
internal fun telemetryInviteDismissedForSession(): Boolean {
    if (LocalInspectionMode.current) return false
    val app = LocalContext.current.applicationContext as ClothesCastApplication
    val dismissed by app.telemetryInviteDismissedForSession.collectAsState()
    return dismissed
}

@Composable
internal fun TelemetryNoticeBannerCard(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
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
                    text = stringResource(R.string.today_telemetry_invite_title),
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
                text = stringResource(R.string.today_telemetry_invite_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // Both answers are here, because a question you can only walk
                // away from is not one that was asked. Declining is a stored
                // "no" rather than an absent choice, so nothing later reads it
                // as never having been put to them.
                TextButton(onClick = onDecline) {
                    Text(stringResource(R.string.today_telemetry_invite_decline))
                }
                TextButton(onClick = onAccept) {
                    Text(stringResource(R.string.today_telemetry_invite_accept))
                }
            }
        }
    }
}
