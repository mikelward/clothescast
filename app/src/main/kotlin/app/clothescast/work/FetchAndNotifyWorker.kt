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
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.usecase.shouldSpeak
import app.clothescast.core.data.tts.WavEncoder
import app.clothescast.data.InsightCache
import app.clothescast.mqtt.MqttPublishOutcome
import app.clothescast.diag.Telemetry
import app.clothescast.diag.classifyDailyRefreshReason
import app.clothescast.insight.InsightFormatter
import app.clothescast.location.LocationResolver
import app.clothescast.location.hasBackgroundLocationPermission
import app.clothescast.location.hasCoarseLocationPermission
import app.clothescast.tts.GeminiTtsSpeaker
import app.clothescast.tts.InsightTtsUtterance
import app.clothescast.tts.insightTtsUtterance
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
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sqrt
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
        val period = inputData.getString(KEY_PERIOD)
            ?.let { runCatching { ForecastPeriod.valueOf(it) }.getOrNull() }
            ?: ForecastPeriod.TODAY
        val startMs = System.currentTimeMillis()
        // The cache-only path doesn't deliver an insight, so don't count it
        // as a refresh outcome — that would skew the success-rate dashboard
        // with no-op runs every time the user toggles device location.
        return try {
            val result = stamped(doWorkInternal())
            if (!isCacheOnly) recordDailyRefreshOutcome(period, result, startMs)
            result
        } catch (ce: CancellationException) {
            if (!isCacheOnly) {
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

        val period = inputData.getString(KEY_PERIOD)
            ?.let { runCatching { ForecastPeriod.valueOf(it) }.getOrNull() }
            ?: ForecastPeriod.TODAY

        // The tonight alarm rearms blindly via AlarmReceiver; the user's enable
        // toggle is honoured here so a stale alarm doesn't ship a tonight insight
        // after the user disabled the feature.
        if (period == ForecastPeriod.TONIGHT && !prefs.tonightEnabled) {
            DiagLog.i(TAG, "Tonight insight is disabled; skipping.")
            // Stamp KEY_SKIP_TELEMETRY so the daily_refresh outcome event
            // doesn't count this as a success. TodayScreen.triggerRefresh
            // enqueues a TONIGHT run purely on the clock, so any user who
            // turned the feature off would otherwise show a steady stream
            // of "successful" tonight refreshes that delivered nothing.
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
        val today = LocalDate.now()

        // Honour force-refresh only when it's still the day the user tapped on. If
        // the request was enqueued near midnight and only ran after the date rolled
        // over (offline retries, deferred backoff), the *new* day's cache should
        // win — otherwise we'd silently bypass it and burn an extra Gemini call on
        // a stale tap.
        val requestedEpochDay = inputData.getLong(KEY_REQUESTED_EPOCH_DAY, Long.MIN_VALUE)
        val forceRequested = inputData.getBoolean(KEY_FORCE_REFRESH, false)
        val forceRefresh = forceRequested && requestedEpochDay == today.toEpochDay()
        if (forceRequested && !forceRefresh) {
            DiagLog.i(TAG, "Ignoring force refresh from a previous day (requested=$requestedEpochDay, today=${today.toEpochDay()}).")
        }

        // 24h cost cap: if we already generated an insight for today, redeliver it
        // rather than refetching. Same path serves the morning alarm and any
        // "Fire insight now" debug taps later in the day.
        //
        // The Today screen's Refresh button sets [KEY_FORCE_REFRESH] = true so an
        // explicit user tap always regenerates — without that flag, tapping Refresh
        // on the same calendar day just redelivers the same cached payload, which
        // is surprising for the user.
        //
        // TODO: opportunistic auto-refresh — when the user opens the app and the
        // cached insight is more than ~1h old, regenerate (bypassing the same-day
        // cache). Avoids a full Refresh tap when the clothes / forecast has moved
        // since the morning generation.
        val cached = if (forceRefresh) {
            DiagLog.i(TAG, "Force refresh requested; bypassing today's cache.")
            null
        } else {
            runCatching { app.insightCache.deliveredForToday(today, period) }.getOrNull()
        }
        if (cached != null) {
            DiagLog.i(TAG, "Using cached $period insight for ${cached.forDate}.")
            return runCatching { deliver(cached, prefs, formatProse(cached, prefs)) }
                .map { Result.success() }
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
            val result = app.generateDailyInsight(location, prefs, period)
            // Stamp the resolved location onto the insight so the home screen
            // can show the city next to the date and deep-link to maps. UI-only
            // — never read by InsightFormatter / RenderInsightSummary, so it
            // can't leak into LLM / TTS prose.
            val insight = result.insight.copy(location = location)
            // Severe alerts are out-of-band: post them as separate high-priority
            // notifications on every fresh fetch, regardless of whether the daily
            // summary itself is blank or suppressed.
            result.alerts.filter { it.isHighPriority() }.forEach { alert ->
                runCatching { app.weatherAlertNotifier.notify(alert) }
                    .onFailure { DiagLog.w(TAG, "Severe alert notification failed for ${alert.event}.", it) }
            }
            runCatching { app.insightCache.store(InsightCache.Slot.THIS_PERIOD, insight) }
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
            deliver(insight, prefs, prose)
            DiagLog.i(TAG, "Insight delivered for ${insight.forDate}: $prose")
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
            val result = app.generateDailyInsight(location, prefs, nextWindowPeriod, dayOffset)
            val pairedInsight = result.insight.copy(location = location)
            app.insightCache.store(InsightCache.Slot.NEXT_PERIOD, pairedInsight)
            DiagLog.i(TAG, "Next-window $nextWindowPeriod insight cached for ${pairedInsight.forDate}.")
        }.onFailure {
            if (it is CancellationException) throw it
            DiagLog.w(TAG, "Next-window $nextWindowPeriod insight generation failed; not blocking $primaryPeriod delivery.", it)
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
                val cityName = app.reverseGeocoder.resolveCityName(device.latitude, device.longitude)
                // When reverse-geo fails for a fix close to the previously
                // cached one, reuse the cached friendly name rather than
                // clobbering "London" with the placeholder. Without this
                // a single transient geocoder timeout permanently degrades
                // the home screen to the localised "Your location" fallback
                // until reverse-geo next succeeds.
                val resolved = when {
                    cityName != null -> device.copy(displayName = cityName)
                    else -> reuseNearbyDisplayName(prefs.location, device)
                        ?.let { device.copy(displayName = it) }
                        ?: device
                }
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
    // yesterday's trip to a different city.
    private fun reuseNearbyDisplayName(prior: Location?, device: Location): String? {
        if (prior == null) return null
        val priorName = prior.displayName
            ?.takeUnless { it.isBlank() || it == DEVICE_LOCATION_PLACEHOLDER }
            ?: return null
        return if (approxDistanceKm(prior, device) < REUSE_LABEL_RADIUS_KM) priorName else null
    }

    // Equirectangular approximation. Accurate to well under a kilometre
    // at temperate latitudes for sub-100km separations — fine for "is
    // the cached city name still meaningful?". TODO: switch to haversine
    // if we ever need correctness near the poles or across the
    // antimeridian; neither applies to current users.
    private fun approxDistanceKm(a: Location, b: Location): Double {
        val avgLatRad = (a.latitude + b.latitude) / 2 * Math.PI / 180
        val dLat = (a.latitude - b.latitude) * Math.PI / 180
        val dLon = (a.longitude - b.longitude) * Math.PI / 180 * cos(avgLatRad)
        return EARTH_RADIUS_KM * sqrt(dLat * dLat + dLon * dLon)
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
        // Publish to MQTT before waiting for the delivery-alignment delay so
        // that retained topics are current by the time HA automations fire.
        // deliverToday/Tonight both start with awaitDeliveryAlignment() (60 s
        // window) and then await TTS — Home Assistant automations timed to the
        // scheduled alarm would race with the retained topics if we published
        // after that. The publisher caps each prose / image / audio publish at
        // ~11 s (two attempts × the 5-second timeout + a short retry delay), so
        // a permanently-down broker delays notification by at most that, well
        // within the 60-second alignment window.
        // Prose publish — outcome persisted for the Smart Home settings UI.
        val mqttOutcome = app.mqttPublisher.publishIfEnabled(insight.period, prose)
        // Image publish — fire-and-forget alongside the prose.
        insight.outfit?.let { outfit ->
            runCatching {
                val formatter = InsightFormatter.forRegion(applicationContext, prefs.region)
                val info = outfitCardInfoLines(
                    context = applicationContext,
                    formatter = formatter,
                    hourly = insight.hourly,
                    temperatureUnit = prefs.temperatureUnit,
                )
                val header = applicationContext.getString(
                    if (insight.period == ForecastPeriod.TODAY) R.string.outfit_card_header_today
                    else R.string.outfit_card_header_tonight
                )
                val png = renderOutfitCard(
                    context = applicationContext,
                    outfit = outfit,
                    header = header,
                    prose = prose,
                    tempLine = info.tempLine,
                    rainLine = info.rainLine,
                    tempFillFraction = info.tempFillFraction,
                    rainFillFraction = info.rainFillFraction,
                    topColors = prefs.outfitTopColors,
                    bottomColors = prefs.outfitBottomColors,
                )
                app.mqttPublisher.publishImageIfEnabled(insight.period, png)
            }.onFailure { t ->
                if (t is CancellationException) throw t
                DiagLog.w(TAG, "Outfit image MQTT publish failed.", t)
            }
        }
        when (insight.period) {
            ForecastPeriod.TODAY -> deliverToday(insight, prefs, prose)
            ForecastPeriod.TONIGHT -> deliverTonight(insight, prefs, prose)
        }
        // Status persistence is best-effort: a DataStore I/O failure here must
        // not surface as a deliver() exception, which would cause the
        // cached-delivery path to fall through to a fresh fetch and duplicate
        // the user-facing notification.
        runCatching {
            when (mqttOutcome) {
                is MqttPublishOutcome.NotConfigured -> Unit
                is MqttPublishOutcome.Success -> app.settingsRepository.setMqttLastError(null)
                is MqttPublishOutcome.Failure -> app.settingsRepository.setMqttLastError(mqttOutcome.message)
            }
        }
    }

    private suspend fun deliverToday(insight: Insight, prefs: UserPreferences, prose: String) {
        awaitDeliveryAlignment()
        val mode = prefs.deliveryMode
        if (mode == DeliveryMode.NOTIFICATION_ONLY || mode == DeliveryMode.NOTIFICATION_AND_TTS) {
            app.insightNotifier.notify(insight, prose, prefs.outfitTopColors)
            recordDeliveryDelay(ForecastPeriod.TODAY)
        }
        val ttsRequested = mode == DeliveryMode.TTS_ONLY || mode == DeliveryMode.NOTIFICATION_AND_TTS
        if (shouldSpeakNow(ttsRequested, prefs)) {
            speakWithFallback(insight.period, ttsUtterance(insight, prefs), prefs)
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

    /**
     * Tonight delivery honours the user's tonight-specific [DeliveryMode]
     * (the night card has its own delivery selector, separate from the day
     * card's) and is also event-gated:
     *  - Notification posts only when the delivery mode includes notifications,
     *    matching today's behaviour. The notifier still picks the silent channel
     *    vs the default-priority channel based on whether there are calendar
     *    events tonight.
     *  - TTS only speaks when the delivery mode includes TTS *and* there are
     *    events tonight. If the evening is empty there's nothing to interrupt
     *    for, even on a TTS-enabled mode.
     */
    // Region-language prose for notification text and the audit log. Spoken
    // playback is rendered separately through ttsUtterance() so explicit voice
    // locales like de-AT speak German even when the app UI remains English.
    private fun formatProse(insight: Insight, prefs: UserPreferences): String =
        InsightFormatter.forRegion(applicationContext, prefs.region, prefs.temperatureUnit)
            .format(insight.summary)

    // TODO(brand-intro): consider prepending "Today's ClothesCast: " / "Tonight's ClothesCast: "
    // here (and mirror it in the SAMPLE_SUMMARY render used by the top-level
    // runTtsPreview function in ui/settings/VoiceSettings.kt) once the voice
    // preview's phrasing settles — the brand-name pronunciation check that the
    // per-locale settings_tts_test_sample used to give us is currently absent
    // from both the preview and the real briefing.
    private fun ttsUtterance(insight: Insight, prefs: UserPreferences): InsightTtsUtterance =
        insightTtsUtterance(
            context = applicationContext,
            summary = insight.summary,
            region = prefs.region,
            voiceLocale = prefs.voiceLocale,
            temperatureUnit = prefs.temperatureUnit,
        )

    private suspend fun deliverTonight(insight: Insight, prefs: UserPreferences, prose: String) {
        awaitDeliveryAlignment()
        val mode = prefs.tonightDeliveryMode
        val canNotify = mode == DeliveryMode.NOTIFICATION_ONLY || mode == DeliveryMode.NOTIFICATION_AND_TTS
        // The notify-only-on-events toggle skips the notification entirely on
        // empty evenings — the cache is still written and the widget updated
        // upstream, so the user sees fresh state when they open the app. Only
        // log "skipping" when a notification would otherwise have posted.
        val skipEmptyEveningNotification = canNotify && prefs.tonightNotifyOnlyOnEvents && !insight.hasEvents
        if (skipEmptyEveningNotification) {
            DiagLog.i(TAG, "Tonight insight has no events and notify-only-on-events is on; skipping notification.")
        } else if (canNotify) {
            app.tonightInsightNotifier.notify(insight, prose, prefs.outfitTopColors)
            recordDeliveryDelay(ForecastPeriod.TONIGHT)
        }
        if (!insight.hasEvents) {
            DiagLog.i(TAG, "Tonight insight has no events; skipping TTS.")
            return
        }
        val ttsRequested = mode == DeliveryMode.TTS_ONLY || mode == DeliveryMode.NOTIFICATION_AND_TTS
        if (shouldSpeakNow(ttsRequested, prefs)) {
            speakWithFallback(insight.period, ttsUtterance(insight, prefs), prefs)
        }
    }

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
     * [SKIP_TTS_AT_HOME_MAX_AGE_MS] is the freshness ceiling: anything older
     * is treated as unavailable, which falls open to speaking.
     */
    private suspend fun shouldSpeakNow(ttsRequested: Boolean, prefs: UserPreferences): Boolean {
        if (!ttsRequested) return false
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

    /**
     * Speaks via the user-preferred engine; on Gemini failure (network, quota,
     * missing key) falls back to the on-device engine so the user still hears
     * something. We never let a TTS error fail the worker — the notification
     * path is the primary delivery channel and has already fired by this point.
     */
    private suspend fun speakWithFallback(
        period: ForecastPeriod,
        utterance: InsightTtsUtterance,
        prefs: UserPreferences,
    ) {
        withSpeechAudioFocus(applicationContext) {
            when (prefs.ttsEngine) {
                TtsEngine.GEMINI -> {
                    try {
                        val speaker = GeminiTtsSpeaker(
                            app.geminiTtsClient,
                            voiceName = prefs.geminiVoice,
                            style = prefs.ttsStyle,
                        )
                        val audio = speaker.synthesize(utterance.text, utterance.locale)
                        // Publish before local playback so a Hub speaking off
                        // the retained audio topic doesn't trail the phone.
                        // Mirrors the image publish in deliver(): the call is
                        // capped at two attempts × MqttPublisher.DEFAULT_PUBLISH_TIMEOUT_MS
                        // plus the retry delay, so a broker outage costs at
                        // most ~11 s of TTS-path latency.
                        runCatching {
                            app.mqttPublisher.publishAudioIfEnabled(
                                period,
                                WavEncoder.encode(audio),
                            )
                        }.onFailure { t ->
                            if (t is CancellationException) throw t
                            DiagLog.w(TAG, "TTS audio MQTT publish failed.", t)
                        }
                        speaker.play(audio)
                        return@withSpeechAudioFocus
                    } catch (t: Throwable) {
                        DiagLog.w(TAG, "Gemini TTS failed; falling back to device TTS.", t)
                    }
                }
                TtsEngine.DEVICE -> Unit
            }
            try {
                app.deviceTtsSpeaker(prefs.deviceVoice).speak(utterance.text, utterance.locale)
            } catch (t: Throwable) {
                DiagLog.w(TAG, "Device TTS failed; insight is still posted as notification.", t)
            }
        }
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
        private const val KEY_SKIP_TELEMETRY = "skip_telemetry"

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
        private const val REUSE_LABEL_RADIUS_KM = 25.0

        private const val EARTH_RADIUS_KM = 6371.0

        /** Set true via [enqueueOneShot] when the user explicitly taps Refresh. */
        private const val KEY_FORCE_REFRESH = "force_refresh"

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
        private const val KEY_REQUESTED_EPOCH_DAY = "requested_epoch_day"

        /** Which slice of the day this run is for; defaults to TODAY when absent. */
        private const val KEY_PERIOD = "period"

        /**
         * Set true via [enqueueLocationCacheRefresh] when the user toggles
         * device location ON in Settings. The worker resolves + caches the
         * device fix and exits without delivering an insight, so the user
         * doesn't get a duplicate notification at e.g. 10am after the
         * morning run already fired at 7am.
         */
        private const val KEY_CACHE_LOCATION_ONLY = "cache_location_only"

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
    }
}
