package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.ui.EdgeFadeOverlay

private const val PRIVACY_POLICY_URL =
    "https://github.com/mikelward/clothescast/blob/main/PRIVACY.md"

@Composable
internal fun PrivacyContent(
    telemetryEnabled: Boolean,
    padding: PaddingValues,
    onSetTelemetryEnabled: (Boolean) -> Unit,
) {
    val scrollState = rememberScrollState()
    EdgeFadeOverlay(
        scrollState = scrollState,
        modifier = Modifier.padding(padding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TelemetryCard(
                enabled = telemetryEnabled,
                onSetEnabled = onSetTelemetryEnabled,
            )
        }
    }
}

@Composable
private fun TelemetryCard(
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.settings_privacy_telemetry_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_privacy_telemetry_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = onSetEnabled)
        }
        Text(
            text = stringResource(R.string.settings_privacy_telemetry_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Spell out the hard limits inline so a user looking at the toggle
        // doesn't have to leave the app to know what is and isn't sent. The
        // long-form policy still lives in PRIVACY.md, linked below.
        Text(
            text = stringResource(R.string.settings_privacy_telemetry_limits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { openUrl(context, PRIVACY_POLICY_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_privacy_open_policy)) }
    }
}
