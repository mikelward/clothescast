package app.clothescast.core.domain.model

/**
 * Summary of *where* the consulted models disagree most today — on the day's
 * high or on the day's low — and by how much.
 *
 * Computed from a [PerModelHourly]: each model's daily high and daily low
 * feels-like temperature (read off a spike-filtered series via
 * [ConfidenceInfo.medianFiltered]), then the wider of the cross-model spread
 * on the high and the spread on the low. The intuition is "the forecasters
 * broadly agree, except they put today's high anywhere from 21 to 24°".
 *
 * Deliberately keyed to the **maxima and minima only**, never the hours in
 * between. The models carry differently-shaped diurnal curves — Google's
 * blend, folded in as a peer, warms faster in the morning and cools slower
 * in the evening than the Open-Meteo models — so a mid-curve hour can show a
 * wide feels-like spread that's purely a timing artifact, not a real
 * disagreement about how hot or cold it'll actually get. Comparing only each
 * model's own high and own low makes the hint timing-invariant: two models
 * that peak at 24° an hour apart *agree* on the high. This is the same pair
 * of extremes the confidence *tier* ([ConfidenceInfo.computeFrom]) keys off,
 * so the chip's title and this detail line can never contradict each other.
 *
 * Returned by [computeFrom] only when the wider extreme's spread crosses
 * [MIN_SPREAD_C]; below that there's not enough disagreement worth surfacing.
 *
 * All numeric fields are in canonical units (°C) — the UI handles user-unit
 * conversion for display.
 */
data class ModelDivergenceSummary(
    /** Whether the daily high or the daily low is where the models disagree most. */
    val extreme: Extreme,
    /** Width of the cross-model spread on [extreme], °C (max - min across models). */
    val spreadC: Double,
    /** Lowest of the consulted models' values for [extreme], °C. */
    val minC: Double,
    /** Highest of the consulted models' values for [extreme], °C. */
    val maxC: Double,
) {
    /** Which daily extreme [ModelDivergenceSummary] describes. */
    enum class Extreme { HIGH, LOW }

    companion object {
        /**
         * Below this spread on the wider extreme we don't show a summary at
         * all. Matches [ConfidenceInfo.TEMP_HIGH_AGREEMENT_C] so the hint
         * surfaces exactly when the temperature disagreement is what pushed
         * the tier below HIGH — a precip-only downgrade (tight temps, wide
         * rain) is explained by the separate rain-spread line instead.
         */
        const val MIN_SPREAD_C = ConfidenceInfo.TEMP_HIGH_AGREEMENT_C

        /**
         * Returns the divergence summary for [perModelHourly], or null when
         * there aren't at least two models with hours, or the wider extreme's
         * cross-model spread is below [MIN_SPREAD_C].
         *
         * `best_match` counts as a regular model here, matching
         * [blendConsensusHourly]'s posture: Open-Meteo's auto-pick is one of
         * the forecasts in play, and when *it* is the outlier on the day's
         * high or low we still want the summary to surface that. (In the
         * Today chip the caller already filters to the tier's consulted
         * set before calling this, so best_match doesn't reach it there.)
         */
        fun computeFrom(perModelHourly: PerModelHourly): ModelDivergenceSummary? {
            val models = perModelHourly.byModel.values.filter { it.isNotEmpty() }
            if (models.size < 2) return null

            // Read each model's daily high and low off the same spike-filtered
            // feels-like series the confidence tier uses, so one model's lone
            // outlier hour can't masquerade as the day's extreme and inflate
            // the reported spread past what the tier saw.
            val smoothed = models.map { ConfidenceInfo.medianFiltered(it) }
            val highs = smoothed.map { it.max() }
            val lows = smoothed.map { it.min() }
            val highSpread = highs.max() - highs.min()
            val lowSpread = lows.max() - lows.min()

            // Surface whichever extreme the models split on more widely. Ties
            // (and the all-agree case) fall to the high, the extreme users key
            // their "how warm will the peak be?" read on.
            val (extreme, values, spread) = if (highSpread >= lowSpread) {
                Triple(Extreme.HIGH, highs, highSpread)
            } else {
                Triple(Extreme.LOW, lows, lowSpread)
            }
            if (spread < MIN_SPREAD_C) return null

            return ModelDivergenceSummary(
                extreme = extreme,
                spreadC = spread,
                minC = values.min(),
                maxC = values.max(),
            )
        }
    }
}
