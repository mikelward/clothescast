package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.core.domain.repository.WeatherRepository
import app.clothescast.core.domain.util.coRunCatching
import java.time.Clock
import java.time.LocalDate

/**
 * The product. Fetches the forecast + reads today's calendar events, captures
 * them as a [ForecastSnapshot], and runs [DeriveInsight] against the current
 * [UserPreferences] to produce a [DailyInsightResult].
 *
 * Splitting fetch + read from derive lets the cache layer store the snapshot
 * (the raw upstream inputs) rather than the derived insight, so a settings
 * change re-runs the derivation against the latest prefs for free — no
 * preservation / re-gating logic on the cache side, no waiting for the next
 * worker run. Callers that already hold a fresh snapshot (the cache after a
 * worker write, the Today screen / widget / cast / Format settings preview
 * combining snapshot + prefs flows) call [DeriveInsight] directly.
 *
 * Severe-weather alerts piggy-back on the same fetch and are returned alongside
 * the insight in [DailyInsightResult]; the worker uses them to drive a separate
 * high-priority notification while still feeding them into the daily summary.
 */
class GenerateDailyInsight(
    private val weatherRepository: WeatherRepository,
    private val deriveInsight: DeriveInsight = DeriveInsight(),
    private val calendarEventReader: CalendarEventReader? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(
        location: Location,
        prefs: UserPreferences,
        period: ForecastPeriod = ForecastPeriod.TODAY,
        // Which day's window the caller wants generated. 0 == today (the
        // default; the user-facing alarm path), 1 == tomorrow. The evening
        // alarm uses `dayOffset = 1` with `period = TODAY` to pre-render
        // tomorrow's daytime insight for the Today screen's "next" card —
        // Open-Meteo's `forecast_days=2` response already carries tomorrow's
        // daily aggregates and hourly, so no extra fetch is needed. Only
        // `(TODAY, 1)` is supported — there's no day-after-tomorrow data in
        // the bundle to wrap a tomorrow-tonight window around.
        dayOffset: Int = 0,
    ): DailyInsightResult {
        val snapshot = snapshot(location, prefs, period, dayOffset)
        return deriveInsight(snapshot, prefs)
    }

    /**
     * Captures the inputs `DeriveInsight` needs into a [ForecastSnapshot] without
     * running the derivation. The worker uses this to write the snapshot to
     * [app.clothescast.data.InsightCache] before delivering the insight, so any
     * later consumer (Today screen, widget, cast, Format settings preview) can
     * re-render against the latest prefs for free.
     */
    suspend fun snapshot(
        location: Location,
        prefs: UserPreferences,
        period: ForecastPeriod = ForecastPeriod.TODAY,
        dayOffset: Int = 0,
    ): ForecastSnapshot {
        require(dayOffset == 0 || (dayOffset == 1 && period == ForecastPeriod.TODAY)) {
            "Only same-day (dayOffset=0) and tomorrow-daytime (dayOffset=1, period=TODAY) generation are supported."
        }
        val fetched = weatherRepository.fetchForecast(location)
        val bundle = if (dayOffset == 1) {
            requireNotNull(fetched.shiftedToTomorrow()) {
                "Bundle has no tomorrow forecast; cannot generate next-day insight (was the fetch a forecast_days=1 response?)."
            }
        } else {
            fetched
        }
        val events = readEventsForDay(bundle.today.date, prefs)
        return ForecastSnapshot(
            bundle = bundle,
            events = events,
            location = location,
            period = period,
            generatedAt = clock.instant(),
        )
    }

    private suspend fun readEventsForDay(date: LocalDate, prefs: UserPreferences): List<CalendarEvent> {
        // Failures (missing permission, provider crash) degrade to no events so
        // a misbehaving reader can never fail the insight pipeline. Reader
        // implementations are expected to log their own failures before
        // throwing; we don't have a logger in pure-Kotlin :core:domain.
        val reader = calendarEventReader
        if (!prefs.calendarEventMentionsActive || reader == null) return emptyList()
        return coRunCatching { reader.eventsForDay(date, prefs.schedule.zoneId) }
            .getOrDefault(emptyList())
    }
}
