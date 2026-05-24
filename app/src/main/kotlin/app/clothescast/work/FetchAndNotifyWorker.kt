package app.clothescast.work

import android.content.Context
import android.location.LocationManager
import app.clothescast.diag.DiagLog
import androidx.core.content.getSystemService
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import androidx.work.workDataOf
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.DailyHistoryEntry
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.calendar.resolveHolidayTheme
import app.clothescast.cast.CastInsightController
import app.clothescast.cast.Mp4Encoder
import app.clothescast.core.domain.usecase.computeDeliveryGates
import app.clothescast.core.domain.usecase.isGeminiEngineSelected
import app.clothescast.core.domain.usecase.isMqttPublishable
import app.clothescast.core.domain.usecase.shouldSpeak
import app.clothescast.core.domain.util.isWithin
import app.clothescast.core.data.tts.PcmAudio
import app.clothescast.core.data.tts.WavEncoder
import app.clothescast.data.InsightCache
import app.clothescast.mqtt.MqttPublishOutcome
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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
import app.clothescast.R
import app.clothescast.ui.garment.outfitCardInfoLines
import app.clothescast.ui.garment.renderOutfitCard
import app.clothescast.widget.OutfitWidget
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
import java.time.LocalTime
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
        val period = inputData.getString(KEY_PERIOD)
            ?.let { runCatching { ForecastPeriod.valueOf(it) }.getOrNull() }
            ?: ForecastPeriod.TODAY
        val startMs = System.currentTimeMillis()
        // The cache-only and silent-refresh paths don't deliver a
        // notification, so don't count them as refresh outcomes — they'd
        // skew the success-rate dashboard with runs the user never
        // perceived as a "daily refresh".
        val skipTelemetry = isCacheOnly || isSilent
        return try {
            val result = stamped(doWorkInternal())
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

        // Cache-only path triggered by the Settings location toggle. Just
        // resolves the device fix and writes it back via setLocation; skips
        // the forecast / insight / deliver pipeline so the user doesn't get
        // a duplicate notification (or TTS) when they enable device location
        // later in the day, after the morning run already fired.
        if (inputData.getBoolean(KEY_CACHE_LOCATION_ONLY, false)) {
            DiagLog.i(TAG, "Cache-only location refresh; skipping insight pipeline.")
            resolveLocation(prefs)
            return Result.success()
        }

        val isSilentRefresh = inputData.getBoolean(KEY_SILENT_REFRESH, false)
        // Silent app-open refreshes derive the period from the user's
        // schedule against wall-clock time — same logic the Today screen's
        // manual Refresh button uses — so a cached snapshot left in the
        // wrong window after a missed alarm (e.g. yesterday-evening
        // TONIGHT carried into morning) gets corrected to the current
        // window on the next open, not perpetuated.
        val period = if (isSilentRefresh) {
            currentPeriodForSchedule(prefs)
        } else {
            inputData.getString(KEY_PERIOD)
                ?.let { runCatching { ForecastPeriod.valueOf(it) }.getOrNull() }
                ?: ForecastPeriod.TODAY
        }

        // Honour force-refresh only when it's still the day the user tapped on. If
        // the request was enqueued near midnight and only ran after the date rolled
        // over (offline retries, deferred backoff), the *new* day's cache should
        // win — otherwise we'd silently bypass it and burn an extra Gemini call on
        // a stale tap. The tonight gate below also keys off this same-day check so
        // a stale tap from yesterday can't bypass a tonight-disabled toggle either.
        val today = LocalDate.now()
        val requestedEpochDay = inputData.getLong(KEY_REQUESTED_EPOCH_DAY, Long.MIN_VALUE)
        val isUserForcedRefresh = inputData.getBoolean(KEY_FORCE_REFRESH, false)
        // Silent app-open refreshes fire precisely *because* the cached
        // snapshot is stale, so they always bypass the same-day cache.
        val forceRefresh = (isUserForcedRefresh && requestedEpochDay == today.toEpochDay()) ||
            isSilentRefresh
        if (isUserForcedRefresh && requestedEpochDay != today.toEpochDay()) {
            DiagLog.i(TAG, "Ignoring force refresh from a previous day (requested=$requestedEpochDay, today=${today.toEpochDay()}).")
        }

        // The tonight alarm rearms blindly via AlarmReceiver; the user's enable
        // toggle is honoured here so a stale alarm doesn't ship a tonight insight
        // after the user disabled the feature. Same-day manual Refresh taps from
        // the Today screen bypass this gate — a user inside the tonight window
        // who taps Refresh wants fresh data regardless of whether the scheduled
        // tonight notification is on, otherwise the screen sits on stale morning
        // insights all evening with no way to update them. A force-refresh that
        // crossed midnight loses its bypass: by then the user's "evening tap"
        // intent is stale, and delivering a tonight insight after midnight with
        // tonight disabled is exactly the surprise the gate exists to prevent.
        if (period == ForecastPeriod.TONIGHT && !prefs.tonightEnabled && !forceRefresh) {
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
        // The Today screen's Refresh button sets [KEY_FORCE_REFRESH] = true so an
        // explicit user tap always regenerates — without that flag, tapping Refresh
        // on the same calendar day just redelivers the same cached payload, which
        // is surprising for the user. Silent app-open refreshes likewise set
        // [forceRefresh] above so they bypass this cache and pull fresh data
        // when the stored snapshot is over [SILENT_REFRESH_MIN_AGE] old.
        val cached = if (forceRefresh) {
            DiagLog.i(TAG, "Force refresh requested; bypassing today's cache.")
            null
        } else {
            runCatching {
                app.insightCache.deliveredForToday(today, period, prefs, diagLog = { DiagLog.i(TAG, it) })
            }.getOrNull()
        }
        if (cached != null) {
            val cachedInsight = cached.insight
            DiagLog.i(TAG, "Using cached $period insight for ${cachedInsight.forDate}.")
            return runCatching { deliver(cachedInsight, prefs, formatProse(cachedInsight, prefs)) }
                .map {
                    recordDailyHistory(cachedInsight)
                    Result.success()
                }
                .getOrElse {
                    if (it is CancellationException) throw it
                    DiagLog.e(TAG, "Cached delivery failed; falling through to fresh generate.", it)
                    fresh(location, prefs, period)
                }
        }

        return fresh(location, prefs, period)
    }

    private suspend fun fresh(
        location: Location,
        prefs: UserPreferences,
        period: ForecastPeriod,
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
            val snapshot = capturedSnapshot(location, prefs, period, dayOffset = 0)
            val result = app.deriveInsight(snapshot, prefs, diagLog = { DiagLog.i(TAG, it) })
            val isSilentRun = inputData.getBoolean(KEY_SILENT_REFRESH, false)
            // Severe alerts are out-of-band: post them as separate high-priority
            // notifications on every fresh fetch, regardless of whether the daily
            // summary itself is blank or suppressed. Silent app-open refreshes
            // skip this — re-issuing the morning alarm's already-posted alert
            // with the same stable notification ID re-fires its HUN (heads-up
            // sound / banner) on every app open with a stale cache, which is
            // exactly the surprise the silent contract is meant to avoid. New
            // alerts that landed since the last scheduled run will be picked
            // up by the next morning / tonight alarm.
            if (!isSilentRun) {
                result.alerts.filter { it.isHighPriority() }.forEach { alert ->
                    runCatching { app.weatherAlertNotifier.notify(alert) }
                        .onFailure { DiagLog.w(TAG, "Severe alert notification failed for ${alert.event}.", it) }
                }
            }
            val insight = result.insight
            runCatching { app.insightCache.store(InsightCache.Slot.THIS_PERIOD, snapshot) }
                .onSuccess {
                    // Push the fresh outfit out to any home-screen widgets.
                    // Gated on cache success because provideGlance() reads
                    // from the cache — kicking updateAll() after a failed
                    // write would just re-render the stale outfit. Failure
                    // here is non-blocking; the widget will catch up on the
                    // next successful fetch.
                    runCatching { OutfitWidget().updateAll(applicationContext) }
                        .onFailure { DiagLog.w(TAG, "Outfit widget update failed.", it) }
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
            generatePairedInsight(location, prefs, period)
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
                deliver(insight, prefs, prose)
                DiagLog.i(TAG, "Insight delivered for ${insight.forDate}: $prose")
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
     *
     * Silent — no notification, no TTS, no widget update. Failures are
     * logged and swallowed; the Today screen's pager falls back to its
     * placeholder for the NEXT_PERIOD slot in that case.
     */
    private suspend fun generatePairedInsight(
        location: Location,
        prefs: UserPreferences,
        primaryPeriod: ForecastPeriod,
    ) {
        val nextWindowPeriod = when (primaryPeriod) {
            ForecastPeriod.TODAY -> ForecastPeriod.TONIGHT
            ForecastPeriod.TONIGHT -> ForecastPeriod.TODAY
        }
        val dayOffset = if (primaryPeriod == ForecastPeriod.TONIGHT) 1 else 0
        runCatching {
            val snapshot = capturedSnapshot(location, prefs, nextWindowPeriod, dayOffset)
            app.insightCache.store(InsightCache.Slot.NEXT_PERIOD, snapshot)
            DiagLog.i(TAG, "Next-window $nextWindowPeriod snapshot cached for ${snapshot.bundle.today.date}.")
        }.onFailure {
            if (it is CancellationException) throw it
            DiagLog.w(TAG, "Next-window $nextWindowPeriod insight generation failed; not blocking $primaryPeriod delivery.", it)
        }
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
    ): ForecastSnapshot {
        val raw = app.generateDailyInsight.snapshot(location, prefs, period, dayOffset)
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

    private suspend fun resolveLocation(prefs: UserPreferences): Location? {
        if (prefs.useDeviceLocation) {
            // resolve() catches and DiagLog-warns about the actual failures
            // (SecurityException from a missing background grant, disabled
            // providers, timeouts) — don't double-wrap and lose the cause.
            val device = app.locationResolver.resolve()
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

    private suspend fun deliver(insight: Insight, prefs: UserPreferences, prose: String) {
        // Apply today's holiday theme on top of the user's persisted outfit
        // colour customisations so the notification's large icon and the
        // Home-Assistant outfit card match what the Today screen renders —
        // birthday yellows / Christmas reds, plus the tricolour stroke
        // accents on themes like July 4 / Bastille Day / Italy / Germany /
        // New Year's. The screen's TodayViewModel does the same merge;
        // without it the off-screen renderers would silently fall back to
        // user defaults (or the auto-derived darker-shade stroke) on
        // themed days. The manual "Publish now" path in MainActivity uses
        // the same helper so the retained MQTT image stays themed after a
        // user-initiated refresh too.
        val theme = resolveHolidayTheme(prefs, app.calendarEventReader)
        val topColors: Map<OutfitSuggestion.Top, Long> =
            prefs.outfitTopColors + (theme?.topOverrides ?: emptyMap())
        val bottomColors: Map<OutfitSuggestion.Bottom, Long> =
            prefs.outfitBottomColors + (theme?.bottomOverrides ?: emptyMap())
        val topStrokes: Map<OutfitSuggestion.Top, Long> =
            theme?.topStrokeOverrides ?: emptyMap()
        val bottomStrokes: Map<OutfitSuggestion.Bottom, Long> =
            theme?.bottomStrokeOverrides ?: emptyMap()

        // Compute every "should this fire?" decision from one snapshot
        // of prefs so the pre- and post-alignment fan-outs stay
        // consistent and the gate algebra is unit-testable (see
        // DeliveryGatesTest in :core:domain).
        val geminiAvailable = isGeminiEngineSelected(prefs) &&
            runCatching { app.secureKeyStore.geminiKeyConfiguredFlow.first() }.getOrDefault(false)
        val gates = computeDeliveryGates(
            prefs = prefs,
            period = insight.period,
            insightHasEvents = insight.hasEvents,
            geminiAvailable = geminiAvailable,
            mqttPublishable = isMqttPublishable(prefs),
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

            val notifyJob = launch { postPeriodNotification(insight, prefs, prose, topColors, topStrokes, gates) }

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

            val phoneSpeakerJob = launch {
                playPhoneSpeaker(insight, prefs, gates, pcm, wav = wav, castDeferred = castDeferred, theme = theme)
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
                runCatching { app.settingsRepository.setCastLastError(castOutcomeToError(castOutcome)) }
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
            }
        }
    }

    /**
     * Synthesises insight prose via Gemini TTS for the pre-alignment
     * synth track. Returns null on any failure so downstream
     * consumers (MQTT audio, phone speaker) can degrade gracefully:
     * the bridge skips its audio publish, the phone speaker falls back
     * to the device engine. Logs the failure with enough context to
     * diagnose without spamming successes.
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
        return runCatching {
            GeminiTtsSpeaker(
                app.geminiTtsClient,
                voiceName = selection.voiceName,
                style = selection.style,
            ).synthesize(utterance.text, utterance.locale)
        }
            .onFailure { t ->
                if (t is CancellationException) throw t
                DiagLog.w(TAG, "Gemini TTS synth failed; downstream audio destinations degrade.", t)
            }
            .getOrNull()
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
                tempLine = info.tempLine,
                rainLine = info.rainLine,
                tempFillFraction = info.tempFillFraction,
                rainFillFraction = info.rainFillFraction,
                topColors = topColors,
                bottomColors = bottomColors,
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
     * Hands the pre-rendered media to [CastInsightController] for
     * the smart display load. The cast destination follows the same
     * "fire-and-forget but capture outcome" pattern as MQTT: the
     * controller swallows all failures into [CastWorkerOutcome], so
     * a route-not-found or load-rejected case doesn't cancel sibling
     * destinations.
     *
     * When the synth buffer is null (Gemini unavailable or synth
     * failed pre-alignment), feeds [CastInsightController.silentWavStub]
     * as the loading carrier — the receiver still shows the outfit
     * PNG, just without speaking. SPEC.md's image-only fallback.
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
            ?: return CastInsightController.CastWorkerOutcome.Failed("No smart display picked")
        val pngBytes = png
            ?: return CastInsightController.CastWorkerOutcome.Failed("Outfit render unavailable")
        // Silent stub keeps Default Media Receiver happy on the
        // image-only path — receiver won't load with no audio media.
        val wavBytes = wav ?: CastInsightController.silentWavStub
        return controller.castWithPreparedMedia(
            routeId = routeId,
            wav = wavBytes,
            hasRealAudio = wav != null,
            png = pngBytes,
            title = applicationContext.getString(R.string.app_name),
            subtitle = insight.location?.displayName,
        )
    }

    /**
     * Maps a cast outcome onto the user-facing string that lands in
     * the Settings → Smart Home → Cast status row.
     *
     * Null result means "record a success": [SettingsRepository.setCastLastError]
     * stamps the last-success timestamp and clears any prior error
     * message. Non-null is a short, ready-to-display reason string.
     *
     * Only called after a cast was actually attempted — callers gate
     * on a non-null outcome.
     */
    private fun castOutcomeToError(outcome: CastInsightController.CastWorkerOutcome): String? =
        when (outcome) {
            is CastInsightController.CastWorkerOutcome.Success -> null
            is CastInsightController.CastWorkerOutcome.SkippedNoRoute ->
                "Smart display not reachable"
            is CastInsightController.CastWorkerOutcome.Failed -> outcome.reason
        }

    private fun postPeriodNotification(
        insight: Insight,
        prefs: UserPreferences,
        prose: String,
        topColors: Map<OutfitSuggestion.Top, Long>,
        topStrokes: Map<OutfitSuggestion.Top, Long>,
        gates: app.clothescast.core.domain.usecase.DeliveryGates,
    ) {
        if (gates.emptyEveningSkip) {
            DiagLog.i(TAG, "Tonight insight has no events and notify-only-on-events is on; skipping notification.")
            return
        }
        val mode = when (insight.period) {
            ForecastPeriod.TODAY -> prefs.deliveryMode
            ForecastPeriod.TONIGHT -> prefs.tonightDeliveryMode
        }
        val canNotify = mode == DeliveryMode.NOTIFICATION_ONLY ||
            mode == DeliveryMode.NOTIFICATION_AND_TTS
        if (!canNotify) return
        when (insight.period) {
            ForecastPeriod.TODAY ->
                app.insightNotifier.notify(insight, prose, topColors, topStrokes)
            ForecastPeriod.TONIGHT ->
                app.tonightInsightNotifier.notify(insight, prose, topColors, topStrokes)
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
        theme: HolidayTheme?,
    ) {
        if (gates.emptyEveningSkip) {
            DiagLog.i(TAG, "Tonight insight has no events and notify-only-on-events is on; skipping TTS.")
            return
        }
        if (!gates.phoneTtsConfigured) return
        if (!shouldSpeakNow(prefs)) return

        // Cast suppression: only when this cast is genuinely playing
        // audio (willCast AND a synth buffer exists). An image-only
        // cast (Gemini unavailable / synth failed → silent WAV stub)
        // doesn't trigger suppression — the smart display isn't the
        // speaker in that path.
        val castHasAudio = gates.willCast && wav != null
        if (castHasAudio && castDeferred != null && prefs.castSkipPhoneSpeech) {
            val castOutcome = castDeferred.await()
            if (castOutcome is CastInsightController.CastWorkerOutcome.Success) {
                DiagLog.i(TAG, "Phone speech suppressed — smart display is playing the forecast.")
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
            prefs.rainAccessory,
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
            rainAccessory = prefs.rainAccessory,
            holidayTheme = theme,
        )

    /**
     * Wraps [shouldSpeak] with the per-run device-location read. Resolves the
     * coarse fix via [LocationResolver.resolveFresh] (network provider; no
     * GPS; ~hundreds of metres accuracy) only when the gate is on and a home
     * pin is configured — otherwise we'd be paying a permission-checked
     * resolver call on every run for nothing.
     *
     * Uses [resolveFresh] rather than [LocationResolver.resolve] so a stale
     * at-home fix from before the user left can't suppress TTS once they've
     * gone away — exactly the failure mode this feature exists to avoid.
     *
     * The caller is responsible for already gating on
     * [DeliveryGates.phoneTtsConfigured] / [DeliveryGates.emptyEveningSkip]
     * before reaching here; this function only handles the runtime
     * at-home check.
     */
    private suspend fun shouldSpeakNow(prefs: UserPreferences): Boolean {
        if (!prefs.skipTtsAtHome || prefs.homeLocation == null) return true
        val current = app.locationResolver.resolveFresh(LocationResolver.FRESH_FIX_MAX_AGE_MS)
        val speak = shouldSpeak(
            ttsRequested = true,
            skipTtsAtHome = true,
            homeLocation = prefs.homeLocation,
            currentLocation = current,
        )
        if (!speak) DiagLog.i(TAG, "At home and skip-TTS-at-home is on; skipping TTS.")
        return speak
    }

    companion object {
        private const val TAG = "FetchAndNotifyWorker"
        const val UNIQUE_WORK_NAME = "daily_insight_fetch"
        const val UNIQUE_WORK_NAME_TONIGHT = "tonight_insight_fetch"
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

        /** Set true via [enqueueOneShot] when the user explicitly taps Refresh. */
        internal const val KEY_FORCE_REFRESH = "force_refresh"

        /**
         * Wall-clock millis at which the alarm fired. Set by [AlarmReceiver] for
         * scheduled morning runs; absent (0) for force-refresh taps and other
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
         * Epoch-day of the calendar date the force-refresh was requested for. Used
         * to drop a stale force flag if the worker only runs after midnight (e.g. a
         * tap at 23:59 that retried offline until 00:05).
         */
        internal const val KEY_REQUESTED_EPOCH_DAY = "requested_epoch_day"

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
         * Set true via [enqueueSilentRefresh] for the opportunistic app-open
         * refresh: fetch + update the cache so the Today screen re-renders
         * off fresh data, but skip everything user-facing — no notification,
         * no TTS, no MQTT publish, no cast load. The widget update still
         * fires because it's already on the user's launcher and would
         * otherwise sit on the stale outfit.
         */
        internal const val KEY_SILENT_REFRESH = "silent_refresh"

        /**
         * Min staleness before app-open triggers a silent refresh — anything
         * within the hour is "still good enough", matches CachingWeatherRepository's
         * own 1h TTL so we don't burn an Open-Meteo call just to read the
         * same bundle out of the in-memory cache.
         */
        val SILENT_REFRESH_MIN_AGE: Duration = Duration.ofHours(1)

        fun enqueueOneShot(
            context: Context,
            force: Boolean = false,
            period: ForecastPeriod = ForecastPeriod.TODAY,
            alarmScheduledAtMs: Long = 0L,
            alarmFiredAtMs: Long = 0L,
        ) {
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
                        KEY_FORCE_REFRESH to force,
                        KEY_REQUESTED_EPOCH_DAY to LocalDate.now().toEpochDay(),
                        KEY_PERIOD to period.name,
                        KEY_ALARM_SCHEDULED_AT_MS to alarmScheduledAtMs,
                        KEY_ALARM_FIRED_AT_MS to alarmFiredAtMs,
                    )
                )
                .build()

            // Alarm-driven enqueues use KEEP so a still-retrying run isn't duplicated.
            // User-initiated force enqueues use REPLACE — the user just tapped Refresh
            // expecting a fresh fetch, so cancel any in-flight retry and start over.
            val policy = if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            val workName = when (period) {
                ForecastPeriod.TODAY -> UNIQUE_WORK_NAME
                ForecastPeriod.TONIGHT -> UNIQUE_WORK_NAME_TONIGHT
            }
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName, policy, request)
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
                        KEY_REQUESTED_EPOCH_DAY to LocalDate.now().toEpochDay(),
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
         * The 12-hour window we should refresh for *right now*, based on the
         * user's schedule and wall-clock time. Mirrors the period selection
         * the Today screen's manual Refresh button uses so a silent refresh
         * lands in the same slot the user would otherwise have to tap to
         * regenerate. Returns TODAY whenever tonight delivery is disabled —
         * the tonight slot is off, so there's nothing useful to refresh there.
         */
        fun currentPeriodForSchedule(
            prefs: UserPreferences,
            now: LocalTime = LocalTime.now(),
        ): ForecastPeriod {
            if (!prefs.tonightEnabled) return ForecastPeriod.TODAY
            val morning = prefs.schedule.time
            val tonight = prefs.tonightSchedule.time
            val inTonightWindow = if (tonight > morning) {
                now >= tonight || now < morning
            } else {
                now >= tonight && now < morning
            }
            return if (inTonightWindow) ForecastPeriod.TONIGHT else ForecastPeriod.TODAY
        }
    }
}
