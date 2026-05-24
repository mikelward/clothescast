package app.clothescast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.DistanceUnitSetting
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.HolidayCountrySelection
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.RainAccessory
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TemperatureUnitSetting
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.core.domain.model.thresholdC
import app.clothescast.core.domain.model.withThresholdC
import app.clothescast.diag.ClothesRulesSnapshot
import app.clothescast.diag.SettingsAnalyticsSnapshot
import app.clothescast.diag.SettingsSnapshot
import app.clothescast.tts.resolve
import app.clothescast.tts.toJavaLocale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Persists [UserPreferences] in DataStore Preferences.
 *
 * `Schedule.zoneId` is intentionally NOT persisted — it's resolved from
 * [zoneIdProvider] (defaulting to the current system zone) every time the flow
 * emits. This way, if the user travels or DST flips, the next read picks up the
 * correct zone without us having to migrate stored data.
 *
 * Constructor takes the [DataStore] for unit-test injection;
 * [SettingsRepository.create] is the production factory.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val systemLocaleProvider: () -> Locale = { Locale.getDefault() },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val preferences: Flow<UserPreferences> = dataStore.data.map { prefs -> prefs.toUserPreferences() }

    /**
     * Default / override / effective view of the language, accent, and TTS
     * settings, intended for Firebase Analytics user properties. Driven by the
     * raw DataStore data so that "is this value stored?" — the difference
     * between [SettingsAnalyticsSnapshot.UNSET] and an explicit choice — is
     * observable, which [preferences] alone smears over because it always
     * resolves a default.
     */
    val analyticsSnapshot: Flow<SettingsAnalyticsSnapshot> = dataStore.data.map { it.toAnalyticsSnapshot() }

    /**
     * Non-voice user settings flattened for the Firebase Analytics
     * `settings_snapshot` event. Driven by the resolved [UserPreferences] so
     * the bucketed time-of-day and effective unit values reflect what the
     * user actually sees, not the raw DataStore key.
     */
    val settingsSnapshot: Flow<SettingsSnapshot> = preferences.map { it.toSettingsSnapshot() }

    /**
     * Clothes-rule customisation flattened for the Firebase Analytics
     * `clothes_rules_snapshot` event. Per-category deltas are integer °C
     * differences from [ClothesRule.DEFAULTS], clamped to ±5°C.
     */
    val clothesRulesSnapshot: Flow<ClothesRulesSnapshot> =
        preferences.map { ClothesRulesSnapshot.from(it.clothesRules) }

    /**
     * The available-version code the user has dismissed the in-app update
     * banner for. `0` means "never dismissed" — any non-zero
     * `availableVersionCode` from Play surfaces the banner. Stored separately
     * from [UserPreferences] because it isn't a user-visible preference and
     * doesn't need to flow through the rest of the settings UI.
     */
    val dismissedUpdateVersion: Flow<Int> = dataStore.data.map {
        it[DISMISSED_UPDATE_VERSION] ?: 0
    }

    suspend fun setDismissedUpdateVersion(versionCode: Int) {
        dataStore.edit { it[DISMISSED_UPDATE_VERSION] = versionCode }
    }

    /**
     * The git SHA the user has dismissed the local-build banner for. Empty
     * means "never dismissed". Keyed on SHA so installing a build from a new
     * commit automatically resurfaces the banner — the user sees it once per
     * commit, not once per launch.
     */
    val dismissedLocalBuildSha: Flow<String> = dataStore.data.map {
        it[DISMISSED_LOCAL_BUILD_SHA] ?: ""
    }

    suspend fun setDismissedLocalBuildSha(sha: String) {
        dataStore.edit { it[DISMISSED_LOCAL_BUILD_SHA] = sha }
    }

    /**
     * Whether the user has ticked "Don't show this again" on the bug-report
     * consent dialog. When `true`, the three "share bug report" entry points
     * (Today overflow, About page, post-crash banner) skip the dialog and
     * open the share sheet directly. Default `false` — first invocation
     * always surfaces the disclosure.
     *
     * Stored alongside the other one-off ack flags rather than in
     * [UserPreferences] because it isn't a user-visible setting (no
     * Settings UI exposes it; reinstall is the only reset path).
     */
    val bugReportConsentAcknowledged: Flow<Boolean> = dataStore.data.map {
        it[BUG_REPORT_CONSENT_ACKED] == true
    }

    suspend fun setBugReportConsentAcknowledged(acked: Boolean) {
        dataStore.edit { it[BUG_REPORT_CONSENT_ACKED] = acked }
    }

    suspend fun setSchedule(time: LocalTime, days: Set<DayOfWeek>) {
        require(days.isNotEmpty()) { "Schedule must include at least one day" }
        dataStore.edit { prefs ->
            prefs[SCHEDULE_TIME] = TIME_FORMAT.format(time)
            prefs[SCHEDULE_DAYS] = days.map { it.name }.toSet()
        }
    }

    suspend fun setTonightSchedule(time: LocalTime, days: Set<DayOfWeek>) {
        require(days.isNotEmpty()) { "Schedule must include at least one day" }
        dataStore.edit { prefs ->
            prefs[TONIGHT_TIME] = TIME_FORMAT.format(time)
            prefs[TONIGHT_DAYS] = days.map { it.name }.toSet()
        }
    }

    suspend fun setTonightEnabled(enabled: Boolean) {
        dataStore.edit { it[TONIGHT_ENABLED] = enabled }
    }

    suspend fun setTonightNotifyOnlyOnEvents(enabled: Boolean) {
        dataStore.edit { it[TONIGHT_NOTIFY_ONLY_ON_EVENTS] = enabled }
    }

    suspend fun setDailyMentionEveningEvents(enabled: Boolean) {
        dataStore.edit { it[DAILY_MENTION_EVENING_EVENTS] = enabled }
    }

    suspend fun setClothesMentionMode(mode: ClothesMentionMode) {
        dataStore.edit { it[CLOTHES_MENTION_MODE] = mode.name }
    }

    suspend fun setRangeFormat(format: RangeFormat) {
        dataStore.edit { it[INSIGHT_RANGE_FORMAT] = format.name }
    }

    suspend fun setClothesFormat(format: ClothesFormat) {
        dataStore.edit { it[INSIGHT_CLOTHES_FORMAT] = format.name }
    }

    suspend fun setRainAccessory(accessory: RainAccessory) {
        dataStore.edit { it[RAIN_ACCESSORY] = accessory.name }
    }

    suspend fun setDeltaThresholdC(thresholdC: Double?) {
        dataStore.edit { prefs ->
            if (thresholdC == null) prefs[INSIGHT_DELTA_THRESHOLD_C] = DELTA_THRESHOLD_OFF
            else prefs[INSIGHT_DELTA_THRESHOLD_C] = thresholdC
        }
    }

    suspend fun setDeliveryMode(mode: DeliveryMode) {
        dataStore.edit { it[DELIVERY_MODE] = mode.name }
    }

    suspend fun setTonightDeliveryMode(mode: DeliveryMode) {
        dataStore.edit { it[TONIGHT_DELIVERY_MODE] = mode.name }
    }

    suspend fun setRegion(region: Region) {
        dataStore.edit { it[REGION] = region.name }
    }

    suspend fun setTemperatureUnitSetting(setting: TemperatureUnitSetting) {
        dataStore.edit { prefs ->
            when (setting) {
                TemperatureUnitSetting.AUTO -> prefs.remove(TEMPERATURE_UNIT)
                else -> prefs[TEMPERATURE_UNIT] = setting.name
            }
        }
    }

    suspend fun setDistanceUnitSetting(setting: DistanceUnitSetting) {
        dataStore.edit { prefs ->
            when (setting) {
                DistanceUnitSetting.AUTO -> prefs.remove(DISTANCE_UNIT)
                else -> prefs[DISTANCE_UNIT] = setting.name
            }
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setClothesRules(rules: List<ClothesRule>) {
        dataStore.edit { it[CLOTHES_RULES] = json.encodeToString(rules.map { rule -> rule.toDto() }) }
    }

    suspend fun setDefaultBottom(bottom: OutfitSuggestion.Bottom) {
        dataStore.edit { it[DEFAULT_BOTTOM] = bottom.name }
    }

    suspend fun setDefaultTop(top: OutfitSuggestion.Top) {
        dataStore.edit { it[DEFAULT_TOP] = top.name }
    }

    suspend fun setLocation(location: Location) {
        dataStore.edit { prefs ->
            prefs[LOCATION_LAT] = location.latitude
            prefs[LOCATION_LON] = location.longitude
            location.displayName?.let { prefs[LOCATION_NAME] = it } ?: prefs.remove(LOCATION_NAME)
            location.countryCode
                ?.takeIf { it.isNotBlank() }
                ?.let { prefs[LOCATION_COUNTRY] = it.uppercase() }
                ?: prefs.remove(LOCATION_COUNTRY)
        }
    }

    suspend fun clearLocation() {
        dataStore.edit { prefs ->
            prefs.remove(LOCATION_LAT)
            prefs.remove(LOCATION_LON)
            prefs.remove(LOCATION_NAME)
            prefs.remove(LOCATION_COUNTRY)
        }
    }

    suspend fun setUseDeviceLocation(enabled: Boolean) {
        dataStore.edit { it[USE_DEVICE_LOCATION] = enabled }
    }

    suspend fun setHomeLocation(location: Location?) {
        dataStore.edit { prefs ->
            if (location == null) {
                prefs.remove(HOME_LAT)
                prefs.remove(HOME_LON)
                prefs.remove(HOME_NAME)
                prefs.remove(HOME_COUNTRY)
            } else {
                prefs[HOME_LAT] = location.latitude
                prefs[HOME_LON] = location.longitude
                location.displayName
                    ?.let { prefs[HOME_NAME] = it }
                    ?: prefs.remove(HOME_NAME)
                location.countryCode
                    ?.takeIf { it.isNotBlank() }
                    ?.let { prefs[HOME_COUNTRY] = it.uppercase() }
                    ?: prefs.remove(HOME_COUNTRY)
            }
        }
    }

    suspend fun setSkipTtsAtHome(enabled: Boolean) {
        dataStore.edit { it[SKIP_TTS_AT_HOME] = enabled }
    }

    suspend fun setTtsEngine(engine: TtsEngine) {
        dataStore.edit { it[TTS_ENGINE] = engine.name }
    }

    /**
     * Writes [engine] only if no TTS engine has been explicitly stored. Used by
     * onboarding to flip the default from DEVICE to GEMINI when the user enters
     * a Gemini key, without clobbering an explicit choice the user later made
     * in Settings if they re-enter onboarding.
     */
    suspend fun setTtsEngineIfUnset(engine: TtsEngine) {
        dataStore.edit { prefs ->
            if (prefs[TTS_ENGINE] == null) prefs[TTS_ENGINE] = engine.name
        }
    }

    suspend fun setGeminiVoice(voice: String) {
        dataStore.edit { it[GEMINI_VOICE] = voice }
    }

    suspend fun setTtsStyle(style: TtsStyle) {
        dataStore.edit { it[TTS_STYLE] = style.name }
    }

    suspend fun setDeviceVoice(voice: String?) {
        dataStore.edit {
            // Null clears the pin → speaker reverts to auto-pick; the worker
            // and Settings preview both treat absent and explicit-null the same.
            if (voice.isNullOrBlank()) it.remove(DEVICE_VOICE) else it[DEVICE_VOICE] = voice
        }
    }

    suspend fun setVoiceLocale(locale: VoiceLocale) {
        dataStore.edit { it[VOICE_LOCALE] = locale.name }
    }

    /**
     * Master calendar-access switch. Turning it off clears the three
     * per-feature toggles in the *same* edit, so a later single-feature
     * re-enable (which switches the master back on) doesn't silently revive the
     * others. Done as one atomic [androidx.datastore.preferences.core.edit] so a
     * rapid enable-then-disable can't interleave with [setUseCalendarEvents] and
     * co. and leave the master stuck on against the user's last action.
     */
    suspend fun setCalendarEnabled(enabled: Boolean) {
        dataStore.edit {
            it[CALENDAR_ENABLED] = enabled
            if (!enabled) {
                it[USE_CALENDAR_EVENTS] = false
                it[THEME_FROM_CALENDAR_HOLIDAYS] = false
                it[THEME_FROM_CALENDAR_BIRTHDAYS] = false
            }
        }
    }

    // Enabling any per-feature toggle implies calendar access, so each setter
    // also flips the master on in the same atomic edit — never as a separate
    // write that could be reordered against a concurrent master change.
    suspend fun setUseCalendarEvents(enabled: Boolean) {
        dataStore.edit {
            it[USE_CALENDAR_EVENTS] = enabled
            if (enabled) it[CALENDAR_ENABLED] = true
        }
    }

    suspend fun setThemeFromCalendarHolidays(enabled: Boolean) {
        dataStore.edit {
            it[THEME_FROM_CALENDAR_HOLIDAYS] = enabled
            if (enabled) it[CALENDAR_ENABLED] = true
        }
    }

    suspend fun setThemeFromCalendarBirthdays(enabled: Boolean) {
        dataStore.edit {
            it[THEME_FROM_CALENDAR_BIRTHDAYS] = enabled
            if (enabled) it[CALENDAR_ENABLED] = true
        }
    }

    suspend fun setCelebrationCardDismissed(dismissed: Boolean) {
        dataStore.edit { it[CELEBRATION_CARD_DISMISSED] = dismissed }
    }

    /**
     * Bumps [UserPreferences.calendarPermissionRecheckTick] to the current
     * wall-clock millis so the preferences flow re-emits. Use after the
     * user re-grants `READ_CALENDAR` from the in-app permission chip so
     * downstream consumers (the `TodayViewModel` calendar-events read)
     * pick up the new permission state without waiting for a midnight
     * rollover or unrelated pref edit. Always writes a fresh value so
     * DataStore's equal-value short-circuit can't swallow the emit.
     */
    suspend fun markCalendarPermissionRechecked() {
        dataStore.edit { it[CALENDAR_PERMISSION_RECHECK_TICK] = System.currentTimeMillis() }
    }

    suspend fun setTelemetryEnabled(enabled: Boolean) {
        dataStore.edit { it[TELEMETRY_ENABLED] = enabled }
    }

    suspend fun setMqttBridgeEnabled(enabled: Boolean) {
        dataStore.edit { it[MQTT_BRIDGE_ENABLED] = enabled }
    }

    /**
     * Persists the user's chosen Cast destination. Pass null [routeId] to
     * clear the pick (Settings shows "No display selected"); blank values
     * are coerced to null so a partially-cleared field doesn't surface as
     * an empty string on the next emission. [routeName] is the friendly
     * label captured at pick time — cached so the Settings row stays
     * readable when the display is powered off.
     */
    suspend fun setCastRoute(routeId: String?, routeName: String?) {
        dataStore.edit { prefs ->
            val cleanId = routeId?.trim()?.takeIf { it.isNotBlank() }
            val cleanName = routeName?.trim()?.takeIf { it.isNotBlank() }
            if (cleanId == null) {
                prefs.remove(CAST_ROUTE_ID)
                prefs.remove(CAST_ROUTE_NAME)
            } else {
                prefs[CAST_ROUTE_ID] = cleanId
                if (cleanName != null) prefs[CAST_ROUTE_NAME] = cleanName else prefs.remove(CAST_ROUTE_NAME)
            }
        }
    }

    /**
     * Runtime status of the last cast attempt, separate from user
     * preferences. Mirrors [MqttPublishStatus]; the status row in
     * Settings → Smart Home → Cast reads this to surface "Cast failed:
     * device not found" without polluting [UserPreferences].
     */
    data class CastStatus(
        val errorMessage: String?,
        val recordedAtMs: Long,
        val lastSuccessAtMs: Long = 0L,
    )

    /** Null until the first cast attempt is recorded; thereafter always non-null. */
    val castStatus: Flow<CastStatus?> = dataStore.data.map { prefs ->
        val ms = prefs[CAST_LAST_ERROR_AT_MS] ?: return@map null
        CastStatus(
            errorMessage = prefs[CAST_LAST_ERROR_MSG],
            recordedAtMs = ms,
            lastSuccessAtMs = prefs[CAST_LAST_SUCCESS_AT_MS] ?: 0L,
        )
    }

    /** Pass null [errorMessage] to record a success. */
    suspend fun setCastLastError(errorMessage: String?, atMs: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            prefs[CAST_LAST_ERROR_AT_MS] = atMs
            if (errorMessage != null) {
                prefs[CAST_LAST_ERROR_MSG] = errorMessage
            } else {
                prefs.remove(CAST_LAST_ERROR_MSG)
                prefs[CAST_LAST_SUCCESS_AT_MS] = atMs
            }
        }
    }

    suspend fun setCastMorning(enabled: Boolean) {
        dataStore.edit { it[CAST_MORNING] = enabled }
    }

    suspend fun setCastTonight(enabled: Boolean) {
        dataStore.edit { it[CAST_TONIGHT] = enabled }
    }

    suspend fun setCastSkipPhoneSpeech(enabled: Boolean) {
        dataStore.edit { it[CAST_SKIP_PHONE_SPEECH] = enabled }
    }

    /**
     * Runtime status of the last MQTT publish attempt, separate from user
     * preferences. [errorMessage] is null on success (or no record yet); non-null
     * is the human-readable failure reason. [recordedAtMs] is the epoch-ms wall
     * clock of the attempt (0 when no publish has ever been recorded).
     * [lastSuccessAtMs] is the most recent successful publish (0 when none),
     * tracked separately so the UI can still show "last published" after a
     * later failure has overwritten the attempt timestamp.
     */
    data class MqttPublishStatus(
        val errorMessage: String?,
        val recordedAtMs: Long,
        val lastSuccessAtMs: Long = 0L,
    )

    /**
     * Emits the result of the last MQTT publish attempt. Null until the first
     * attempt is recorded; thereafter always non-null. Updated by both the daily
     * worker and the "Publish now" button.
     */
    val mqttPublishStatus: Flow<MqttPublishStatus?> = dataStore.data.map { prefs ->
        val ms = prefs[MQTT_LAST_ERROR_AT_MS] ?: return@map null
        val msg = prefs[MQTT_LAST_ERROR_MSG]  // null = success
        MqttPublishStatus(
            errorMessage = msg,
            recordedAtMs = ms,
            lastSuccessAtMs = prefs[MQTT_LAST_SUCCESS_AT_MS] ?: 0L,
        )
    }

    /**
     * Persists the outcome of an MQTT publish attempt. Pass null [errorMessage]
     * to record a success (clears any displayed error and stamps the
     * last-published timestamp).
     */
    suspend fun setMqttLastError(errorMessage: String?, atMs: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            prefs[MQTT_LAST_ERROR_AT_MS] = atMs
            if (errorMessage != null) {
                prefs[MQTT_LAST_ERROR_MSG] = errorMessage
            } else {
                prefs.remove(MQTT_LAST_ERROR_MSG)
                prefs[MQTT_LAST_SUCCESS_AT_MS] = atMs
            }
        }
    }

    /**
     * Persists the MQTT broker connection settings for the optional Smart Home
     * bridge. Blank host clears it (and effectively disables the bridge even
     * if [setMqttBridgeEnabled] is true — the publisher gates on a non-blank
     * host). The password is stored separately via [SecureKeyStore] and not
     * touched here. A null [username] is interpreted as "anonymous auth";
     * blank values are coerced to null so a half-typed field doesn't surface
     * as an empty string on the next emission.
     */
    suspend fun setMqttConfig(
        host: String?,
        port: Int,
        useTls: Boolean,
        username: String?,
        topic: String,
    ) {
        dataStore.edit { prefs ->
            val cleanHost = host?.trim()?.takeIf { it.isNotBlank() }
            if (cleanHost == null) prefs.remove(MQTT_HOST) else prefs[MQTT_HOST] = cleanHost
            prefs[MQTT_PORT] = port
            prefs[MQTT_USE_TLS] = useTls
            val cleanUser = username?.trim()?.takeIf { it.isNotBlank() }
            if (cleanUser == null) prefs.remove(MQTT_USER) else prefs[MQTT_USER] = cleanUser
            val cleanTopic = topic.trim().takeIf { it.isNotBlank() }
                ?: UserPreferences.DEFAULT_MQTT_TOPIC
            prefs[MQTT_TOPIC] = cleanTopic
        }
    }

    suspend fun setColorPalette(palette: ColorPalette) {
        dataStore.edit { it[COLOR_PALETTE] = palette.name }
    }

    /**
     * Persists the user's [ForecastModel] selection as the stored enum names,
     * or clears the key when [models] is null — which is the "Auto, derive
     * from current location" state the Forecasters picker exposes via the
     * Auto switch. An explicitly-passed empty set is treated the same as
     * null (clear), guarding against a hand-edited DataStore persisting an
     * empty set that would later trip the fetcher's degenerate-input
     * safety net.
     */
    suspend fun setForecastModels(models: Set<ForecastModel>?) {
        dataStore.edit { prefs ->
            if (models.isNullOrEmpty()) {
                prefs.remove(FORECAST_MODELS)
            } else {
                prefs[FORECAST_MODELS] = models.map { it.name }.toSet()
            }
        }
    }

    /**
     * Sets the per-icon fill colour for [top]. `null` clears any override —
     * the icon then falls back to the baked-in XML colour. Read-modify-write
     * happens inside a single [dataStore.edit] so concurrent edits don't
     * drop entries; serialisation matches [parseOutfitTopColors] below.
     */
    suspend fun setOutfitTopColor(top: OutfitSuggestion.Top, argb: Long?) {
        dataStore.edit { prefs ->
            val current = parseOutfitTopColors(prefs[OUTFIT_TOP_COLORS])
            val updated = if (argb == null) current - top else current + (top to argb)
            prefs[OUTFIT_TOP_COLORS] = json.encodeToString(updated.mapKeys { it.key.name })
        }
    }

    /** Sibling of [setOutfitTopColor] for the bottom-garment slot. */
    suspend fun setOutfitBottomColor(bottom: OutfitSuggestion.Bottom, argb: Long?) {
        dataStore.edit { prefs ->
            val current = parseOutfitBottomColors(prefs[OUTFIT_BOTTOM_COLORS])
            val updated = if (argb == null) current - bottom else current + (bottom to argb)
            prefs[OUTFIT_BOTTOM_COLORS] = json.encodeToString(updated.mapKeys { it.key.name })
        }
    }

    /** Persists the per-holiday override (or clears it back to AUTO). */
    suspend fun setHolidayOverride(id: HolidayId, override: HolidayOverride) {
        dataStore.edit { prefs ->
            val current = parseHolidayOverrides(prefs[HOLIDAY_OVERRIDES])
            val updated = if (override == HolidayOverride.AUTO) current - id else current + (id to override)
            prefs[HOLIDAY_OVERRIDES] = encodeHolidayOverrides(updated)
        }
    }

    suspend fun setHolidayCountryHome(enabled: Boolean) {
        dataStore.edit { it[HOLIDAY_COUNTRY_HOME] = enabled }
    }

    suspend fun setHolidayCountryCurrent(enabled: Boolean) {
        dataStore.edit { it[HOLIDAY_COUNTRY_CURRENT] = enabled }
    }

    suspend fun setHolidayCountryGlobal(enabled: Boolean) {
        dataStore.edit { it[HOLIDAY_COUNTRY_GLOBAL] = enabled }
    }

    suspend fun setHolidayCountryFunny(enabled: Boolean) {
        dataStore.edit { it[HOLIDAY_COUNTRY_FUNNY] = enabled }
    }

    suspend fun setHolidayCountryChristian(enabled: Boolean) {
        dataStore.edit { it[HOLIDAY_COUNTRY_CHRISTIAN] = enabled }
    }

    suspend fun setHolidayCountryOrthodox(enabled: Boolean) {
        dataStore.edit { it[HOLIDAY_COUNTRY_ORTHODOX] = enabled }
    }

    suspend fun setHolidayCountryAll(enabled: Boolean) {
        dataStore.edit { it[HOLIDAY_COUNTRY_ALL] = enabled }
    }

    /**
     * Persists an explicit per-country override (ISO 3166-1 alpha-2).
     * [HolidayOverride.AUTO] clears the override so the country falls
     * back to the Home / Current / All buckets; [HolidayOverride.ON] and
     * [HolidayOverride.OFF] are stored explicitly.
     */
    suspend fun setHolidayCountryOverride(code: String, override: HolidayOverride) {
        val normalised = code.trim().takeIf { it.isNotEmpty() }?.uppercase() ?: return
        dataStore.edit { prefs ->
            val current = parseHolidayCountryOverrides(prefs[HOLIDAY_COUNTRY_OVERRIDES])
            val updated = if (override == HolidayOverride.AUTO) current - normalised
                else current + (normalised to override)
            prefs[HOLIDAY_COUNTRY_OVERRIDES] = encodeHolidayCountryOverrides(updated)
        }
    }

    suspend fun setTelemetryNoticeAcked(acked: Boolean) {
        dataStore.edit { it[TELEMETRY_NOTICE_ACKED] = acked }
    }

    /**
     * Atomically nudges the temperature threshold of the [ClothesRule] keyed
     * `ruleItem` by [deltaC] degrees Celsius. Used by the rationale dialog's
     * `+1°` / `−1°` buttons.
     *
     * Read-modify-write happens inside a single [dataStore.edit] so a tap-spam
     * can't drop intermediate writes — DataStore serialises edits, and each tap
     * reads the latest persisted rule list rather than the same pre-update
     * snapshot. The resulting Celsius value is clamped to
     * [ClothesRule.THRESHOLD_MIN_C] / [ClothesRule.THRESHOLD_MAX_C], then written
     * back in the rule's existing unit so a Fahrenheit-typed rule stays in °F.
     *
     * Falls back to [ClothesRule.DEFAULTS] when the user has no matching rule
     * on file (e.g. they previously deleted it) and appends a new rule with the
     * adjusted threshold; that way the dialog's controls stay live even on a
     * deleted rule and the next refresh re-evaluates against the recreated cut.
     * No-ops if [ruleItem] isn't a temperature rule (e.g. precipitation).
     */
    suspend fun adjustClothesRuleThreshold(ruleItem: String, deltaC: Double) {
        dataStore.edit { prefs ->
            val current = parseRules(prefs[CLOTHES_RULES])
            val updated = current.adjustOrAddTemperatureRule(ruleItem, deltaC) ?: return@edit
            prefs[CLOTHES_RULES] = json.encodeToString(updated.map { it.toDto() })
        }
    }

    private fun List<ClothesRule>.adjustOrAddTemperatureRule(
        ruleItem: String,
        deltaC: Double,
    ): List<ClothesRule>? {
        val idx = indexOfFirst { it.item == ruleItem && it.thresholdC() != null }
        if (idx >= 0) {
            val rule = this[idx]
            val newC = ((rule.thresholdC() ?: return null) + deltaC).clampThreshold()
            val updated = rule.withThresholdC(newC) ?: return null
            return toMutableList().also { it[idx] = updated }
        }
        // No matching rule on disk — recreate it from the catalog default if there
        // is one. The dialog only ever shows facts for rules that fromForecast can
        // pick (sweater / jacket / coat / shorts), all of which have a default,
        // so this branch covers the "user deleted it, then nudged from the dialog"
        // case rather than a request to invent a rule from nothing.
        val template = ClothesRule.DEFAULTS.firstOrNull { it.item == ruleItem } ?: return null
        val templateC = template.thresholdC() ?: return null
        val newC = (templateC + deltaC).clampThreshold()
        val recreated = template.withThresholdC(newC) ?: return null
        return this + recreated
    }

    private fun Double.clampThreshold(): Double =
        coerceIn(ClothesRule.THRESHOLD_MIN_C, ClothesRule.THRESHOLD_MAX_C)

    private fun Preferences.toUserPreferences(): UserPreferences {
        val time = this[SCHEDULE_TIME]?.let { LocalTime.parse(it, TIME_FORMAT) }
            ?: DEFAULT_TIME
        val days = this[SCHEDULE_DAYS]?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: Schedule.EVERY_DAY
        val deliveryMode = this[DELIVERY_MODE]?.let { runCatching { DeliveryMode.valueOf(it) }.getOrNull() }
            ?: DeliveryMode.NOTIFICATION_AND_TTS
        // Tonight's mode falls back to [deliveryMode] when absent so existing
        // installs keep the old "shared mode" behaviour until the user
        // explicitly diverges the two cards in Settings.
        val tonightDeliveryMode = this[TONIGHT_DELIVERY_MODE]?.let { runCatching { DeliveryMode.valueOf(it) }.getOrNull() }
            ?: deliveryMode
        val region = this[REGION]?.let { runCatching { Region.valueOf(it) }.getOrNull() }
            ?: Region.SYSTEM
        // Resolve units off the user's region — SYSTEM falls through to the phone locale.
        // AUTO (absent key) re-derives from locale on every read, so changing region or
        // system locale takes effect immediately. An explicit CELSIUS/FAHRENHEIT/KM/MILES
        // choice sticks until the user picks AUTO again.
        val regionLocale = region.toJavaLocale() ?: systemLocaleProvider()
        val temperatureUnitSetting = this[TEMPERATURE_UNIT]
            ?.let { runCatching { TemperatureUnitSetting.valueOf(it) }.getOrNull() }
            ?: TemperatureUnitSetting.AUTO
        val temperatureUnit = when (temperatureUnitSetting) {
            TemperatureUnitSetting.AUTO -> defaultTemperatureUnitFor(regionLocale)
            TemperatureUnitSetting.CELSIUS -> TemperatureUnit.CELSIUS
            TemperatureUnitSetting.FAHRENHEIT -> TemperatureUnit.FAHRENHEIT
        }
        val distanceUnitSetting = this[DISTANCE_UNIT]
            ?.let { runCatching { DistanceUnitSetting.valueOf(it) }.getOrNull() }
            ?: DistanceUnitSetting.AUTO
        val distanceUnit = when (distanceUnitSetting) {
            DistanceUnitSetting.AUTO -> defaultDistanceUnitFor(regionLocale)
            DistanceUnitSetting.KILOMETERS -> DistanceUnit.KILOMETERS
            DistanceUnitSetting.MILES -> DistanceUnit.MILES
        }
        val themeMode = this[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
        val rules = parseRules(this[CLOTHES_RULES])
        // Constrain the stored value to the catalog enum so a hand-edited
        // DataStore (or a forward-compat value from a future build) can't drop
        // an unknown variant into the fallback slot. Any valid Bottom is a
        // legitimate "If no rules match" pick — including SHORTS, which a
        // hot-climate user who removed the shorts rule may want as their
        // standard.
        val defaultBottom = this[DEFAULT_BOTTOM]
            ?.let { runCatching { OutfitSuggestion.Bottom.valueOf(it) }.getOrNull() }
            ?: OutfitSuggestion.Bottom.LONG_PANTS
        val defaultTop = this[DEFAULT_TOP]
            ?.let { runCatching { OutfitSuggestion.Top.valueOf(it) }.getOrNull() }
            ?: OutfitSuggestion.Top.TSHIRT
        val location = parseLocation(this)
        val useDeviceLocation = this[USE_DEVICE_LOCATION] == true
        val homeLocation = parseHomeLocation(this)
        val skipTtsAtHome = this[SKIP_TTS_AT_HOME] == true
        val ttsEngine = this[TTS_ENGINE]?.let { runCatching { TtsEngine.valueOf(it) }.getOrNull() }
            ?: TtsEngine.DEVICE
        val geminiVoice = this[GEMINI_VOICE]?.takeIf { it.isNotBlank() }
            ?: UserPreferences.DEFAULT_GEMINI_VOICE
        val ttsStyle = this[TTS_STYLE]?.let { runCatching { TtsStyle.valueOf(it) }.getOrNull() }
            ?: TtsStyle.WEATHER_FORECASTER
        val voiceLocale = this[VOICE_LOCALE]?.let { runCatching { VoiceLocale.valueOf(it) }.getOrNull() }
            ?: VoiceLocale.SYSTEM
        val deviceVoice = this[DEVICE_VOICE]?.takeIf { it.isNotBlank() }
        val useCalendarEvents = this[USE_CALENDAR_EVENTS] == true
        val themeFromCalendarHolidays = this[THEME_FROM_CALENDAR_HOLIDAYS] == true
        val themeFromCalendarBirthdays = this[THEME_FROM_CALENDAR_BIRTHDAYS] == true
        // Master calendar switch. Absent on installs that predate it, so derive
        // its value from the per-feature toggles: if the user already had any
        // calendar feature on, calendar access is implicitly on — never silently
        // disable a feature they were using. Once the user flips the master
        // toggle, the stored value wins.
        val calendarEnabled = this[CALENDAR_ENABLED]
            ?: (useCalendarEvents || themeFromCalendarHolidays || themeFromCalendarBirthdays)
        val celebrationCardDismissed = this[CELEBRATION_CARD_DISMISSED] == true
        val calendarPermissionRecheckTick = this[CALENDAR_PERMISSION_RECHECK_TICK] ?: 0L
        val tonightTime = this[TONIGHT_TIME]?.let { LocalTime.parse(it, TIME_FORMAT) }
            ?: DEFAULT_TONIGHT_TIME
        val tonightDays = this[TONIGHT_DAYS]?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: Schedule.EVERY_DAY
        // Default-on: existing installs that haven't seen the tonight pref yet get
        // the silent overnight notification (it's quiet by default when there are
        // no calendar events, so it's not noisy out of the box).
        val tonightEnabled = this[TONIGHT_ENABLED] != false
        val tonightNotifyOnlyOnEvents = this[TONIGHT_NOTIFY_ONLY_ON_EVENTS] == true
        val dailyMentionEveningEvents = this[DAILY_MENTION_EVENING_EVENTS] != false
        val clothesMentionMode = this[CLOTHES_MENTION_MODE]
            ?.let { runCatching { ClothesMentionMode.valueOf(it) }.getOrNull() }
            ?: ClothesMentionMode.ALWAYS
        // Range-format clause rendering. Falls back to migrating the legacy
        // boolean: a stored omit-range=true seeds NONE; otherwise DEGREES (the
        // historical default when the range was shown).
        val rangeFormat = this[INSIGHT_RANGE_FORMAT]
            ?.let { runCatching { RangeFormat.valueOf(it) }.getOrNull() }
            ?: if (this[OMIT_TEMPERATURE_RANGE] == true) RangeFormat.NONE else RangeFormat.DEGREES
        val clothesFormat = this[INSIGHT_CLOTHES_FORMAT]
            ?.let { runCatching { ClothesFormat.valueOf(it) }.getOrNull() }
            ?: ClothesFormat.ITEMS
        val rainAccessory = this[RAIN_ACCESSORY]
            ?.let { runCatching { RainAccessory.valueOf(it) }.getOrNull() }
            ?: RainAccessory.NONE
        // Delta-clause threshold in °C; the OFF sentinel maps to null (clause
        // disabled). Absent key keeps the historical 3°C default.
        val deltaThresholdC = when (val stored = this[INSIGHT_DELTA_THRESHOLD_C]) {
            null -> 3.0
            DELTA_THRESHOLD_OFF -> null
            else -> stored
        }
        // Default on for installs that predate the toggle, matching the new-install
        // default; the one-time Today banner is what surfaces the choice to the user.
        val telemetryEnabled = this[TELEMETRY_ENABLED] != false
        val telemetryNoticeAcked = this[TELEMETRY_NOTICE_ACKED] == true
        val colorPalette = this[COLOR_PALETTE]?.let { runCatching { ColorPalette.valueOf(it) }.getOrNull() }
            ?: ColorPalette.RAINBOW
        val outfitTopColors = parseOutfitTopColors(this[OUTFIT_TOP_COLORS])
        val outfitBottomColors = parseOutfitBottomColors(this[OUTFIT_BOTTOM_COLORS])
        val holidayCountrySelection = HolidayCountrySelection(
            home = this[HOLIDAY_COUNTRY_HOME] != false,
            current = this[HOLIDAY_COUNTRY_CURRENT] != false,
            // Default-true on missing key so pre-upgrade installs keep their
            // universal-holiday themes (Christmas / NYE / Valentine's /
            // Halloween) firing — the new toggle is opt-out, not opt-in.
            global = this[HOLIDAY_COUNTRY_GLOBAL] != false,
            // Default-true on missing key, same opt-out contract as Global —
            // fresh and pre-upgrade installs see Funny themes (Talk Like a
            // Pirate Day) by default.
            funny = this[HOLIDAY_COUNTRY_FUNNY] != false,
            // Default-false on missing key. These are religious-tradition
            // observances; users in Christian-tradition countries with
            // public holidays still pick up the relevant entries via
            // their `home` country (each Christian holiday carries both
            // the CHRISTIAN sentinel and the per-country ISO codes where
            // it's a public holiday). Opt in to extend to the strictly-
            // liturgical days (Ash Wed, Palm Sun, Maundy Thu).
            christian = this[HOLIDAY_COUNTRY_CHRISTIAN] == true,
            // Default-false on missing key — Orthodox is a narrower
            // tradition and Orthodox-observing users pick up Jan 7
            // Christmas via their home country anyway.
            orthodox = this[HOLIDAY_COUNTRY_ORTHODOX] == true,
            all = this[HOLIDAY_COUNTRY_ALL] == true,
            countryOverrides = parseHolidayCountryOverrides(this[HOLIDAY_COUNTRY_OVERRIDES]),
        )
        val holidayOverrides = parseHolidayOverrides(this[HOLIDAY_OVERRIDES])
        // Resolve stored enum names back to [ForecastModel]. Unknown / removed
        // entries are dropped silently so a forward-compat (future enum value
        // we didn't ship yet) or stale value from a downgrade doesn't break
        // the picker. A missing key or one that resolves to an empty set
        // becomes null — that's "Auto", and downstream code resolves the
        // location-aware default via [ForecastModel.defaultsFor]. The previous
        // behaviour ("fall back to DEFAULTS") was equivalent to a global trio
        // forced on every install; null lets fresh installs follow the user's
        // region.
        val forecastModels: Set<ForecastModel>? = this[FORECAST_MODELS]
            ?.mapNotNull { runCatching { ForecastModel.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val mqttBridgeEnabled = this[MQTT_BRIDGE_ENABLED] == true
        val mqttHost = this[MQTT_HOST]?.takeIf { it.isNotBlank() }
        val mqttPort = this[MQTT_PORT] ?: UserPreferences.DEFAULT_MQTT_PORT
        val mqttUseTls = this[MQTT_USE_TLS] == true
        val mqttUsername = this[MQTT_USER]?.takeIf { it.isNotBlank() }
        val mqttTopic = this[MQTT_TOPIC]?.takeIf { it.isNotBlank() }
            ?: UserPreferences.DEFAULT_MQTT_TOPIC
        val castRouteId = this[CAST_ROUTE_ID]?.takeIf { it.isNotBlank() }
        val castRouteName = this[CAST_ROUTE_NAME]?.takeIf { it.isNotBlank() }
        val castMorning = this[CAST_MORNING] ?: true
        val castTonight = this[CAST_TONIGHT] ?: true
        val castSkipPhoneSpeech = this[CAST_SKIP_PHONE_SPEECH] ?: true
        val zone = zoneIdProvider()

        return UserPreferences(
            schedule = Schedule(time = time, days = days, zoneId = zone),
            deliveryMode = deliveryMode,
            region = region,
            temperatureUnit = temperatureUnit,
            distanceUnit = distanceUnit,
            temperatureUnitSetting = temperatureUnitSetting,
            distanceUnitSetting = distanceUnitSetting,
            themeMode = themeMode,
            clothesRules = rules,
            defaultBottom = defaultBottom,
            defaultTop = defaultTop,
            location = location,
            useDeviceLocation = useDeviceLocation,
            homeLocation = homeLocation,
            skipTtsAtHome = skipTtsAtHome,
            ttsEngine = ttsEngine,
            geminiVoice = geminiVoice,
            ttsStyle = ttsStyle,
            deviceVoice = deviceVoice,
            voiceLocale = voiceLocale,
            calendarEnabled = calendarEnabled,
            useCalendarEvents = useCalendarEvents,
            themeFromCalendarHolidays = themeFromCalendarHolidays,
            themeFromCalendarBirthdays = themeFromCalendarBirthdays,
            celebrationCardDismissed = celebrationCardDismissed,
            calendarPermissionRecheckTick = calendarPermissionRecheckTick,
            tonightSchedule = Schedule(time = tonightTime, days = tonightDays, zoneId = zone),
            tonightEnabled = tonightEnabled,
            tonightDeliveryMode = tonightDeliveryMode,
            tonightNotifyOnlyOnEvents = tonightNotifyOnlyOnEvents,
            dailyMentionEveningEvents = dailyMentionEveningEvents,
            clothesMentionMode = clothesMentionMode,
            rangeFormat = rangeFormat,
            clothesFormat = clothesFormat,
            rainAccessory = rainAccessory,
            deltaThresholdC = deltaThresholdC,
            telemetryEnabled = telemetryEnabled,
            telemetryNoticeAcked = telemetryNoticeAcked,
            colorPalette = colorPalette,
            outfitTopColors = outfitTopColors,
            outfitBottomColors = outfitBottomColors,
            holidayCountrySelection = holidayCountrySelection,
            holidayOverrides = holidayOverrides,
            forecastModels = forecastModels,
            mqttBridgeEnabled = mqttBridgeEnabled,
            mqttHost = mqttHost,
            mqttPort = mqttPort,
            mqttUseTls = mqttUseTls,
            mqttUsername = mqttUsername,
            mqttTopic = mqttTopic,
            castRouteId = castRouteId,
            castRouteName = castRouteName,
            castMorning = castMorning,
            castTonight = castTonight,
            castSkipPhoneSpeech = castSkipPhoneSpeech,
        )
    }

    private fun Preferences.toAnalyticsSnapshot(): SettingsAnalyticsSnapshot {
        val resolved = toUserPreferences()
        val systemLocale = systemLocaleProvider()
        // SYSTEM-fallback chain: VoiceLocale.SYSTEM follows the region locale,
        // and Region.SYSTEM follows the system locale. Capture the locale that
        // each setting's SYSTEM sentinel would resolve to so the "default"
        // value reflects what the user actually gets when they leave the
        // override at SYSTEM, not just a constant string.
        val regionLocale = resolved.region.toJavaLocale() ?: systemLocale
        val effectiveVoiceLocale = resolved.voiceLocale.resolve(regionLocale)
        return SettingsAnalyticsSnapshot(
            regionDefault = systemLocale.toLanguageTag(),
            // Read the raw key rather than `resolved.region.name`: the resolved
            // value collapses "no DataStore key" into Region.SYSTEM, which would
            // make a never-touched picker indistinguishable from an explicit
            // SYSTEM pick in reports — exactly the distinction this snapshot is
            // meant to surface.
            regionOverride = this[REGION] ?: SettingsAnalyticsSnapshot.UNSET,
            regionEffective = regionLocale.toLanguageTag(),
            voiceLocaleDefault = regionLocale.toLanguageTag(),
            voiceLocaleOverride = this[VOICE_LOCALE] ?: SettingsAnalyticsSnapshot.UNSET,
            voiceLocaleEffective = effectiveVoiceLocale.toLanguageTag(),
            ttsEngineDefault = TtsEngine.DEVICE.name,
            ttsEngineOverride = this[TTS_ENGINE] ?: SettingsAnalyticsSnapshot.UNSET,
            ttsEngineEffective = resolved.ttsEngine.name,
            ttsStyleDefault = TtsStyle.WEATHER_FORECASTER.name,
            ttsStyleOverride = this[TTS_STYLE] ?: SettingsAnalyticsSnapshot.UNSET,
            ttsStyleEffective = resolved.ttsStyle.name,
            geminiVoiceDefault = UserPreferences.DEFAULT_GEMINI_VOICE,
            geminiVoiceOverride = this[GEMINI_VOICE]?.takeIf { it.isNotBlank() }
                ?: SettingsAnalyticsSnapshot.UNSET,
            geminiVoiceEffective = resolved.geminiVoice,
            deviceVoiceDefault = SettingsAnalyticsSnapshot.AUTO,
            deviceVoiceOverride = this[DEVICE_VOICE]?.takeIf { it.isNotBlank() }
                ?: SettingsAnalyticsSnapshot.UNSET,
            deviceVoiceEffective = resolved.deviceVoice ?: SettingsAnalyticsSnapshot.AUTO,
        )
    }

    /**
     * Flattens [UserPreferences] into the [SettingsSnapshot] shape Firebase
     * Analytics consumes as event params. Effective-value-only (the
     * default-vs-override distinction lives in [SettingsAnalyticsSnapshot] for
     * the voice / region settings, which is where it matters for SYSTEM
     * sentinels); schedule times are hour-bucketed.
     */
    private fun UserPreferences.toSettingsSnapshot(): SettingsSnapshot = SettingsSnapshot(
        temperatureUnitSetting = temperatureUnitSetting.name,
        temperatureUnitEffective = temperatureUnit.name,
        distanceUnitSetting = distanceUnitSetting.name,
        distanceUnitEffective = distanceUnit.name,
        deliveryModeDaily = deliveryMode.name,
        deliveryModeTonight = tonightDeliveryMode.name,
        themeMode = themeMode.name,
        colorPalette = colorPalette.name,
        defaultBottom = defaultBottom.name,
        defaultTop = defaultTop.name,
        dailyTimeBucketHour = schedule.time.hour.toString().padStart(2, '0'),
        dailyDaysCount = schedule.days.size,
        tonightEnabled = tonightEnabled,
        tonightTimeBucketHour = tonightSchedule.time.hour.toString().padStart(2, '0'),
        tonightDaysCount = tonightSchedule.days.size,
        tonightNotifyOnlyOnEvents = tonightNotifyOnlyOnEvents,
        dailyMentionEveningEvents = dailyMentionEveningEvents,
        clothesMentionMode = clothesMentionMode.name,
        rangeFormat = rangeFormat.name,
        clothesFormat = clothesFormat.name,
        rainAccessory = rainAccessory.name,
        deltaThresholdC = deltaThresholdC?.roundToInt() ?: -1,
        useCalendarEvents = useCalendarEvents,
        skipTtsAtHome = skipTtsAtHome,
        homeLocationConfigured = homeLocation != null,
    )

    private fun parseLocation(prefs: Preferences): Location? {
        val lat = prefs[LOCATION_LAT] ?: return null
        val lon = prefs[LOCATION_LON] ?: return null
        return runCatching {
            Location(
                latitude = lat,
                longitude = lon,
                displayName = prefs[LOCATION_NAME],
                countryCode = prefs[LOCATION_COUNTRY]?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    private fun parseHomeLocation(prefs: Preferences): Location? {
        val lat = prefs[HOME_LAT] ?: return null
        val lon = prefs[HOME_LON] ?: return null
        return runCatching {
            Location(
                latitude = lat,
                longitude = lon,
                displayName = prefs[HOME_NAME],
                countryCode = prefs[HOME_COUNTRY]?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    /**
     * Decodes the persisted per-holiday override map. Each set entry is
     * `ID:STATE` where STATE is `ON` or `OFF`; missing ids implicitly mean
     * [HolidayOverride.AUTO]. Unknown ids (downgrade across a future-added
     * entry) drop silently. An empty / missing key is a fresh install:
     * every holiday is AUTO.
     */
    private fun parseHolidayOverrides(raw: Set<String>?): Map<HolidayId, HolidayOverride> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val id = runCatching { HolidayId.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val state = runCatching { HolidayOverride.valueOf(parts[1]) }.getOrNull()
                ?: return@mapNotNull null
            if (state == HolidayOverride.AUTO) null else id to state
        }.toMap()
    }

    private fun encodeHolidayOverrides(overrides: Map<HolidayId, HolidayOverride>): Set<String> =
        overrides.entries.mapNotNullTo(mutableSetOf()) { (id, state) ->
            if (state == HolidayOverride.AUTO) null else "${id.name}:${state.name}"
        }

    /**
     * Decodes the persisted per-country override map. Each set entry is
     * `CODE:STATE` where CODE is an ISO 3166-1 alpha-2 (case-normalised to
     * uppercase on read) and STATE is `ON` or `OFF`; missing codes implicitly
     * mean [HolidayOverride.AUTO]. Unknown / malformed entries drop silently.
     */
    private fun parseHolidayCountryOverrides(raw: Set<String>?): Map<String, HolidayOverride> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val code = parts[0].trim().takeIf { it.isNotEmpty() }?.uppercase() ?: return@mapNotNull null
            val state = runCatching { HolidayOverride.valueOf(parts[1]) }.getOrNull()
                ?: return@mapNotNull null
            if (state == HolidayOverride.AUTO) null else code to state
        }.toMap()
    }

    private fun encodeHolidayCountryOverrides(overrides: Map<String, HolidayOverride>): Set<String> =
        overrides.entries.mapNotNullTo(mutableSetOf()) { (code, state) ->
            if (state == HolidayOverride.AUTO) null else "$code:${state.name}"
        }

    private fun parseOutfitTopColors(raw: String?): Map<OutfitSuggestion.Top, Long> =
        parseOutfitColors(raw) { name -> runCatching { OutfitSuggestion.Top.valueOf(name) }.getOrNull() }

    private fun parseOutfitBottomColors(raw: String?): Map<OutfitSuggestion.Bottom, Long> =
        parseOutfitColors(raw) { name -> runCatching { OutfitSuggestion.Bottom.valueOf(name) }.getOrNull() }

    /**
     * Decodes a `Map<String, Long>` JSON blob and projects keys through
     * [resolveKey], dropping entries with unknown enum names. Tolerant of
     * forward-compat values (a future-added `Top` variant in stored JSON
     * silently disappears on read rather than crashing the whole flow).
     */
    private fun <K : Any> parseOutfitColors(raw: String?, resolveKey: (String) -> K?): Map<K, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, Long>>(raw)
                .mapNotNull { (key, value) -> resolveKey(key)?.let { it to value } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun parseRules(raw: String?): List<ClothesRule> {
        if (raw.isNullOrBlank()) return ClothesRule.DEFAULTS
        return runCatching {
            json.decodeFromString<List<ClothesRuleDto>>(raw).map { it.toDomain() }
        }.getOrDefault(ClothesRule.DEFAULTS)
            // An empty stored list is also treated as "no rules configured" rather
            // than honoured as an intentional zero — with editing locked
            // (ClothesSettings is read-only), a user who deleted all their rules
            // in a previous editable-UI version would otherwise have no way to
            // recover the defaults.
            .ifEmpty { ClothesRule.DEFAULTS }
    }

    companion object {
        private val SCHEDULE_TIME = stringPreferencesKey("schedule_time_hhmm")
        private val SCHEDULE_DAYS = stringSetPreferencesKey("schedule_days")
        private val DELIVERY_MODE = stringPreferencesKey("delivery_mode")
        private val REGION = stringPreferencesKey("region")
        private val TEMPERATURE_UNIT = stringPreferencesKey("temperature_unit")
        private val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val CLOTHES_RULES = stringPreferencesKey("clothes_rules_json")
        private val DEFAULT_BOTTOM = stringPreferencesKey("default_bottom")
        private val DEFAULT_TOP = stringPreferencesKey("default_top")
        private val LOCATION_LAT = doublePreferencesKey("location_latitude")
        private val LOCATION_LON = doublePreferencesKey("location_longitude")
        private val LOCATION_NAME = stringPreferencesKey("location_display_name")
        private val LOCATION_COUNTRY = stringPreferencesKey("location_country_code")
        private val USE_DEVICE_LOCATION = booleanPreferencesKey("use_device_location")
        private val HOME_LAT = doublePreferencesKey("home_latitude")
        private val HOME_LON = doublePreferencesKey("home_longitude")
        private val HOME_NAME = stringPreferencesKey("home_display_name")
        private val HOME_COUNTRY = stringPreferencesKey("home_country_code")
        private val SKIP_TTS_AT_HOME = booleanPreferencesKey("skip_tts_at_home")
        private val TTS_ENGINE = stringPreferencesKey("tts_engine")
        private val GEMINI_VOICE = stringPreferencesKey("gemini_voice")
        private val TTS_STYLE = stringPreferencesKey("tts_style")
        private val DEVICE_VOICE = stringPreferencesKey("device_voice")
        private val VOICE_LOCALE = stringPreferencesKey("voice_locale")
        private val CALENDAR_ENABLED = booleanPreferencesKey("calendar_enabled")
        private val USE_CALENDAR_EVENTS = booleanPreferencesKey("use_calendar_events")
        private val THEME_FROM_CALENDAR_HOLIDAYS = booleanPreferencesKey("theme_from_calendar_holidays")
        private val THEME_FROM_CALENDAR_BIRTHDAYS = booleanPreferencesKey("theme_from_calendar_birthdays")
        private val CELEBRATION_CARD_DISMISSED = booleanPreferencesKey("celebration_card_dismissed")
        private val CALENDAR_PERMISSION_RECHECK_TICK = longPreferencesKey("calendar_permission_recheck_tick")
        private val TONIGHT_TIME = stringPreferencesKey("tonight_time_hhmm")
        private val TONIGHT_DAYS = stringSetPreferencesKey("tonight_days")
        private val TONIGHT_ENABLED = booleanPreferencesKey("tonight_enabled")
        private val TONIGHT_DELIVERY_MODE = stringPreferencesKey("tonight_delivery_mode")
        private val TONIGHT_NOTIFY_ONLY_ON_EVENTS = booleanPreferencesKey("tonight_notify_only_on_events")
        private val DAILY_MENTION_EVENING_EVENTS = booleanPreferencesKey("daily_mention_evening_events")
        private val CLOTHES_MENTION_MODE = stringPreferencesKey("clothes_mention_mode")
        // Legacy boolean, read on migration only — superseded by INSIGHT_RANGE_FORMAT.
        private val OMIT_TEMPERATURE_RANGE = booleanPreferencesKey("omit_temperature_range")
        private val INSIGHT_RANGE_FORMAT = stringPreferencesKey("insight_range_format")
        private val INSIGHT_CLOTHES_FORMAT = stringPreferencesKey("insight_clothes_format")
        private val RAIN_ACCESSORY = stringPreferencesKey("rain_accessory")
        private val INSIGHT_DELTA_THRESHOLD_C = doublePreferencesKey("insight_delta_threshold_c")
        // Sentinel stored for "Significant change: Off" — distinct from an absent
        // key (which keeps the historical 3°C default).
        private const val DELTA_THRESHOLD_OFF = -1.0
        private val DISMISSED_UPDATE_VERSION = intPreferencesKey("dismissed_update_version")
        private val DISMISSED_LOCAL_BUILD_SHA = stringPreferencesKey("dismissed_local_build_sha")
        private val TELEMETRY_ENABLED = booleanPreferencesKey("telemetry_enabled")
        private val TELEMETRY_NOTICE_ACKED = booleanPreferencesKey("telemetry_notice_acked")
        private val BUG_REPORT_CONSENT_ACKED = booleanPreferencesKey("bug_report_consent_acked")
        private val COLOR_PALETTE = stringPreferencesKey("color_palette")
        private val OUTFIT_TOP_COLORS = stringPreferencesKey("outfit_top_colors_json")
        private val OUTFIT_BOTTOM_COLORS = stringPreferencesKey("outfit_bottom_colors_json")
        private val HOLIDAY_OVERRIDES = stringSetPreferencesKey("holiday_overrides")
        private val HOLIDAY_COUNTRY_HOME = booleanPreferencesKey("holiday_country_home")
        private val HOLIDAY_COUNTRY_CURRENT = booleanPreferencesKey("holiday_country_current")
        private val HOLIDAY_COUNTRY_GLOBAL = booleanPreferencesKey("holiday_country_global")
        private val HOLIDAY_COUNTRY_FUNNY = booleanPreferencesKey("holiday_country_funny")
        private val HOLIDAY_COUNTRY_CHRISTIAN = booleanPreferencesKey("holiday_country_christian")
        private val HOLIDAY_COUNTRY_ORTHODOX = booleanPreferencesKey("holiday_country_orthodox")
        private val HOLIDAY_COUNTRY_ALL = booleanPreferencesKey("holiday_country_all")
        private val HOLIDAY_COUNTRY_OVERRIDES = stringSetPreferencesKey("holiday_country_overrides")
        private val FORECAST_MODELS = stringSetPreferencesKey("forecast_models")
        private val MQTT_BRIDGE_ENABLED = booleanPreferencesKey("mqtt_bridge_enabled")
        private val MQTT_HOST = stringPreferencesKey("mqtt_host")
        private val MQTT_PORT = intPreferencesKey("mqtt_port")
        private val MQTT_USE_TLS = booleanPreferencesKey("mqtt_use_tls")
        private val MQTT_USER = stringPreferencesKey("mqtt_user")
        private val MQTT_TOPIC = stringPreferencesKey("mqtt_topic")
        private val MQTT_LAST_ERROR_MSG = stringPreferencesKey("mqtt_last_error_msg")
        private val MQTT_LAST_ERROR_AT_MS = longPreferencesKey("mqtt_last_error_at_ms")
        private val MQTT_LAST_SUCCESS_AT_MS = longPreferencesKey("mqtt_last_success_at_ms")

        // Cast destination — the smart display the user picked in Settings.
        // routeId is the Cast SDK's stable identifier; routeName is the
        // friendly label cached at pick time so the Settings row stays
        // readable when the device is powered off and live discovery
        // returns nothing.
        private val CAST_ROUTE_ID = stringPreferencesKey("cast_route_id")
        private val CAST_ROUTE_NAME = stringPreferencesKey("cast_route_name")
        private val CAST_LAST_ERROR_MSG = stringPreferencesKey("cast_last_error_msg")
        private val CAST_LAST_ERROR_AT_MS = longPreferencesKey("cast_last_error_at_ms")
        private val CAST_LAST_SUCCESS_AT_MS = longPreferencesKey("cast_last_success_at_ms")
        // Per-period cast toggles + the "smart display speaks, mute the
        // phone" suppression. Stored separately from CAST_ROUTE_ID so the
        // user's picked display survives an off / on flip of either
        // period; default true on first read (mirrors the data-class
        // defaults).
        private val CAST_MORNING = booleanPreferencesKey("cast_morning")
        private val CAST_TONIGHT = booleanPreferencesKey("cast_tonight")
        private val CAST_SKIP_PHONE_SPEECH = booleanPreferencesKey("cast_skip_phone_speech")

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DEFAULT_TIME: LocalTime = LocalTime.of(7, 0)
        private val DEFAULT_TONIGHT_TIME: LocalTime = LocalTime.of(19, 0)

        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.settingsDataStore)
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// Only the US uses Fahrenheit in everyday weather contexts. A handful of
// dependencies (BS, BZ, KY, PW) also do, but they're rounding error and the
// user can override via the unit picker if needed — not worth the extra surface.
// Internal so SettingsState can mirror the repository's defaults at construction
// time, before the first DataStore emission lands.
internal fun defaultTemperatureUnitFor(locale: Locale): TemperatureUnit =
    if (locale.country == "US") TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS

// US and UK both use miles for everyday distance / speed (mph on roads,
// wind in mph). UK weather apps report wind in mph and walkers think in
// miles, so MILES is the right default even though UK rainfall is in mm and
// temperatures in Celsius.
internal fun defaultDistanceUnitFor(locale: Locale): DistanceUnit =
    if (locale.country in setOf("US", "GB")) DistanceUnit.MILES else DistanceUnit.KILOMETERS
