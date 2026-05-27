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
 * Today-screen promo card nudging the user at Clothes settings — the
 * per-temperature clothing rules are the core thing that makes outfits
 * "theirs", but the path to them isn't obvious from the Today screen.
 * Sits between the telemetry-notice banner and the celebration-themes
 * promo.
 *
 * Visibility is decided upstream in [TodayViewModel] via
 * [TodayState.clothesPromoCardVisible] — true iff the user hasn't
 * dismissed it AND their clothes rules still match
 * [ClothesRule.DEFAULTS]. Any edit / add / delete / threshold-nudge
 * auto-hides the card (the user found the thing the card was pointing
 * at, so the promo's done). Dismissal (X tap *or* "Clothes settings"
 * tap) persists via [SettingsRepository.setClothesPromoCardDismissed]
 * so the card never re-surfaces.
 */
@Composable
internal fun ClothesPromoCard(
    visible: Boolean,
    onOpenClothes: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    ClothesPromoCardContent(
        onOpenSettings = onOpenClothes,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
internal fun ClothesPromoCardContent(
    onOpenSettings: () -> Unit,
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
                    text = stringResource(R.string.today_clothes_promo_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.today_clothes_promo_dismiss),
                    )
                }
            }
            Text(
                text = stringResource(R.string.today_clothes_promo_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.today_clothes_promo_cta))
                }
            }
        }
    }
}
