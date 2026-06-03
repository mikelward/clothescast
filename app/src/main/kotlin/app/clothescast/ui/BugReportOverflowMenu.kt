package app.clothescast.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.clothescast.ClothesCastApplication
import app.clothescast.R
import app.clothescast.diag.BugReport
import app.clothescast.diag.BugReportConsentDialog
import app.clothescast.diag.findActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Navigation to the About settings page, provided once at the nav host so the
 * shared [BugReportOverflowMenu] can reach it from any screen — Today and every
 * settings sub-page — without each call site threading a callback through the
 * deeply-nested [app.clothescast.ui.settings.SettingsScaffold]. Defaults to a
 * no-op so snapshot previews that render a scaffold standalone don't need a
 * provider; the nav host overrides it with the real navigation.
 */
val LocalNavigateToAbout = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * The app-bar overflow menu shared across Today and every settings page: "Report
 * a bug" — which screenshots the current screen and shares the diagnostic
 * payload, gated behind the one-time consent dialog — and "About". Putting the
 * same ⋮ on every screen means the captured screenshot always reflects whatever
 * the user is actually looking at when they report.
 *
 * The consent flag is read lazily on tap (not collected during composition) so
 * the menu can sit in [app.clothescast.ui.settings.SettingsScaffold] without a
 * snapshot preview — which has no [ClothesCastApplication] and no main
 * dispatcher — tripping over a lifecycle-bound flow collection.
 */
@Composable
internal fun BugReportOverflowMenu(
    onNavigateToAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    // Nullable so a standalone scaffold preview (Robolectric's default plain
    // Application, not ClothesCastApplication) renders the ⋮ without crashing.
    val app = context.applicationContext as? ClothesCastApplication
    val coroutineScope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var consentVisible by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }, modifier = modifier) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.today_more_options),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.today_report_a_bug)) },
            onClick = {
                expanded = false
                val act = activity
                val repo = app?.settingsRepository
                if (act != null && repo != null) {
                    coroutineScope.launch {
                        if (repo.bugReportConsentAcknowledged.first()) {
                            BugReport.share(act)
                        } else {
                            consentVisible = true
                        }
                    }
                }
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.settings_root_about)) },
            onClick = {
                expanded = false
                onNavigateToAbout()
            },
        )
    }

    if (consentVisible) {
        BugReportConsentDialog(
            onConfirm = { dontShowAgain ->
                consentVisible = false
                val act = activity
                if (act != null) {
                    coroutineScope.launch {
                        if (dontShowAgain) {
                            app?.settingsRepository?.setBugReportConsentAcknowledged(true)
                        }
                        BugReport.share(act)
                    }
                }
            },
            onDismiss = { consentVisible = false },
        )
    }
}
