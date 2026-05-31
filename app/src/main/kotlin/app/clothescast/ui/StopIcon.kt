package app.clothescast.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The standard Material "stop" glyph — a filled, slightly rounded square.
 *
 * We only depend on `material-icons-core`, whose curated subset doesn't include
 * `Stop`, and pulling in `material-icons-extended` for a single glyph would
 * bloat the APK and method count. So we hand-roll it: same 24dp viewport and
 * `M6,6 h12 v12 H6 z` path the Material icon uses. The fill colour is a
 * placeholder — [androidx.compose.material3.Icon] tints it with the current
 * content colour, so it picks up the app bar / button foreground like any
 * built-in icon.
 */
val StopSquareIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "StopSquare",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 6f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(12f)
            horizontalLineTo(6f)
            close()
        }
    }.build()
}
