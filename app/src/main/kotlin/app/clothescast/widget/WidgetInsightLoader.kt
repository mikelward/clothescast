package app.clothescast.widget

import android.content.Context
import app.clothescast.ClothesCastApplication
import app.clothescast.alarm.reconcileWidgetRefreshChain
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.diag.DiagLog
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Loads the current-period insight every home-screen widget renders from: reads
 * the [app.clothescast.data.InsightCache.Slot.THIS_PERIOD] snapshot plus the
 * live [UserPreferences], derives the insight against those prefs, and — the
 * point of routing every widget through one loader — returns null when the
 * cached snapshot's rendered window has wholly elapsed.
 *
 * Why the freshness gate exists: the widgets have no polling of their own
 * (`updatePeriodMillis=0`; refreshes are pushed by the fetch worker via
 * `updateAll()`). When scheduled delivery is off, the cache only moves on
 * app-open — so a user who hasn't opened the app since yesterday was shown
 * *yesterday's* forecast plotted as today's. The per-period feels-like chart is
 * the clearest victim: it plots hour-of-day points for the snapshot's own date,
 * so a day-old snapshot draws a previous day's curve under today's axis (the
 * bug report's "Feels like 17 – 22°C" was yesterday's range, not today's).
 *
 * Rather than confidently render stale data, a widget whose snapshot has elapsed
 * shows its empty state, and we kick a silent refresh on the way out so it
 * self-heals: the worker's cache write fires `updateAll()`, which re-renders the
 * widget off the fresh snapshot. The refresh lands on the unique silent-refresh
 * queue with REPLACE (see [FetchAndNotifyWorker.enqueueSilentRefresh]), so a
 * burst of widget repaints collapses to a single trailing run rather than
 * stacking requests.
 */
internal suspend fun loadCurrentInsight(context: Context): Pair<Insight, UserPreferences>? {
    val app = context.applicationContext as ClothesCastApplication
    val prefs = runCatching { app.settingsRepository.preferences.first() }
        .onFailure { DiagLog.w(TAG, "Widget: reading preferences failed", it) }
        .getOrNull() ?: return null
    // A render is the one signal that a widget is actually placed, so each one
    // (re)arms the widget-only refresh chain for the next schedule boundary.
    // Idempotent — boundary times are absolute, so repeated arms collapse onto
    // the same trigger. This is what keeps a placed widget refreshing when
    // scheduled delivery is disabled (the delivery alarms are then cancelled
    // and nothing else fetches in the background); see WidgetRefreshReceiver.
    runCatching { reconcileWidgetRefreshChain(context, prefs) }
        .onFailure { DiagLog.w(TAG, "Widget: arming the refresh chain failed", it) }
    val snapshot = runCatching { app.insightCache.thisPeriod.first() }
        .onFailure { DiagLog.w(TAG, "Widget: reading cached snapshot failed", it) }
        .getOrNull()
    if (snapshot == null) {
        // Nothing cached yet — a freshly placed widget on a fresh install, or a
        // cache that never filled because both delivery slots are opt-in and the
        // user hasn't opened the app / tapped Refresh. Show the empty state, but
        // kick a silent refresh so the widget self-heals once the fetch lands
        // (the worker's cache write fires updateAll). Deduped REPLACE, so repeat
        // repaints coalesce onto one in-flight run.
        DiagLog.i(TAG, "Widget: no cached forecast yet — showing empty state and kicking a silent refresh")
        FetchAndNotifyWorker.enqueueSilentRefresh(context)
        return null
    }
    val insight = runCatching { app.deriveInsight(snapshot, prefs).insight }
        .onFailure { DiagLog.w(TAG, "Widget: deriveInsight failed", it) }
        .getOrNull() ?: return null
    when (widgetCacheAction(insight, Instant.now(), prefs.schedule.time, prefs.tonightSchedule.time)) {
        WidgetCacheAction.RENDER -> Unit
        WidgetCacheAction.KEEP ->
            DiagLog.i(
                TAG,
                "Widget: cached ${insight.period} insight for ${insight.forDate} isn't the current " +
                    "window, but a dayOffset=0 refresh couldn't reach that window either — " +
                    "keeping the last-known render rather than churning",
            )
        WidgetCacheAction.REFRESH -> {
            DiagLog.i(
                TAG,
                "Widget: cached ${insight.period} insight for ${insight.forDate} is no longer the " +
                    "current window — showing empty state and kicking a silent refresh",
            )
            FetchAndNotifyWorker.enqueueSilentRefresh(context)
            return null
        }
    }
    return insight to prefs
}

/** What a widget should do with the cached snapshot — see [widgetCacheAction]. */
internal enum class WidgetCacheAction {
    /** The snapshot is the current window (or freshly written) — render it as-is. */
    RENDER,

    /**
     * Stale, *and* a silent refresh could replace it with the current window —
     * render the empty state and kick one. The worker's cache write then
     * re-renders the widget off the fresh snapshot.
     */
    REFRESH,

    /**
     * Stale, but a `dayOffset = 0` silent refresh (which targets the window
     * dated *today*) couldn't replace it — render the last-known snapshot and
     * *don't* kick one. Defensive backstop: since the overnight window became
     * today-anchored ("Show Overnight and Today before dawn"), the current
     * window's date always equals the device date, so this normally can't
     * fire; if it somehow does, keeping the last render beats churning toward
     * a window the refresh can't produce.
     */
    KEEP,
}

/**
 * Decides what a widget does with the cached [insight]: render it, render-and-
 * refresh, or keep it without refreshing. The cached snapshot is the current
 * window when its period+date is what a refresh kicked right now would target —
 * the current period via [FetchAndNotifyWorker.currentPeriodForSchedule] and its
 * dated day via [FetchAndNotifyWorker.playTargetDate], the same wrap-past-
 * midnight logic (and the same `bundle.today.date == playTargetDate(...)` test)
 * the worker's own cache match runs, in the device's wall clock [zone]. When it
 * isn't — a day-old snapshot, or one left over from a crossed schedule boundary
 * — plotting it would mislabel a previous window as the current one (the day-old
 * "Feels like 17 – 22°C" case), so we [REFRESH].
 *
 * Two carve-outs keep that from churning:
 *
 *  - **Loop-breaker (age).** The worker stamps [Insight.forDate] from the
 *    *forecast location's* zone but picks the period from the *device* clock, so
 *    for a manual location a calendar day off the device the period/date match
 *    can disagree with what a refresh just wrote. Accepting any snapshot younger
 *    than [FetchAndNotifyWorker.SILENT_REFRESH_MIN_AGE] ([RENDER]) guarantees a
 *    freshly written one ends the staleness instead of feeding refresh→reject→…
 *  - **Unreachable window ([KEEP]).** Defensive backstop: a `dayOffset = 0`
 *    refresh targets the window dated *today*, so if the current window's date
 *    ever trailed the device date the refresh couldn't reach it. Not a live
 *    case since the overnight window became today-anchored (see the note above
 *    the branch); if it recurs, keeping the last-known render beats churning.
 *
 * In the common case — device location, so forecast zone == device zone — forDate
 * and the device date never diverge and the age gate is moot. The forecast zone
 * still governs only where the chart's "now" line falls, not which cast is
 * current.
 */
internal fun widgetCacheAction(
    insight: Insight,
    now: Instant,
    morningTime: LocalTime,
    tonightTime: LocalTime,
    zone: ZoneId = ZoneId.systemDefault(),
): WidgetCacheAction {
    val localNow = LocalDateTime.ofInstant(now, zone)
    val currentPeriod = FetchAndNotifyWorker.currentPeriodForSchedule(morningTime, tonightTime, localNow.toLocalTime())
    val currentDate = FetchAndNotifyWorker.playTargetDate(currentPeriod, localNow, morningTime, tonightTime)
    // The ongoing overnight (the post-midnight tail of the TONIGHT window) dates
    // its *derived* insight to yesterday — the night began yesterday evening —
    // even though its snapshot bundle, and so [currentDate], stays anchored on
    // today (see FetchAndNotifyWorker.playTargetDate / InsightSummary.overnight).
    // So in that tail match the current window on the overnight flag plus the
    // expected night's date (yesterday), not on [currentDate]: the flag alone
    // would also accept a day-old overnight, and [currentDate] (today) would
    // accept the *coming* night. Outside the tail, the usual period+date match.
    val currentlyOvernight = currentPeriod == ForecastPeriod.TONIGHT &&
        tonightTime > morningTime && localNow.toLocalTime() < morningTime
    val matchesCurrentWindow = if (currentlyOvernight) {
        insight.period == ForecastPeriod.TONIGHT &&
            insight.summary.overnight &&
            insight.forDate == localNow.toLocalDate().minusDays(1)
    } else {
        insight.period == currentPeriod && insight.forDate == currentDate
    }
    if (matchesCurrentWindow) return WidgetCacheAction.RENDER
    if (Duration.between(insight.generatedAt, now) < FetchAndNotifyWorker.SILENT_REFRESH_MIN_AGE) {
        return WidgetCacheAction.RENDER
    }
    // Defensive: [currentDate] no longer trails the device date (the overnight is
    // today-anchored since "Show Overnight and Today before dawn"), so a refresh
    // can always reach the current window and this branch is normally not taken.
    if (currentDate != localNow.toLocalDate()) return WidgetCacheAction.KEEP
    return WidgetCacheAction.REFRESH
}

private const val TAG = "Widget"
