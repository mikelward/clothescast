package app.clothescast.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.BuildConfig
import app.clothescast.ClothesCastApplication
import app.clothescast.R
import app.clothescast.diag.BugReport
import app.clothescast.diag.BugReportConsentDialog
import app.clothescast.diag.findActivity
import app.clothescast.ui.EdgeFadeOverlay
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.launch

@Composable
internal fun AboutContent(padding: PaddingValues, onOpenLicenses: () -> Unit = {}) {
    val scrollState = rememberScrollState()
    EdgeFadeOverlay(
        scrollState = scrollState,
        modifier = Modifier.padding(padding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AboutCard(onOpenLicenses = onOpenLicenses)
            if (BuildConfig.DEBUG) {
                DebugCard()
            }
        }
    }
}

@Composable
private fun AboutCard(onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val coroutineScope = rememberCoroutineScope()
    val app = context.applicationContext as ClothesCastApplication
    val bugReportConsentAcked by app.settingsRepository.bugReportConsentAcknowledged
        .collectAsStateWithLifecycle(initialValue = false)
    var bugReportConsentVisible by remember { mutableStateOf(false) }

    val launchBugReport: () -> Unit = launchBugReport@{
        val act = activity ?: return@launchBugReport
        // On the application scope: the share sheet taking the foreground (or the
        // user navigating away while the payload is still being built) tears this
        // screen down, and a composition-scoped share would be cancelled partway
        // through — the report would silently never arrive.
        app.applicationScope.launch { BugReport.share(act) }
    }

    SectionCard(title = stringResource(R.string.settings_about_title)) {
        // Release builds get a clean "Version 0.1.0+61.85d100b (61)". Anything else
        // (debug today, possibly internal QA flavours later) appends " · <type> build"
        // so a tester can tell which install they're on without digging into adb.
        val versionText = stringResource(
            R.string.settings_about_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        val buildTypeSuffix = if (BuildConfig.BUILD_TYPE != "release") {
            stringResource(R.string.settings_about_build_type_suffix, BuildConfig.BUILD_TYPE)
        } else {
            ""
        }
        val fullVersionLine = versionText + buildTypeSuffix
        val copiedToast = stringResource(R.string.settings_about_version_copied)
        Text(
            text = fullVersionLine,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("ClothesCast version", fullVersionLine))
                    // Android 13+ shows its own clipboard preview; older devices need our toast
                    // to know the tap actually did something.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                    }
                },
        )
        TextButton(
            onClick = { openUrl(context, "https://github.com/mikelward/clothescast") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_about_source)) }
        TextButton(
            onClick = { openUrl(context, "https://dontkillmyapp.com") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_about_dontkillmyapp)) }
        TextButton(
            onClick = { openUrl(context, OPEN_METEO_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_about_open_meteo)) }
        TextButton(
            onClick = { openUrl(context, PRIVACY_POLICY_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_privacy_open_policy)) }
        // Below the outbound links, above the bug report: the attribution list
        // belongs with the legal reading matter, but the bug report is the one
        // row here a user acts on, so it stays last.
        TextButton(
            onClick = onOpenLicenses,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_about_licenses)) }
        TextButton(
            onClick = {
                if (bugReportConsentAcked) {
                    launchBugReport()
                } else {
                    bugReportConsentVisible = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_about_share_bug_report)) }
    }

    if (bugReportConsentVisible) {
        BugReportConsentDialog(
            onConfirm = { dontShowAgain ->
                bugReportConsentVisible = false
                if (dontShowAgain) {
                    coroutineScope.launch {
                        app.settingsRepository.setBugReportConsentAcknowledged(true)
                    }
                }
                launchBugReport()
            },
            onDismiss = { bugReportConsentVisible = false },
        )
    }
}

@Composable
private fun DebugCard() {
    val context = LocalContext.current
    val fireToast = stringResource(R.string.settings_debug_fire_toast)
    SectionCard(title = stringResource(R.string.settings_debug_title)) {
        Text(
            text = stringResource(R.string.settings_debug_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = {
                FetchAndNotifyWorker.enqueueOneShot(context.applicationContext)
                Toast.makeText(
                    context,
                    fireToast,
                    Toast.LENGTH_SHORT,
                ).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_debug_fire_now)) }
    }
}
