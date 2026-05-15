package app.clothescast.core.domain.model

/**
 * Cross-model agreement signal. Open-Meteo aggregates several national weather
 * services (ECMWF, DWD ICON, NOAA GFS, Météo-France, …) under one API; the same
 * endpoint accepts `&models=…` and returns each requested model side-by-side.
 *
 * When the major models *disagree* — say, one says 18 °C and another says 23 °C
 * — the forecast is least trustworthy. Surfacing that as a confidence level on
 * Today is actionable information we get for free; the user has a cue to check
 * again later or hedge their clothes choice.
 *
 * Two construction paths exist:
 *  - [MultiModelConfidenceFetcher] in `:core:data` builds one from the daily
 *    endpoint's `apparent_temperature_max` / `precipitation_probability_max`
 *    per-model fields. Always over the full calendar day; used as a fallback
 *    on older bundles that don't carry [PerModelHourly].
 *  - [computeFrom] (below) builds one directly from a [PerModelHourly]
 *    series, computing the spread over whatever hours that series covers.
 *    Used by [app.clothescast.core.domain.usecase.GenerateDailyInsight] so
 *    `Insight.confidence` describes the *same* window the Today/Tonight
 *    chip is rendering (`[morningStart, tonightStart)` for TODAY, the
 *    nighttime window for TONIGHT), keeping the chip's title and detail
 *    lines about the same interval.
 *
 * Idea sketched in [docs/MODELS.md](../../../../../../../../docs/MODELS.md) #1.
 */
data class ConfidenceInfo(
    val level: ForecastConfidence,
    /** Max - min of peak apparent ("feels-like") temperature across the consulted models, °C. */
    val tempSpreadC: Double,
    /** Max - min of peak precipitation probability across the consulted models, percentage points. */
    val precipSpreadPp: Double,
    /** Open-Meteo model ids that contributed (e.g. `ecmwf_ifs04`, `gfs_seamless`). */
    val modelsConsulted: List<String>,
) {
    companion object {
        // Cross-model spread thresholds that define the tier boundaries.
        // Both temp and precip have to clear the bar for HIGH; either one
        // dropping moves us down a tier. Thresholds are deliberate first-
        // pass guesses; refine with real data.
        //
        // Lives here (in `:core:domain`) rather than inside
        // `MultiModelConfidenceFetcher`'s companion (which historically
        // owned these) so that both the data-layer fetcher that *picks*
        // the tier and the UI-layer chip that surfaces "do I need a
        // detail line for this spread?" reference the same numbers. Drift
        // here means the tier-picker and the chip's precip-line gate stop
        // agreeing on what "noteworthy" means.
        const val TEMP_HIGH_AGREEMENT_C = 1.5
        const val TEMP_MEDIUM_AGREEMENT_C = 3.0
        const val PRECIP_HIGH_AGREEMENT_PP = 15.0
        const val PRECIP_MEDIUM_AGREEMENT_PP = 30.0

        /**
         * Compute a [ConfidenceInfo] from a [PerModelHourly] series, using
         * the same tier thresholds the data-layer fetcher uses. Returns
         * null when there's not enough data to compute a meaningful spread
         * — fewer than 2 consulted models with non-empty hours after
         * filtering out the Open-Meteo `best_match` overlay.
         *
         * The spread is computed *over the hours present in the input*:
         * pass a full-day series to match the daily-endpoint compute,
         * pass a sliced series (e.g. the TODAY day-view window
         * `[morningStart, tonightStart)`) to get a confidence that
         * describes only that window. This is what `GenerateDailyInsight`
         * uses so the Today chip's tier title and the chart-side
         * divergence hint describe the same interval the user is seeing.
         *
         * `best_match` is excluded from the consulted set because it's
         * not one of the named source models — it's Open-Meteo's
         * location-tuned auto-pick that we render as an overlay rather
         * than treat as a peer of ECMWF / GFS / ICON. See the
         * [PerModelHourly.BEST_MATCH_MODEL_ID] doc.
         */
        fun computeFrom(perModelHourly: PerModelHourly): ConfidenceInfo? {
            val consulted = perModelHourly.byModel
                .filterKeys { it != PerModelHourly.BEST_MATCH_MODEL_ID }
                .filterValues { it.isNotEmpty() }
            if (consulted.size < 2) return null
            val tempMaxes = consulted.values.map { hours -> hours.maxOf { it.apparentTemperatureC } }
            // Models whose precipitation_probability is wholesale missing (Open-Meteo
            // omits the per-model series for some models) contribute nothing to the
            // precip-spread metric — including them with a fake zero would have
            // silently inflated divergence on a calm day and downgraded confidence.
            // When fewer than two models have any precip readings we ignore the
            // precip dimension entirely and let the tier fall through to a temp-only
            // decision.
            val precipMaxes = consulted.values.mapNotNull { hours ->
                hours.mapNotNull { it.precipitationProbabilityPct }.maxOrNull()
            }
            val tempSpread = tempMaxes.max() - tempMaxes.min()
            val precipSpread = if (precipMaxes.size >= 2) precipMaxes.max() - precipMaxes.min() else 0.0
            val level = when {
                tempSpread <= TEMP_HIGH_AGREEMENT_C && precipSpread <= PRECIP_HIGH_AGREEMENT_PP ->
                    ForecastConfidence.HIGH
                tempSpread <= TEMP_MEDIUM_AGREEMENT_C && precipSpread <= PRECIP_MEDIUM_AGREEMENT_PP ->
                    ForecastConfidence.MEDIUM
                else -> ForecastConfidence.LOW
            }
            return ConfidenceInfo(
                level = level,
                tempSpreadC = tempSpread,
                precipSpreadPp = precipSpread,
                modelsConsulted = consulted.keys.toList(),
            )
        }
    }
}

enum class ForecastConfidence { HIGH, MEDIUM, LOW }
