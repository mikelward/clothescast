package app.clothescast.core.domain.model

import java.time.LocalDate

/**
 * A snapshot of the feels-like aggregates we actually delivered for a past day,
 * persisted across runs in a small rolling store. Preferred over Open-Meteo's
 * `forecast?past_days=N` data as the yesterday side of the delta clause because:
 *
 *  - `past_days=N` returns the forecast model's hindcast — its retroactive guess
 *    at yesterday from its latest run — not actual observations. When the model
 *    under-called a clear-sky high (as observed in the bug we're addressing),
 *    today's prose ends up comparing today's forecast against yesterday's
 *    missed forecast, which user-facingly reads as a confidently wrong delta.
 *  - The aggregate reflects the forecast we actually told the user about
 *    yesterday morning, so "4° warmer than yesterday" matches what they were
 *    dressed for rather than what some reanalysis decided yesterday was.
 *  - The aggregate naturally tracks the *location they were at* yesterday: we
 *    wrote whatever the snapshot's location resolved to. A user who wakes up in
 *    a different city today still gets a comparison against the day they
 *    actually lived rather than against the new city's past-day model output.
 *
 * Aggregates are for the daytime slice the morning insight runs against
 * (07:00..19:00 by default; whatever the user's notification schedule defines).
 * Hourly values aren't stored — the delta clause reads min and max only.
 */
data class DailyHistoryEntry(
    val date: LocalDate,
    val feelsLikeMinC: Double,
    val feelsLikeMaxC: Double,
)
