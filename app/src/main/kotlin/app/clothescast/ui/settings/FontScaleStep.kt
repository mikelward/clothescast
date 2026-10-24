package app.clothescast.ui.settings

import app.clothescast.core.domain.model.DEFAULT_APP_FONT_SCALE
import com.mikelward.androidzoom.clampFontScale
import com.mikelward.androidzoom.fontScalePercent

/**
 * The text size one minus or plus tap away: [deltaPercent] whole points of the
 * percentage shown beside the slider.
 *
 * Steps from the percentage **shown**, not from [scale] itself: the slider and
 * the pinch are continuous, so a size can sit between whole percents (1.153,
 * shown as 115%), and adding 0.01 to that would land on a number the screen
 * then rounds the same way twice. Clamped to the offered range; a non-finite
 * size (a corrupt stored value) steps from the app's starting size, as the
 * clamp everywhere else falls back to it.
 */
internal fun stepFontScale(scale: Float, deltaPercent: Int): Float {
    val shown = fontScalePercent(clampFontScale(scale, DEFAULT_APP_FONT_SCALE))
    return clampFontScale((shown + deltaPercent) / 100f, DEFAULT_APP_FONT_SCALE)
}
