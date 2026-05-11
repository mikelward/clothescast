package app.clothescast.diag

import android.content.Context
import android.os.Build
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
    }
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
