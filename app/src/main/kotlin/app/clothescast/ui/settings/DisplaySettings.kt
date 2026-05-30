package app.clothescast.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.HomeSection
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.ui.EdgeFadeOverlay
import sh.calvin.reorderable.ReorderableColumn

@Composable
internal fun DisplayContent(
    themeMode: ThemeMode,
    colorPalette: ColorPalette,
    homeSectionOrder: List<HomeSection>,
    padding: PaddingValues,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetColorPalette: (ColorPalette) -> Unit,
    onReorderHomeSection: (from: Int, to: Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    EdgeFadeOverlay(
        scrollState = scrollState,
        modifier = Modifier.padding(padding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = stringResource(R.string.settings_display_theme_title)) {
                ThemeMode.entries.forEach { mode ->
                    RadioRow(
                        label = stringResource(themeModeLabel(mode)),
                        selected = mode == themeMode,
                        onSelect = { onSetThemeMode(mode) },
                    )
                }
            }
            SectionCard(title = stringResource(R.string.settings_display_colors_title)) {
                ColorPalette.entries.forEach { palette ->
                    RadioRow(
                        label = stringResource(colorPaletteLabel(palette)),
                        selected = palette == colorPalette,
                        onSelect = { onSetColorPalette(palette) },
                    )
                }
                Text(
                    text = stringResource(colorPaletteDescription(colorPalette)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionCard(title = stringResource(R.string.settings_display_home_layout_title)) {
                Text(
                    text = stringResource(R.string.settings_display_home_layout_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HomeLayoutReorderList(
                    order = homeSectionOrder,
                    onReorder = onReorderHomeSection,
                )
            }
        }
    }
}

/**
 * Drag-to-reorder list of the home-screen sections. A dedicated grab handle
 * starts the drag (rather than long-pressing the row) so the labels stay
 * tappable for future per-section controls. [order] is the persisted order;
 * a local copy mirrors the in-flight drag and re-seeds whenever the persisted
 * order changes, and [onReorder] commits the settled move to the repository.
 */
@Composable
private fun HomeLayoutReorderList(
    order: List<HomeSection>,
    onReorder: (from: Int, to: Int) -> Unit,
) {
    var items by remember(order) { mutableStateOf(order) }
    ReorderableColumn(
        list = items,
        onSettle = { fromIndex, toIndex ->
            items = items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            onReorder(fromIndex, toIndex)
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { _, section, isDragging ->
        key(section) {
            ReorderableItem {
                val label = stringResource(homeSectionLabel(section))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isDragging) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color.Transparent
                            },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = stringResource(
                                R.string.settings_display_home_layout_drag,
                                label,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_display_theme_system
    ThemeMode.LIGHT -> R.string.settings_display_theme_light
    ThemeMode.DARK -> R.string.settings_display_theme_dark
}

private fun colorPaletteLabel(palette: ColorPalette): Int = when (palette) {
    ColorPalette.RAINBOW -> R.string.settings_display_palette_rainbow
    ColorPalette.ACCESSIBLE -> R.string.settings_display_palette_accessible
    ColorPalette.HIGHLIGHTER -> R.string.settings_display_palette_highlighter
}

private fun colorPaletteDescription(palette: ColorPalette): Int = when (palette) {
    ColorPalette.RAINBOW -> R.string.settings_display_palette_rainbow_description
    ColorPalette.ACCESSIBLE -> R.string.settings_display_palette_accessible_description
    ColorPalette.HIGHLIGHTER -> R.string.settings_display_palette_highlighter_description
}

private fun homeSectionLabel(section: HomeSection): Int = when (section) {
    HomeSection.CONDITIONS -> R.string.settings_display_home_section_conditions
    HomeSection.OUTFIT -> R.string.settings_display_home_section_outfit
    HomeSection.INSIGHT -> R.string.settings_display_home_section_insight
    HomeSection.CONFIDENCE -> R.string.settings_display_home_section_confidence
    // TODO(home-sections): when individual charts become reorderable, this
    //  single "Charts" row expands into the chart deck's own sub-list.
    HomeSection.CHARTS -> R.string.settings_display_home_section_charts
}
