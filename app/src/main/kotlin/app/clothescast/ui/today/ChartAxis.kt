package app.clothescast.ui.today

import kotlin.math.ceil
import kotlin.math.floor

// Y-axis layout for the Today screen charts. Vico's default item placer
// stretches the bounds to land on round-number ticks: on a day where the
// temperature peaks at 10.3°C it adds a phantom "12, 14" label pair above
// the data, and on a 26 km/h wind day it divides the range into "0, 13, 26"
// — the only step that fits two ticks in 26 units is the ugly 13. Pre-align
// the bounds and pin the step via `VerticalAxis.ItemPlacer.step()` so the
// top tick sits exactly at the data ceiling instead.
internal data class YAxisBounds(val min: Double, val max: Double, val step: Double)

// Bucketed (rather than the standard log/fraction nice-numbers algorithm)
// so each metric reads at a predictable step regardless of the day's data:
// a 5-12°C temperature swing always lands on step 2, a 15-30 km/h wind day
// always on step 5. Targets 4-7 visible ticks across the typical range.
internal fun niceStep(span: Double): Double = when {
    span <= 4.0 -> 1.0
    span <= 12.0 -> 2.0
    span <= 30.0 -> 5.0
    span <= 60.0 -> 10.0
    span <= 150.0 -> 25.0
    span <= 300.0 -> 50.0
    span <= 600.0 -> 100.0
    span <= 1500.0 -> 250.0
    else -> 500.0
}

internal fun alignToStep(rawMin: Double, rawMax: Double, step: Double): YAxisBounds =
    YAxisBounds(
        min = floor(rawMin / step) * step,
        max = ceil(rawMax / step) * step,
        step = step,
    )
