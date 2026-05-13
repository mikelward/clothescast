package app.clothescast.core.domain.model

import java.time.LocalTime

/**
 * Per-hour summary of *why* the consulted models disagree the most today.
 *
 * Computed from a [PerModelHourly]: the hour where the cross-model
 * feels-like spread peaks, plus the per-hour ancillary factor (air temp,
 * wind, cloud cover, humidity) whose own cross-model spread — normalised
 * against rough "what counts as a meaningful disagreement" scales — is the
 * widest. The intuition is "models broadly agreed except at 13:00, where
 * they disagreed mostly because they disagreed on the cloud cover".
 *
 * Returned by [computeFrom] only when the peak feels-like spread crosses
 * [MIN_FEELS_LIKE_SPREAD_C]; below that threshold there's not enough
 * disagreement worth surfacing, and we'd rather show nothing than a
 * misleading "biggest factor" attribution on essentially-agreeing models.
 *
 * All numeric fields are in canonical units (°C, km/h, percent) — the UI
 * handles user-unit conversion for display.
 */
data class ModelDivergenceSummary(
    /** Hour where the feels-like temperature spread across consulted models was widest. */
    val peakHour: LocalTime,
    /** Width of the feels-like spread at [peakHour], °C (max - min across models). */
    val feelsLikeSpreadC: Double,
    /** Which ancillary factor disagreed most (in normalised terms) at [peakHour]. */
    val topFactor: Factor,
    /** Minimum value of [topFactor] across the consulted models at [peakHour]. */
    val topFactorMin: Double,
    /** Maximum value of [topFactor] across the consulted models at [peakHour]. */
    val topFactorMax: Double,
) {
    /**
     * Ancillary factor ranked as the biggest driver of the feels-like
     * disagreement. [AIR_TEMPERATURE] is included separately from
     * feels-like itself because the two diverge when wind / humidity
     * are involved — knowing it's the raw temperature that disagrees
     * (and not, say, the wind chill) is the useful signal.
     */
    enum class Factor { AIR_TEMPERATURE, WIND_SPEED, CLOUD_COVER, RELATIVE_HUMIDITY }

    companion object {
        /** Below this peak feels-like spread we don't show a summary at all. */
        const val MIN_FEELS_LIKE_SPREAD_C = 1.5

        // Per-factor normalisation scales — "this much spread is roughly as
        // notable as a 1.5 °C feels-like spread". Hand-tuned guesses; the
        // intent is to let the ranking compare unlike units, not to be
        // scientifically defensible. Revisit if the wrong factor keeps
        // winning on real data.
        private const val SCALE_AIR_TEMP_C = 1.5
        private const val SCALE_WIND_KMH = 10.0
        private const val SCALE_CLOUD_PP = 30.0
        private const val SCALE_HUMIDITY_PP = 20.0

        /**
         * Returns the divergence summary for [perModelHourly], or null when
         * there aren't at least two consulted models, no hour is shared
         * across enough of them, or the peak feels-like spread is below
         * [MIN_FEELS_LIKE_SPREAD_C].
         */
        fun computeFrom(perModelHourly: PerModelHourly): ModelDivergenceSummary? {
            // best_match counts as a regular model here, matching
            // [blendConsensusHourly]'s posture: Open-Meteo's auto-pick is
            // one of the forecasts in play, and when *it* is the outlier
            // on a given hour we still want the summary to surface that
            // disagreement (best_match is what users see for the "Auto"
            // overlay; pretending it didn't disagree would be misleading).
            val models = perModelHourly.byModel.values.toList()
            if (models.size < 2) return null

            // Group hours by time across all models so we can compute spreads.
            val byHour = mutableMapOf<LocalTime, MutableList<PerModelHour>>()
            for (entries in models) {
                for (entry in entries) {
                    byHour.getOrPut(entry.time) { mutableListOf() }.add(entry)
                }
            }
            val peak = byHour.entries
                .filter { it.value.size >= 2 }
                .maxByOrNull { (_, entries) ->
                    val feels = entries.map { it.apparentTemperatureC }
                    feels.max() - feels.min()
                } ?: return null

            val peakEntries = peak.value
            val feels = peakEntries.map { it.apparentTemperatureC }
            val feelsLikeSpread = feels.max() - feels.min()
            if (feelsLikeSpread < MIN_FEELS_LIKE_SPREAD_C) return null

            // Per-factor spread candidates at the peak hour. A factor is only a
            // candidate if at least two models reported it — single-model
            // "spread" is meaningless and would skew the ranking.
            data class Candidate(
                val factor: Factor,
                val min: Double,
                val max: Double,
                val normalised: Double,
            )
            fun candidate(
                factor: Factor,
                scale: Double,
                pick: (PerModelHour) -> Double?,
            ): Candidate? {
                val values = peakEntries.mapNotNull(pick)
                if (values.size < 2) return null
                val min = values.min()
                val max = values.max()
                return Candidate(factor, min, max, (max - min) / scale)
            }
            val candidates = listOfNotNull(
                candidate(Factor.AIR_TEMPERATURE, SCALE_AIR_TEMP_C) { it.temperatureC },
                candidate(Factor.WIND_SPEED, SCALE_WIND_KMH) { it.windSpeedKmh },
                candidate(Factor.CLOUD_COVER, SCALE_CLOUD_PP) { it.cloudCoverPct },
                candidate(Factor.RELATIVE_HUMIDITY, SCALE_HUMIDITY_PP) { it.relativeHumidityPct },
            )
            val top = candidates.maxByOrNull { it.normalised } ?: return null
            return ModelDivergenceSummary(
                peakHour = peak.key,
                feelsLikeSpreadC = feelsLikeSpread,
                topFactor = top.factor,
                topFactorMin = top.min,
                topFactorMax = top.max,
            )
        }
    }
}
