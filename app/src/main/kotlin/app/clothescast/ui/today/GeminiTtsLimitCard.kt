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
 * Today-screen card surfaced when a cast hit the shared free Gemini TTS daily
 * allowance. The cast still plays via the device's built-in voice — this just
 * tells the user the high-quality Gemini voices are spent for the day and
 * points them at Speech settings, where they can add their own (BYOK) Gemini
 * key for unlimited casts.
 *
 * Visibility is decided upstream in [TodayViewModel] via
 * [TodayState.geminiTtsLimitCardVisible] — true iff a synth hit the shared-key
 * daily quota and the user hasn't dismissed that exceedance. Unlike the
 * promo card, the "Speech settings" CTA doesn't dismiss: the card retires on
 * its own once the next synth succeeds (the allowance reset, or the user added
 * a key). The X persists the dismissal so the card hides until a strictly-newer
 * exceedance resurfaces. Stands in for [GeminiTtsPromoCard], which is held back
 * while this shows.
 */
@Composable
internal fun GeminiTtsLimitCard(
    visible: Boolean,
    onOpenVoice: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    GeminiTtsLimitCardContent(
        onOpenVoice = onOpenVoice,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
internal fun GeminiTtsLimitCardContent(
    onOpenVoice: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
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
                    text = stringResource(R.string.today_gemini_limit_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.today_gemini_limit_dismiss),
                    )
                }
            }
            Text(
                text = stringResource(R.string.today_gemini_limit_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenVoice) {
                    Text(stringResource(R.string.today_gemini_limit_cta))
                }
            }
        }
    }
}

/**
 * Persistent Today-screen card shown when the stored BYOK Gemini key could not
 * be decrypted locally. It intentionally has no dismiss action: the card
 * clears only when the user re-enters or explicitly clears the key, matching
 * the planner's privacy boundary.
 */
@Composable
internal fun GeminiKeyNeedsReentryCard(
    visible: Boolean,
    onOpenVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.today_gemini_key_invalid_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.today_gemini_key_invalid_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenVoice) {
                    Text(stringResource(R.string.today_gemini_key_invalid_cta))
                }
            }
        }
    }
}
