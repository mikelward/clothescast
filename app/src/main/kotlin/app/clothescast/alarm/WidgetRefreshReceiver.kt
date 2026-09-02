package app.clothescast.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import app.clothescast.diag.DiagLog
import app.clothescast.widget.hasPlacedClothesCastWidgets
import app.clothescast.widget.updateAllClothesCastWidgets
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * Fires when one of the widget-only refresh alarms goes off (see
 * [WidgetRefreshScheduler]). No widgets left → cancel both slots and the arm
 * record, so nothing is left pending and nothing later mistakes a consumed
 * alarm for a live one; the chain ends here. Otherwise the work depends on which tick this is, and
 * either way that slot — and only that slot — re-arms for its next occurrence.
 *
 * At a [WidgetRefreshKind.BOUNDARY] tick the window the widgets draw has just
 * flipped, so the cache needs the new window fetched:
 *
 *  1. The boundary's delivery alarm would fire anyway (slot enabled and today
 *     in its day set) → skip the enqueue; the delivery run refreshes the cache
 *     and repaints the widgets itself.
 *  2. Otherwise → enqueue a silent refresh (fetch + cache + widget repaint, no
 *     notification / TTS / MQTT / cast).
 *
 * The silent run derives its period from wall-clock time inside the worker
 * ([FetchAndNotifyWorker.currentPeriodForSchedule]), which at a boundary fire
 * is exactly the window that just opened.
 *
 * At a [WidgetRefreshKind.HOURLY] tick the window hasn't changed, only the hour
 * has. It repaints from the cache — that alone moves everything anchored to
 * *now*, since the chart's current-time line and readout and the conditions
 * strip are all computed at render time and the cached snapshot already covers
 * the whole window — and it refetches only once that snapshot is older than
 * [WIDGET_REFRESH_MAX_AGE].
 *
 * That age is deliberately **not** the one an app open uses. Both run the same
 * pipeline: the same [app.clothescast.data.InsightCache] snapshot, the same
 * [FetchAndNotifyWorker.shouldSilentlyRefresh] shape of test, the same
 * [FetchAndNotifyWorker.enqueueSilentRefresh] onto the same REPLACE-deduped
 * queue — so a widget tick and an app open collapse into one run rather than
 * two. What differs is the threshold, and it differs because of who is looking:
 * an app open is a person on the screen, and its
 * [FetchAndNotifyWorker.SILENT_REFRESH_MIN_AGE] is an hour to match
 * `CachingWeatherRepository`'s own 1h TTL, so a fetch inside the hour would only
 * re-read the same bundle. This tick fires whether or not anyone is looking, so
 * paying for freshness at that rate would be spending a person's battery on a
 * launcher nobody has glanced at. A tap on the widget opens the app and gets the
 * 1h path.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        // Default BOUNDARY: an alarm armed by an older build (or one whose
        // extra didn't survive) does the more thorough of the two jobs.
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { name -> WidgetRefreshKind.entries.firstOrNull { it.name == name } }
            ?: WidgetRefreshKind.BOUNDARY
        DiagLog.i(TAG, "WidgetRefreshReceiver fired (%s)", kind)

        val pending = goAsync()
        ReceiverWork.launch(pending) {
            val appCtx = context.applicationContext
            try {
                if (!hasPlacedClothesCastWidgets(appCtx)) {
                    // Cancel, don't just stop re-arming. This fire consumed one
                    // slot; the *other* is still pending, and the arm record
                    // still names a boundary that has now been consumed. A
                    // widget re-added while that record looks pending would read
                    // it as a live alarm and decline to arm one — leaving only
                    // the non-wakeup hourly tick to notice. cancel() clears both
                    // slots and the record, so "the chain ended" is true.
                    DiagLog.i(TAG, "No ClothesCast widgets placed; ending the widget-refresh chain.")
                    WidgetRefreshScheduler(appCtx).cancel()
                    return@launch
                }
                val app = appCtx as ClothesCastApplication
                val prefs = app.settingsRepository.preferences.first()
                when (kind) {
                    WidgetRefreshKind.HOURLY -> refreshHourly(app)
                    WidgetRefreshKind.BOUNDARY ->
                        if (deliveryCoversWidgetRefresh(
                                dailyEnabled = prefs.dailyEnabled,
                                tonightEnabled = prefs.tonightEnabled,
                                morning = prefs.schedule,
                                tonight = prefs.tonightSchedule,
                                now = LocalDateTime.now(),
                            )
                        ) {
                            DiagLog.i(TAG, "Delivery alarm covers this boundary; skipping the widget-only refresh.")
                        } else {
                            FetchAndNotifyWorker.enqueueSilentRefresh(
                                appCtx,
                                alarmFiredAtMs = System.currentTimeMillis(),
                            )
                        }
                }
                // Only this slot: re-arming the other would cancel-and-replace an
                // alarm that may be due right now and not yet delivered.
                WidgetRefreshScheduler(appCtx).scheduleTick(kind, prefs.schedule, prefs.tonightSchedule)
            } catch (c: CancellationException) {
                // Not a failure to recover from, and the catch below would
                // treat it as one — logging it and running a fallback that
                // enqueues work and arms an alarm after the work was called
                // off. This is also what makes the rethrows in refreshHourly
                // mean anything: without it they land here and are absorbed
                // two lines later. ReceiverWork finishes the goAsync() result
                // in a finally, so propagating still releases the broadcast.
                throw c
            } catch (t: Throwable) {
                DiagLog.e(TAG, t, "Widget-refresh fire failed")
                // Widgets are (probably) still placed, so a one-off failure —
                // typically the prefs read — shouldn't end the chain: refresh
                // and re-arm on the default schedule times so the next fire
                // gets another chance to read the real ones.
                runCatching {
                    FetchAndNotifyWorker.enqueueSilentRefresh(appCtx, alarmFiredAtMs = System.currentTimeMillis())
                    WidgetRefreshScheduler(appCtx)
                        .scheduleTick(kind, Schedule.default(), Schedule.defaultTonight())
                }.onFailure { DiagLog.e(TAG, it, "Widget-refresh fallback re-arm failed") }
            }
        }
    }

    /**
     * The hourly tick's work: repaint every placed widget so its now-anchored
     * elements move on, and refetch only once the cached snapshot has aged past
     * [WIDGET_REFRESH_MAX_AGE].
     *
     * The repaint is first and unconditional. It needs no network, so it is
     * what keeps a widget honest when the fetch can't run; when the fetch does
     * run, its cache write repaints again off the fresh snapshot.
     *
     * A read of the cached snapshot that fails is treated as stale — the fetch
     * is the recovery for a cache we can't read, and the shared queue's REPLACE
     * dedupe bounds what a repeatedly failing read can cost.
     */
    private suspend fun refreshHourly(app: ClothesCastApplication) {
        // Both runCatching blocks below would otherwise swallow
        // CancellationException — updateAllClothesCastWidgets deliberately
        // rethrows it, and Flow.first() is a cancellation point — and carry on
        // reading the cache, enqueueing work and re-arming after cancellation
        // was requested. Rethrow it, keep the fallback for real failures.
        runCatching { updateAllClothesCastWidgets(app) }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { DiagLog.w(TAG, it, "Hourly widget repaint failed") }
        val snapshot = runCatching { app.insightCache.thisPeriod.first() }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { DiagLog.w(TAG, it, "Hourly tick: reading the cached snapshot failed") }
        val stale = snapshot.fold(
            onSuccess = { it == null || Duration.between(it.generatedAt, Instant.now()) >= WIDGET_REFRESH_MAX_AGE },
            onFailure = { true },
        )
        if (stale) {
            FetchAndNotifyWorker.enqueueSilentRefresh(app, alarmFiredAtMs = System.currentTimeMillis())
        } else {
            DiagLog.i(TAG, "Hourly tick: cached forecast is still fresh enough; repainted without refetching.")
        }
    }

    companion object {
        const val ACTION_FIRE = "app.clothescast.alarm.WIDGET_REFRESH"

        /** [WidgetRefreshKind] name carried by the alarm's intent. */
        const val EXTRA_KIND = "app.clothescast.alarm.WIDGET_REFRESH_KIND"

        private const val TAG = "WidgetRefreshReceiver"

        /**
         * How stale the cached forecast may get before an hourly tick refetches
         * it. Six hours rather than the app-open hour: the same pipeline and
         * the same queue, but a threshold sized for a surface that nobody may
         * be looking at (see the class doc). Half a 12-hour window, so a window
         * whose own boundary fetch was missed still gets one attempt inside it.
         */
        internal val WIDGET_REFRESH_MAX_AGE: Duration = Duration.ofHours(6)
    }
}

/**
 * True when the window boundary being crossed at [now] is already covered by
 * an armed delivery alarm — the slot is enabled *and* today is in its
 * day-of-week set — so the widget chain should defer to the delivery run
 * rather than double-fetching alongside it. Pure and time-injected for tests.
 */
internal fun deliveryCoversWidgetRefresh(
    dailyEnabled: Boolean,
    tonightEnabled: Boolean,
    morning: Schedule,
    tonight: Schedule,
    now: LocalDateTime,
): Boolean = when (
    FetchAndNotifyWorker.currentPeriodForSchedule(morning.time, tonight.time, now.toLocalTime())
) {
    ForecastPeriod.TODAY -> dailyEnabled && now.dayOfWeek in morning.days
    ForecastPeriod.TONIGHT -> tonightEnabled && now.dayOfWeek in tonight.days
}
