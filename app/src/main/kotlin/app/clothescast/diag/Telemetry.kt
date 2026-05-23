package app.clothescast.diag

import android.content.Context
import android.os.Build
import android.os.Bundle
import app.clothescast.BuildConfig
import app.clothescast.data.SettingsRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Bridges the user's Privacy → "Send crash + usage data" toggle to Firebase
 * Analytics + Crashlytics collection flags, and mirrors the user's language /
 * accent / TTS configuration into Firebase Analytics user properties so
 * aggregate reports can break usage events down by configuration.
 *
 * The contract for what may / may not appear in those payloads is in
 * PRIVACY.md — calendar event data, location, insight prose, notification
 * text, and API keys are out of scope. This class deliberately does NOT set
 * Crashlytics custom keys from any of those, and the user properties it does
 * set are short configuration strings (enum names, BCP-47 locale tags, voice
 * IDs) — no user content, no identifiers.
 *
 * No-ops if Firebase didn't initialise — i.e. when this build was assembled
 * without `app/google-services.json` (CI). The .gitignore-d JSON is the only
 * thing keeping Firebase from auto-starting via FirebaseInitProvider, so
 * "no JSON, no SDK calls" is the natural quiet path. The Settings toggle
 * still flips the persisted preference in that case so a later build that
 * does have the JSON inherits the user's choice.
 */
object Telemetry {
    /**
     * Captured in [start] so background components (the WorkManager worker,
     * the alarm receiver) can emit events without re-resolving the SDK from
     * a Context. Stays null on builds without google-services.json (CI) or
     * on the OnePlus 8 Pro install-bypass path described in [start], which
     * is how all the logEvent helpers below stay silent on those builds.
     */
    @Volatile
    private var analyticsRef: FirebaseAnalytics? = null

