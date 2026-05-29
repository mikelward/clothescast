package app.clothescast.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R

/**
 * Today-screen promo card nudging users who haven't set up Gemini toward the
 * higher-quality Gemini voices for their spoken ClothesCast. By default casts
 * speak through the device's built-in TTS; adding a (BYOK) Gemini API key in
 * Voice settings unlocks the natural Gemini voices. Sits right after the
 * "Preview your daily ClothesCast now" play promo.
 *
 * Visibility is decided upstream in [TodayViewModel] via
 * [TodayState.geminiTtsPromoCardVisible] — true iff the user hasn't dismissed
 * it AND no Gemini API key is configured yet (so the card never shows once the
 * user has Gemini set up). Dismissal (X tap *or* "Speech settings" tap) persists
 * via [SettingsRepository.setGeminiPromoCardDismissed] so the card never
 * re-surfaces, even if the user backs out of Voice settings without adding a
 * key.
 */
@Composable
internal fun GeminiTtsPromoCard(
    visible: Boolean,
    onSetUpVoices: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    GeminiTtsPromoCardContent(
        onSetUpVoices = onSetUpVoices,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
internal fun GeminiTtsPromoCardContent(
    onSetUpVoices: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.today_gemini_promo_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.today_gemini_promo_dismiss),
                    )
                }
            }
            Text(
                text = stringResource(R.string.today_gemini_promo_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSetUpVoices) {
                    Text(stringResource(R.string.today_gemini_promo_cta))
                }
            }
        }
    }
}
