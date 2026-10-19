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
 * Today-screen promo card pointing everyone at the Google forecaster. The CTA
 * opens Forecasters settings, where turning Google on starts the Google
 * sign-in when there's no key of the user's own.
 *
 * Visibility is decided upstream in [TodayViewModel] via
 * [TodayState.googleForecastPromoCardVisible]: shown until the user dismisses
 * it or follows the CTA, both persisted via
 * [app.clothescast.data.SettingsRepository.setGoogleForecastPromoCardDismissed].
 */
@Composable
internal fun GoogleForecastPromoCard(
    visible: Boolean,
    onOpenForecasters: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    GoogleForecastPromoCardContent(
        onOpenForecasters = onOpenForecasters,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
internal fun GoogleForecastPromoCardContent(
    onOpenForecasters: () -> Unit,
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
                // Weighted so a long translated title wraps instead of
                // pushing the close button off the card.
                Text(
                    text = stringResource(R.string.today_google_forecast_promo_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.today_google_forecast_promo_dismiss),
                    )
                }
            }
            Text(
                text = stringResource(R.string.today_google_forecast_promo_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenForecasters) {
                    Text(stringResource(R.string.today_google_forecast_promo_cta))
                }
            }
        }
    }
}
