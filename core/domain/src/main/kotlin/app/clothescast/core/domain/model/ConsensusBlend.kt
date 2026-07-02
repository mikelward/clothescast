package app.clothescast.core.domain.model

/**
 * Blends per-hour values from the consulted models — including Open-Meteo's
 * `best_match` overlay — into a single [HourlyForecast] series, replacing
 * the corresponding [bestMatch] entry with the arithmetic mean across
 * models. Covers temperature, feels-like, precipitation probability, rain
 * amount, wind speed, UV index, and the weather condition. Used to drive the chart's
 * "Combined" main line and — crucially — the clothes-rule + insight-prose
 * pipeline and the conditions strip, so on days when best_match diverges
 * from the consulted ECMWF / GFS / ICON consensus (the rain case the user
 * kept catching), every surface follows the broader picture rather than the
 * single auto-selected line.
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
 *   - An hour at least two models cover but [bestMatch] doesn't (its
 *     temperature came back null upstream, so the mapper dropped the hour
 *     rather than zero-fill it) is synthesized from the consensus, keeping
 *     the day's coverage at the forecast horizon where best_match thins
 *     out before the consulted models do.
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
    val replaced = bestMatch.map { hour ->
        val candidates = byHour[java.time.LocalDateTime.of(bestMatchDate, hour.time)].orEmpty()
        if (candidates.size < 2) {
            hour
        } else {
            anyBlended = true
            // Precip is averaged only over candidates that actually
            // provided a value — Open-Meteo omits per-model precipitation
            // probability for some models (UKMO, JMA, GEM, ARPEGE, …) and
            // including a synthetic 0 for those would silently downgrade
            // the blended rain probability when *other* models predict
            // rain. When no candidate had a precip reading at this hour,
            // fall back to best_match's own value rather than NaN'ing.
            val precipCandidates = candidates.mapNotNull { it.precipitationProbabilityPct }
            val blendedPrecip = if (precipCandidates.isEmpty()) {
                hour.precipitationProbabilityPct
            } else {
                precipCandidates.average()
            }
            // Wind / UV: same skip-nulls-then-average treatment as precip.
            // best_match carries both off the primary forecast call, so it
            // votes here like any consulted model; models whose runs omit the
            // field sit the hour out. Fall back to best_match's own value when
            // no candidate reported (keeps a sane backstop rather than
            // dropping the field to null).
            val windCandidates = candidates.mapNotNull { it.windSpeedKmh }
            val blendedWind = if (windCandidates.isEmpty()) hour.windSpeedKmh else windCandidates.average()
            val uvCandidates = candidates.mapNotNull { it.uvIndex }
            val blendedUv = if (uvCandidates.isEmpty()) hour.uvIndex else uvCandidates.average()
            // Rain amount blends like the rest: skip models that didn't
            // report it, fall back to best_match when none did. Keeping
            // best_match's raw mm here while the synthesized path below
            // averages the models produced a series whose hour-to-hour shape
            // reflected best_match's *coverage*, not the weather — 0 mm on a
            // covered hour next to 2 mm on a synthesized one when the models
            // agreed on 2 mm all along.
            val mmCandidates = candidates.mapNotNull { it.precipitationMm }
            val blendedMm = if (mmCandidates.isEmpty()) hour.precipitationMm else mmCandidates.average()
            hour.copy(
                temperatureC = candidates.map { it.temperatureC }.average(),
                feelsLikeC = candidates.map { it.apparentTemperatureC }.average(),
                precipitationProbabilityPct = blendedPrecip,
                precipitationMm = blendedMm,
                windSpeedKmh = blendedWind,
                uvIndex = blendedUv,
                condition = consensusCondition(
                    fallback = hour.condition,
                    candidates = candidates.mapNotNull { it.condition },
                ),
            )
        }
    }

    // Hours the consulted models cover but best_match doesn't: synthesize the
    // consensus entry. Same ≥2-candidates bar as the replacement path; the
    // hour must land on this day's date (byHour spans the full per-model
    // window). Precip and amount fall back to 0.0 when no candidate reported
    // them — those fields are non-null on [HourlyForecast] and "models
    // reported the hour but none carried precip" is the no-data case the
    // chart already renders as zero.
    val covered = bestMatch.mapTo(HashSet()) { java.time.LocalDateTime.of(bestMatchDate, it.time) }
    val synthesized = byHour.entries
        .filter { (t, candidates) ->
            t.toLocalDate() == bestMatchDate && candidates.size >= 2 && t !in covered
        }
        .map { (t, candidates) ->
            HourlyForecast(
                time = t.toLocalTime(),
                temperatureC = candidates.map { it.temperatureC }.average(),
                feelsLikeC = candidates.map { it.apparentTemperatureC }.average(),
                precipitationProbabilityPct = candidates.mapNotNull { it.precipitationProbabilityPct }
                    .takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                condition = consensusCondition(
                    fallback = WeatherCondition.UNKNOWN,
                    candidates = candidates.mapNotNull { it.condition },
                ),
                precipitationMm = candidates.mapNotNull { it.precipitationMm }
                    .takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                windSpeedKmh = candidates.mapNotNull { it.windSpeedKmh }
                    .takeIf { it.isNotEmpty() }?.average(),
                uvIndex = candidates.mapNotNull { it.uvIndex }
                    .takeIf { it.isNotEmpty() }?.average(),
            )
        }
    if (synthesized.isNotEmpty()) anyBlended = true
    if (!anyBlended) return null
    // Stable sort keeps a DST fall-back day's duplicated wall-clock hour as
    // two adjacent entries in their original order.
    return (replaced + synthesized).sortedBy { it.time }
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
 * min-max, peak precipitation probability, and representative condition are
 * recomputed from [blendedHourly]. Used after [blendConsensusHourly] swaps a
 * day's hourly array for the consulted-model consensus, so the daily summary
 * (which feeds clothes rules, the insight prose, and the week-ahead headline)
 * stays in sync with what the chart shows. When [blendedHourly] is empty, the
 * daily fields stay as best_match.
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
        // Sum of the blended per-hour amounts, so the daily total agrees with
        // the hourly series the chart draws (both now carry the cross-model
        // mean; previously this stayed best_match's total while the hourly
        // series blended, and the two could tell different stories).
        precipitationMmTotal = blendedHourly.sumOf { it.precipitationMm },
        // Take the condition from the peak-precip hour of the blended series
        // (mirroring DailyForecast.slicedForToday), so a day the consensus
        // turns wet carries a precipitating daily condition. Without this the
        // daily code stays best_match's clear/cloudy while the blended precip
        // max climbs, and DeriveWeekAheadInsight.rainHeadline — which keys its
        // precip *type* off the daily condition — would announce a consensus
        // snow / thunderstorm / drizzle day as plain rain. UNKNOWN doesn't
        // override a real best_match code.
        condition = blendedHourly.maxByOrNull { it.precipitationProbabilityPct }
            ?.condition
            ?.takeIf { it != WeatherCondition.UNKNOWN }
            ?: condition,
    )
}