    /**
     * Subscribes to [settings]'s telemetry preference and pushes each change
     * into FirebaseAnalytics + FirebaseCrashlytics. Both SDKs persist their
     * collection flag across launches, so a `false` set here also suppresses
     * the very first crash on next process start before this collector
     * attaches.
     *
     * Also subscribes to [SettingsRepository.analyticsSnapshot] and mirrors
     * each value into a Firebase Analytics user property. Properties are set
     * unconditionally — when collection is disabled the SDK won't send
     * anything anyway, and the property store is local until an event flushes.
     */
    fun start(context: Context, settings: SettingsRepository, scope: CoroutineScope) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val analytics = FirebaseAnalytics.getInstance(context)
        val crashlytics = FirebaseCrashlytics.getInstance()
        // Firebase has shown the app crashing on a OnePlus 8 Pro reporting
        // Android 11 (API 30) against a minSdk=31 APK — the OS package
        // manager normally enforces minSdk at install time, but Test Lab
        // elevated installs and some sideload paths can bypass that. The
        // resulting Crashlytics / Analytics traffic is from a config the app
        // was never built for, so silence both SDKs synchronously here
        // (before the collectors below race a startup crash) and skip the
        // user-preference subscription entirely — the persisted disable
        // survives across launches.
        if (Build.VERSION.SDK_INT < BuildConfig.MIN_SDK_VERSION) {
            analytics.setAnalyticsCollectionEnabled(false)
            crashlytics.setCrashlyticsCollectionEnabled(false)
            return
        }
        analyticsRef = analytics
        scope.launch {
            settings.preferences
                .map { it.telemetryEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    analytics.setAnalyticsCollectionEnabled(enabled)
                    crashlytics.setCrashlyticsCollectionEnabled(enabled)
                }
        }
        scope.launch {
            settings.analyticsSnapshot
                .distinctUntilChanged()
                .collect { snapshot -> snapshot.applyTo(analytics) }
        }
        scope.launch {
            settings.settingsSnapshot
                .distinctUntilChanged()
                .collect { snapshot -> logSettingsSnapshot(snapshot) }
        }
        scope.launch {
            settings.clothesRulesSnapshot
                .distinctUntilChanged()
                .collect { snapshot -> logClothesRulesSnapshot(snapshot) }
        }
    }

    /**
     * Emits an `api_call` event. Called inline with every Open-Meteo / Gemini
     * request — see [TelemetryApiCallLogger] for the offline-filter wrapper
     * that decides which events make it here. Params:
     *
     *  - `endpoint` (string, ≤40 chars): see [app.clothescast.core.data.diag.ApiEndpoints].
     *  - `outcome` (string): `success`, `http_error`, `timeout`, `network_error`, `other_error`.
     *  - `status_code` (long): HTTP status when [outcome] is `success` / `http_error`, else 0.
     *  - `latency_ms` (long): wall-clock from request start.
     *
     * No-op when Firebase didn't initialise (builds without google-services.json),
     * and when the user's Privacy toggle has set collection to disabled the SDK
     * itself silently drops the event — no per-call gate needed here.
     */
    fun logApiCall(endpoint: String, outcome: String, statusCode: Int, latencyMs: Long) {
        val analytics = analyticsRef ?: return
        val params = Bundle().apply {
            putString(PARAM_ENDPOINT, endpoint)
            putString(PARAM_OUTCOME, outcome)
            putLong(PARAM_STATUS_CODE, statusCode.toLong())
            putLong(PARAM_LATENCY_MS, latencyMs)
        }
        analytics.logEvent(EVENT_API_CALL, params)
    }

    /**
     * Emits a `notification_delivery` event when the daily / tonight alarm
     * pipeline posts a notification. Params:
     *
     *  - `period` (string): `today` or `tonight`.
     *  - `alarm_delay_ms` (long): scheduled-fire time → AlarmReceiver fire time.
     *    Catches Doze deferral on `setExactAndAllowWhileIdle`. Always ≥ 0.
     *  - `total_delay_ms` (long): scheduled-fire time → moment the notification
     *    posted. Adds in WorkManager constraint waits (NetworkType.CONNECTED)
     *    and our own pipeline latency. Always ≥ alarm_delay_ms.
     *
     * Only emitted when the post actually happens, so a powered-off / offline
     * device that misses the alarm entirely simply doesn't show up in the
     * stream — matching the user's request to filter those out.
     */
    fun logNotificationDelivery(period: String, alarmDelayMs: Long, totalDelayMs: Long) {
        val analytics = analyticsRef ?: return
        val params = Bundle().apply {
            putString(PARAM_PERIOD, period)
            putLong(PARAM_ALARM_DELAY_MS, alarmDelayMs)
            putLong(PARAM_TOTAL_DELAY_MS, totalDelayMs)
        }
        analytics.logEvent(EVENT_NOTIFICATION_DELIVERY, params)
    }

    /**
     * Emits a `daily_refresh` event when a scheduled / force-refresh run of
     * the FetchAndNotifyWorker reaches a terminal Result. Params:
     *
     *  - `slot` (string): `today` or `tonight`.
     *  - `outcome` (string): `success`, `forecast_error`, `no_location`,
     *    `cancelled`, or `other_error`. See [classifyDailyRefreshReason].
     *  - `latency_ms` (long): wall-clock from `doWork()` entry to the
     *    terminal Result.
     *
     * Result.retry is not terminal and does not emit — WorkManager retries
     * silently on backoff, so the offline / transient-network case shows up
     * only when it eventually succeeds (or gives up and fails for some other
     * reason). That keeps the stream reflecting "did the user actually get a
     * refresh today?" rather than "how many retry hops did it take."
     */
    fun logDailyRefresh(slot: String, outcome: String, latencyMs: Long) {
        val analytics = analyticsRef ?: return
        val params = Bundle().apply {
            putString(PARAM_SLOT, slot)
            putString(PARAM_OUTCOME, outcome)
            putLong(PARAM_LATENCY_MS, latencyMs)
        }
        analytics.logEvent(EVENT_DAILY_REFRESH, params)
    }

    /**
     * Emits a `settings_snapshot` event whose params mirror the non-voice user
     * settings. Fires once per process start (the DataStore-backed flow
     * replays its current value to each new subscriber) and again every time
     * the resolved snapshot's `equals` changes within the process — i.e.
     * whenever the user toggles a setting. The per-launch baseline is
     * intentional: it lets reports see "what's the default-vs-customised
     * mix across the population?" without requiring the user to interact.
     */
    private fun logSettingsSnapshot(snapshot: SettingsSnapshot) {
        val analytics = analyticsRef ?: return
        val params = Bundle().apply {
            putString("temperature_unit_setting", snapshot.temperatureUnitSetting)
            putString("temperature_unit_effective", snapshot.temperatureUnitEffective)
            putString("distance_unit_setting", snapshot.distanceUnitSetting)
            putString("distance_unit_effective", snapshot.distanceUnitEffective)
            putString("delivery_mode_daily", snapshot.deliveryModeDaily)
            putString("delivery_mode_tonight", snapshot.deliveryModeTonight)
            putString("theme_mode", snapshot.themeMode)
            putString("color_palette", snapshot.colorPalette)
            putString("default_bottom", snapshot.defaultBottom)
            putString("default_top", snapshot.defaultTop)
            putString("daily_time_bucket_hour", snapshot.dailyTimeBucketHour)
            putLong("daily_days_count", snapshot.dailyDaysCount.toLong())
            putLong("tonight_enabled", snapshot.tonightEnabled.toLongFlag())
            putString("tonight_time_bucket_hour", snapshot.tonightTimeBucketHour)
            putLong("tonight_days_count", snapshot.tonightDaysCount.toLong())
            putLong("tonight_notify_only_on_events", snapshot.tonightNotifyOnlyOnEvents.toLongFlag())
            putLong("daily_mention_evening_events", snapshot.dailyMentionEveningEvents.toLongFlag())
            putString("clothes_mention_mode", snapshot.clothesMentionMode)
            putString("range_format", snapshot.rangeFormat)
            putString("clothes_format", snapshot.clothesFormat)
            putString("rain_accessory", snapshot.rainAccessory)
            putLong("delta_threshold_c", snapshot.deltaThresholdC.toLong())
            putLong("use_calendar_events", snapshot.useCalendarEvents.toLongFlag())
            putLong("skip_tts_at_home", snapshot.skipTtsAtHome.toLongFlag())
            putLong("home_location_configured", snapshot.homeLocationConfigured.toLongFlag())
        }
        analytics.logEvent(EVENT_SETTINGS_SNAPSHOT, params)
    }

    /**
     * Emits a `clothes_rules_snapshot` event whose params describe the user's
     * clothes-rule customisation. Per-category deltas are integer Celsius
     * differences from the catalog default, clamped to ±5°C — see
     * [ClothesRulesSnapshot] for the bucket format.
     *
     * Same emission cadence as [logSettingsSnapshot]: once on process start
     * (DataStore replay) and again on each subsequent change to the user's
     * rule list within the process.
     */
    private fun logClothesRulesSnapshot(snapshot: ClothesRulesSnapshot) {
        val analytics = analyticsRef ?: return
        val params = Bundle().apply {
            putLong("customised_count", snapshot.customisedCount.toLong())
            putLong("extra_rules_count", snapshot.extraRulesCount.toLong())
            putString("categories_customised", snapshot.categoriesCustomised)
            putLong("all_defaults", snapshot.allDefaults.toLongFlag())
            putString("sweater_delta_c", snapshot.sweaterDeltaC)
            putString("jacket_delta_c", snapshot.jacketDeltaC)
            putString("coat_delta_c", snapshot.coatDeltaC)
            putString("shorts_delta_c", snapshot.shortsDeltaC)
        }
        analytics.logEvent(EVENT_CLOTHES_RULES_SNAPSHOT, params)
    }

    /** Booleans ship as `0` / `1` longs so they slice cleanly in Firebase reports. */
    private fun Boolean.toLongFlag(): Long = if (this) 1L else 0L

    // Firebase Analytics caps: event names ≤40 chars, param names ≤40 chars,
    // string values ≤100 chars. All identifiers below comfortably fit.
    private const val EVENT_API_CALL = "api_call"
    private const val EVENT_NOTIFICATION_DELIVERY = "notification_delivery"
    private const val EVENT_DAILY_REFRESH = "daily_refresh"
    private const val EVENT_SETTINGS_SNAPSHOT = "settings_snapshot"
    private const val EVENT_CLOTHES_RULES_SNAPSHOT = "clothes_rules_snapshot"
    private const val PARAM_ENDPOINT = "endpoint"
    private const val PARAM_OUTCOME = "outcome"
    private const val PARAM_STATUS_CODE = "status_code"
    private const val PARAM_LATENCY_MS = "latency_ms"
    private const val PARAM_PERIOD = "period"
    private const val PARAM_SLOT = "slot"
    private const val PARAM_ALARM_DELAY_MS = "alarm_delay_ms"
    private const val PARAM_TOTAL_DELAY_MS = "total_delay_ms"
}

