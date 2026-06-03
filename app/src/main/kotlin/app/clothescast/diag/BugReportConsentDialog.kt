package app.clothescast.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.clothescast.R

/**
 * Confirmation dialog the user sees before any "share bug report" entry point
 * actually opens the share sheet. The payload built by [BugReport] includes
 * the saved location, app settings, and recent log lines — plus a screenshot
 * of the current screen — all of which leave the device the moment the user
 * picks a destination, so we surface that up front and require an explicit
 * Continue tap rather than burying it in the privacy policy.
 *
 * The "Don't show this again" checkbox is offered because the disclosure is
 * the same every time and a power-user filing repeated reports shouldn't have
 * to re-confirm; ticking it persists via
 * [app.clothescast.data.SettingsRepository.setBugReportConsentAcknowledged]
 * and the three callsites skip this dialog on subsequent invocations.
 * Reinstalling the app resets the flag. The persisted key is versioned (see
 * SettingsRepository) so adding the screenshot disclosure re-prompts anyone
 * who had dismissed the earlier, text-only version of this dialog.
 *
 * Stateless w.r.t. that persistence: callers own the Don't-show pref and
 * receive the user's checkbox choice through [onConfirm]'s `dontShowAgain`
 * argument, which is only meaningful when the user actually taps Continue
 * (Cancel ignores the box, matching the standard Android pattern).
 */
@Composable
internal fun BugReportConsentDialog(
    onConfirm: (dontShowAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bug_report_consent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.bug_report_consent_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.bug_report_consent_screenshot),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Whole-row toggleable so taps on the label flip the box too —
                // standard Material a11y pattern. Checkbox.onCheckedChange is
                // null because the row owns the click; setting it would double-
                // fire on the box itself.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(
                        value = dontShowAgain,
                        role = Role.Checkbox,
                        onValueChange = { dontShowAgain = it },
                    ),
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.bug_report_consent_dont_show_again),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontShowAgain) }) {
                Text(stringResource(R.string.bug_report_consent_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bug_report_consent_cancel))
            }
        },
    )
}
