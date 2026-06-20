package app.clothescast.work

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.location.LocationManager
import app.clothescast.diag.DiagLog
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.clothescast.ClothesCastApplication
import app.clothescast.alarm.ScheduledDeliveryService
import app.clothescast.core.domain.model.DailyHistoryEntry
import app.clothescast.core.domain.model.postsNotification
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.playsSpeech
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.windSpeedUnit
import app.clothescast.calendar.resolveHolidayTheme
import app.clothescast.cast.CastInsightController
import app.clothescast.cast.Mp4Encoder
import app.clothescast.core.domain.usecase.computeDeliveryGates
import app.clothescast.core.domain.usecase.isGeminiEngineSelected
import app.clothescast.core.domain.usecase.isMqttPublishable
import app.clothescast.core.domain.util.isWithin
import app.clothescast.core.data.tts.GeminiTtsDailyQuotaExhaustedException
import app.clothescast.core.data.tts.PcmAudio
import app.clothescast.core.data.tts.WavEncoder
import app.clothescast.core.data.tts.isRetryableGeminiTtsFailure
import app.clothescast.data.InsightCache
import app.clothescast.mqtt.MqttPublishOutcome
import app.clothescast.notification.CHANNEL_PLAYBACK
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import app.clothescast.diag.Telemetry
import app.clothescast.diag.classifyDailyRefreshReason
import app.clothescast.insight.InsightFormatter
import app.clothescast.location.LocationResolver
import app.clothescast.location.hasBackgroundLocationPermission
import app.clothescast.location.hasCoarseLocationPermission
import app.clothescast.tts.GeminiTtsSpeaker
import app.clothescast.tts.InsightTtsUtterance
import app.clothescast.tts.insightTtsUtterance
import app.clothescast.tts.resolveHolidayVoice
import app.clothescast.tts.withSpeechAudioFocus
import app.clothescast.ui.today.WorkInfoLite
import app.clothescast.ui.today.toLite
import app.clothescast.R
import app.clothescast.ui.garment.outfitCardInfoLines
import app.clothescast.ui.garment.renderOutfitCard
import app.clothescast.widget.updateAllClothesCastWidgets
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Performs one daily fetch + insight + notify cycle. Runs in a WorkManager
 * OneTimeWorkRequest enqueued by AlarmReceiver, with NetworkType.CONNECTED and
 * exponential backoff so transient failures (no Wi-Fi at 7am) retry on next connectivity.
 *
 * Outcomes:
 * - Result.success — insight posted (or notification permission denied; we still cached the insight).
 * - Result.retry — transient network / 5xx from OpenMeteo; WorkManager retries with backoff.
 * - Result.failure — non-recoverable HTTP error or unhandled throwable.
 */
class FetchAndNotifyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val app: ClothesCastApplication
        get() = applicationContext as ClothesCastApplication

    override suspend fun doWork(): Result {
        val isCacheOnly = inputData.getBoolean(KEY_CACHE_LOCATION_ONLY, false)
        val isSilent = inputData.getBoolean(KEY_SILENT_REFRESH, false)
        val isPlay = inputData.getBoolean(KEY_PLAY, false)
        val period = inputData.getString(KEY_PERIOD)
            ?.let { runCatching { ForecastPeriod.valueOf(it) }.getOrNull() }
            ?: ForecastPeriod.TODAY
        val startMs = System.currentTimeMillis()
        // The cache-only, silent-refresh, and on-demand play paths aren't
        // scheduled "daily refresh" outcomes — they'd skew the success-rate
        // dashboard with runs the user never perceived as one. (Play may now
        // fetch fresh on a cache miss, but it's still a manual tap, not the
        // alarm, so it stays off the daily_refresh stream.)
        val skipTelemetry = isCacheOnly || isSilent || isPlay
        return try {
            val result = stamped(doWorkInternal())
            // Last snapshot before this returns: WorkManager stops the playback
            // foreground service right after doWork() completes, so anything the
            // teardown clears is still present here. Comparing this against the
            // "after delivery" line pins the disappearance to the teardown.
            logActiveAppNotifications("at worker exit")
            if (!skipTelemetry) recordDailyRefreshOutcome(period, result, startMs)
            result
        } catch (ce: CancellationException) {
            if (!skipTelemetry) {
                Telemetry.logDailyRefresh(
                    slot = slotName(period),
                    outcome = OUTCOME_CANCELLED,
                    latencyMs = System.currentTimeMillis() - startMs,
                )
            }
            throw ce
        }
    }

    // WorkManager marks Result.Success/Failure and getOutputData @RestrictTo its
    // own library group; reading the terminal Result's output data here is
    // intentional and has no public equivalent.
    @Suppress("RestrictedApi")
    private fun recordDailyRefreshOutcome(period: ForecastPeriod, result: Result, startMs: Long) {
        val outcome = when (result) {
            is Result.Success -> {
                // Tonight-disabled and similar early-exit branches stamp
                // KEY_SKIP_TELEMETRY so the dashboard's success rate
                // reflects "did the user actually get a refresh?", not
                // "did the worker happen to return successfully?".
                if (result.outputData.getBoolean(KEY_SKIP_TELEMETRY, false)) return
                OUTCOME_SUCCESS
            }
            is Result.Failure -> classifyDailyRefreshReason(result.outputData.getString(KEY_REASON))
            // Result.retry is non-terminal — WorkManager will re-run us — so leave it off the stream.
            else -> return
        }
        Telemetry.logDailyRefresh(
            slot = slotName(period),
            outcome = outcome,
            latencyMs = System.currentTimeMillis() - startMs,
        )
    }

    private fun slotName(period: ForecastPeriod): String = when (period) {
        ForecastPeriod.TODAY -> "today"
        ForecastPeriod.TONIGHT -> "tonight"
    }

    private suspend fun doWorkInternal(): Result {
        val prefs = try {
            app.settingsRepository.preferences.first()
        } catch (t: Throwable) {
            DiagLog.e(TAG, "Failed to read user preferences; retrying", t)
            return Result.retry()
        }

        // Promote to a media-playback foreground service as early as possible
        // (before the jitter/alignment waits, so it lands inside the
        // exact-alarm FGS-start exemption window) when this scheduled run will
        // speak. Without it, the TTS later can't get audio focus on Android
        // 15+ and is silenced outright on Android 17+ — both because the
        // worker runs in the background. No-op for the play / cache-only /
        // silent paths, which aren't alarm-driven.
        promoteToPlaybackServiceIfNeeded(prefs)

        // On-demand play path: the Today screen's Play button and the
        // Schedule settings "Play now" buttons. Runs the full deliver()
        // fan-out (notification + TTS + MQTT + cast) for the requested
        // period — replaying a fresh cached snapshot when one exists, or
        // fetching fresh when this period has nothing cached, so a tap
        // never silently no-ops.
        if (inputData.getBoolean(KEY_PLAY, false)) {
            val requestedPeriod = inputData.getString(KEY_PERIOD)
                ?.let { runCatching { ForecastPeriod.valueOf(it) }.getOrNull() }
                ?: ForecastPeriod.TODAY
            return playInsight(prefs, requestedPeriod)
        }

        // Cache-only path triggered by the Settings / onboarding location
        // toggle. Resolves the device fix and writes it back via setLocation,
        // then skips the forecast / insight / deliver pipeline so the user
        // doesn't get a duplicate notification (or TTS) when they enable device
        // location later in the day, after the morning run already fired.
        //
        // Exception: when there's no cached insight to duplicate — the common
        // case being the scheduled run having failed for lack of a location
        // ("No location available; failing run"), then the user granting it —
        // there's nothing for the Today screen to show, and it would sit on its
        // empty state until the next alarm. Kick a silent refresh (same
        // no-notification / no-TTS / no-MQTT / no-cast contract) so a forecast
        // actually populates the screen the user just unblocked.
        if (inputData.getBoolean(KEY_CACHE_LOCATION_ONLY, false)) {
            val resolved = resolveLocation(prefs, forceFresh = true)
            if (resolved != null && !hasCachedThisPeriodInsight()) {
                // Route the recovery through the *observed* scheduled-delivery
                // queue (silent = no notification / TTS), not the silent-refresh
                // queue. The scheduled run that failed for lack of a location
                // left a terminal REASON_NO_LOCATION WorkInfo on the observed
                // queue, and the Today banner only suppresses it while the
                // location-action banner is showing — which stops the moment
                // resolveLocation() saves a fallback here. A success on the
                // unobserved silent queue can't supersede that stale failure
                // (TodayViewModel.workStatusFlow doesn't watch it), so the screen
                // would populate the forecast yet still show the "no location"
                // error. enqueueOneShot's REPLACE on the observed queue both
                // clears the stale failure and shows a "Fetching" spinner while
                // it runs.
                val period = currentPeriodForSchedule(prefs)
                DiagLog.i(TAG, "Cache-only refresh resolved a location with nothing cached; kicking $period refresh.")
                enqueueOneShot(applicationContext, silent = true, period = period)
            } else {
                DiagLog.i(TAG, "Cache-only location refresh; skipping insight pipeline.")
            }
            return Result.success()
        }

        val isSilentRefresh = inputData.getBoolean(KEY_SILENT_REFRESH, false)
        // Read wall-clock time once and derive both the date and every
        // schedule-relative decision (period selection + day-offset) from the
        // same instant. Two separate now() reads can straddle the morning cutoff
        // mid-run (e.g. 06:59 → 07:00), which would pick the period for one side
        // of the boundary and the day-offset for the other.
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        // Silent refreshes — app-open, onboarding, and the manual Refresh tap —
        // derive the window from the user's schedule against wall-clock time at
        // *run* time, so they refresh whichever 12-hour window the user is
        // currently in (correcting a snapshot left in the wrong slot after a
        // missed alarm). A scheduled alarm instead carries the period it fired
        // for. The enable toggles gate scheduled *delivery*, not refresh, so the
        // window choice ignores them — the Today screen shows both windows from
        // the cache regardless.
        val period = if (isSilentRefresh) {
            currentPeriodForSchedule(prefs, now.toLocalTime())
        } else {
            inputData.getString(KEY_PERIOD)
                ?.let { runCatching { ForecastPeriod.valueOf(it) }.getOrNull() }
                ?: ForecastPeriod.TODAY
        }

        // True when the current TONIGHT window is the ongoing overnight (the
        // post-midnight tail). Same [now] as the period selection so the two
        // can't disagree across the morning cutoff. See [currentWindowIsOvernight].
        val overnight = currentWindowIsOvernight(prefs, period, now.toLocalTime())

        // The enable toggles gate *scheduled* delivery only: the tonight alarm
        // rearms blindly via AlarmReceiver, so the toggle is honoured here to stop
        // a stale alarm shipping a tonight insight after the user disabled the
        // feature. Silent refreshes (app-open, onboarding, manual Refresh) bypass
        // the gate — they only update the cache the Today screen reads, which
        // shows both windows regardless of the toggles.
        if (period == ForecastPeriod.TODAY && !prefs.dailyEnabled && !isSilentRefresh) {
            DiagLog.i(TAG, "Daily insight is disabled; skipping.")
            return Result.success(workDataOf(KEY_SKIP_TELEMETRY to true))
        }
        if (period == ForecastPeriod.TONIGHT && !prefs.tonightEnabled && !isSilentRefresh) {
            DiagLog.i(TAG, "Tonight insight is disabled; skipping.")
            return Result.success(workDataOf(KEY_SKIP_TELEMETRY to true))
        }

        val location = resolveLocation(prefs)
            ?: run {
                // Two distinct null cases land here, and they want different
                // outcomes:
                //
                //   1. Misconfigured — useDeviceLocation off + no saved
                //      fallback, OR useDeviceLocation on but the user hasn't
                //      granted ACCESS_BACKGROUND_LOCATION yet. The user
                //      has to do something; retrying on backoff would just
                //      hammer the system every 30s with no progress. Fail
                //      with REASON_NO_LOCATION so the Today banner prompts
                //      them to grant permission or pick a city.
                //
                //   2. Transient — useDeviceLocation on, both permissions
                //      granted, location services enabled system-wide, no
                //      saved fallback, but LocationResolver returned null
                //      this time (NETWORK provider returned null, timeout,
                //      momentary signal flake). A later attempt is likely
                //      to succeed, so let WorkManager retry with
                //      exponential backoff rather than burning the period's
                //      forecast. Without a saved fallback this would
                //      otherwise be the only path; with one, resolveLocation
                //      would have used it and we wouldn't be in this branch.
                //
                // The provider-enabled check is what separates "Location
                // services flipped off in system Settings" (user-actionable
                // misconfig — would retry forever otherwise) from the
                // genuinely-transient cases above.
                val transientDeviceFailure = prefs.useDeviceLocation &&
                    prefs.location == null &&
                    hasCoarseLocationPermission(applicationContext) &&
                    hasBackgroundLocationPermission(applicationContext) &&
                    isLocationServicesEnabled(applicationContext)
                if (transientDeviceFailure) {
                    DiagLog.w(TAG, "Device location read failed transiently; retrying.")
                    return Result.retry()
                }
                DiagLog.w(TAG, "No location available; failing run.")
                return Result.failure(reason(REASON_NO_LOCATION))
            }

        // 24h cost cap: if we already generated an insight for today, redeliver it
        // rather than refetching. Same path serves the morning alarm and any
        // "Fire insight now" debug taps later in the day.
        //
        // Silent refreshes (app-open, onboarding, manual Refresh) bypass this
        // cache and pull fresh data: the user either tapped Refresh expecting
        // regeneration, or the stored snapshot is over [SILENT_REFRESH_MIN_AGE]
        // old. Scheduled alarm runs reuse a same-day insight when one's already
        // been generated.
        val cached = if (isSilentRefresh) {
            DiagLog.i(TAG, "Silent refresh; bypassing today's cache.")
            null
        } else {
            runCatching {
                app.insightCache.deliveredForToday(today, period, prefs, diagLog = { DiagLog.i(TAG, it) })
            }.getOrNull()
        }
        if (cached != null) {
            val cachedInsight = cached.insight
            DiagLog.i(TAG, "Using cached $period insight for ${cachedInsight.forDate}.")
            // Mirror the fresh-fetch path: stamp KEY_FETCH_COMPLETE so
            // ScheduledDeliveryService swaps from PREPARING to DELIVERING
            // (mediaPlayback FGS when speech plays). Otherwise the Service
            // sits at "Preparing" through the whole cached delivery, and
            // because isHoldingForegroundFor(id) is true the worker has
            // already skipped its own promoteToPlaybackServiceIfNeeded —
            // background TTS could be silenced on Android 15+.
            setProgress(workDataOf(KEY_FETCH_COMPLETE to true))
            return runCatching { deliver(cachedInsight, prefs, formatProse(cachedInsight, prefs)) }
                .map {
                    recordDailyHistory(cachedInsight)
                    // Redeliver-from-cache still owes both MQTT windows: deliver()
                    // above republished the active period to /now + its segment,
                    // so also push the pre-captured next window (cached in
                    // NEXT_PERIOD by the fresh run that seeded this slot) to its
                    // own segment. Without this a same-day cache hit — common
                    // once a silent refresh has populated the cache — would leave
                    // the other day/night topic stale.
                    //
                    // Guard against a stale sibling: generatePairedInsight() is
                    // best-effort and can fail on the seeding run, leaving an
                    // older NEXT_PERIOD behind while THIS_PERIOD refreshed (only
                    // deliveredForToday() validated THIS_PERIOD's date/period).
                    // Publish only when the cached next snapshot is the expected
                    // opposite window for this date — night same-day after a day
                    // run, tomorrow's day after a night run — else skip rather
                    // than push a stale bundle to the day/night topic.
                    // `today` is the base date deliveredForToday() just confirmed
                    // THIS_PERIOD is for, so the expected next-window date is it
                    // (day run) or the day after (night run -> tomorrow's day).
                    // Also require the cached sibling to be for *this run's*
                    // location: a refresh can update THIS_PERIOD to a new city
                    // while a failed generatePairedInsight() leaves a same-date
                    // sibling for the old one, which we must not publish.
                    val expectedPeriod = period.opposite()
                    val expectedDate = today.plusDays(if (period == ForecastPeriod.TONIGHT) 1L else 0L)
                    val nextSnapshot = runCatching { app.insightCache.nextPeriod.first() }
                        .onFailure { t -> if (t is CancellationException) throw t }
                        .getOrNull()
                    if (nextSnapshot != null &&
                        nextSnapshot.period == expectedPeriod &&
                        nextSnapshot.bundle.today.date == expectedDate &&
                        nextSnapshot.location == location
                    ) {
                        publishNextWindowFromSnapshot(nextSnapshot, prefs)
                    } else {
                        DiagLog.i(
                            TAG,
                            "Skipping next-window MQTT publish on cache hit: NEXT_PERIOD " +
                                "(${nextSnapshot?.period}/${nextSnapshot?.bundle?.today?.date}) isn't the " +
                                "expected $expectedPeriod/$expectedDate sibling for the current location — " +
                                "stale, unrefreshed, or from a previous location.",
                        )
                    }
                    Result.success()
                }
                .getOrElse {
                    if (it is CancellationException) throw it
                    DiagLog.e(TAG, "Cached delivery failed; falling through to fresh generate.", it)
                    fresh(location, prefs, period, overnight)
                }
        }

        return fresh(location, prefs, period, overnight)
    }

    /**
     * On-demand play for [requestedPeriod]. Replays a fresh same-day cached
     * snapshot when one exists; otherwise fetches fresh so a Play tap on an
     * empty (or stale) cache still delivers something rather than silently
     * no-opping. Shared by the Today screen's Play button (always the current
     * window) and the Schedule settings "Play now" buttons (which can target
     * the not-yet-current window).
     */
    private suspend fun playInsight(
        prefs: UserPreferences,
        requestedPeriod: ForecastPeriod,
    ): Result {
        // The Today screen's Play button forces notify + speak; the
        // Schedule "Play now" preview leaves this false to simulate the
        // configured delivery mode (see enqueuePlay).
        val forceNotifyAndSpeak = inputData.getBoolean(KEY_PLAY_FORCE, false)
        val now = LocalDateTime.now()
        val currentPeriod = currentPeriodForSchedule(prefs, now.toLocalTime())
        // The calendar date the [requestedPeriod] cast the user wants is dated
        // for. Accounts for the nightly window wrapping past midnight (see
        // [playTargetDate]): in the post-midnight tail the next daytime cast is
        // *this* morning (today), not tomorrow. The nightly cast is always
        // today's — the ongoing overnight snapshot stays anchored on today.
        val targetDate = playTargetDate(
            requestedPeriod,
            now,
            prefs.schedule.time,
            prefs.tonightSchedule.time,
        )
        // Look for a snapshot the user actually asked to hear: matching period
        // AND the right day. Both cache slots are eligible — THIS_PERIOD holds
        // the current window, NEXT_PERIOD the pre-captured next one, so a
        // "Play now" for the not-yet-current window can hit the pre-capture
        // without a fetch. A snapshot for the wrong day — e.g. this morning's
        // already-delivered daytime cast when the user wants tomorrow's daily —
        // is skipped and falls through to a fresh fetch.
        val cached = listOfNotNull(
            app.insightCache.thisPeriod.first(),
            app.insightCache.nextPeriod.first(),
        ).firstOrNull { it.period == requestedPeriod && it.bundle.today.date == targetDate }

        if (cached != null) {
            // Cache hit replays without fetching; signal "fetch is done"
            // up-front so any banner observer on this queue sees the worker as
            // past the fetching phase. (Play stays disabled throughout
            // delivery via TodayState.anyWorkActive, which keys off
            // WorkInfo.state directly.)
            setProgress(workDataOf(KEY_FETCH_COMPLETE to true))
            val insight = app.deriveInsight(cached, prefs, diagLog = { DiagLog.i(TAG, it) }).insight
            val prose = formatProse(insight, prefs)
            return deliverBestEffort(insight, prefs, prose, "Replayed", forceNotifyAndSpeak)
        }

        // Cache miss — fetch fresh for the requested period.
        val location = resolveLocation(prefs)
        if (location == null) {
            // Best-effort, like the old replay-on-empty-cache: nothing to play,
            // and the Today screen's location-required banner already prompts
            // the fix. No failure banner.
            DiagLog.i(TAG, "Play requested for $requestedPeriod but no location available; skipping.")
            return Result.success(workDataOf(KEY_SKIP_TELEMETRY to true))
        }
        setProgress(workDataOf(KEY_FETCH_COMPLETE to true))
        // When the requested period IS the current window, route through the
        // full fresh() path so the fetched snapshot lands in the cache (+
        // next-window pre-capture, + history): an empty-cache Play populates
        // the Today screen as a side effect. Current-window play carries the same
        // overnight flag the silent refresh would ([currentWindowIsOvernight]) so
        // a post-midnight Play of the ongoing overnight casts the night "now"
        // sits in, matching what the Today screen shows. When it's the *other*
        // window (e.g. "Play now" on Nightly tapped in the morning, or on Daily
        // tapped at night), fetch ephemerally for the right day and DON'T
        // store — fresh() always writes THIS_PERIOD, which would clobber the
        // current window's snapshot the Today screen is showing.
        if (requestedPeriod == currentPeriod) {
            DiagLog.i(TAG, "Play cache miss for current window $requestedPeriod; fetching fresh.")
            return fresh(
                location,
                prefs,
                requestedPeriod,
                currentWindowIsOvernight(prefs, requestedPeriod, now.toLocalTime()),
            )
        }
        // capturedSnapshot supports dayOffset 0 (either period) and 1 (TODAY =
        // tomorrow's daytime). The non-current ephemeral path only ever needs
        // tomorrow's daytime (+1, when Daily is tapped in the evening); every
        // other non-current case is today (0). The overnight is always the
        // *current* window, so it goes through the cache hit or the fresh()
        // branch above, never here (and isn't an ephemeral fetch).
        val fetchDayOffset =
            if (requestedPeriod == ForecastPeriod.TODAY && targetDate.isAfter(now.toLocalDate())) 1 else 0
        DiagLog.i(TAG, "Play cache miss for non-current window $requestedPeriod (+${fetchDayOffset}d); ephemeral fetch.")
        return try {
            val snapshot = capturedSnapshot(location, prefs, requestedPeriod, fetchDayOffset)
            val insight = app.deriveInsight(snapshot, prefs, diagLog = { DiagLog.i(TAG, it) }).insight
            val prose = formatProse(insight, prefs)
            deliverBestEffort(insight, prefs, prose, "Previewed", forceNotifyAndSpeak)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DiagLog.e(TAG, "Play fetch failed for $requestedPeriod.", t)
            Result.success(workDataOf(KEY_SKIP_TELEMETRY to true))
        }
    }

    /**
     * Runs [deliver] for an on-demand play, swallowing delivery failures as a
     * logged success: a Play/Preview that fails to speak shouldn't surface as a
     * refresh failure banner — whatever's on screen is still valid, and the
     * next genuine refresh will retry the pipeline. [verb] tags the success log
     * ("Replayed" / "Previewed").
     *
     * [forceNotifyAndSpeak] carries the Today Play button's "do it now"
     * intent through to [deliver]; the Schedule "Play now" preview passes
     * false so it simulates the configured delivery mode.
     */
    private suspend fun deliverBestEffort(
        insight: Insight,
        prefs: UserPreferences,
        prose: String,
        verb: String,
        forceNotifyAndSpeak: Boolean,
    ): Result = try {
        deliver(insight, prefs, prose, forceNotifyAndSpeak = forceNotifyAndSpeak)
        DiagLog.i(TAG, "$verb insight for ${insight.forDate}: $prose")
        Result.success(workDataOf(KEY_SKIP_TELEMETRY to true))
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        DiagLog.e(TAG, "$verb delivery failed.", t)
        Result.success(workDataOf(KEY_SKIP_TELEMETRY to true))
    }

    private suspend fun fresh(
        location: Location,
        prefs: UserPreferences,
        period: ForecastPeriod,
        overnight: Boolean = false,
    ): Result {
        // Spread alarm-triggered fetches across a small window so multiple
        // devices on the same home IP (alarms armed for the same wall-clock
        // time) don't all hit Open-Meteo at the same instant. Without this,
        // the API answers one device and rate-limits the rest with HTTP 429
        // "Too many concurrent requests" — and re-rolled on every retry,
        // since WorkManager's exponential backoff would otherwise re-sync
        // the collision on each attempt. Force-refresh taps and location-cache
        // runs (KEY_ALARM_FIRED_AT_MS == 0) skip the wait — the user expects
        // immediate work in those paths.
        //
        // Pair with [awaitDeliveryAlignment] in deliver*: jitter spreads the
        // API calls; alignment then resyncs every device's notification post
        // and TTS start to alarm + [DELIVERY_ALIGN_AFTER_ALARM_MS]. The
        // user-visible morning briefing time stays deterministic regardless
        // of how the jitter rolled.
        val alarmFiredAtMs = inputData.getLong(KEY_ALARM_FIRED_AT_MS, 0L)
        if (alarmFiredAtMs != 0L) {
            val jitterMs = Random.Default.nextLong(0L, ALARM_FETCH_JITTER_MS)
            if (jitterMs > 0L) {
                DiagLog.i(TAG, "Jittering alarm-triggered fetch by ${jitterMs}ms.")
                delay(jitterMs)
            }
        }
        return try {
            // Two-phase: capture the snapshot (fetch + read events), then derive
            // the insight from it against current prefs. The snapshot is what
            // lands in the cache so any later settings change re-renders
            // off the same upstream data for free; the derive call here is the
            // one we deliver on this run.
            val snapshot = capturedSnapshot(location, prefs, period, dayOffset = 0, overnight = overnight)
            val insight = app.deriveInsight(snapshot, prefs, diagLog = { DiagLog.i(TAG, it) }).insight
            val isSilentRun = inputData.getBoolean(KEY_SILENT_REFRESH, false)
            runCatching { app.insightCache.store(InsightCache.Slot.THIS_PERIOD, snapshot) }
                .onSuccess {
                    // Push the fresh forecast out to any home-screen widgets
                    // (outfit + feels-like charts). Gated on cache success
                    // because provideGlance() reads from the cache — kicking
                    // updateAll() after a failed write would just re-render the
                    // stale data. Failure is non-blocking; the widgets catch up
                    // on the next successful fetch.
                    updateAllClothesCastWidgets(applicationContext)
                }
                .onFailure { DiagLog.w(TAG, "Insight cache write failed; not blocking delivery.", it) }
            // Also generate and cache the *next* 12-hour window's insight off
            // the same Open-Meteo bundle so the Today screen's pager always
            // surfaces the current + next windows, never a previous one.
            // CachingWeatherRepository keeps the bundle for 1h, so this is a
            // re-render rather than a refetch — no extra network call, no
            // extra Gemini call, no extra TTS.
            //
            // What "next" means depends on which alarm fired:
            //  - Morning (period=TODAY): next is *tonight* of the same date
            //    (the morning fetch already includes the tonight slice for
            //    the evening tie-in).
            //  - Evening (period=TONIGHT): next is *tomorrow's daytime*
            //    (Open-Meteo's `forecast_days=2` response includes tomorrow's
            //    full daily aggregates + hourly, so the day-after-the-alarm
            //    window is fully covered).
            //
            // The pre-rendered insight goes into Slot.NEXT_PERIOD; dedup only
            // consults Slot.THIS_PERIOD so the next morning's fresh fetch
            // isn't suppressed by yesterday-evening's tomorrow-daytime
            // pre-render.
            val pairedSnapshot = generatePairedInsight(location, prefs, period, overnight)
            // Render once per delivery so notification, TTS, and the audit log
            // all share the same string and we don't reconfigure the
            // Configuration-overridden Resources three times per fire.
            val prose = formatProse(insight, prefs)
            // Silent app-open refreshes update the cache (so the Today screen
            // re-renders off fresh data) but skip the notification / TTS /
            // MQTT / cast fan-out — the user is already in the app looking at
            // it, a new banner / chime / cast load on top of that is exactly
            // the surprise this flag exists to avoid.
            if (isSilentRun) {
                DiagLog.i(TAG, "Silent refresh updated cache for ${insight.forDate}: $prose")
            } else {
                // Signal "fetch + cache are done" so the Today screen's
                // working-banner can hide while deliver() handles the
                // alignment wait, notification, and TTS playback. Without
                // this, the banner stays visible until the worker returns
                // — which is after TTS finishes, because deliver() joins
                // on phoneSpeakerJob. TodayViewModel.selectStatus treats
                // an active WorkInfo carrying this progress flag as no
                // longer in the "fetching" phase; a deliver-side failure
                // still surfaces via the terminal FAILED entry.
                setProgress(workDataOf(KEY_FETCH_COMPLETE to true))
                // A current-window Today Play tap with an empty/stale cache
                // lands here (playInsight → fresh). Carry its "notify + speak
                // now" intent through so delivery fires regardless of the
                // mode; scheduled runs and the Schedule preview leave
                // KEY_PLAY_FORCE unset and keep honouring the mode.
                deliver(insight, prefs, prose, forceNotifyAndSpeak = inputData.getBoolean(KEY_PLAY_FORCE, false))
                DiagLog.i(TAG, "Insight delivered for ${insight.forDate}: $prose")
                // With the active period delivered, publish the *next* window's
                // full bundle to its own day/night MQTT segment so a consumer
                // always finds both the current and upcoming cast. Strictly
                // after deliver() returns — nothing here touches the first
                // period's alignment / notification / TTS — and a no-op unless
                // the MQTT bridge is enabled.
                publishNextWindowFromSnapshot(pairedSnapshot, prefs)
            }
            recordDailyHistory(insight)
            Result.success()
        } catch (e: ResponseException) {
            // OpenMeteo 4xx → fail; 5xx → retry with backoff. 429 is the one
            // 4xx we *also* retry — "Too many concurrent requests" is transient
            // by definition and surfaces when several devices on the same home
            // IP wake at 7am together. The per-run jitter above already spreads
            // them, but if a burst still collides we want the backoff to clear
            // it rather than burning today's slot on a permanent failure.
            val status = e.response.status
            when {
                status.value == 429 -> {
                    DiagLog.w(TAG, "Rate-limited by OpenMeteo ($status); retrying.")
                    Result.retry()
                }
                status.value in 500..599 -> {
                    DiagLog.w(TAG, "Server error $status from OpenMeteo; retrying.")
                    Result.retry()
                }
                else -> {
                    DiagLog.e(TAG, "Unexpected HTTP status $status from OpenMeteo", e)
                    Result.failure(reason(REASON_UNEXPECTED_HTTP, "$status"))
                }
            }
        } catch (e: ConnectTimeoutException) {
            DiagLog.w(TAG, "Connect timeout; retrying.", e); Result.retry()
        } catch (e: SocketTimeoutException) {
            DiagLog.w(TAG, "Socket timeout; retrying.", e); Result.retry()
        } catch (e: HttpRequestTimeoutException) {
            DiagLog.w(TAG, "Request timeout; retrying.", e); Result.retry()
        } catch (e: IOException) {
            DiagLog.w(TAG, "Network IO failure; retrying.", e); Result.retry()
        } catch (e: NoTransformationFoundException) {
            // Belt-and-braces for OpenMeteoClient's expectSuccess=true: the
            // gateway occasionally returns a 5xx with a text/html error page,
            // and if the response validator ever doesn't fire (R8 quirk, an
            // un-flagged call site) the JSON deserializer throws this instead
            // of ResponseException. Treat as transient and retry — the
            // alternative is the cryptic Ktor message landing on the failure
            // card.
            DiagLog.w(TAG, "Content-type mismatch from upstream (likely 5xx HTML body); retrying.", e)
            Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            DiagLog.e(TAG, "Unhandled error; failing.", t)
            Result.failure(reason(REASON_UNHANDLED, summarize(t)))
        }
    }

    /**
     * Builds and caches the insight for the *next* 12-hour window after the
     * one the current alarm is delivering, sharing the just-fetched Open-Meteo
     * bundle via [app.clothescast.core.domain.repository.CachingWeatherRepository]'s
     * in-memory cache so this is a re-render rather than a second network
     * call.
     *
     * What "next" means depends on which alarm fired:
     *  - Morning (`primaryPeriod = TODAY`): the next window is tonight of the
     *    same date — generated with `dayOffset = 0`, `period = TONIGHT`.
     *  - Evening (`primaryPeriod = TONIGHT`): the next window is tomorrow's
     *    daytime — generated with `dayOffset = 1`, `period = TODAY`. The
     *    bundle's `forecast_days=2` response carries tomorrow's full daily
     *    aggregates + hourly, so no extra fetch is needed.
     *  - Post-midnight overnight (`primaryPeriod = TONIGHT`, [primaryOvernight]
     *    = true): the primary is the ongoing night, so the next window is
     *    *today's* daytime — `period = TODAY` at `dayOffset = 0`. This is what
     *    makes the 6am pager read "Overnight" + "Today".
     *
     * Silent — no notification, no TTS, no widget update. Failures are
     * logged and swallowed; the Today screen's pager falls back to its
     * placeholder for the NEXT_PERIOD slot in that case.
     */
    private suspend fun generatePairedInsight(
        location: Location,
        prefs: UserPreferences,
        primaryPeriod: ForecastPeriod,
        primaryOvernight: Boolean,
    ): ForecastSnapshot? {
        // The overnight pairs with today's daytime (dayOffset 0); the coming
        // night with tomorrow's (dayOffset 1); the morning with that evening's
        // night (dayOffset 0). The paired window is never itself an overnight.
        val (nextWindowPeriod, dayOffset) = when {
            primaryOvernight -> ForecastPeriod.TODAY to 0
            primaryPeriod == ForecastPeriod.TODAY -> ForecastPeriod.TONIGHT to 0
            else -> ForecastPeriod.TODAY to 1
        }
        return runCatching {
            val snapshot = capturedSnapshot(location, prefs, nextWindowPeriod, dayOffset)
            app.insightCache.store(InsightCache.Slot.NEXT_PERIOD, snapshot)
            DiagLog.i(TAG, "Next-window $nextWindowPeriod snapshot cached for ${snapshot.bundle.today.date}.")
            snapshot
        }.onFailure {
            if (it is CancellationException) throw it
            DiagLog.w(TAG, "Next-window $nextWindowPeriod insight generation failed; not blocking $primaryPeriod delivery.", it)
        }.getOrNull()
    }

    /**
     * Wraps [GenerateDailyInsight.snapshot] with a lookup for yesterday's
     * delivered daytime aggregate, baking the result into the snapshot's
     * [ForecastSnapshot.historicYesterday]. Every downstream consumer
     * (this worker's delivery, the cache redeliver path, the Today screen's
     * reactive re-derive, the format-preview, the home-screen widget) then
     * sees the same record without each having to read from
     * [DailyHistoryStore] themselves. Lookup failures degrade to no historic
     * value — the delta clause falls back to `bundle.yesterday`, which is
     * the legacy behaviour.
     */
    private suspend fun capturedSnapshot(
        location: Location,
        prefs: UserPreferences,
        period: ForecastPeriod,
        dayOffset: Int,
        overnight: Boolean = false,
    ): ForecastSnapshot {
        val raw = app.generateDailyInsight.snapshot(location, prefs, period, dayOffset, overnight)
        val historic = runCatching {
            app.dailyHistoryStore.entryFor(raw.bundle.today.date.minusDays(1))
        }.getOrElse {
            // Preserve structured cancellation. Without this, a WorkManager
            // cancel during the read would be swallowed and the run would
            // fall through to a normal success path — fetching, derivation,
            // delivery and cache writes would still execute under a worker
            // the framework already gave up on. Matches the rethrow pattern
            // every other catch in this file uses.
            if (it is CancellationException) throw it
            DiagLog.w(
                TAG,
                "Daily history read failed; delta will fall back to upstream past-days data.",
                it,
            )
            null
        }
        return raw.copy(historicYesterday = historic)
    }

    /**
     * Persists today's delivered daytime aggregate to [DailyHistoryStore] so
     * tomorrow's delta clause can compare against what we actually told the
     * user about today (see [DailyHistoryEntry]). Only TODAY-period
     * deliveries write: TONIGHT covers the evening slice, which isn't a
     * meaningful "yesterday" comparison for tomorrow's daytime forecast.
     */
    private suspend fun recordDailyHistory(insight: Insight) {
        if (insight.period != ForecastPeriod.TODAY) return
        if (insight.hourly.isEmpty()) return
        val entry = DailyHistoryEntry(
            date = insight.forDate,
            feelsLikeMinC = insight.hourly.minOf { it.feelsLikeC },
            feelsLikeMaxC = insight.hourly.maxOf { it.feelsLikeC },
        )
        runCatching { app.dailyHistoryStore.put(entry) }
            .onFailure {
                if (it is CancellationException) throw it
                DiagLog.w(TAG, "Daily history write failed; tomorrow's delta will fall back to upstream past-days data.", it)
            }
    }

    private fun reason(code: String, detail: String? = null) =
        if (detail.isNullOrBlank()) workDataOf(KEY_REASON to code)
        else workDataOf(KEY_REASON to code, KEY_REASON_DETAIL to detail)

    /**
     * Adds a wall-clock completion timestamp to every terminal Result so the Today
     * screen can pick out the genuinely-most-recent run from WorkManager's history.
     * Without this, [TodayViewModel.selectStatus] has no way to order multiple
     * SUCCEEDED/FAILED entries and a stale failure can mask a fresh success — see
     * the "error persists after it worked" report on PR claude/fix-forecast-api-error.
     *
     * Result.retry() leaves the WorkInfo non-terminal, so it doesn't need stamping;
     * we only annotate success / failure outputs.
     */
    @Suppress("RestrictedApi") // see recordDailyRefreshOutcome: reading restricted Result output is intentional
    private fun stamped(result: Result): Result {
        val now = System.currentTimeMillis()
        return when (result) {
            is Result.Success -> Result.success(result.outputData.merged(KEY_COMPLETED_AT, now))
            is Result.Failure -> Result.failure(result.outputData.merged(KEY_COMPLETED_AT, now))
            else -> result
        }
    }

    private fun Data.merged(key: String, value: Long): Data =
        Data.Builder().putAll(this).putLong(key, value).build()

    // First line of the exception message only — Ktor's NoTransformationFoundException
    // packs the URL, body excerpt, and a FAQ link into a multi-line wall of text.
    // Full stack trace stays in logcat and the on-disk diag log (DiagLog.e above).
    private fun summarize(t: Throwable): String {
        val firstLine = t.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        val joined = if (firstLine.isNullOrEmpty()) t.javaClass.simpleName
        else "${t.javaClass.simpleName}: $firstLine"
        return if (joined.length <= MAX_DETAIL_LEN) joined else joined.take(MAX_DETAIL_LEN - 1) + "…"
    }

    // True when the current-window slot already holds a snapshot. The
    // cache-only refresh uses this to decide whether enabling device location
    // needs to kick a first fetch (empty cache) or can stay a cheap
    // location-only write (a forecast is already on screen). A read failure
    // degrades to "nothing cached" so we err towards fetching rather than
    // leaving the user on a blank Today screen.
    private suspend fun hasCachedThisPeriodInsight(): Boolean =
        runCatching { app.insightCache.thisPeriod.first() }.getOrNull() != null

    private suspend fun resolveLocation(prefs: UserPreferences, forceFresh: Boolean = false): Location? {
        if (prefs.useDeviceLocation) {
            // resolve() catches and DiagLog-warns about the actual failures
            // (SecurityException from a missing background grant, disabled
            // providers, timeouts) — don't double-wrap and lose the cause.
            //
            // forceFresh=true is the user-tapped "Refresh location" path:
            // resolve()'s default 1-hour cache window would silently return
            // the morning alarm's fix even if the user has since moved a
            // few km, defeating the whole point of the button. resolveFresh
            // with FRESH_FIX_MAX_AGE_MS forces a live request when the
            // cached fix is older than 5 minutes — short enough to surface
            // a recent move, long enough that a co-located app's fresh
            // read still counts. If no fresh fix is reachable, fall
            // through to prefs.location (a no-op write here) rather than
            // clobbering the cache with stale coordinates.
            val device = if (forceFresh) {
                app.locationResolver.resolveFresh(LocationResolver.FRESH_FIX_MAX_AGE_MS)
            } else {
                app.locationResolver.resolve()
            }
            if (device != null) {
                DiagLog.i(TAG, "Using device-resolved location at ${device.latitude}, ${device.longitude}.")
                // Best-effort reverse geocode so the home screen can show a
                // friendly city name next to the date instead of the
                // resolver's "Device location" placeholder. Null on AOSP /
                // network failure / nothing useful in the address — the UI
                // falls back to a date-only header in that case.
                val geo = app.reverseGeocoder.resolve(device.latitude, device.longitude)
                // When reverse-geo fails for a fix close to the previously
                // cached one, reuse the cached friendly name rather than
                // clobbering "London" with the placeholder. Without this
                // a single transient geocoder timeout permanently degrades
                // the home screen to the localised "Your location" fallback
                // until reverse-geo next succeeds.
                //
                // The country code intentionally has no proximity fallback:
                // even a 25 km radius straddles real borders (Basel sits at
                // the CH / FR / DE corner; US / Canada border towns are
                // similar), so reusing the prior country whenever the new
                // fix is "nearby" would silently misfilter AUTO holidays
                // after a brief cross-border move. If the geocoder didn't
                // resolve a country this time, we leave it null and AUTO
                // falls back to the locale country until the next
                // successful geocode.
                val resolvedName = geo.city ?: reuseNearbyDisplayName(prefs.location, device)
                val resolved = device.copy(
                    displayName = resolvedName ?: device.displayName,
                    countryCode = geo.countryCode ?: device.countryCode,
                    // Same proximity gate as the displayName fallback: reuse
                    // the cached address detail only when the prior fix is
                    // within ~25km, so a transient geocoder failure after a
                    // move doesn't leave the old neighbourhood / postcode
                    // showing under the new coordinates.
                    addressDetail = geo.addressDetail ?: reuseNearbyAddressDetail(prefs.location, device),
                )
                // Persist the resolved fix as the fallback so the next run can
                // use the most recent good read when the device read fails
                // (provider blip, no fix, services briefly off). With this in
                // place users no longer need to manually pick a city as a
                // safety net — manual entry becomes the explicit override for
                // when auto-detection is wrong. Re-emits to any active
                // SettingsViewModel collector; if the user has Settings open
                // when the morning alarm fires, the displayed location will
                // swap to the freshly-resolved city — acceptable.
                runCatching { app.settingsRepository.setLocation(resolved) }
                    .onFailure { DiagLog.w(TAG, "Failed to cache resolved location.", it) }
                return resolved
            }
            DiagLog.i(TAG, "Device location unavailable; falling back to settings location.")
        }
        return prefs.location
    }

    // Reuse the previously cached displayName when it's a real city (not
    // blank / not the LocationResolver placeholder) and the new device fix
    // is close enough that the cached name is still meaningful. ~25km
    // covers a typical commute / errand radius while still rejecting
    // yesterday's trip to a different city. The country code intentionally
    // doesn't follow the same fallback — see the call site for why.
    private fun reuseNearbyDisplayName(prior: Location?, device: Location): String? {
        if (prior == null) return null
        val priorName = prior.displayName
            ?.takeUnless { it.isBlank() || it == DEVICE_LOCATION_PLACEHOLDER }
            ?: return null
        return if (prior.isWithin(REUSE_LABEL_RADIUS_METERS, of = device)) priorName else null
    }

    // Mirror of [reuseNearbyDisplayName] for the address-detail line: only
    // reuse when the prior fix is within ~25km of the new one, otherwise
    // clear it. Without this gate a transient geocode failure after a move
    // would carry the previous neighbourhood / postcode forward under the
    // new coordinates until a later run produced a fresh detail.
    private fun reuseNearbyAddressDetail(prior: Location?, device: Location): String? {
        if (prior == null) return null
        val priorDetail = prior.addressDetail?.takeUnless { it.isBlank() } ?: return null
        return if (prior.isWithin(REUSE_LABEL_RADIUS_METERS, of = device)) priorDetail else null
    }

    // Mirrors the providers LocationResolver itself queries — NETWORK +
    // PASSIVE only (no GPS hardware fix). When both are off system-wide the
    // user has flipped Location services off in Settings; our retry path
    // would then loop indefinitely with no chance of progress, so callers
    // treat that as misconfiguration and fail visibly instead.
    private fun isLocationServicesEnabled(context: Context): Boolean {
        val manager = context.getSystemService<LocationManager>() ?: return false
        return try {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Resolves the holiday theme for [date] and merges it onto the user's
     * persisted outfit colour customisations, so every off-screen renderer
     * (notification large icon, MQTT outfit card) matches what the Today screen
     * draws on themed days — birthday yellows / Christmas reds, plus the
     * tricolour stroke accents on themes like July 4 / Bastille Day. Shared by
     * [deliver] and [publishPairedPeriodToMqtt]; the latter passes the next
     * window's own date so a Dec-24 evening run themes the Dec-25 day bundle by
     * Dec 25, not by the run's wall-clock day. Gloves, the umbrella, and the
     * rain jacket aren't holiday-themed, so those take the user's picked colour
     * only.
     */
    private suspend fun resolveThemedOutfitColors(
        prefs: UserPreferences,
        date: LocalDate = LocalDate.now(prefs.schedule.zoneId),
    ): ThemedOutfitColors {
        val theme = resolveHolidayTheme(prefs, app.calendarEventReader, today = date)
        return ThemedOutfitColors(
            theme = theme,
            topColors = prefs.outfitTopColors + (theme?.topOverrides ?: emptyMap()),
            bottomColors = prefs.outfitBottomColors + (theme?.bottomOverrides ?: emptyMap()),
            handsColors = prefs.outfitHandsColors,
            carriedColors = prefs.outfitCarriedColors,
            outerColors = prefs.outfitOuterColors,
            topStrokes = theme?.topStrokeOverrides ?: emptyMap(),
            bottomStrokes = theme?.bottomStrokeOverrides ?: emptyMap(),
        )
    }

    private data class ThemedOutfitColors(
        val theme: HolidayTheme?,
        val topColors: Map<OutfitSuggestion.Top, Long>,
        val bottomColors: Map<OutfitSuggestion.Bottom, Long>,
        val handsColors: Map<OutfitSuggestion.Hands, Long>,
        val carriedColors: Map<OutfitSuggestion.Carried, Long>,
        val outerColors: Map<OutfitSuggestion.Outer, Long>,
        val topStrokes: Map<OutfitSuggestion.Top, Long>,
        val bottomStrokes: Map<OutfitSuggestion.Bottom, Long>,
    )

    /**
     * Publishes the *next* window's full bundle (prose + outfit card + TTS
     * audio + muxed video) to its own day/night MQTT segment — never to
     * `/now`, which stays on the period the run just delivered. Called strictly
     * after the active period's [deliver] returns, so nothing here can delay
     * the current period's alignment, notification, or speech.
     *
     * MQTT is the only consumer of this bundle, so the whole thing — including
     * the Gemini TTS synth that crosses the device boundary — is gated on the
     * bridge being enabled: with the bridge off this is a no-op and no extra
     * prose leaves the device. Audio (and therefore video) is only produced
     * when Gemini can synth; device TTS yields no WAV bytes, matching the
     * active path's behaviour for the audio/video topics.
     */
    private suspend fun publishPairedPeriodToMqtt(insight: Insight, prefs: UserPreferences) {
        if (!isMqttPublishable(prefs)) return
        runCatching {
            // Theme by the next window's own date, not the run's wall-clock day,
            // so the cross-day evening->tomorrow bundle gets tomorrow's holiday
            // theme / voice.
            val colors = resolveThemedOutfitColors(prefs, insight.forDate)
            val prose = formatProse(insight, prefs)
            val png = withContext(Dispatchers.Default) {
                renderOutfitPngIfPossible(
                    insight = insight,
                    prefs = prefs,
                    prose = prose,
                    topColors = colors.topColors,
                    bottomColors = colors.bottomColors,
                    handsColors = colors.handsColors,
                    carriedColors = colors.carriedColors,
                    outerColors = colors.outerColors,
                    topStrokes = colors.topStrokes,
                    bottomStrokes = colors.bottomStrokes,
                )
            }
            val geminiAvailable = isGeminiEngineSelected(prefs) && (
                runCatching { app.secureKeyStore.geminiKeyConfiguredFlow.first() }.getOrDefault(false) ||
                    app.sharedTtsAvailable
                )
            val wav: ByteArray? = if (geminiAvailable) {
                withContext(Dispatchers.IO) {
                    synthesizeForDelivery(insight, prefs, colors.theme)?.let { WavEncoder.encode(it) }
                }
            } else null
            val video: ByteArray? = if (png != null && wav != null) {
                withContext(Dispatchers.Default) {
                    runCatching { Mp4Encoder.encode(png, wav) }
                        .onFailure { t ->
                            if (t is CancellationException) throw t
                            DiagLog.w(TAG, "Next-window MQTT video mux failed; publishing bundle without video.", t)
                        }
                        .getOrNull()
                }
            } else null
            app.mqttPublisher.publishIfEnabled(
                insight.period,
                prose,
                image = png,
                audio = wav,
                video = video,
                mirrorToNow = false,
            )
            DiagLog.i(
                TAG,
                "Next-window ${insight.period} MQTT bundle published " +
                    "(image=${png != null}, audio=${wav != null}, video=${video != null}).",
            )
        }.onFailure { t ->
            if (t is CancellationException) throw t
            DiagLog.w(TAG, "Next-window MQTT publish failed.", t)
        }
    }

    /**
     * Derives [snapshot] into the next window's insight and publishes its
     * bundle via [publishPairedPeriodToMqtt]. Null snapshot or a derive failure
     * is logged and skipped — the next window's MQTT segment keeps its last
     * value rather than failing the run. Shared by the fresh-fetch path (which
     * passes the snapshot it just captured) and the same-day cache-hit redeliver
     * path (which passes the pre-captured [InsightCache.Slot.NEXT_PERIOD]
     * snapshot), so both honour the "each scheduled run publishes both windows"
     * contract.
     */
    private suspend fun publishNextWindowFromSnapshot(snapshot: ForecastSnapshot?, prefs: UserPreferences) {
        val insight = snapshot?.let {
            runCatching { app.deriveInsight(it, prefs).insight }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    DiagLog.w(TAG, "Next-window insight derive failed; skipping its MQTT publish.", t)
                }
                .getOrNull()
        } ?: return
        publishPairedPeriodToMqtt(insight, prefs)
    }

    /**
     * @param forceNotifyAndSpeak the user tapped the Today screen's Play
     *   button, an explicit "do it now" that posts the notification *and*
     *   speaks regardless of the period's delivery mode — a SILENT /
     *   notification-only user still sees and hears a tapped forecast.
     *   Defaults to false so scheduled runs and the Schedule "Play now"
     *   preview (which deliberately simulates the configured mode) honour
     *   the delivery mode exactly as before.
     */
    private suspend fun deliver(
        insight: Insight,
        prefs: UserPreferences,
        prose: String,
        forceNotifyAndSpeak: Boolean = false,
    ) {
        // Apply today's holiday theme on top of the user's persisted outfit
        // colours (see resolveThemedOutfitColors). Destructured here so the
        // existing call sites below read the same locals as before; the same
        // helper feeds the next-window MQTT publish so both windows render
        // identically.
        val (
            theme, topColors, bottomColors, handsColors,
            carriedColors, outerColors, topStrokes, bottomStrokes,
        ) = resolveThemedOutfitColors(prefs)

        // Compute every "should this fire?" decision from one snapshot
        // of prefs so the pre- and post-alignment fan-outs stay
        // consistent and the gate algebra is unit-testable (see
        // DeliveryGatesTest in :core:domain).
        // Gemini is reachable from the worker if the user picked it AND either
        // has their own key set (BYOK → direct to Google) or the shared-key
        // proxy is available on this build (Cloud Function holds the dev key).
        // Without either, fall back to device TTS.
        val geminiAvailable = isGeminiEngineSelected(prefs) && (
            runCatching { app.secureKeyStore.geminiKeyConfiguredFlow.first() }.getOrDefault(false) ||
                app.sharedTtsAvailable
            )
        val gates = computeDeliveryGates(
            prefs = prefs,
            period = insight.period,
            insightHasEvents = insight.hasEvents,
            geminiAvailable = geminiAvailable,
            mqttPublishable = isMqttPublishable(prefs),
            forcePhoneSpeech = forceNotifyAndSpeak,
        )

        // Two-phase fan-out per SPEC.md §Sequencing:
        //
        //  Pre-alignment — two concurrent tracks (synth + render).
        //    Both finish before the alignment barrier so the
        //    post-alignment steps are user-visible at a predictable
        //    moment.
        //
        //  Alignment barrier — awaitDeliveryAlignment(): pause until
        //    ~60 s past the alarm-fire timestamp so a multi-device
        //    household sees / hears the new forecast at the same
        //    wall-clock instant.
        //
        //  Post-alignment — notification, MQTT prose / image / audio /
        //    video, and the phone speaker fire in parallel. The MQTT publishes
        //    moved here from pre-alignment so HA's "speak the prose when
        //    the wardrobe opens" automation fires with the phone
        //    notification, not 30 s ahead of it.
        //
        // supervisorScope so one destination's failure (e.g. MQTT broker
        // unreachable) doesn't cancel the siblings. Each launch /
        // async runs its own runCatching; CancellationException
        // propagates so WorkManager stops unwind cleanly.
        supervisorScope {
            val synthDeferred: Deferred<PcmAudio?>? = if (gates.needsSynth) {
                async(Dispatchers.IO) { synthesizeForDelivery(insight, prefs, theme) }
            } else null

            val renderDeferred: Deferred<ByteArray?> = async(Dispatchers.Default) {
                renderOutfitPngIfPossible(
                    insight = insight,
                    prefs = prefs,
                    prose = prose,
                    topColors = topColors,
                    bottomColors = bottomColors,
                    handsColors = handsColors,
                    carriedColors = carriedColors,
                    outerColors = outerColors,
                    topStrokes = topStrokes,
                    bottomStrokes = bottomStrokes,
                )
            }

            val pcm: PcmAudio? = synthDeferred?.await()
            val png: ByteArray? = renderDeferred.await()
            val wav: ByteArray? = pcm?.let { WavEncoder.encode(it) }

            // Mux the outfit card + TTS audio into a single MP4 for the MQTT
            // video topic, so a consumer (e.g. Home Assistant) can hand one
            // media item to a Cast receiver / Home Hub and get the card and
            // the announcement together. Muxing runs during the alignment
            // wait below to stay off the post-alignment hot path, and only
            // when the bridge will publish and both inputs exist. A mux
            // failure degrades to a video-less bundle rather than failing the
            // whole publish.
            val mqttVideoDeferred: Deferred<ByteArray?>? =
                if (gates.mqttPublishable && png != null && wav != null) {
                    async(Dispatchers.Default) {
                        runCatching { Mp4Encoder.encode(png, wav) }
                            .onFailure { t ->
                                if (t is CancellationException) throw t
                                DiagLog.w(TAG, "MQTT video mux failed; publishing bundle without video.", t)
                            }
                            .getOrNull()
                    }
                } else null

            awaitDeliveryAlignment()

            // Guard the launch body: a child `launch` failing inside a
            // supervisorScope doesn't fail the scope — its exception goes to
            // the (unset) CoroutineExceptionHandler and would kill the
            // process. A notification post can throw for real (bitmap render,
            // a SecurityException when POST_NOTIFICATIONS is revoked between
            // the permission check and the post), and best-effort-per-
            // destination is this scope's documented policy.
            val notifyJob = launch {
                runCatching { postPeriodNotification(insight, prefs, prose, topColors, topStrokes, handsColors, outerColors, gates, forceNotify = forceNotifyAndSpeak) }
                    .onFailure { t ->
                        if (t is CancellationException) throw t
                        DiagLog.w(TAG, "Posting the period notification failed.", t)
                    }
            }

            // Cast load — runs in parallel with notification + MQTT.
            // The phone speaker awaits this when castWillHaveAudio so
            // `castSkipPhoneSpeech` can actually suppress double audio;
            // when willCast is false or the cast has no real audio
            // (image-only fallback), phone speech fires immediately.
            val castDeferred: Deferred<CastInsightController.CastWorkerOutcome>? =
                if (gates.willCast && !gates.emptyEveningSkip) async {
                    castDestination(insight, prefs, wav = wav, png = png)
                } else null

            // Prose-publish outcome drives the Smart Home settings status
            // row; the bundle publish handles prose + image + audio for the
            // period and then mirrors the lot to /now in a coordinated
            // order (image/audio first, text last) so an HA automation
            // triggered on `/now/text` sees a consistent `/now/image` /
            // `/now/audio` from the same forecast rather than a stale
            // payload from the previous period.
            val mqttDeferred: Deferred<MqttPublishOutcome?> = async {
                if (!gates.mqttPublishable) null
                else runCatching {
                    val video = mqttVideoDeferred?.await()
                    app.mqttPublisher.publishIfEnabled(
                        insight.period,
                        prose,
                        image = png,
                        audio = wav,
                        video = video,
                    )
                }
                    .onFailure { t ->
                        if (t is CancellationException) throw t
                        DiagLog.w(TAG, "MQTT insight bundle publish failed.", t)
                    }
                    .getOrNull()
            }

            // Same guard as notifyJob: parts of the speaker path (utterance
            // build, the cast-outcome await's rethrow) run outside
            // playPhoneSpeaker's internal guards, and an unhandled child
            // failure here would down the process, not just this destination.
            val phoneSpeakerJob = launch {
                runCatching {
                    playPhoneSpeaker(
                        insight, prefs, gates, pcm,
                        wav = wav,
                        castDeferred = castDeferred,
                        mqttDeferred = mqttDeferred,
                        theme = theme,
                    )
                }.onFailure { t ->
                    if (t is CancellationException) throw t
                    DiagLog.w(TAG, "Phone speaker playback failed.", t)
                }
            }

            notifyJob.join()
            phoneSpeakerJob.join()
            val mqttOutcome = mqttDeferred.await()
            // Only persist a cast outcome when we actually attempted
            // one — same condition that gates castDeferred above.
            // An empty-evening tonight skip with a picked route still
            // has willCast = true, but the cast was intentionally not
            // attempted; recording a "failure" here would surface a
            // false error in Settings on every such skipped run.
            val castOutcome = castDeferred?.await()
            if (castOutcome != null) {
                runCatching {
                    val (error, publishedAt, fetchedAt) = castOutcomeToResult(castOutcome)
                    app.settingsRepository.setCastResult(
                        errorMessage = error,
                        publishedAtMs = publishedAt,
                        fetchedAtMs = fetchedAt,
                    )
                }.onFailure { t ->
                    // Rethrow cancellation (mid-DataStore.edit) like
                    // persistQuotaStatus does; log other write failures —
                    // a stale status row isn't worth failing delivery over.
                    if (t is CancellationException) throw t
                    DiagLog.w(TAG, "Failed to persist the cast status.", t)
                }
            }

            // Status persistence is best-effort: a DataStore I/O
            // failure here must not surface as a deliver() exception,
            // which would cause the cached-delivery path to fall
            // through to a fresh fetch and duplicate the user-facing
            // notification.
            runCatching {
                when (mqttOutcome) {
                    null, is MqttPublishOutcome.NotConfigured -> Unit
                    is MqttPublishOutcome.Success -> app.settingsRepository.setMqttLastError(null)
                    is MqttPublishOutcome.Failure -> app.settingsRepository.setMqttLastError(mqttOutcome.message)
                }
            }.onFailure { t ->
                if (t is CancellationException) throw t
                DiagLog.w(TAG, "Failed to persist the MQTT publish status.", t)
            }

            logActiveAppNotifications("after delivery")
        }
    }

    /**
     * Synthesises insight prose via Gemini TTS for the pre-alignment
     * synth track. Returns null on failure so downstream consumers
     * (MQTT audio, phone speaker) can degrade gracefully: the bridge
     * skips its audio publish, the phone speaker falls back to the
     * device engine.
     *
     * A single transient network blip — socket timeout, dropped
     * connection, a 429 / 5xx from the shared-key proxy — shouldn't
     * downgrade the whole briefing to the device engine; the user
     * picked Gemini. Retry [GEMINI_TTS_SYNTH_ATTEMPTS] times with a
     * short exponential backoff before giving up. The synth runs
     * pre-alignment, concurrently with the outfit render and ahead of
     * [awaitDeliveryAlignment], so these waits are usually absorbed by
     * the alignment barrier rather than delaying delivery. Permanent
     * failures for this run (spent daily quota, blocked prompt, a
     * non-retryable HTTP 4xx) skip the retries via
     * [isRetryableGeminiTtsFailure] and fall back immediately. Logs each
     * failure with enough context to diagnose without spamming successes.
     */
    private suspend fun synthesizeForDelivery(
        insight: Insight,
        prefs: UserPreferences,
        theme: HolidayTheme?,
    ): PcmAudio? {
        val utterance = ttsUtterance(insight, prefs, theme)
        // On a themed day with no deliberate persona pick, speak in the
        // holiday's voice (Father Christmas on Dec 25, a president on
        // Presidents' Day, …) and switch to a matching-gender voice.
        val selection = resolveHolidayVoice(theme?.id, prefs.geminiVoice, prefs.ttsStyle)
        val speaker = GeminiTtsSpeaker(
            app.geminiTtsClient,
            voiceName = selection.voiceName,
            style = selection.style,
        )

        var lastFailure: Throwable? = null
        for (attempt in 0 until GEMINI_TTS_SYNTH_ATTEMPTS) {
            try {
                val pcm = speaker.synthesize(utterance.text, utterance.locale)
                // A successful synth means the shared-key daily allowance isn't
                // exhausted (or the user added their own key), so retire any
                // Today limit card. Best-effort: a failed write just leaves the
                // card up until the next success.
                persistQuotaStatus { app.settingsRepository.clearGeminiTtsLimitExceeded() }
                return pcm
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastFailure = t
                if (!isRetryableGeminiTtsFailure(t)) break
                if (attempt < GEMINI_TTS_SYNTH_ATTEMPTS - 1) {
                    val backoffMs = GEMINI_TTS_SYNTH_BACKOFF_MS shl attempt
                    DiagLog.w(
                        TAG,
                        "Gemini TTS synth attempt ${attempt + 1}/$GEMINI_TTS_SYNTH_ATTEMPTS " +
                            "failed; retrying in ${backoffMs}ms.",
                        t,
                    )
                    delay(backoffMs)
                }
            }
        }

        // Every attempt failed, or a permanent failure broke the loop early.
        val failure = lastFailure
        if (failure is GeminiTtsDailyQuotaExhaustedException) {
            // The free shared-key allowance is spent for today. The cast
            // still plays via the device engine below; record the limit so
            // the Today screen can nudge the user toward Speech settings to
            // add their own Gemini key for unlimited casts. Persist the
            // reset time so the card auto-expires at the daily boundary
            // even before a later synth clears it.
            persistQuotaStatus {
                app.settingsRepository.setGeminiTtsLimitExceeded(
                    resetAtMs = quotaResetAtMs(failure.resetAtUtc),
                )
            }
        }
        DiagLog.w(TAG, "Gemini TTS synth failed; downstream audio destinations degrade.", failure)
        return null
    }

    /**
     * Best-effort persistence of the Gemini TTS quota status (the limit-card
     * set/clear). Rethrows [CancellationException] so a worker cancellation
     * mid-`DataStore.edit` still unwinds the delivery path rather than being
     * swallowed (per the repo's structured-concurrency guidance); any other
     * write failure is logged and ignored — a dropped status write just leaves
     * the Today card slightly stale, not worth failing delivery over.
     */
    private suspend fun persistQuotaStatus(block: suspend () -> Unit) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Failed to persist Gemini TTS quota status.", t)
        }
    }

    /**
     * Epoch-ms at which the shared free TTS allowance next opens. Prefers the
     * proxy's ISO-8601 `resetAtUtc`; when it's absent or unparseable, falls
     * back to the next UTC midnight — the free budget is a per-UTC-day quota,
     * so that's when a fresh slot is guaranteed. Used to auto-expire the Today
     * limit card at the reset boundary.
     */
    private fun quotaResetAtMs(resetAtUtc: String?): Long {
        resetAtUtc?.let { iso ->
            runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()?.let { return it }
        }
        return Instant.now()
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Renders the outfit card PNG for the pre-alignment render track.
     * Same render the MQTT image publish has always used; returns
     * null when there's no outfit (e.g. early-morning fetch with no
     * recommendation) or when the renderer throws. The MQTT image
     * publish is the only consumer in PR1; the cast destination joins
     * in PR2.
     */
    private fun renderOutfitPngIfPossible(
        insight: Insight,
        prefs: UserPreferences,
        prose: String,
        topColors: Map<OutfitSuggestion.Top, Long>,
        bottomColors: Map<OutfitSuggestion.Bottom, Long>,
        handsColors: Map<OutfitSuggestion.Hands, Long>,
        carriedColors: Map<OutfitSuggestion.Carried, Long>,
        outerColors: Map<OutfitSuggestion.Outer, Long>,
        topStrokes: Map<OutfitSuggestion.Top, Long>,
        bottomStrokes: Map<OutfitSuggestion.Bottom, Long>,
    ): ByteArray? {
        val outfit = insight.outfit ?: return null
        return runCatching {
            val formatter = InsightFormatter.forRegion(applicationContext, prefs.region)
            val info = outfitCardInfoLines(
                context = applicationContext,
                formatter = formatter,
                hourly = insight.hourly,
                temperatureUnit = prefs.temperatureUnit,
                windSpeedUnit = prefs.distanceUnit.windSpeedUnit(),
            )
            val header = applicationContext.getString(
                if (insight.period == ForecastPeriod.TODAY) R.string.outfit_card_header_today
                else R.string.outfit_card_header_tonight,
            )
            renderOutfitCard(
                context = applicationContext,
                outfit = outfit,
                header = header,
                prose = prose,
                info = info,
                topColors = topColors,
                bottomColors = bottomColors,
                handsColors = handsColors,
                carriedColors = carriedColors,
                outerColors = outerColors,
                topStrokes = topStrokes,
                bottomStrokes = bottomStrokes,
            )
        }
            .onFailure { t ->
                if (t is CancellationException) throw t
                DiagLog.w(TAG, "Outfit render failed; MQTT image publish skipped.", t)
            }
            .getOrNull()
    }

    /**
     * Hands the pre-rendered media to [CastInsightController] for the
     * load. The controller resolves the picked route's device class and
     * sends a display the muxed image+audio MP4, a speaker the bare
     * spoken-forecast WAV. The cast destination follows the same
     * "fire-and-forget but capture outcome" pattern as MQTT: the
     * controller swallows all failures into [CastWorkerOutcome], so
     * a route-not-found or load-rejected case doesn't cancel sibling
     * destinations.
     *
     * When the synth buffer is null (Gemini unavailable or synth failed
     * pre-alignment), feeds [CastInsightController.silentWavStub] as the
     * loading carrier and flags `hasRealAudio = false`. On a display the
     * receiver still shows the outfit PNG, just without speaking
     * (SPEC.md's image-only fallback); on a speaker there's nothing to
     * play, so the controller skips the load (SkippedNoAudio).
     */
    private suspend fun castDestination(
        insight: Insight,
        prefs: UserPreferences,
        wav: ByteArray?,
        png: ByteArray?,
    ): CastInsightController.CastWorkerOutcome {
        val controller = app.castInsightController
            ?: return CastInsightController.CastWorkerOutcome.Failed("Cast unavailable on this device")
        val routeId = prefs.castRouteId
            ?: return CastInsightController.CastWorkerOutcome.Failed("No smart device picked")
        // A speaker plays audio only, so a missing outfit image mustn't
        // block an otherwise-playable spoken forecast — the controller
        // requires the PNG only on the display (MP4) branch. Pass it
        // through nullable and let the device class decide.
        // Silent stub keeps Default Media Receiver happy on the display
        // image-only path — the receiver won't load with no audio media.
        val wavBytes = wav ?: CastInsightController.silentWavStub
        return controller.castWithPreparedMedia(
            routeId = routeId,
            wav = wavBytes,
            hasRealAudio = wav != null,
            png = png,
            title = applicationContext.getString(R.string.app_name),
            subtitle = insight.location?.displayName,
        )
    }

    /**
     * Maps a cast outcome onto the (error, publishedAt, fetchedAt) triple
     * the Settings → Smart Home → Cast status row reads. Carries enough
     * state for the row to differentiate "we never sent the load" from
     * "we sent it but the display didn't fetch the bytes":
     *
     *  - Success → no error, both timestamps advance.
     *  - PublishedButNotFetched → error explains why nothing played; the
     *    publishedAt timestamp still advances (we DID send the load) but
     *    fetchedAt does not (the bytes never transferred).
     *  - SkippedNoRoute / Failed → error only, neither timestamp moves.
     *
     * Only called after a cast was actually attempted — callers gate on
     * a non-null outcome.
     */
    private fun castOutcomeToResult(
        outcome: CastInsightController.CastWorkerOutcome,
        nowMs: Long = System.currentTimeMillis(),
    ): Triple<String?, Long?, Long?> = when (outcome) {
        is CastInsightController.CastWorkerOutcome.Success ->
            Triple(null, nowMs, nowMs)
        is CastInsightController.CastWorkerOutcome.PublishedButNotFetched ->
            Triple(outcome.reason, nowMs, null)
        is CastInsightController.CastWorkerOutcome.SkippedNoRoute ->
            Triple("Smart device not reachable", null, null)
        is CastInsightController.CastWorkerOutcome.SkippedNoAudio ->
            Triple("Add a Gemini voice to cast to a speaker", null, null)
        is CastInsightController.CastWorkerOutcome.Failed ->
            Triple(outcome.reason, null, null)
    }

    private fun postPeriodNotification(
        insight: Insight,
        prefs: UserPreferences,
        prose: String,
        topColors: Map<OutfitSuggestion.Top, Long>,
        topStrokes: Map<OutfitSuggestion.Top, Long>,
        handsColors: Map<OutfitSuggestion.Hands, Long>,
        outerColors: Map<OutfitSuggestion.Outer, Long>,
        gates: app.clothescast.core.domain.usecase.DeliveryGates,
        forceNotify: Boolean = false,
    ) {
        if (gates.emptyEveningSkip) {
            DiagLog.i(TAG, "Tonight insight has no events and notify-only-on-events is on; skipping notification.")
            return
        }
        val mode = when (insight.period) {
            ForecastPeriod.TODAY -> prefs.deliveryMode
            ForecastPeriod.TONIGHT -> prefs.tonightDeliveryMode
        }
        // forceNotify: the Today screen's Play button posts the notification
        // even on SILENT / TTS-only — an explicit tap means "show it now."
        val canNotify = forceNotify || mode.postsNotification
        if (!canNotify) return
        when (insight.period) {
            ForecastPeriod.TODAY ->
                app.insightNotifier.notify(insight, prose, topColors, topStrokes, handsColors, outerColors)
            ForecastPeriod.TONIGHT ->
                app.tonightInsightNotifier.notify(insight, prose, topColors, topStrokes, handsColors, outerColors)
        }
        recordDeliveryDelay(insight.period)
    }

    /**
     * Plays the spoken briefing on the phone speaker (or skips it).
     * Reads the pre-synthesised [pcm] when available: on the Gemini
     * engine that's a same-buffer playback with no synth latency at
     * the post-alignment moment. On the Device engine, or when synth
     * failed, falls back to [app.clothescast.tts.AndroidTtsSpeaker]
     * which synths + plays in one call. The audio focus block wraps
     * only the playback — the synth ran pre-alignment, outside any
     * focus claim.
     *
     * When the cast destination is carrying real audio (defined as
     * `gates.willCast && wav != null` — willCast may be true with no
     * buffer if the synth track failed), the speaker awaits the cast
     * outcome before deciding whether to play: a successful
     * audio-carrying cast + `castSkipPhoneSpeech` suppresses the
     * phone speaker (the smart display is the speaker for this run).
     * Image-only casts don't suppress phone speech — the display
     * isn't doing the audio.
     */
    private suspend fun playPhoneSpeaker(
        insight: Insight,
        prefs: UserPreferences,
        gates: app.clothescast.core.domain.usecase.DeliveryGates,
        pcm: PcmAudio?,
        wav: ByteArray?,
        castDeferred: Deferred<CastInsightController.CastWorkerOutcome>?,
        mqttDeferred: Deferred<MqttPublishOutcome?>,
        theme: HolidayTheme?,
    ) {
        if (gates.emptyEveningSkip) {
            DiagLog.i(TAG, "Tonight insight has no events and notify-only-on-events is on; skipping TTS.")
            return
        }
        if (!gates.phoneTtsConfigured) return

        // Cast suppression: only when this cast is genuinely playing
        // audio (willCast AND a synth buffer exists). An image-only
        // cast (Gemini unavailable / synth failed → silent WAV stub)
        // doesn't trigger suppression — the smart display isn't the
        // speaker in that path. gates.castSuppressesPhone folds in
        // castSkipPhoneSpeech and the forced-Play override: a tapped
        // Play always speaks on this phone even when a cast is playing.
        val castHasAudio = gates.castSuppressesPhone && wav != null
        if (castHasAudio && castDeferred != null) {
            val castOutcome = castDeferred.await()
            if (castOutcome is CastInsightController.CastWorkerOutcome.Success) {
                DiagLog.i(TAG, "Phone speech suppressed — smart display is playing the forecast.")
                return
            }
        }

        // MQTT suppression: mirror of the cast block above. The MQTT
        // bridge publishes the rendered audio to the broker; we trust
        // the user's HA-side automation to play it. Only suppresses
        // when the publish included audio (synth succeeded) — without
        // a buffer the broker has nothing to speak, so the phone needs
        // to. gates.mqttSuppressesPhone folds in mqttSkipPhoneSpeech and
        // the forced-Play override, so a tapped Play isn't silenced just
        // because the bridge published the forecast.
        val mqttHasAudio = gates.mqttSuppressesPhone && wav != null
        if (mqttHasAudio) {
            val mqttOutcome = mqttDeferred.await()
            if (mqttOutcome is MqttPublishOutcome.Success) {
                DiagLog.i(TAG, "Phone speech suppressed — MQTT bridge published the forecast.")
                return
            }
        }

        val utterance = ttsUtterance(insight, prefs, theme)
        withSpeechAudioFocus(applicationContext) {
            if (prefs.ttsEngine == TtsEngine.GEMINI && pcm != null) {
                runCatching {
                    GeminiTtsSpeaker(
                        app.geminiTtsClient,
                        voiceName = prefs.geminiVoice,
                        style = prefs.ttsStyle,
                    ).play(pcm)
                }.onFailure { t ->
                    if (t is CancellationException) throw t
                    DiagLog.w(TAG, "Gemini playback failed; insight is still posted as notification.", t)
                }
                return@withSpeechAudioFocus
            }
            // Device engine, or Gemini synth failed pre-alignment.
            // Either way, on-device TTS synthesises at playback time
            // and gets the user audio without burning another Gemini
            // call.
            runCatching {
                app.deviceTtsSpeaker(prefs.deviceVoice).speak(utterance.text, utterance.locale)
            }.onFailure { t ->
                if (t is CancellationException) throw t
                DiagLog.w(TAG, "Device TTS failed; insight is still posted as notification.", t)
            }
        }
    }

    /**
     * Holds the worker until [DELIVERY_ALIGN_AFTER_ALARM_MS] past the alarm-fire
     * timestamp before posting the notification or speaking TTS. Two reasons:
     *
     *  - Originally: the spoken briefing must not overlap the ringing alarm.
     *    Alarm audio uses STREAM_ALARM which bypasses AudioFocus entirely, so
     *    focus-based ducking has no effect on alarm volume; a time-based gap is
     *    the only reliable approach.
     *  - Added later: deterministic delivery time across a multi-device home.
     *    The per-run jitter in [fresh] spreads the API calls across a
     *    [ALARM_FETCH_JITTER_MS] window so devices on the same IP don't
     *    rate-limit each other; we then realign here so every device's
     *    notification posts (and TTS starts) at the same wall-clock moment.
     *
     * The target time is derived from [KEY_ALARM_FIRED_AT_MS] set by [AlarmReceiver].
     * If the key is absent (force-refresh tap, location-cache run) or the target has
     * already passed (slow fetch, retry backoff), the wait is skipped and delivery
     * starts immediately.
     */
    /**
     * Promote a scheduled, speaking run to a media-playback foreground service
     * so its TTS can actually be heard. Two background-audio restrictions make
     * this necessary on modern Android, both because [FetchAndNotifyWorker]
     * runs in the background:
     *  - Android 15+ (API 35) refuses audio focus to apps that aren't the top
     *    app or running a foreground service (`AUDIOFOCUS_REQUEST_FAILED`).
     *  - Android 17+ goes further and silences background `AudioTrack` writes
     *    entirely — so even "speak without focus" produces nothing.
     *
     * Only the alarm-driven path qualifies: a foreground "Play" tap already
     * runs while the app is the top app (focus works without a service), and
     * the exact-alarm exemption that lets us *start* a foreground service from
     * the background only covers the alarm fire. We also skip it when the
     * period's delivery mode won't speak, so notification-only users don't see
     * a service notification for nothing. Best-effort: if the system refuses
     * the start (e.g. the exemption window lapsed), log and continue in the
     * background rather than failing the whole run.
     */
    private suspend fun promoteToPlaybackServiceIfNeeded(prefs: UserPreferences) {
        val alarmTriggered = inputData.getLong(KEY_ALARM_FIRED_AT_MS, 0L) != 0L
        if (!alarmTriggered) return
        // ScheduledDeliveryService is the primary FGS owner for alarm-driven
        // runs. While it's holding id 1005, we skip our own setForeground —
        // a second call would clobber its notification and race the type
        // transition. The static check (not a persisted input flag) is what
        // covers the corner cases: the Service's 5-min safety timeout, a
        // process restart, or a Service start that the receiver couldn't
        // reach — in any of them isHoldingForeground() is false and the
        // worker promotes itself so background TTS still has the
        // mediaPlayback FGS Android 15+ requires.
        if (ScheduledDeliveryService.isHoldingForegroundFor(id)) return
        val period = inputData.getString(KEY_PERIOD)
            ?.let { runCatching { ForecastPeriod.valueOf(it) }.getOrNull() }
            ?: ForecastPeriod.TODAY
        val mode = when (period) {
            ForecastPeriod.TODAY -> prefs.deliveryMode
            ForecastPeriod.TONIGHT -> prefs.tonightDeliveryMode
        }
        if (!mode.playsSpeech) return
        runCatching { setForeground(playbackForegroundInfo()) }
            .onSuccess {
                DiagLog.i(TAG, "Promoted to the playback foreground service (notification id=$PLAYBACK_NOTIFICATION_ID).")
            }
            .onFailure { t ->
                if (t is CancellationException) throw t
                DiagLog.w(TAG, "Couldn't start the playback foreground service; speech may be inaudible from the background.", t)
            }
    }

    /**
     * Logs the app's currently-posted notifications at [stage] so a bug report
     * shows whether the forecast post (daily id 1001 / tonight id 1003) is still
     * in the shade at that point or has already been cleared — and whether the
     * playback foreground-service notification (id 1005) is still up alongside it.
     *
     * Pairs with the delete-intent log in [app.clothescast.notification.NotificationDismissReceiver]:
     * a forecast id that's present at "after delivery" but gone by "at worker exit"
     * with no dismissal line in between points at the foreground-service teardown
     * clearing it rather than a user swipe. Only ids are logged — never the prose —
     * so no insight content crosses into the diag file.
     */
    private fun logActiveAppNotifications(stage: String) {
        val manager = applicationContext.getSystemService<NotificationManager>() ?: return
        val active = runCatching { manager.activeNotifications }.getOrNull() ?: return
        val ids = active.joinToString { sbn ->
            "id=${sbn.id}" + (sbn.tag?.let { "/tag=$it" } ?: "")
        }
        DiagLog.i(TAG, "Active notifications $stage: [$ids] (count=${active.size}).")
    }

    /**
     * The notification carried by the playback foreground service (see
     * [promoteToPlaybackServiceIfNeeded]). Silent and LOW-importance — it's a
     * platform requirement for background audio, not a user-actionable post —
     * and tagged `mediaPlayback` so the foreground-service type matches the
     * permission and manifest declaration.
     */
    private fun playbackForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification_insight)
            .setContentTitle(applicationContext.getString(R.string.notification_playback_title))
            .setOngoing(true)
            .setSilent(true)
            .build()
        // minSdk is 31, so the typed ForegroundInfo constructor (foreground
        // service type, applied on API 29+) is always available here.
        return ForegroundInfo(
            PLAYBACK_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private suspend fun awaitDeliveryAlignment() {
        val alarmFiredAtMs = inputData.getLong(KEY_ALARM_FIRED_AT_MS, 0L)
        if (alarmFiredAtMs == 0L) return
        val alignAtMs = alarmFiredAtMs + DELIVERY_ALIGN_AFTER_ALARM_MS
        val waitMs = alignAtMs - System.currentTimeMillis()
        if (waitMs > 0) {
            DiagLog.i(TAG, "Aligning delivery to alarm + ${DELIVERY_ALIGN_AFTER_ALARM_MS}ms (waiting ${waitMs}ms).")
            delay(waitMs)
        }
    }

    /**
     * Emits a Firebase Analytics `notification_delivery` event covering the gap
     * between when the schedule said the notification should fire and when it
     * actually posted. Reads both timestamps stamped by [AlarmReceiver]:
     *
     *  - [KEY_ALARM_SCHEDULED_AT_MS] — the trigger time we handed to
     *    AlarmManager.setExactAndAllowWhileIdle. Doze can push the actual fire
     *    minutes later.
     *  - [KEY_ALARM_FIRED_AT_MS] — System.currentTimeMillis at the receiver.
     *    `alarm_delay_ms = fired - scheduled` isolates the doze deferral.
     *
     * `total_delay_ms` is taken at the moment this helper runs — moments after
     * the system notification is posted — so it also folds in WorkManager
     * constraint waits (no network, low battery), our own pipeline latency,
     * and the deferred-TTS pause when applicable. The user wanted "schedule
     * said X, we actually notified at Y" — that's `total_delay_ms`. The
     * `alarm_delay_ms` breakdown lets us distinguish doze from worker waits
     * when slicing the data.
     *
     * Silently no-ops when either timestamp is 0 (force-refresh tap,
     * location-cache run, boot-completed re-arm path) — those aren't on the
     * schedule we're measuring, so there's no scheduled-vs-actual to report.
     */
    private fun recordDeliveryDelay(period: ForecastPeriod) {
        val scheduledAtMs = inputData.getLong(KEY_ALARM_SCHEDULED_AT_MS, 0L)
        val firedAtMs = inputData.getLong(KEY_ALARM_FIRED_AT_MS, 0L)
        if (scheduledAtMs == 0L || firedAtMs == 0L) return
        val now = System.currentTimeMillis()
        // Floor at 0 to defend against early-fire on a freshly-set wall clock
        // (NTP correction jumping backward, manual time changes). Negative
        // delays would only confuse the dashboard.
        val alarmDelay = (firedAtMs - scheduledAtMs).coerceAtLeast(0L)
        val totalDelay = (now - scheduledAtMs).coerceAtLeast(0L)
        Telemetry.logNotificationDelivery(
            period = when (period) {
                ForecastPeriod.TODAY -> "today"
                ForecastPeriod.TONIGHT -> "tonight"
            },
            alarmDelayMs = alarmDelay,
            totalDelayMs = totalDelay,
        )
    }

    // Region-language prose for notification text and the audit log. Spoken
    // playback is rendered separately through ttsUtterance() so explicit voice
    // locales like de-AT speak German even when the app UI remains English.
    private fun formatProse(insight: Insight, prefs: UserPreferences): String =
        InsightFormatter.forRegion(
            applicationContext,
            prefs.region,
            prefs.temperatureUnit,
            prefs.rangeFormat,
            prefs.clothesFormat,
            prefs.bottomsFormat,
            prefs.periodPreamble,
            prefs.wearPreamble,
        ).format(insight.summary)

    // TODO(brand-intro): consider prepending "Today's ClothesCast: " / "Tonight's ClothesCast: "
    // here (and mirror it in the SAMPLE_SUMMARY render used by the top-level
    // runTtsPreview function in ui/settings/VoiceSettings.kt) once the voice
    // preview's phrasing settles — the brand-name pronunciation check that the
    // per-locale settings_tts_test_sample used to give us is currently absent
    // from both the preview and the real briefing.
    private fun ttsUtterance(
        insight: Insight,
        prefs: UserPreferences,
        theme: HolidayTheme?,
    ): InsightTtsUtterance =
        insightTtsUtterance(
            context = applicationContext,
            summary = insight.summary,
            region = prefs.region,
            voiceLocale = prefs.voiceLocale,
            temperatureUnit = prefs.temperatureUnit,
            rangeFormat = prefs.rangeFormat,
            clothesFormat = prefs.clothesFormat,
            bottomsFormat = prefs.bottomsFormat,
            periodPreamble = prefs.periodPreamble,
            wearPreamble = prefs.wearPreamble,
            holidayTheme = theme,
        )

    companion object {
        private const val TAG = "FetchAndNotifyWorker"
        // Single unique-work queue for both TODAY and TONIGHT scheduled runs.
        // Sharing one queue means WorkManager's ExistingWorkPolicy.REPLACE
        // naturally cancels any in-flight previous run when a new alarm
        // fires — covers the "morning still mid-flight when evening alarm
        // fires" overlap without any cross-queue dance. The period the run
        // is for is carried on the WorkInfo's input data ([KEY_PERIOD]);
        // observers that care about which slot is running read it there.
        //
        // Name preserved as "daily_insight_fetch" — WorkManager persists the
        // queue keyed by this name and renaming would orphan any run armed
        // before the upgrade.
        const val UNIQUE_WORK_NAME = "daily_insight_fetch"
        // Distinct queue from the daily / tonight runs so a user toggling
        // device location while a forecast run is in flight doesn't cancel
        // it (and vice versa). Cache-only runs are idempotent and skip the
        // insight pipeline entirely.
        const val UNIQUE_WORK_NAME_LOCATION_CACHE = "location_cache_refresh"
        // Distinct queue from the alarm-driven daily / tonight runs so a
        // silent app-open refresh in flight doesn't get cancelled by an
        // alarm fire (and vice versa). KEEP-deduped so config-change
        // re-triggers on app open coalesce into the one in-flight worker.
        const val UNIQUE_WORK_NAME_SILENT = "silent_insight_refresh"
        // Distinct queue from the daily / tonight runs so an offline Play
        // tap sitting in the queue can't block the morning alarm: alarm
        // enqueues use ExistingWorkPolicy.KEEP and would otherwise be
        // dropped in favour of the pending play, leaving the user
        // listening to yesterday's cached insight at 7am instead of the
        // fresh forecast. Race vs. concurrent plays is handled in the
        // UI gate — see TodayState.anyWorkActive.
        const val UNIQUE_WORK_NAME_PLAY = "insight_play"

        // Foreground-service notification ID for the playback service. Distinct
        // from the insight notification IDs (1001 daily, 1003 tonight) so the
        // service notification and the delivered insight don't clobber each other.
        private const val PLAYBACK_NOTIFICATION_ID = 1005

        // Output Data keys for surfacing failure reasons in the UI.
        const val KEY_REASON = "reason"
        const val KEY_REASON_DETAIL = "reason_detail"

        /**
         * Wall-clock millis stamped on every terminal Result. Used by
         * [TodayViewModel.selectStatus] to disambiguate "which of these
         * SUCCEEDED/FAILED WorkInfos is actually the latest" — WorkInfo itself
         * exposes neither a completion time nor a chronological ordering.
         */
        const val KEY_COMPLETED_AT = "completed_at_ms"

        /**
         * Progress-data flag set once the fetch + insight cache + paired-window
         * pre-render are complete and the worker is about to enter [deliver]
         * (alignment wait → notification → TTS playback). Read by
         * [TodayViewModel.selectStatus]: an active WorkInfo carrying this flag
         * is treated as past the "fetching" phase, so the Today screen's
         * spinner banner hides as soon as the fresh data lands rather than
         * waiting on TTS to finish speaking.
         */
        const val KEY_FETCH_COMPLETE = "fetch_complete"

        const val REASON_UNEXPECTED_HTTP = "unexpected_http"
        const val REASON_UNHANDLED = "unhandled"
        const val REASON_NO_LOCATION = "no_location"

        // daily_refresh event outcome buckets — kept here next to the reason
        // codes so the mapping in classifyDailyRefreshReason stays
        // discoverable. "success" / "cancelled" don't have matching reason
        // codes because they aren't WorkManager Failure reasons.
        private const val OUTCOME_SUCCESS = "success"
        private const val OUTCOME_CANCELLED = "cancelled"

        /**
         * Output-data flag set on the no-op success branches (tonight-disabled
         * being the canonical case) to tell [recordDailyRefreshOutcome] that
         * the run reached `Result.success()` without delivering anything, so
         * the daily_refresh event should be suppressed for it.
         */
        internal const val KEY_SKIP_TELEMETRY = "skip_telemetry"

        // Cap unhandled-error detail so the "Show details" pane stays readable.
        private const val MAX_DETAIL_LEN = 240

        // Must match the literal LocationResolver stamps onto every device
        // fix; we use it to spot the "no friendly name yet" case when
        // deciding whether the previously cached displayName is worth
        // reusing on a reverse-geo miss.
        private const val DEVICE_LOCATION_PLACEHOLDER = "Device location"

        // Radius within which we'll reuse the previously cached city name
        // when reverse-geo fails. ~25km is generous enough to cover a
        // commute or weekend outing without keeping yesterday's "Paris"
        // glued to today's "London" fix.
        private const val REUSE_LABEL_RADIUS_METERS = 25_000.0

        /**
         * Wall-clock millis at which the alarm fired. Set by [AlarmReceiver] for
         * scheduled morning runs; absent (0) for manual refresh taps and other
         * non-alarm-triggered enqueues. Read by [awaitDeliveryAlignment] to compute the
         * earliest moment TTS should start speaking, and by [recordDeliveryDelay]
         * to split the scheduled→delivered gap into "doze deferral" vs "worker
         * wait".
         */
        private const val KEY_ALARM_FIRED_AT_MS = "alarm_fired_at_ms"

        /**
         * Wall-clock millis at which AlarmManager *should* have fired this alarm —
         * the trigger time DailyAlarmScheduler stamped onto the PendingIntent's
         * extras. AlarmReceiver forwards it here so [recordDeliveryDelay] can
         * compute `total_delay_ms = now - scheduled` for the
         * `notification_delivery` analytics event. Absent (0) for non-alarm
         * enqueues (force refresh, location cache, boot re-arm path).
         */
        private const val KEY_ALARM_SCHEDULED_AT_MS = "alarm_scheduled_at_ms"

        /**
         * Sync point for the notification post and the spoken briefing on
         * alarm-triggered runs: both fire at `alarmFiredAt + this`. Originally
         * a TTS-only "wait for the alarm to stop ringing" gap; now also the
         * realignment after [ALARM_FETCH_JITTER_MS] so every device in a
         * multi-device home delivers at the same wall-clock moment. One
         * minute leaves plenty of slack for the longest jitter roll plus a
         * slow fetch.
         */
        private const val DELIVERY_ALIGN_AFTER_ALARM_MS = 60_000L

        /**
         * Upper bound (exclusive) of the randomized wait before an alarm-triggered
         * fetch. Spreads multiple devices on the same home IP across this window
         * so Open-Meteo doesn't see them as a synchronized concurrent-request
         * burst. Held strictly below [DELIVERY_ALIGN_AFTER_ALARM_MS] so the
         * post-fetch alignment is *reliable* — worst-case jitter (30 s) plus
         * a slow fetch (a few seconds) still leaves headroom to land before
         * the 60 s deadline, and every device delivers at exactly alarm + 60 s.
         * Going wider would let some rolls overshoot the deadline and reintroduce
         * the staggered-delivery problem alignment exists to fix.
         *
         * The math for the 429 case still works at 30 s: 4 devices land
         * ~6 s apart on average, well over Open-Meteo's per-IP concurrent
         * budget for the ~1–2 s connections we make, and the 429 retry covers
         * the unlucky tail where rolls bunch up.
         */
        private const val ALARM_FETCH_JITTER_MS = 30_000L

        /**
         * Total Gemini TTS synth attempts (initial try + retries) before the
         * delivery falls back to the device engine. A single transient blip —
         * the socket timeout in the field report that triggered this — shouldn't
         * downgrade the briefing to the device voice the user didn't pick. Three
         * attempts clears the common one-off timeout while staying bounded; the
         * retries run pre-alignment, usually absorbed by the alignment barrier.
         */
        private const val GEMINI_TTS_SYNTH_ATTEMPTS = 3

        /**
         * Base backoff between Gemini TTS synth retries, doubled each attempt
         * (1 s, then 2 s). Short on purpose: the synth track runs ahead of
         * [awaitDeliveryAlignment], so a couple of seconds of retry is normally
         * swallowed by the ~60 s alignment wait without pushing delivery later.
         */
        private const val GEMINI_TTS_SYNTH_BACKOFF_MS = 1_000L

        /** Which slice of the day this run is for; defaults to TODAY when absent. */
        internal const val KEY_PERIOD = "period"

        /**
         * Set true via [enqueueLocationCacheRefresh] when the user toggles
         * device location ON in Settings. The worker resolves + caches the
         * device fix and exits without delivering an insight, so the user
         * doesn't get a duplicate notification at e.g. 10am after the
         * morning run already fired at 7am.
         */
        internal const val KEY_CACHE_LOCATION_ONLY = "cache_location_only"

        /**
         * Set true for refreshes that fetch + update the cache so the Today
         * screen re-renders off fresh data, but skip everything user-facing —
         * no notification, no TTS, no MQTT publish, no cast load. The widget
         * update still fires because it's already on the user's launcher and
         * would otherwise sit on the stale outfit.
         *
         * Sources: the opportunistic app-open refresh ([enqueueSilentRefresh]),
         * the first-run onboarding fetch ([enqueueOnboardingRefresh]), and the
         * Today screen's manual Refresh button ([enqueueOneShot] with
         * `silent = true`) — the user is already looking at the screen the
         * fetch updates, so a banner / chime / cast on top of that is exactly
         * the surprise this flag avoids. The top progress banner is the only
         * feedback.
         */
        internal const val KEY_SILENT_REFRESH = "silent_refresh"

        /**
         * Min staleness before app-open triggers a silent refresh — anything
         * within the hour is "still good enough", matches CachingWeatherRepository's
         * own 1h TTL so we don't burn an Open-Meteo call just to read the
         * same bundle out of the in-memory cache.
         */
        val SILENT_REFRESH_MIN_AGE: Duration = Duration.ofHours(1)

        /**
         * Set true via [enqueuePlay] for on-demand play — the Today screen's
         * Play button and the Schedule settings "Play now" buttons. The worker
         * runs the full deliver() fan-out (notification + TTS + MQTT + cast)
         * for the requested period: replaying a fresh same-day cached snapshot
         * when one exists (no fetch), or fetching fresh when the cache is empty
         * or stale. Cached deliveries derive against the *current* prefs so any
         * Format / clothes-rules changes since capture are spoken too.
         */
        internal const val KEY_PLAY = "play"

        /**
         * Set true via [enqueuePlay] when the on-demand play should post the
         * notification *and* speak regardless of the period's delivery mode
         * — the Today screen's Play button, an explicit "do it now." Left
         * false for the Schedule settings "Play now" preview, which
         * deliberately simulates the configured delivery mode (SILENT plays
         * nothing, TTS-only speaks without a banner, etc.).
         */
        internal const val KEY_PLAY_FORCE = "play_force"

        /**
         * Returns the [java.util.UUID] of the enqueued work request so callers
         * (notably [app.clothescast.alarm.AlarmReceiver]) can hand it to
         * [app.clothescast.alarm.ScheduledDeliveryService] to watch the
         * specific run rather than racing whatever stale terminal WorkInfo
         * the unique-work queue is hanging on to.
         */
        fun enqueueOneShot(
            context: Context,
            silent: Boolean = false,
            period: ForecastPeriod = ForecastPeriod.TODAY,
            alarmScheduledAtMs: Long = 0L,
            alarmFiredAtMs: Long = 0L,
        ): java.util.UUID {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<FetchAndNotifyWorker>()
                .setConstraints(constraints)
                // 10s is WorkManager's MIN_BACKOFF_MILLIS — going lower silently
                // clamps. The just-after-doze case (DNS resolver not warm yet
                // when NetworkType.CONNECTED is satisfied) recovers in seconds,
                // so the previous 30s floor + exponential growth was burning
                // ~16min on what's typically a 1-2s glitch. See bug report
                // 2026-05-02: alarm at 07:00 → insight delivered at 07:17.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_SILENT_REFRESH to silent,
                        KEY_PERIOD to period.name,
                        KEY_ALARM_SCHEDULED_AT_MS to alarmScheduledAtMs,
                        KEY_ALARM_FIRED_AT_MS to alarmFiredAtMs,
                    )
                )
                .build()

            // REPLACE across the board, and one shared queue for both
            // periods: the alarm path uses it so a stuck retry from an
            // earlier fire — *or* an in-flight worker for the other slot —
            // is canceled in favor of the new alarm's fresh request. The
            // silent refresh path already used REPLACE; the merged queue
            // means a manual Refresh tap supersedes any in-flight scheduled
            // run too. With REPLACE we always know `request.id` is the id
            // WorkManager kept, so callers (notably AlarmReceiver) don't
            // need to read it back.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        /**
         * Play the [period] insight via the full delivery fan-out — the Today
         * screen's Play button and the Schedule settings "Play now" buttons.
         * Replays a fresh same-day cached snapshot when one exists, else
         * fetches fresh (see [playInsight]). Runs on its own
         * [UNIQUE_WORK_NAME_PLAY] queue so an offline tap sitting here can't
         * block a later scheduled alarm fire (which uses REPLACE on
         * [UNIQUE_WORK_NAME] — sharing the queue would let the pending
         * play be canceled by the alarm or vice versa). REPLACE because the
         * user's most-recent tap is the one that matters; concurrent
         * Refresh+Play double-delivery is prevented by the UI gate (see
         * [TodayState.anyWorkActive]), not by sharing a queue.
         *
         * @param forceNotifyAndSpeak true for the Today Play button — post
         *   the notification and speak regardless of the delivery mode, so a
         *   SILENT user still sees and hears the tapped forecast. False (the
         *   default) for the Schedule "Play now" preview, which simulates the
         *   configured mode.
         */
        fun enqueuePlay(
            context: Context,
            period: ForecastPeriod,
            forceNotifyAndSpeak: Boolean = false,
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<FetchAndNotifyWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_PLAY to true,
                        KEY_PERIOD to period.name,
                        KEY_PLAY_FORCE to forceNotifyAndSpeak,
                    )
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME_PLAY, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Cancel any in-flight delivery — the Today screen's Play button turns
         * into a Stop button while a delivery is mid-announcement and routes
         * here. Cancels all three delivery queues that feed
         * [TodayState.anyWorkActive] (daily / tonight scheduled fetches and the
         * on-demand play), covering both a Play the user started and a Refresh
         * or scheduled run that's currently speaking. WorkManager cancellation
         * cancels the running coroutine, which propagates into the suspending
         * `speak()` call and stops the AudioTrack / TextToSpeech engine via
         * their `invokeOnCancellation` hooks. A future scheduled alarm is
         * unaffected — it re-enqueues on its own next fire.
         */
        /**
         * One-shot cleanup of unique-work queue names this class no longer
         * uses. Idempotent — `cancelUniqueWork` is a no-op when no rows
         * exist under the name. Called from
         * [app.clothescast.ClothesCastApplication.onCreate] so an upgrade
         * doesn't leave a stale TONIGHT WorkSpec lurking on the retired
         * `tonight_insight_fetch` queue: that worker would otherwise run
         * later with its old period input, fire a duplicate notification /
         * TTS / MQTT / cast on top of the merged-queue run, and not be
         * observable via the current [TodayViewModel] / [SettingsViewModel]
         * flows (which only watch [UNIQUE_WORK_NAME] now).
         */
        fun cleanupLegacyQueues(context: Context) {
            // WorkManager initializes via androidx.startup on real devices,
            // but Robolectric doesn't fire that provider — getInstance() then
            // throws IllegalStateException from a test that didn't explicitly
            // initialise WorkManager. Treat that as "nothing to clean up"; on
            // a real device the cleanup runs once per cold start.
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork(LEGACY_UNIQUE_WORK_NAME_TONIGHT)
            }.onFailure { DiagLog.w(TAG, "Legacy queue cleanup skipped (WorkManager unavailable)", it) }
        }

        // Retired in the queue-merge — both periods now share UNIQUE_WORK_NAME.
        // Kept here only so [cleanupLegacyQueues] can cancel any orphaned row.
        private const val LEGACY_UNIQUE_WORK_NAME_TONIGHT = "tonight_insight_fetch"

        fun cancelDelivery(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME_PLAY)
        }

        /**
         * Cache-only refresh: resolves the device location and writes it to
         * settings without running the insight pipeline. Used when the user
         * toggles device location ON from Settings so they see their city
         * populate within seconds without waiting for the next morning run —
         * and crucially without a duplicate notification / TTS for today.
         */
        fun enqueueLocationCacheRefresh(context: Context) {
            // NetworkType.CONNECTED so the reverse-geocode resolves a friendly
            // city name; the underlying NETWORK_PROVIDER fix itself works
            // offline from cached cell-tower / WiFi data, but the displayed
            // displayName ("London") is much nicer than the lat/lon fallback.
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<FetchAndNotifyWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_CACHE_LOCATION_ONLY to true))
                .build()

            // REPLACE: a rapid off→on→off→on toggle cancels any in-flight
            // refresh and starts a new one — the user's most recent intent
            // is the only one that matters.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME_LOCATION_CACHE,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
        }

        /**
         * Opportunistic refresh fired on app open when the cached insight is
         * older than [SILENT_REFRESH_MIN_AGE]. Runs the full fetch + cache
         * pipeline but skips notification, TTS, MQTT, and cast — the Today
         * screen re-renders silently when the new snapshot lands in
         * [InsightCache.thisPeriod]. KEEP so config-change retriggers on the
         * same app-open coalesce into the one in-flight run.
         *
         * The period is resolved inside the worker via [currentPeriodForSchedule]
         * so we refresh whichever window the user is *currently* in — not
         * whichever window a previously cached snapshot happens to label
         * itself as. This corrects the slot when an alarm was missed and the
         * cache crossed a period boundary while stale.
         */
        fun enqueueSilentRefresh(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<FetchAndNotifyWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_SILENT_REFRESH to true,
                    )
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME_SILENT,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
        }

        /**
         * First-run fetch fired when the user finishes (or skips) onboarding.
         * Mechanically identical to [enqueueSilentRefresh] — `KEY_SILENT_REFRESH=true`
         * so no notification / TTS / MQTT / cast fan-out while the user is
         * looking at the screen the fetch updates — but enqueued under
         * [UNIQUE_WORK_NAME] instead of [UNIQUE_WORK_NAME_SILENT] so the
         * Today screen's `workStatusFlow` observes it: the empty state then
         * lands with a spinner banner and the Fetch-now button disabled,
         * stopping a tap-happy user from kicking a second concurrent fetch
         * for the same onboarding completion. KEEP so a re-trigger (config
         * change, returning from a permission dialog before Today drew) is a
         * no-op rather than a replace.
         */
        fun enqueueOnboardingRefresh(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<FetchAndNotifyWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_SILENT_REFRESH to true,
                    )
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
        }

        /**
         * App-open freshness predicate: true when [snapshot] is non-null and
         * its [ForecastSnapshot.generatedAt] is at least [SILENT_REFRESH_MIN_AGE]
         * before [now]. Null snapshot returns false — the app hasn't
         * successfully fetched yet, so there's nothing user-visible to
         * silently replace (the alarm or onboarding flow drives the first
         * fetch).
         */
        fun shouldSilentlyRefresh(snapshot: ForecastSnapshot?, now: Instant): Boolean {
            if (snapshot == null) return false
            return Duration.between(snapshot.generatedAt, now) >= SILENT_REFRESH_MIN_AGE
        }

        /**
         * The 12-hour window we should refresh for *right now*, based purely on
         * the user's schedule and wall-clock time — used by every silent refresh
         * (app-open, onboarding, manual Refresh) so it lands in whichever slot the
         * user is currently in. Time-based only: the enable toggles gate scheduled
         * delivery, not refresh, and the Today screen shows both windows from the
         * cache regardless, so a disabled tonight slot is still refreshed.
         */
        fun currentPeriodForSchedule(
            prefs: UserPreferences,
            now: LocalTime = LocalTime.now(),
        ): ForecastPeriod = currentPeriodForSchedule(prefs.schedule.time, prefs.tonightSchedule.time, now)

        /**
         * [currentPeriodForSchedule] over raw cutoff times rather than the whole
         * [UserPreferences] — so callers that already hold the schedule times
         * (e.g. the widget freshness gate) can share the exact tonight-window
         * test without threading prefs through, and without re-deriving the
         * wrap-past-midnight logic by hand.
         */
        fun currentPeriodForSchedule(
            morningTime: LocalTime,
            tonightTime: LocalTime,
            now: LocalTime,
        ): ForecastPeriod {
            val inTonightWindow = if (tonightTime > morningTime) {
                now >= tonightTime || now < morningTime
            } else {
                now >= tonightTime && now < morningTime
            }
            return if (inTonightWindow) ForecastPeriod.TONIGHT else ForecastPeriod.TODAY
        }

        /**
         * Whether the *current* TONIGHT window is the **ongoing overnight** — the
         * night that began yesterday evening and ends this morning — rather than
         * the coming night. True only in the post-midnight tail: before the
         * morning run, with the usual schedule shape (evening time later than
         * morning time). The overnight slices its night off yesterday → today
         * pre-dawn, dates itself to yesterday, and pairs with *today's* daytime,
         * so the 6am pager reads "Overnight" + "Today" instead of "Tonight" +
         * "Tomorrow" (which skipped today's daytime entirely). The calendar
         * anchor stays on the real today regardless — see [ForecastSnapshot.overnight].
         *
         * Purely time-based, not trigger-based: scheduled alarms fire at the
         * morning / evening times and never land in the tail, and a TONIGHT alarm
         * *delayed* past midnight genuinely IS the ongoing overnight. TODAY is
         * never overnight; the degenerate crossed schedule (tonight not after
         * morning) falls back to false.
         *
         * TODO(overnight): revisit leading with "Overnight" late in the window —
         * by ~6am the ongoing night is nearly spent (an hour to the morning run),
         * so past some cutoff we may prefer to lead with "Today". A deliberate
         * follow-up; "overnight leads" is the agreed behaviour for now.
         */
        fun currentWindowIsOvernight(
            prefs: UserPreferences,
            period: ForecastPeriod,
            now: LocalTime = LocalTime.now(),
        ): Boolean =
            period == ForecastPeriod.TONIGHT &&
                prefs.tonightSchedule.time > prefs.schedule.time &&
                now < prefs.schedule.time

        /**
         * The calendar date the [requestedPeriod] cast that on-demand play
         * should target is dated for, given wall-clock [now] and the schedule
         * [morningTime] / [tonightTime]. This is the date the desired snapshot's
         * [ForecastBundle.today] carries, so it's used directly to match the
         * cache (and to derive the fetch day-offset).
         *
         * The nightly window wraps past midnight (tonightTime today →
         * morningTime tomorrow), so the date depends on where in the day we are:
         *
         *  - **Daytime cast (TODAY):** today's, unless we're already past the
         *    evening cutoff — then today's daytime cast has gone out and the next
         *    one is tomorrow's. Crucially, in the post-midnight tail of the night
         *    (before the morning cutoff) the next daytime cast is *this* morning,
         *    i.e. today — not tomorrow.
         *  - **Nightly cast (TONIGHT):** always today's. The snapshot's
         *    `bundle.today` is the real current day in every case — the coming
         *    night and the post-midnight ongoing overnight are both anchored on
         *    today (the overnight only dates its *derived* insight to yesterday
         *    via [ForecastSnapshot.overnight]; its bundle stays today-anchored),
         *    so the cache always matches on today's date.
         *
         * Assumes the usual schedule shape (tonightTime later than morningTime);
         * the degenerate crossed config falls back to "today", same window the
         * cache/fetch would otherwise pick.
         */
        internal fun playTargetDate(
            requestedPeriod: ForecastPeriod,
            now: LocalDateTime,
            morningTime: LocalTime,
            tonightTime: LocalTime,
        ): LocalDate {
            val date = now.toLocalDate()
            val time = now.toLocalTime()
            return when (requestedPeriod) {
                ForecastPeriod.TODAY ->
                    if (tonightTime > morningTime && time >= tonightTime) date.plusDays(1) else date
                ForecastPeriod.TONIGHT -> date
            }
        }
    }
}

/**
 * True when [infos] (one observed queue's WorkManager history) shows a *stale*
 * no-location failure: nothing currently active, and the most-recent terminal
 * run is a [FetchAndNotifyWorker.REASON_NO_LOCATION] failure. Mirrors
 * [app.clothescast.ui.today.selectStatus]'s "latest terminal by completed-at,
 * active runs win" rule so the cache-only recovery clears exactly the failure
 * the Today banner would otherwise surface.
 *
 * Top-level + [WorkInfoLite]-based (rather than a private method querying
 * WorkManager) so it's directly unit-testable — same pattern as `selectStatus`.
 */
internal fun staleNoLocationFailure(infos: List<WorkInfoLite>): Boolean {
    val active = infos.any {
        it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.BLOCKED
    }
    if (active) return false
    val latest = infos
        .filter { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED }
        .maxByOrNull { it.outputData.getLong(FetchAndNotifyWorker.KEY_COMPLETED_AT, 0L) }
        ?: return false
    return latest.state == WorkInfo.State.FAILED &&
        latest.outputData.getString(FetchAndNotifyWorker.KEY_REASON) == FetchAndNotifyWorker.REASON_NO_LOCATION
}