/**
 * Pure mapping from a [androidx.work.ListenableWorker.Result.Failure] reason
 * code (the `KEY_REASON` string stamped by FetchAndNotifyWorker) onto the
 * `outcome` bucket of the `daily_refresh` event. Extracted so the
 * classification is unit-testable without dragging WorkManager into the test
 * classpath.
 */
internal fun classifyDailyRefreshReason(reason: String?): String = when (reason) {
    "no_location" -> "no_location"
    "unexpected_http" -> "forecast_error"
    else -> "other_error"
}

/**
 * Pushes each field of [this] onto [analytics] as a user property. Values are
 * truncated to Firebase's 36-char per-property limit — voice IDs in particular
 * can grow beyond that with future engines, and Firebase silently drops
 * oversized values rather than truncating them itself.
 */
internal fun SettingsAnalyticsSnapshot.applyTo(analytics: FirebaseAnalytics) {
    analytics.setUserProperty("region_default", regionDefault.cap())
    analytics.setUserProperty("region_override", regionOverride.cap())
    analytics.setUserProperty("region_effective", regionEffective.cap())
    analytics.setUserProperty("voice_locale_default", voiceLocaleDefault.cap())
    analytics.setUserProperty("voice_locale_override", voiceLocaleOverride.cap())
    analytics.setUserProperty("voice_locale_effective", voiceLocaleEffective.cap())
    analytics.setUserProperty("tts_engine_default", ttsEngineDefault.cap())
    analytics.setUserProperty("tts_engine_override", ttsEngineOverride.cap())
    analytics.setUserProperty("tts_engine_effective", ttsEngineEffective.cap())
    analytics.setUserProperty("tts_style_default", ttsStyleDefault.cap())
    analytics.setUserProperty("tts_style_override", ttsStyleOverride.cap())
    analytics.setUserProperty("tts_style_effective", ttsStyleEffective.cap())
    analytics.setUserProperty("gemini_voice_default", geminiVoiceDefault.cap())
    analytics.setUserProperty("gemini_voice_override", geminiVoiceOverride.cap())
    analytics.setUserProperty("gemini_voice_effective", geminiVoiceEffective.cap())
    analytics.setUserProperty("device_voice_default", deviceVoiceDefault.cap())
    analytics.setUserProperty("device_voice_override", deviceVoiceOverride.cap())
    analytics.setUserProperty("device_voice_effective", deviceVoiceEffective.cap())
}

private fun String.cap(): String = if (length <= 36) this else take(36)
