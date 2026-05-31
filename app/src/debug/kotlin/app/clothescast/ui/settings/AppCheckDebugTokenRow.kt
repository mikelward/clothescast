package app.clothescast.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.clothescast.BuildConfig

/**
 * Surfaces the per-developer Firebase App Check debug token baked into
 * this build's `BuildConfig.APP_CHECK_DEBUG_TOKEN`, with a copy button
 * so the developer / tester can paste it into Firebase Console → App
 * Check → Manage debug tokens without grepping Logcat. The token is
 * stable across `Clear data` and reinstalls (it lives in
 * `local.properties`, not on-device state), so register once per
 * developer and every fresh install on every device built off the
 * same laptop reuses it.
 *
 * Hidden when the token is blank — that's the CI-build case (see
 * `ensureAppCheckDebugToken` in `app/build.gradle.kts`), where the
 * Firebase Debug provider falls back to its own per-install random
 * UUID logged to Logcat as before. We don't show that UUID here
 * because it isn't exposed by the public Firebase API.
 */
@Composable
internal fun AppCheckDebugTokenRow() {
    val token = BuildConfig.APP_CHECK_DEBUG_TOKEN
    if (token.isBlank()) return

    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // weight(1f) lets the text column share the row width with the Copy
        // button — without it, a 36-char UUID would measure as one very long
        // line at intrinsic width and either clip or push the Copy button
        // off-screen on a phone-width Settings panel. The bodySmall token
        // line soft-wraps at the hyphens (Compose default) so the UUID
        // stays readable across one or two lines.
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            Text(
                text = "App Check debug token",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = token,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TextButton(onClick = { copyToClipboard(context, token) }) {
            Text(text = "Copy")
        }
    }
}

private fun copyToClipboard(context: Context, token: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("App Check debug token", token))
    // Android 13+ shows its own clipboard confirmation overlay; suppressing
    // the Toast there avoids a double notification. On older devices the
    // Toast is the only feedback the copy worked.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}
