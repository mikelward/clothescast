package app.clothescast.ui.today

import androidx.compose.animation.core.AnimationSpec

// Shared `CartesianChartHost` animation defaults for every forecast chart in
// the app. The chart appears at its final shape the moment its card composes
// ([CHART_ANIMATE_IN] = false) and every later data change — per-model spread
// toggle, °C/°F, feels-like, a live refresh — applies instantly
// ([CHART_ANIMATION_SPEC] = null).
//
// Why: the entry grow-in ran on every page swipe for no gain on a static
// glanceable card. The diff animation grew newly-added series up from the
// layer baseline rather than fanning them out from the combined line, so the
// spread reveal read as a grow-in, not an uncertainty cue — and the toggles
// it fired on are deliberate user actions (or rare background refreshes)
// where instant is the right feel.
internal const val CHART_ANIMATE_IN = false
internal val CHART_ANIMATION_SPEC: AnimationSpec<Float>? = null
