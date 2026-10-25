package app.clothescast.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.HomeSection
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.ui.EdgeFadeOverlay
import app.clothescast.ui.RemoveIcon
import com.mikelward.androidzoom.MAX_FONT_SCALE
import com.mikelward.androidzoom.MIN_FONT_SCALE
import com.mikelward.androidzoom.compose.LocalFontSizeState
import com.mikelward.androidzoom.compose.StableInputDensity
import com.mikelward.androidzoom.fontScalePercent
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
    // Keeps the text-size minus, plus and slider under the finger as each change resizes the page.
    val textSizeHold = rememberScrollHold(scrollState)
    EdgeFadeOverlay(
        scrollState = scrollState,
        modifier = Modifier.padding(padding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .holdSlack(textSizeHold)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextSizeCard(textSizeHold)
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

internal const val TEXT_SIZE_SMALLER_TAG = "text_size_smaller"
internal const val TEXT_SIZE_LARGER_TAG = "text_size_larger"

/**
 * The text-size slider, the minus and plus buttons at its ends (one percent a
 * tap), and the pinch switch, moving the shared state the whole app is sized
 * by (mikelward/androidzoom), so this screen is its own preview while the
 * slider is dragged. Hidden where no state is provided --
 * previews and tests that never set one.
 */
@Composable
private fun TextSizeCard(hold: ScrollHold? = null) {
    val state = LocalFontSizeState.current ?: return
    val percent = fontScalePercent(state.scale)
    val shown = stringResource(R.string.settings_display_text_size_value, percent)
    // The percentage sits on the title's line rather than beside the slider,
    // so the minus and plus buttons can take the slider's two ends without
    // squeezing its track (at 160% the label alone is wider than a button).
    SectionCard(
        title = stringResource(R.string.settings_display_text_size_title),
        titleTrailing = {
            Text(text = shown, style = MaterialTheme.typography.titleMedium)
        },
    ) {
        // Whether the slider holds a drag, so leaving the screen mid-drag still
        // stores the size on screen: disposal never calls
        // `onValueChangeFinished`, and the state outlives this screen, so it
        // would otherwise stay mid-gesture and hold back every later stored
        // value (as in snoozemo). The release reads [FontSizeState.scale]
        // directly, which `preview` has already set, so it stores exactly where
        // the finger let go.
        var dragging by remember { mutableStateOf(false) }
        DisposableEffect(state) {
            onDispose { if (dragging) state.commit(state.scale) }
        }
        // A tap is a whole change, shown and stored at once: preview then
        // commit, the path a slider release takes (commit alone stores nothing
        // that no preview has moved).
        val step = { delta: Int ->
            hold?.hold()
            val stepped = stepFontScale(state.scale, delta)
            state.preview(stepped)
            state.commit(stepped)
        }
        // Held still as the size changes ([ScrollHold]), so the next tap or the rest of a drag
        // lands where the last one did, however much the card's title above grows or shrinks.
        Row(modifier = Modifier.holding(hold), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { step(-1) },
                enabled = percent > fontScalePercent(MIN_FONT_SCALE),
                modifier = Modifier.testTag(TEXT_SIZE_SMALLER_TAG),
            ) {
                Icon(
                    imageVector = RemoveIcon,
                    contentDescription = stringResource(R.string.settings_display_text_size_smaller),
                )
            }
            // Hosted at the unscaled density: the drag changes the scaled one
            // on every frame, which would otherwise restart its own gesture.
            Box(modifier = Modifier.weight(1f)) {
                StableInputDensity {
                    Slider(
                        value = state.scale,
                        onValueChange = {
                            dragging = true
                            hold?.hold()
                            state.preview(it)
                        },
                        onValueChangeFinished = {
                            dragging = false
                            state.commit(state.scale)
                        },
                        valueRange = MIN_FONT_SCALE..MAX_FONT_SCALE,
                        // Says the percentage the card shows; a slider's own
                        // description is its raw value.
                        modifier = Modifier.semantics { stateDescription = shown },
                    )
                }
            }
            IconButton(
                onClick = { step(+1) },
                enabled = percent < fontScalePercent(MAX_FONT_SCALE),
                modifier = Modifier.testTag(TEXT_SIZE_LARGER_TAG),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.settings_display_text_size_larger),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.pinchEnabled,
                    role = Role.Switch,
                    onValueChange = state::choosePinch,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_display_text_size_pinch),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.pinchEnabled, onCheckedChange = null)
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
