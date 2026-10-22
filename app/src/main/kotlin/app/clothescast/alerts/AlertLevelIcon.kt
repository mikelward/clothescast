package app.clothescast.alerts

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * An alert level's icon in its tint, for Compose surfaces. The shape alone
 * tells the levels apart, so it reads in grayscale; the tint follows the
 * surface's lightness as the conditions strip does. [decorative] drops the
 * spoken description where a label beside it already says the level.
 */
@Composable
internal fun AlertLevelIcon(
    icon: AlertIcon,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    decorative: Boolean = false,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Icon(
        painter = painterResource(icon.drawable),
        contentDescription = if (decorative) null else stringResource(icon.description),
        tint = Color(icon.argb(dark)),
        modifier = modifier.size(size),
    )
}
