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
 * Idea sketched in [docs/MODELS.md](../../../../../../../../docs/MODELS.md) #1.
 */
data class ConfidenceInfo(
    val level: ForecastConfidence,
    /** Max - min of today's apparent-max temperature across the consulted models, °C. */
    val tempSpreadC: Double,
    /** Max - min of today's peak precipitation probability across the consulted models, percentage points. */
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
    }
}

enum class ForecastConfidence { HIGH, MEDIUM, LOW }
