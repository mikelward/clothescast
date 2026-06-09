package app.clothescast.core.domain.model

import java.time.LocalDate

/**
 * Hour-wise consensus average shared by the day-level rainfall and sunshine
 * blurbs.
 *
 * At each hour, the [select]ed values from the models that reported that hour
 * are averaged; the per-hour means are then summed (optionally restricted to
 * [dateFilter]). Averaging per-hour over the models that actually reported —
 * rather than summing each model first and averaging the per-model totals —
 * keeps a model with only a partial hourly series from silently dragging the
 * consensus down: a per-model-totals mean treats the partial model's sum as a
 * full-window total, which is arithmetically identical to counting its
 * missing hours as zero. (That was this function's original implementation,
 * while its doc already claimed the protection — the doc was the intent.)
 *
 * [select] returns null for hours a model didn't report; those drop out of
 * that hour's mean (not treated as zero). A model that reported nothing in
 * range contributes nowhere. With fewer than two models contributing
 * anywhere in the window, returns null — a one-model "consensus" isn't a
 * consensus, and callers fall back rather than surfacing a misleading
 * single-source number.
 *
 * Treats `best_match` like any other model, matching [blendConsensusHourly]'s
 * posture and the rest of the Today-screen consensus blends.
 */
internal fun PerModelHourly.consensusPerModelAverage(
    dateFilter: LocalDate?,
    select: (PerModelHour) -> Double?,
): Double? {
    // Per model: sum values per wall-clock timestamp first. Normally that's
    // a no-op (one entry per hour), but on the DST fall-back day Open-Meteo's
    // local-time array repeats an hour and both physical hours land on the
    // same LocalDateTime — summing them keeps the model's 25-hour physical
    // total intact and stops a model with two entries from double-voting
    // that hour's cross-model mean against models with one.
    val perModelByHour = byModel.values.map { entries ->
        entries
            .filter { dateFilter == null || it.time.toLocalDate() == dateFilter }
            .mapNotNull { entry -> select(entry)?.let { entry.time to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.sum() }
    }.filter { it.isNotEmpty() }
    if (perModelByHour.size < 2) return null
    return perModelByHour
        .flatMap { it.entries }
        .groupBy({ it.key }, { it.value })
        .values
        .sumOf { it.average() }
}
