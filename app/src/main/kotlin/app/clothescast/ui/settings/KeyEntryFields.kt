package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Status text + password-style input + save/clear buttons for one BYOK API key.
 * Used inline by the Voice settings screen (once per cloud engine) and by the
 * onboarding screen (Gemini only).
 *
 * When a key is already configured the input field is hidden behind a Replace
 * button: most visits to this screen aren't to change the key, so an always-
 * visible empty input next to the Clear button is confusing. Tapping Replace
 * reveals the field; a successful save collapses it again, and tapping Clear
 * (which un-configures the key) auto-expands the input so the user can type a
 * fresh one without an extra tap.
 */
@Composable
internal fun KeyEntryFields(
    configured: Boolean,
    statusText: String,
    placeholder: String,
    saveLabel: String,
    replaceLabel: String,
    clearLabel: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    // Match the muted color of the surrounding body paragraphs (engine
    // description, per-engine header) so the status line doesn't read
    // heavier than them — by default LinkifiedText uses LocalContentColor
    // (full onSurface), which renders darker than the body text above.
    LinkifiedText(
        text = statusText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    var input by remember { mutableStateOf("") }
    // Default the input to hidden when a key is already saved; re-key on
    // `configured` so a Clear (configured: true → false) re-expands and a
    // first-time Save (false → true) collapses without extra wiring.
    var showInput by remember(configured) { mutableStateOf(!configured) }

    if (showInput) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            visualTransformation = if (input.isEmpty()) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = {
                    // Trim before persisting — clipboard / password-manager pastes
                    // routinely include a trailing newline that the cloud TTS
                    // providers reject as an invalid key.
                    val trimmed = input.trim()
                    if (trimmed.isNotEmpty()) {
                        onSave(trimmed)
                        input = ""
                        // Re-key on `configured` handles the first-save case;
                        // for an already-configured replace, collapse manually.
                        if (configured) showInput = false
                    }
                },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(saveLabel) }

            if (configured) {
                TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text(clearLabel)
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { showInput = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(replaceLabel) }
            TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Text(clearLabel)
            }
        }
    }
}
