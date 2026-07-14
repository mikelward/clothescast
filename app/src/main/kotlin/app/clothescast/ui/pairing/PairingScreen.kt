package app.clothescast.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.R
import kotlinx.coroutines.delay

/**
 * Phone-pairing screen shown when the user wants to transfer their Gemini API key from phone to TV
 * by scanning a QR code.
 *
 * States:
 *  - **Starting**: server bind in flight (first frames / after retry); plain progress.
 *  - **Waiting**: shows QR code + "scan with your phone" instructions.
 *  - **Received**: shows a brief success message, then calls [onSuccess] after a short delay.
 *  - **Timeout**: the 5-minute window expired; offers retry or cancel.
 *  - **Error**: couldn't start the server (no Wi-Fi?); offers retry or cancel.
 */
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                PairingState.Starting -> CircularProgressIndicator()
                is PairingState.Waiting -> WaitingContent(
                    state = s,
                    onCancel = onCancel,
                )
                PairingState.Received -> {
                    ReceivedContent()
                    LaunchedEffect(Unit) {
                        delay(1_500)
                        onSuccess()
                    }
                }
                PairingState.Timeout -> TimeoutContent(
                    onRetry = viewModel::retry,
                    onCancel = onCancel,
                )
                PairingState.Error -> ErrorContent(
                    onRetry = viewModel::retry,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun WaitingContent(
    state: PairingState.Waiting,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.pairing_instructions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Image(
            bitmap = state.qrBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.pairing_qr_description, state.url),
            modifier = Modifier.size(280.dp),
        )
        Text(
            text = state.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = onCancel,
            modifier = Modifier.focusRequester(focusRequester),
        ) {
            Text(stringResource(R.string.pairing_cancel))
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun ReceivedContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.pairing_received),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TimeoutContent(
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.pairing_timeout_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.pairing_timeout_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.focusRequester(focusRequester),
        ) { Text(stringResource(R.string.pairing_retry)) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.pairing_cancel)) }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun ErrorContent(
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.pairing_error_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.pairing_error_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.focusRequester(focusRequester),
        ) { Text(stringResource(R.string.pairing_retry)) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.pairing_cancel)) }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
