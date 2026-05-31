package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.TtsEngine

/**
 * Cut-down "Speech setup" flow shown as a modal bottom sheet the first time the
 * user opts into spoken delivery — from the schedule page's Speak channel or
 * from Smart Home's Cast / audio toggles. A trimmed subset of
 * [VoiceContent]: pick the engine, and either drop in a Gemini API key or fall
 * back to on-device TTS (which always works without a key). Voice / style /
 * locale tuning stays on the full Voice settings page.
 *
 * The engine and key persist immediately through the provided setters; enabling
 * the calling channel is the caller's responsibility (it does so before showing
 * the sheet), so dismissing without finishing key entry still leaves a working
 * device-TTS path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechSetupSheet(
    selectedEngine: TtsEngine,
    geminiKeyConfigured: Boolean,
    sharedTtsAvailable: Boolean,
    onSetTtsEngine: (TtsEngine) -> Unit,
    onSetGeminiKey: (String) -> Unit,
    onClearGeminiKey: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    showSmartHomeSpeechNote: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SpeechSetupContent(
            selectedEngine = selectedEngine,
            geminiKeyConfigured = geminiKeyConfigured,
            sharedTtsAvailable = sharedTtsAvailable,
            onSetTtsEngine = onSetTtsEngine,
            onSetGeminiKey = onSetGeminiKey,
            onClearGeminiKey = onClearGeminiKey,
            onConfirm = onConfirm,
            showSmartHomeSpeechNote = showSmartHomeSpeechNote,
        )
    }
}

/**
 * The sheet's body, split out from [SpeechSetupSheet] so it can be rendered in
 * isolation for Compose `@Preview` / Roborazzi snapshots — a [ModalBottomSheet]
 * lives in its own window and doesn't capture cleanly through the host root.
 */
@Composable
internal fun SpeechSetupContent(
    selectedEngine: TtsEngine,
    geminiKeyConfigured: Boolean,
    sharedTtsAvailable: Boolean,
    onSetTtsEngine: (TtsEngine) -> Unit,
    onSetGeminiKey: (String) -> Unit,
    onClearGeminiKey: () -> Unit,
    onConfirm: () -> Unit,
    showSmartHomeSpeechNote: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.speech_setup_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            // Same one-liner as the Voice engine section in full Settings — the
            // two card-style entry points stay in lockstep with one string instead
            // of two so a copy update in one place can't drift away from the other.
            text = stringResource(R.string.settings_tts_engine_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TtsEngine.entries.forEach { engine ->
            RadioRow(
                label = stringResource(ttsEngineLabel(engine)),
                selected = engine == selectedEngine,
                onSelect = { onSetTtsEngine(engine) },
            )
        }
        when (selectedEngine) {
            TtsEngine.GEMINI -> {
                Text(
                    text = stringResource(R.string.settings_api_key_gemini_header),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KeyEntryFields(
                    configured = geminiKeyConfigured,
                    statusText = stringResource(
                        when {
                            geminiKeyConfigured -> R.string.settings_api_key_status_set
                            // Mirrors the same branch in VoiceContent: with the shared-key
                            // proxy reachable the key is an optional upgrade, not the
                            // gate to using Gemini at all.
                            sharedTtsAvailable -> R.string.settings_api_key_status_unset_shared
                            else -> R.string.settings_api_key_status_unset
                        },
                    ),
                    placeholder = stringResource(R.string.settings_api_key_placeholder),
                    saveLabel = stringResource(R.string.settings_api_key_save),
                    replaceLabel = stringResource(R.string.settings_api_key_replace),
                    clearLabel = stringResource(R.string.settings_api_key_clear),
                    onSave = onSetGeminiKey,
                    onClear = onClearGeminiKey,
                )
            }
            TtsEngine.DEVICE -> {
                Text(
                    text = stringResource(R.string.settings_tts_device_header),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Smart-home spoken audio (Cast / MQTT) is synthesized through
                // Gemini only, so the device voice produces no sound there.
                // Inform rather than disable: the engine setting is global (it
                // also drives phone playback, which the device voice handles
                // fine), and smart-home delivery still works image-/text-only
                // without a key — see CastInsightController's silent fallback.
                if (showSmartHomeSpeechNote) {
                    Text(
                        text = stringResource(R.string.speech_setup_smart_home_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.speech_setup_done)) }
    }
}
