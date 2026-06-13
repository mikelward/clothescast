package app.clothescast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.clothescast.ui.theme.ClothesCastTheme

// A standalone mock of an open menu overlay, so the corner radius and item
// text — which live in a Popup window the chart/screen snapshots can't see —
// get their own visible coverage. Uses the real [DropdownMenuItem] inside a
// surface styled like the M3 menu container, so what renders matches the live
// popup save for the popup plumbing itself.
@Composable
private fun MenuOverlayMock(
    shape: Shape,
    itemStyle: TextStyle,
    items: List<String>,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.width(220.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            items.forEach { label ->
                DropdownMenuItem(text = { Text(label, style = itemStyle) }, onClick = {})
            }
        }
    }
}

@Composable
private fun MenuStyleComparison(items: List<String>) {
    ClothesCastTheme(darkTheme = false, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            // Stacked, not side-by-side: the snapshot renders at the 360dp
            // device width, so two menus in a Row would clip.
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("Before", style = MaterialTheme.typography.labelMedium)
                // Material defaults: 4dp menu corner, labelLarge (14sp) items.
                MenuOverlayMock(
                    shape = RoundedCornerShape(4.dp),
                    itemStyle = MaterialTheme.typography.labelLarge,
                    items = items,
                )
                Text("After", style = MaterialTheme.typography.labelMedium)
                MenuOverlayMock(
                    shape = AppMenuShape,
                    itemStyle = MaterialTheme.typography.bodyLarge,
                    items = items,
                )
            }
        }
    }
}

@Preview(name = "Overflow menu · before/after", widthDp = 560)
@Composable
internal fun OverflowMenuStylePreview() {
    MenuStyleComparison(items = listOf("Report a bug", "About"))
}

@Preview(name = "Settings drop-down · before/after", widthDp = 560)
@Composable
internal fun SettingsMenuStylePreview() {
    MenuStyleComparison(items = listOf("Speech only", "Bands", "Always"))
}
