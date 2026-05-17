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
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.DistanceUnitSetting
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayCountryMode
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
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

    suspend fun setUseCalendarEvents(enabled: Boolean) {
        dataStore.edit { it[USE_CALENDAR_EVENTS] = enabled }
    }

    suspend fun setTelemetryEnabled(enabled: Boolean) {
        dataStore.edit { it[TELEMETRY_ENABLED] = enabled }
    }

    suspend fun setMqttBridgeEnabled(enabled: Boolean) {
        dataStore.edit { it[MQTT_BRIDGE_ENABLED] = enabled }
    }

    /**
     * Runtime status of the last MQTT publish attempt, separate from user
     * preferences. [errorMessage] is null on success (or no record yet); non-null
     * is the human-readable failure reason. [recordedAtMs] is the epoch-ms wall
     * clock of the attempt (0 when no publish has ever been recorded).
     */
    data class MqttPublishStatus(val errorMessage: String?, val recordedAtMs: Long)

    /**
     * Emits the result of the last MQTT publish attempt. Null until the first
     * attempt is recorded; thereafter always non-null. Updated by both the daily
     * worker and the "Publish now" button.
     */
    val mqttPublishStatus: Flow<MqttPublishStatus?> = dataStore.data.map { prefs ->
        val ms = prefs[MQTT_LAST_ERROR_AT_MS] ?: return@map null
        val msg = prefs[MQTT_LAST_ERROR_MSG]  // null = success
        MqttPublishStatus(errorMessage = msg, recordedAtMs = ms)
    }

    /**
     * Persists the outcome of an MQTT publish attempt. Pass null [errorMessage]
     * to record a success (clears any displayed error).
     */
    suspend fun setMqttLastError(errorMessage: String?, atMs: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            prefs[MQTT_LAST_ERROR_AT_MS] = atMs
            if (errorMessage != null) {
                prefs[MQTT_LAST_ERROR_MSG] = errorMessage
            } else {
                prefs.remove(MQTT_LAST_ERROR_MSG)
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

    /**
     * Flips a single holiday theme on or off. The full set is read, modified,
     * and rewritten inside one [dataStore.edit] so a rapid sequence of toggles
     * from the Settings UI doesn't race itself.
     *
     * Missing-key reads default to "all enabled" (see [parseEnabledHolidays]),
     * so the first call from an existing install seeds the full set before
     * subtracting the user's choice — preserving the "all on by default"
     * contract even after the first explicit toggle.
     */
    suspend fun setEnabledHoliday(id: HolidayId, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = parseEnabledHolidays(prefs[ENABLED_HOLIDAYS])
            val updated = if (enabled) current + id else current - id
            prefs[ENABLED_HOLIDAYS] = updated.map { it.name }.toSet()
        }
    }

    suspend fun setHolidayCountryMode(mode: HolidayCountryMode) {
        dataStore.edit { it[HOLIDAY_COUNTRY_MODE] = mode.name }
    }

    /**
     * Flips a single country (ISO 3166-1 alpha-2 or [HolidayCatalog.GLOBAL_COUNTRY])
     * on or off in the user's per-country pick. Mirrors [setEnabledHoliday]
     * — read-modify-write inside a single [dataStore.edit] so rapid taps
     * from the Settings UI don't race. Missing-key reads default to "all
     * countries enabled" (see [parseEnabledHolidayCountries]), so the
     * first toggle from a fresh install seeds the full set before
     * subtracting the user's pick. Only consulted by the resolver when
     * [UserPreferences.holidayCountryMode] is
     * [HolidayCountryMode.CUSTOM]; AUTO / ALL derive their effective set
     * on the fly.
     */
    suspend fun setHolidayCountryEnabled(code: String, enabled: Boolean) {
        val normalised = code.trim().takeIf { it.isNotEmpty() }?.uppercase() ?: return
        dataStore.edit { prefs ->
            val current = parseEnabledHolidayCountries(prefs[ENABLED_HOLIDAY_COUNTRIES])
            val updated = if (enabled) current + normalised else current - normalised
            prefs[ENABLED_HOLIDAY_COUNTRIES] = updated
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
        // Constrain the stored value to the picker's three options so a hand-edited
        // DataStore (or a forward-compat value from a future build) can't drop
        // SHORTS into the fallback slot — shorts have a rule-driven warm-weather
        // path and shouldn't ever be the "no rule fires" answer. LONG_SKIRT is
        // allowed even though it also has a rule-driven path: the catalog's skirt
        // icon is a full-length / long skirt, and a user who picks it as their
        // standard wants it everywhere the fallback fires, not just on hot days.
        val defaultBottom = this[DEFAULT_BOTTOM]
            ?.let { runCatching { OutfitSuggestion.Bottom.valueOf(it) }.getOrNull() }
            ?.takeIf { it in DEFAULT_BOTTOM_OPTIONS }
            ?: OutfitSuggestion.Bottom.LONG_PANTS
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
        // Default on for installs that predate the toggle, matching the new-install
        // default; the one-time Today banner is what surfaces the choice to the user.
        val telemetryEnabled = this[TELEMETRY_ENABLED] != false
        val telemetryNoticeAcked = this[TELEMETRY_NOTICE_ACKED] == true
        val colorPalette = this[COLOR_PALETTE]?.let { runCatching { ColorPalette.valueOf(it) }.getOrNull() }
            ?: ColorPalette.RAINBOW
        val outfitTopColors = parseOutfitTopColors(this[OUTFIT_TOP_COLORS])
        val outfitBottomColors = parseOutfitBottomColors(this[OUTFIT_BOTTOM_COLORS])
        val enabledHolidays = parseEnabledHolidays(this[ENABLED_HOLIDAYS])
        val holidayCountryMode = this[HOLIDAY_COUNTRY_MODE]
            ?.let { runCatching { HolidayCountryMode.valueOf(it) }.getOrNull() }
            ?: HolidayCountryMode.AUTO
        val enabledHolidayCountries = parseEnabledHolidayCountries(this[ENABLED_HOLIDAY_COUNTRIES])
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
            location = location,
            useDeviceLocation = useDeviceLocation,
            homeLocation = homeLocation,
            skipTtsAtHome = skipTtsAtHome,
            ttsEngine = ttsEngine,
            geminiVoice = geminiVoice,
            ttsStyle = ttsStyle,
            deviceVoice = deviceVoice,
            voiceLocale = voiceLocale,
            useCalendarEvents = useCalendarEvents,
            tonightSchedule = Schedule(time = tonightTime, days = tonightDays, zoneId = zone),
            tonightEnabled = tonightEnabled,
            tonightDeliveryMode = tonightDeliveryMode,
            tonightNotifyOnlyOnEvents = tonightNotifyOnlyOnEvents,
            dailyMentionEveningEvents = dailyMentionEveningEvents,
            telemetryEnabled = telemetryEnabled,
            telemetryNoticeAcked = telemetryNoticeAcked,
            colorPalette = colorPalette,
            outfitTopColors = outfitTopColors,
            outfitBottomColors = outfitBottomColors,
            enabledHolidays = enabledHolidays,
            holidayCountryMode = holidayCountryMode,
            enabledHolidayCountries = enabledHolidayCountries,
            forecastModels = forecastModels,
            mqttBridgeEnabled = mqttBridgeEnabled,
            mqttHost = mqttHost,
            mqttPort = mqttPort,
            mqttUseTls = mqttUseTls,
            mqttUsername = mqttUsername,
            mqttTopic = mqttTopic,
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
        dailyTimeBucketHour = schedule.time.hour.toString().padStart(2, '0'),
        dailyDaysCount = schedule.days.size,
        tonightEnabled = tonightEnabled,
        tonightTimeBucketHour = tonightSchedule.time.hour.toString().padStart(2, '0'),
        tonightDaysCount = tonightSchedule.days.size,
        tonightNotifyOnlyOnEvents = tonightNotifyOnlyOnEvents,
        dailyMentionEveningEvents = dailyMentionEveningEvents,
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
     * Resolves the persisted enabled-country set. Missing key (fresh
     * install, or an existing install that predates the country filter)
     * seeds the full set so the first toggle in CUSTOM mode starts from
     * the user's existing universe of holidays rather than from nothing.
     * Blank entries drop silently; values are normalised to uppercase to
     * match catalog tagging.
     */
    private fun parseEnabledHolidayCountries(raw: Set<String>?): Set<String> {
        if (raw == null) return HolidayCatalog.allCountries
        return raw.mapNotNull { it.trim().takeIf { code -> code.isNotEmpty() }?.uppercase() }
            .toSet()
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

    /**
     * Resolves the persisted holiday-enabled set. Missing key (fresh install,
     * or an existing install that predates the holiday feature) seeds the
     * full set — every holiday is on by default. Unknown enum names (e.g.
     * the user downgraded across a future-added holiday) drop silently.
     * An explicit empty set is honoured as "every holiday is off."
     */
    private fun parseEnabledHolidays(raw: Set<String>?): Set<HolidayId> {
        if (raw == null) return HolidayId.entries.toSet()
        return raw.mapNotNull { name -> runCatching { HolidayId.valueOf(name) }.getOrNull() }
            .toSet()
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

        /**
         * Which [OutfitSuggestion.Bottom] values the Settings picker exposes
         * as fallback choices. SHORTS is excluded — it's the warm-weather
         * special case driven by the user's shorts rule and would defeat
         * itself as a fallback (i.e. "what to wear when no warm rule fires"
         * landing on shorts).
         */
        val DEFAULT_BOTTOM_OPTIONS: List<OutfitSuggestion.Bottom> = listOf(
            OutfitSuggestion.Bottom.LONG_PANTS,
            OutfitSuggestion.Bottom.JEANS,
            OutfitSuggestion.Bottom.LONG_SKIRT,
        )
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
        private val USE_CALENDAR_EVENTS = booleanPreferencesKey("use_calendar_events")
        private val TONIGHT_TIME = stringPreferencesKey("tonight_time_hhmm")
        private val TONIGHT_DAYS = stringSetPreferencesKey("tonight_days")
        private val TONIGHT_ENABLED = booleanPreferencesKey("tonight_enabled")
        private val TONIGHT_DELIVERY_MODE = stringPreferencesKey("tonight_delivery_mode")
        private val TONIGHT_NOTIFY_ONLY_ON_EVENTS = booleanPreferencesKey("tonight_notify_only_on_events")
        private val DAILY_MENTION_EVENING_EVENTS = booleanPreferencesKey("daily_mention_evening_events")
        private val DISMISSED_UPDATE_VERSION = intPreferencesKey("dismissed_update_version")
        private val DISMISSED_LOCAL_BUILD_SHA = stringPreferencesKey("dismissed_local_build_sha")
        private val TELEMETRY_ENABLED = booleanPreferencesKey("telemetry_enabled")
        private val TELEMETRY_NOTICE_ACKED = booleanPreferencesKey("telemetry_notice_acked")
        private val BUG_REPORT_CONSENT_ACKED = booleanPreferencesKey("bug_report_consent_acked")
        private val COLOR_PALETTE = stringPreferencesKey("color_palette")
        private val OUTFIT_TOP_COLORS = stringPreferencesKey("outfit_top_colors_json")
        private val OUTFIT_BOTTOM_COLORS = stringPreferencesKey("outfit_bottom_colors_json")
        private val ENABLED_HOLIDAYS = stringSetPreferencesKey("enabled_holidays")
        private val HOLIDAY_COUNTRY_MODE = stringPreferencesKey("holiday_country_mode")
        private val ENABLED_HOLIDAY_COUNTRIES = stringSetPreferencesKey("enabled_holiday_countries")
        private val FORECAST_MODELS = stringSetPreferencesKey("forecast_models")
        private val MQTT_BRIDGE_ENABLED = booleanPreferencesKey("mqtt_bridge_enabled")
        private val MQTT_HOST = stringPreferencesKey("mqtt_host")
        private val MQTT_PORT = intPreferencesKey("mqtt_port")
        private val MQTT_USE_TLS = booleanPreferencesKey("mqtt_use_tls")
        private val MQTT_USER = stringPreferencesKey("mqtt_user")
        private val MQTT_TOPIC = stringPreferencesKey("mqtt_topic")
        private val MQTT_LAST_ERROR_MSG = stringPreferencesKey("mqtt_last_error_msg")
        private val MQTT_LAST_ERROR_AT_MS = longPreferencesKey("mqtt_last_error_at_ms")

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
