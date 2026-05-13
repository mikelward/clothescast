package app.clothescast.core.domain.model

/**
 * Blends per-hour values from the consulted models into a single
 * [HourlyForecast] series, replacing the corresponding [bestMatch] entry
 * with the arithmetic mean across models. Used to drive the chart's
 * "Combined" main line and — crucially — the clothes-rule + insight-prose
 * pipeline, so on days when Open-Meteo's `best_match` auto-selection
 * diverges from the consulted ECMWF / GFS / ICON consensus (the case the
 * user keeps catching: best_match underestimating rain that two real
 * models agreed on), the recommendation follows the consensus rather
 * than the outlier.
 *
 * Mechanics:
 *   - At each hour t, collect values from every consulted model
 *     (anything in [PerModelHourly.byModel] *except*
 *     [PerModelHourly.BEST_MATCH_MODEL_ID] — best_match is excluded from
 *     its own consensus by design, since it's the meta-model we're
 *     trying to outvote).
 *   - When two or more consulted models reported an entry at hour t,
 *     replace best_match's value with their mean. With fewer than two,
 *     keep best_match — a single-model "consensus" isn't a consensus.
 *   - [HourlyForecast.condition] (CLEAR / RAIN / etc.) stays from
 *     best_match for now; modal aggregation of weather codes across
 *     models is a follow-up — see TODO in [PerModelHourly].
 *
 * Returns null when nothing was blended: [perModel] is null, fewer than
 * two consulted models reported overall, or every hour fell back to
 * best_match. The caller should keep the original [bestMatch] (and its
 * upstream-supplied daily aggregates) in that case rather than calling
 * [withAggregatesFrom] on data that hasn't actually changed.
 */
fun blendConsensusHourly(
    bestMatch: List<HourlyForecast>,
    perModel: PerModelHourly?,
): List<HourlyForecast>? {
    if (perModel == null) return null
    val consulted = perModel.byModel
        .filterKeys { it != PerModelHourly.BEST_MATCH_MODEL_ID }
    if (consulted.size < 2) return null

    val byHour = mutableMapOf<java.time.LocalTime, MutableList<PerModelHour>>()
    for (entries in consulted.values) {
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
