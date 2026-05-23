package app.clothescast.core.domain.model

import app.clothescast.core.domain.repository.ForecastBundle
import java.time.Instant

/**
 * The complete set of inputs `DeriveInsight` needs to produce a `DailyInsightResult`.
 * Caching one of these instead of the derived [Insight] lets every consumer
 * re-render against the latest [app.clothescast.core.domain.model.UserPreferences]
 * for free, so a settings change (clothes rules, delta threshold, mention mode,
 * default top / bottom) reflects in the Today screen prose, the Format settings
 * page preview, the home-screen widget and the cast / MQTT surfaces in the same
 * frame the dropdown closes — no preservation / re-gating logic on the cache
 * side, no waiting for the next worker run.
 *
 * The [bundle] is stored already-shifted for the worker's `dayOffset` parameter,
 * so from the renderer's perspective `bundle.today` is always the day the
 * snapshot is for. [events] are the calendar events that overlap that day, used
 * by `RenderInsightSummary` for the calendar-tie-in and evening-event-tie-in
 * clauses; titles and free-form location strings are not consulted by the
 * renderer (only timing + location-presence), so on-disk persistence keeps the
 * minimal subset to keep the existing privacy posture intact (see PRIVACY.md).
 *
 * Other fields are passthrough metadata the consumer needs to stamp on the
 * derived [Insight] or to dedup against: [location] is stamped on for the home
 * screen's city label and maps deep-link, [period] selects the TODAY vs TONIGHT
 * branch in the renderer, and [generatedAt] is the wall-clock when the snapshot
 * was captured (replaces what was the cached `Insight.generatedAt`).
 */
data class ForecastSnapshot(
    val bundle: ForecastBundle,
    val events: List<CalendarEvent>,
    val location: Location?,
    val period: ForecastPeriod,
    val generatedAt: Instant,
)
