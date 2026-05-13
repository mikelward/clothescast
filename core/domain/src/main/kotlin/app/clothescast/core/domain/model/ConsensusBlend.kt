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
 *   - [HourlyForecast.condition] (CLEAR / RAIN / etc.) is aggregated
 *     modally — most-commonly-predicted bucket across the same
 *     candidate set wins, with ties broken by severity so the
 *     "Combined" line's icon stays in sync with its numeric series
 *     (otherwise we'd get a 90% rain line drawn alongside a "Clear"
 *     condition icon when best_match disagreed on the code). See
 *     [consensusCondition].
 *
 * Returns null when nothing was blended: [perModel] is null, fewer than
 * two models reported overall, or every hour fell back to best_match.
 * The caller should keep the original [bestMatch] (and its
 * upstream-supplied daily aggregates) in that case rather than calling
 * [withAggregatesFrom] on data that hasn't actually changed.
 */
fun blendConsensusHourly(
    bestMatchDate: java.time.LocalDate,
    bestMatch: List<HourlyForecast>,
    perModel: PerModelHourly?,
): List<HourlyForecast>? {
    if (perModel == null) return null
    val models = perModel.byModel
    if (models.size < 2) return null

    // Per-model entries carry a full LocalDateTime so the tonight wrap doesn't
    // alias today's 02:00 against tomorrow's 02:00. Best_match is today's
    // hourly only, indexed by LocalTime — pair it against the matching
    // calendar day before looking up consensus candidates.
    val byHour = mutableMapOf<java.time.LocalDateTime, MutableList<PerModelHour>>()
    for (entries in models.values) {
        for (entry in entries) {
            byHour.getOrPut(entry.time) { mutableListOf() }.add(entry)
        }
    }

    var anyBlended = false
    val out = bestMatch.map { hour ->
        val candidates = byHour[java.time.LocalDateTime.of(bestMatchDate, hour.time)].orEmpty()
        if (candidates.size < 2) {
            hour
        } else {
            anyBlended = true
            hour.copy(
                temperatureC = candidates.map { it.temperatureC }.average(),
                feelsLikeC = candidates.map { it.apparentTemperatureC }.average(),
                precipitationProbabilityPct = candidates.map { it.precipitationProbabilityPct }.average(),
                condition = consensusCondition(
                    fallback = hour.condition,
                    candidates = candidates.mapNotNull { it.condition },
                ),
            )
        }
    }
    return if (anyBlended) out else null
}

/**
 * Picks a single weather condition that represents the consulted models
 * at a given hour. Strategy is **modal with severity tiebreak**: the most
 * commonly-predicted bucket wins; on a tie, the more severe one wins
 * (RAIN beats CLEAR, SNOW beats RAIN, etc.). Avoids the over-cautious
 * "any-rain-anywhere → rain" failure mode of pure max-severity while
 * still nudging toward the more actionable outcome when models genuinely
 * split. Returns [fallback] when [candidates] is empty.
 */
private fun consensusCondition(
    fallback: WeatherCondition,
    candidates: List<WeatherCondition>,
): WeatherCondition {
    if (candidates.isEmpty()) return fallback
    val counts = candidates.groupingBy { it }.eachCount()
    val maxCount = counts.values.max()
    val winners = counts.filterValues { it == maxCount }.keys
    return winners.maxBy { it.severityRank() }
}

/**
 * Severity ranking used by [consensusCondition] to break modal-aggregation
 * ties. Order is roughly "how much would this change today's outfit", so
 * THUNDERSTORM > SNOW > RAIN > DRIZZLE > FOG > CLOUDY > PARTLY_CLOUDY >
 * CLEAR > UNKNOWN. Internal to the consensus blend for now; promote to a
 * public extension on [WeatherCondition] if another caller needs it.
 */
private fun WeatherCondition.severityRank(): Int = when (this) {
    WeatherCondition.THUNDERSTORM -> 8
    WeatherCondition.SNOW -> 7
    WeatherCondition.RAIN -> 6
    WeatherCondition.DRIZZLE -> 5
    WeatherCondition.FOG -> 4
    WeatherCondition.CLOUDY -> 3
    WeatherCondition.PARTLY_CLOUDY -> 2
    WeatherCondition.CLEAR -> 1
    WeatherCondition.UNKNOWN -> 0
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
