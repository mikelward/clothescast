package app.clothescast.core.domain.model

/**
 * Blends per-hour values from the consulted models — including Open-Meteo's
 * `best_match` overlay — into a single [HourlyForecast] series, replacing
 * the corresponding [bestMatch] entry with the arithmetic mean across
 * models. Used to drive the chart's "Combined" main line and — crucially —
 * the clothes-rule + insight-prose pipeline, so on days when best_match
 * diverges from the consulted ECMWF / GFS / ICON consensus (the rain case
 * the user kept catching), the recommendation follows the broader picture
 * rather than the single auto-selected line.
 *
 * Mechanics:
 *   - At each hour t, collect values from every model in
 *     [PerModelHourly.byModel] — best_match counts as a regular model
 *     here. Open-Meteo's auto-selection is presumably location-tuned and
 *     more accurate on average than a naive mean of three competitors;
 *     pulling it out of the consensus would dilute that signal. The
 *     implicit consequence is that whichever underlying model best_match
 *     resolved to (often one of the same models we list separately) is
 *     effectively double-weighted in the mean. We accept that as the
 *     price of letting the consulted models still outvote best_match
 *     when they aggressively agree against it.
 *   - When two or more models reported an entry at hour t, replace
 *     best_match's value with their mean. With fewer than two, keep
 *     best_match — a one-model "consensus" isn't a consensus.
 *   - [HourlyForecast.condition] (CLEAR / RAIN / etc.) stays from
 *     best_match for now; modal aggregation of weather codes across
 *     models is a follow-up — see TODO in [PerModelHourly].
 *
 * Returns null when nothing was blended: [perModel] is null, fewer than
 * two models reported overall, or every hour fell back to best_match.
 * The caller should keep the original [bestMatch] (and its
 * upstream-supplied daily aggregates) in that case rather than calling
 * [withAggregatesFrom] on data that hasn't actually changed.
 */
fun blendConsensusHourly(
    bestMatch: List<HourlyForecast>,
    perModel: PerModelHourly?,
): List<HourlyForecast>? {
    if (perModel == null) return null
    val models = perModel.byModel
    if (models.size < 2) return null

    val byHour = mutableMapOf<java.time.LocalTime, MutableList<PerModelHour>>()
    for (entries in models.values) {
        for (entry in entries) {
            byHour.getOrPut(entry.time) { mutableListOf() }.add(entry)
        }
    }

    var anyBlended = false
    val out = bestMatch.map { hour ->
        val candidates = byHour[hour.time].orEmpty()
        if (candidates.size < 2) {
            hour
        } else {
            anyBlended = true
            hour.copy(
                temperatureC = candidates.map { it.temperatureC }.average(),
                feelsLikeC = candidates.map { it.apparentTemperatureC }.average(),
                precipitationProbabilityPct = candidates.map { it.precipitationProbabilityPct }.average(),
            )
        }
    }
    return if (anyBlended) out else null
}

/**
 * Returns a copy of [this] daily forecast whose temperature / feels-like
 * min-max and peak precipitation probability are recomputed from
 * [blendedHourly]. Used after [blendConsensusHourly] swaps a day's
 * hourly array for the consulted-model consensus, so the daily summary
 * (which feeds clothes rules + the insight prose) stays in sync with
 * what the chart shows. When [blendedHourly] is empty, the daily fields
 * stay as best_match.
 */
fun DailyForecast.withAggregatesFrom(blendedHourly: List<HourlyForecast>): DailyForecast {
    if (blendedHourly.isEmpty()) return this
    return copy(
        hourly = blendedHourly,
        temperatureMinC = blendedHourly.minOf { it.temperatureC },
        temperatureMaxC = blendedHourly.maxOf { it.temperatureC },
        feelsLikeMinC = blendedHourly.minOf { it.feelsLikeC },
        feelsLikeMaxC = blendedHourly.maxOf { it.feelsLikeC },
        precipitationProbabilityMaxPct = blendedHourly
            .maxOfOrNull { it.precipitationProbabilityPct } ?: precipitationProbabilityMaxPct,
        // precipitationMmTotal stays from best_match — the per-model fetcher
        // doesn't carry a hourly mm series, only probability percent.
    )
}
