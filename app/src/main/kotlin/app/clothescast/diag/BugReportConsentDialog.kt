package app.clothescast.diag

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.clothescast.R

/**
 * Confirmation dialog the user sees before any "share bug report" entry point
 * actually opens the share sheet. The payload built by [BugReport] includes
 * the saved location, app settings, and recent log lines — all of which leave
 * the device the moment the user picks a destination — so we surface that up
 * front and require an explicit Continue tap rather than burying it in the
 * privacy policy.
 *
 * Stateless: callers own the show/hide state and what to do on confirm.
 */
@Composable
internal fun BugReportConsentDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bug_report_consent_title)) },
        text = {
            Text(
                text = stringResource(R.string.bug_report_consent_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
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
