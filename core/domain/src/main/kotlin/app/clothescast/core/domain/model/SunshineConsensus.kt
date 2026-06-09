package app.clothescast.core.domain.model

import java.time.LocalDate

/**
 * Day-level sunshine total averaged across the consulted models — the
 * single number behind the Today screen's "Xh of sun today" blurb.
 *
 * At each hour of [date], [PerModelHour.sunshineDurationSec] is averaged
 * over the models that reported it, and the per-hour means are summed into a
 * daily total in fractional hours. Averaging hour-wise over the reporting
 * models (rather than summing each model first and averaging the per-model
 * totals) keeps a model with only a partial hourly series from silently
 * dragging the consensus down — its missing hours don't get counted as zero
 * sunshine. See [consensusPerModelAverage].
 *
 * Treats `best_match` like any other model, matching [blendConsensusHourly]'s
 * posture. With fewer than two models reporting any sunshine on [date],
 * returns null — a one-model "consensus" isn't a consensus, and the UI
 * surfaces the absence as "no sunshine blurb today" rather than a misleading
 * single-source number.
 */
fun PerModelHourly.consensusSunshineHoursFor(date: LocalDate): Double? =
    consensusPerModelAverage(dateFilter = date) { it.sunshineDurationSec }
        ?.div(3600.0)

/**
 * Variant that totals every entry in the [PerModelHourly] regardless of date.
 *
 * Used by callers whose [PerModelHourly] is already sliced to the window they
 * care about — notably the nightly insight, whose tonight slice spans
 * `[tonightStart, next morning)` and so straddles midnight. The
 * [consensusSunshineHoursFor] date filter would drop the post-midnight portion
 * (early-morning sun before the morning alarm) on the night view; this variant
 * sums the full slice instead.
 *
 * Same hour-wise averaging contract as [consensusSunshineHoursFor]: each
 * hour's mean over the reporting models is summed into a window total in
 * fractional hours, and < 2 models with any sunshine return null.
 */
fun PerModelHourly.consensusSunshineHours(): Double? =
    consensusPerModelAverage(dateFilter = null) { it.sunshineDurationSec }
        ?.div(3600.0)
