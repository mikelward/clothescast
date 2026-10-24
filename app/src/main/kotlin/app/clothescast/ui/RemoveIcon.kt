package app.clothescast.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The standard Material "remove" glyph (a minus sign), the partner of
 * `Icons.Filled.Add`.
 *
 * Hand-rolled for the same reason as [StopSquareIcon]: `material-icons-core`
 * leaves it out, and `material-icons-extended` is too big for one glyph. Same
 * 24dp viewport and `M19,13 H5 v-2 h14 v2 z` path as the Material icon; the
 * fill is tinted by [androidx.compose.material3.Icon] like any built-in one.
 */
val RemoveIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Remove",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 13f)
            horizontalLineTo(5f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(14f)
            verticalLineToRelative(2f)
            close()
        }
    }.build()
}
