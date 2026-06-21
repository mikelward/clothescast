package app.clothescast.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Shared scaffolding for the insight header cards on the Today pager — the
 * per-period [InsightCard] (pages 0 / 1) and the week-ahead headline card on
 * [SevenDayPage] (page 2). Owns the card geometry both shapes must keep in
 * lockstep so swiping between pages doesn't jitter: a 20.dp-padded [Card], a
 * 12.dp inter-row gap, and a three-zone header row with a fixed 28.dp slot on
 * each edge — so the centered label holds its horizontal position whether or
 * not a chevron is currently shown — framing a `weight(1f)` centered region.
 *
 * The edge slots and the centered region are caller-supplied so each card fills
 * in its own chevrons ([leftSlot] / [rightSlot], typically an
 * [InsightCardChevron] or nothing) and label ([centerContent], typically an
 * [InsightCardCenterLabel]); [body] is the content below the header — the prose
 * headline. Keeping the scaffolding here means a retune of the chevron slot
 * size, the inter-row spacing, or the header layout lands on both cards at once
 * instead of silently drifting between two hand-rolled copies.
 */
@Composable
internal fun InsightCardShell(
    modifier: Modifier = Modifier,
    leftSlot: @Composable () -> Unit = {},
    rightSlot: @Composable () -> Unit = {},
    centerContent: @Composable RowScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(28.dp)) { leftSlot() }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = centerContent,
                )
                Box(modifier = Modifier.size(28.dp)) { rightSlot() }
            }
            body()
        }
    }
}

/**
 * A header chevron sized to the shell's 28.dp edge slot — the back / forward
 * affordances on both insight cards. [imageVector] is expected to be an
 * `Icons.AutoMirrored` arrow so it flips in RTL automatically.
 */
@Composable
internal fun InsightCardChevron(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(imageVector = imageVector, contentDescription = contentDescription)
    }
}

/**
 * The centered header label both cards share: [label] in `labelLarge`, plus an
 * optional " · <city>" suffix that navigates to Location settings when
 * [onNavigateToLocation] is wired (inert in previews / tests). [locationLabel]
 * is the resolved city name, or null to omit the suffix entirely.
 * [labelModifier] lets the per-period card attach its hidden
 * long-press-to-Developer-settings gesture to the label without the week-ahead
 * card inheriting it.
 */
@Composable
internal fun InsightCardCenterLabel(
    label: String,
    locationLabel: String?,
    onNavigateToLocation: (() -> Unit)?,
    labelModifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = labelModifier,
    )
    if (locationLabel != null) {
        Text(
            text = " · ",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = locationLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = if (onNavigateToLocation != null) {
                Modifier.clickable { onNavigateToLocation() }
            } else {
                Modifier
            },
        )
    }
}
