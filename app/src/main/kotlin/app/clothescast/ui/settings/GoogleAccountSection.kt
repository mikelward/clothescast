package app.clothescast.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import app.clothescast.ClothesCastApplication
import app.clothescast.R
import app.clothescast.data.GoogleAccountResult
import app.clothescast.data.SignedInGoogleAccount
import kotlinx.coroutines.launch

/**
 * Sign in with Google for the free Gemini voice (SPEC.md → "Accounts and
 * the developer-funded paths"). Signed out: why it's needed and the button.
 * Signed in: who, plus sign out and delete account (Play's account-deletion
 * requirement).
 */
@Composable
internal fun GoogleAccountSection(
    account: SignedInGoogleAccount?,
    @StringRes signedOutReason: Int,
    enabled: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (account == null) {
            Text(
                text = stringResource(signedOutReason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onSignIn,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_google_sign_in)) }
        } else {
            Text(
                text = account.email
                    ?.let { stringResource(R.string.settings_google_signed_in_as, it) }
                    ?: stringResource(R.string.settings_google_signed_in),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSignOut, enabled = enabled) {
                    Text(stringResource(R.string.settings_google_sign_out))
                }
                TextButton(onClick = { confirmingDelete = true }, enabled = enabled) {
                    Text(stringResource(R.string.settings_google_delete_account))
                }
            }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.settings_google_delete_title)) },
            text = { Text(stringResource(R.string.settings_google_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDeleteAccount()
                }) { Text(stringResource(R.string.settings_google_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** What the settings pages call; see [rememberGoogleAccountActions]. */
internal class GoogleAccountActions(
    val signIn: () -> Unit,
    val signOut: () -> Unit,
    val deleteAccount: () -> Unit,
)

/**
 * Wires [GoogleAccountSection]'s callbacks to the app's
 * [app.clothescast.data.GoogleAccount]. Sign-in needs the Activity
 * context, which is why this lives in composition rather than the
 * ViewModel. [onSignedIn] runs after a successful sign-in.
 */
@Composable
internal fun rememberGoogleAccountActions(onSignedIn: () -> Unit): GoogleAccountActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, onSignedIn) {
        val account = (context.applicationContext as ClothesCastApplication).googleAccount
        GoogleAccountActions(
            signIn = {
                scope.launch {
                    val result = account.signIn(context)
                    if (result == GoogleAccountResult.Success) {
                        onSignedIn()
                    } else {
                        toast(context, result, R.string.settings_google_sign_in_failed)
                    }
                }
            },
            signOut = { scope.launch { account.signOut(context) } },
            deleteAccount = {
                scope.launch {
                    toast(context, account.deleteAccount(context), R.string.settings_google_delete_failed)
                }
            },
        )
    }
}

/** [failed] names the operation that failed, so a failed delete doesn't read as a failed sign-in. */
private fun toast(context: Context, result: GoogleAccountResult, @StringRes failed: Int) {
    val message = when (result) {
        GoogleAccountResult.Success, GoogleAccountResult.Canceled -> return
        GoogleAccountResult.NoAccount -> R.string.settings_google_no_account
        GoogleAccountResult.Failed -> failed
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
