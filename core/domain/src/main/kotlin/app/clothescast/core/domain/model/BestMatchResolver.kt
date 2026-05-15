package app.clothescast.core.domain.model

import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Identifies which consulted model Open-Meteo's `best_match` overlay resolved
 * to for the supplied [selector] metric, returning that model's id when one
 * (and only one) consulted model carries an identical series, otherwise null.
 *
 * Open-Meteo's API doesn't tell us which underlying model `best_match`
 * picked — the response echoes back `"best_match"` as the requested model
 * name and treats the routing as an internal detail of `MultiDomainsReader`.
 * Empirically the picker resolves to a single regional model (UKMO over the
 * British Isles, JMA over Japan, etc.) and serves that model's hourly series
 * verbatim through the `best_match` channel, so we can recover the source
 * by value comparison against each consulted model on the same metric.
 *
 * The check is per-metric, not global: best_match's apparent-temp series
 * might match UKMO's while its precip series matches ICON's, because
 * Open-Meteo silently omits some per-model variables (UKMO doesn't expose
 * `precipitation_probability_ukmo_seamless`, so its precip slot must be
 * filled from a different upstream model). Each chart should call this with
 * the metric selector for the field it actually plots, so the dedup decision
 * matches what's being rendered.
 *
 * Tolerance ([MATCH_EPSILON]) is well below the API's 1-decimal precision —
 * a 0.05 epsilon catches exact matches (the typical case when best_match
 * mirrors an underlying model verbatim) without falsely matching values
 * that differ by one rounded tick. We require at least [MIN_OVERLAP_HOURS]
 * shared hours so a same-day coincidence on a handful of hours can't trip
 * the check. Returns null when:
 *  - best_match isn't in [PerModelHourly.byModel];
 *  - best_match has fewer than [MIN_OVERLAP_HOURS] hours with non-null
 *    values for the metric;
 *  - no consulted model matches within tolerance across the shared hours;
 *  - more than one consulted model matches (ambiguous — e.g. multiple
 *    models reported all-zero precip on a dry day).
 */
fun PerModelHourly.bestMatchSourceBy(
    selector: (PerModelHour) -> Double?,
): String? {
    val bestMatchByTime = byModel[PerModelHourly.BEST_MATCH_MODEL_ID]
        ?.toMetricMap(selector)
        ?: return null
    if (bestMatchByTime.size < MIN_OVERLAP_HOURS) return null

    val matches = byModel.entries
        .filter { (id, _) -> id != PerModelHourly.BEST_MATCH_MODEL_ID }
        .mapNotNull { (id, entries) ->
            val byTime = entries.toMetricMap(selector)
            val shared = bestMatchByTime.keys intersect byTime.keys
            if (shared.size < MIN_OVERLAP_HOURS) return@mapNotNull null
            val allWithinTolerance = shared.all { time ->
                abs(bestMatchByTime.getValue(time) - byTime.getValue(time)) <= MATCH_EPSILON
            }
            if (allWithinTolerance) id else null
        }
    return matches.singleOrNull()
}

private fun List<PerModelHour>.toMetricMap(
    selector: (PerModelHour) -> Double?,
): Map<LocalDateTime, Double> = buildMap {
    for (entry in this@toMetricMap) {
        val value = selector(entry) ?: continue
        put(entry.time, value)
    }
}

private const val MATCH_EPSILON = 0.05
private const val MIN_OVERLAP_HOURS = 6
