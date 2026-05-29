package app.clothescast.core.domain.model

import java.time.LocalDate

/**
 * Per-model-first consensus average shared by the day-level rainfall and
 * sunshine blurbs.
 *
 * Each consulted model's [select]ed hours are summed independently (optionally
 * restricted to [dateFilter]), and the arithmetic mean of those per-model
 * totals is returned. Summing per-model first — rather than averaging the
 * per-hour values across models and then summing — keeps a model with only a
 * partial hourly series from silently dragging the consensus down: its missing
 * hours don't get counted as zero.
 *
 * [select] returns null for hours a model didn't report; those are dropped
 * (not treated as zero). A model that reported nothing in range contributes no
 * total. With fewer than two models contributing a total, returns null — a
 * one-model "consensus" isn't a consensus, and callers fall back rather than
 * surfacing a misleading single-source number.
 *
 * Treats `best_match` like any other model, matching [blendConsensusHourly]'s
 * posture and the rest of the Today-screen consensus blends.
 */
internal fun PerModelHourly.consensusPerModelAverage(
    dateFilter: LocalDate?,
    select: (PerModelHour) -> Double?,
): Double? {
    val perModelTotals = byModel.values.mapNotNull { entries ->
        val values = entries
            .filter { dateFilter == null || it.time.toLocalDate() == dateFilter }
            .mapNotNull(select)
        if (values.isEmpty()) null else values.sum()
    }
    if (perModelTotals.size < 2) return null
    return perModelTotals.average()
}
