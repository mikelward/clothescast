package app.clothescast.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.ClothesCastApplication
import app.clothescast.R
import app.clothescast.diag.BugReport
import app.clothescast.diag.BugReportConsentDialog
import app.clothescast.diag.DiagLog
import app.clothescast.diag.findActivity
import kotlinx.coroutines.launch

/**
 * Banner shown when the previous run died with an uncaught exception and the
 * user hasn't yet acted on the saved trace. Two buttons: "Share report" hands
 * the existing on-device [BugReport] payload to the system share sheet (the
 * user picks where it goes — nothing leaves the device automatically);
 * "Dismiss" silences the banner without sharing.
 *
 * Both buttons mark the crash as acknowledged so the banner doesn't keep
 * reappearing. A *new* crash bumps the on-disk file's mtime, which
 * [DiagLog.hasUnacknowledgedCrash] uses as identity, so the banner
 * surfaces again next launch.
 *
 * Re-checks the file on lifecycle ON_RESUME so a backgrounded app coming
 * forward after a crash in another process surfaces the banner without
 * requiring a process restart.
 */
@Composable
internal fun LastCrashBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val coroutineScope = rememberCoroutineScope()
    val app = context.applicationContext as ClothesCastApplication
    val bugReportConsentAcked by app.settingsRepository.bugReportConsentAcknowledged
        .collectAsStateWithLifecycle(initialValue = false)
    var hasCrash by remember { mutableStateOf(DiagLog.hasUnacknowledgedCrash()) }
    var consentVisible by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCrash = DiagLog.hasUnacknowledgedCrash()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasCrash) return

    val shareCrashReport: () -> Unit = shareCrashReport@{
        val act = activity ?: return@shareCrashReport
        coroutineScope.launch {
            // includeScreenshot=false: the visible screen is *now*, but the
            // crash is from a previous run, so a current screenshot would be
            // misleading.
            BugReport.share(act, includeScreenshot = false)
            DiagLog.acknowledgePersistedCrash()
            hasCrash = false
        }
    }

    LastCrashBannerCard(
        modifier = modifier,
        onShare = {
            if (bugReportConsentAcked) shareCrashReport() else consentVisible = true
        },
        onDismiss = {
            DiagLog.acknowledgePersistedCrash()
            hasCrash = false
        },
    )

    if (consentVisible) {
        // Cancelling consent leaves the banner visible: the user hasn't
        // shared yet, so we don't acknowledge the crash. They can tap
        // Share again or Dismiss it explicitly.
        BugReportConsentDialog(
            onConfirm = { dontShowAgain ->
                consentVisible = false
                if (dontShowAgain) {
                    coroutineScope.launch {
                        app.settingsRepository.setBugReportConsentAcknowledged(true)
                    }
                }
                shareCrashReport()
            },
            onDismiss = { consentVisible = false },
        )
    }
}

@Composable
internal fun LastCrashBannerCard(
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.today_crash_banner_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.today_crash_banner_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.today_crash_banner_dismiss))
                }
                Button(
                    onClick = onShare,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.today_crash_banner_share))
                }
            }
        }
    }
}
