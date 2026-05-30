package app.clothescast.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R

/**
 * Today-screen promo card inviting the user to preview their scheduled
 * ClothesCast on demand rather than waiting for the morning / evening alarm. It
 * carries its own Play button (the same action as the top app bar's), so the
 * user can hear the cast without hunting for the toolbar control, plus a
 * dismiss X.
 *
 * Visibility is decided upstream in [TodayViewModel] via
 * [TodayState.playPromoCardVisible] — true iff the user hasn't dismissed it AND
 * at least one cast slot is enabled (so "your ClothesCast" is meaningful; the
 * morning slot is on by default, so this normally holds). It's retired (via
 * [SettingsRepository.setPlayCardDismissed]) by the dismiss X, by tapping its
 * Play button, and by tapping the top-bar Play button it mirrors — once the
 * user has played a cast, the card has served its purpose.
 */
@Composable
internal fun PlayPromoCard(
    visible: Boolean,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!visible) return
    PlayPromoCardContent(onPlay = onPlay, onDismiss = onDismiss, enabled = enabled, modifier = modifier)
}

@Composable
internal fun PlayPromoCardContent(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit = {},
    enabled: Boolean = true,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.today_play_promo_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.today_play_promo_dismiss),
                    )
                }
            }
            Text(
                text = stringResource(R.string.today_play_promo_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
            Button(onClick = onPlay, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.today_play),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
