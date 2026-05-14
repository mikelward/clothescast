package app.clothescast.core.domain.model

import java.time.LocalDate

/**
 * Day-level sunshine total averaged across the consulted models — the
 * single number behind the Today screen's "Xh of sun today" blurb.
 *
 * Per-hour [PerModelHour.sunshineDurationSec] is summed independently for
 * each model that reported sunshine on [date], and the arithmetic mean of
 * those per-model daily totals is returned in fractional hours. Summing
 * per-model first (rather than averaging the per-hour values across models
 * and then summing) keeps a model with only a partial hourly series from
 * silently dragging the consensus down — its missing hours don't get
 * counted as zero sunshine.
 *
 * Treats `best_match` like any other model, matching [blendConsensusHourly]'s
 * posture. With fewer than two models reporting any sunshine on [date],
 * returns null — a one-model "consensus" isn't a consensus, and the UI
 * surfaces the absence as "no sunshine blurb today" rather than a misleading
 * single-source number.
 */
fun PerModelHourly.consensusSunshineHoursFor(date: LocalDate): Double? {
    val perModelTotalsSec = byModel.values.mapNotNull { entries ->
        val daySeconds = entries
            .filter { it.time.toLocalDate() == date }
            .mapNotNull { it.sunshineDurationSec }
        if (daySeconds.isEmpty()) null else daySeconds.sum()
    }
    if (perModelTotalsSec.size < 2) return null
    return perModelTotalsSec.average() / 3600.0
}

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
 * Same per-model-first averaging contract as [consensusSunshineHoursFor]:
 * each consulted model's hours are summed independently, the arithmetic mean
 * of those per-model totals is returned in fractional hours, and < 2 models
 * with any sunshine return null.
 */
fun PerModelHourly.consensusSunshineHours(): Double? {
    val perModelTotalsSec = byModel.values.mapNotNull { entries ->
        val seconds = entries.mapNotNull { it.sunshineDurationSec }
        if (seconds.isEmpty()) null else seconds.sum()
    }
    if (perModelTotalsSec.size < 2) return null
    return perModelTotalsSec.average() / 3600.0
}
