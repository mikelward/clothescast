package app.clothescast.core.domain.model

import java.time.LocalDate

/**
 * Day-level rainfall total averaged across the consulted models — the
 * single number behind the Today screen's "X mm of rain today" blurb.
 * Sibling of [consensusSunshineHoursFor]; same hour-wise averaging
 * contract.
 *
 * At each hour of [date], [PerModelHour.precipitationMm] is averaged over
 * the models that reported it, and the per-hour means are summed into a
 * daily total in millimetres. Averaging hour-wise over the reporting models
 * (rather than summing each model first and averaging the per-model totals)
 * keeps a model with only a partial hourly series from silently dragging
 * the consensus down — its missing hours don't get counted as zero
 * rainfall. See [consensusPerModelAverage].
 *
 * Treats `best_match` like any other model, matching [blendConsensusHourly]'s
 * posture and the rest of the Today-screen consensus blends. With fewer than
 * two models reporting any precipitation on [date], returns null — a
 * one-model "consensus" isn't a consensus, and the caller falls back to the
 * main-line total instead of surfacing a misleading single-source number.
 */
fun PerModelHourly.consensusRainfallMmFor(date: LocalDate): Double? =
    consensusPerModelAverage(dateFilter = date) { it.precipitationMm }

/**
 * Variant that totals every entry in the [PerModelHourly] regardless of date.
 *
 * Used by callers whose [PerModelHourly] is already sliced to the window they
 * care about — notably the tonight insight, whose slice spans
 * `[tonightStart, next morning)` and so straddles midnight. The
 * [consensusRainfallMmFor] date filter would drop the post-midnight portion
 * (early-morning rain before the morning alarm) on the night view; this
 * variant sums the full slice instead.
 *
 * Same hour-wise averaging contract as [consensusRainfallMmFor]: each hour's
 * mean over the reporting models is summed into a window total in
 * millimetres, and < 2 models with any precipitation return null.
 */
fun PerModelHourly.consensusRainfallMm(): Double? =
    consensusPerModelAverage(dateFilter = null) { it.precipitationMm }
