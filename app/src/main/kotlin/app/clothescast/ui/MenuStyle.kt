package app.clothescast.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Shared corner shape for the app's drop-down / overflow menu overlays — a
 * softer 12dp corner rather than the Material default 4dp menu corner, for a
 * rounder, more modern Material look. Pair it with bodyLarge item text (vs the
 * default 14sp labelLarge) so the options match the rest of the app's body
 * text instead of the tighter button label role.
 */
internal val AppMenuShape = RoundedCornerShape(12.dp)
