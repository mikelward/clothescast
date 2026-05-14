package app.clothescast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.core.domain.model.thresholdC
import app.clothescast.core.domain.model.withThresholdC
import app.clothescast.diag.SettingsAnalyticsSnapshot
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

    suspend fun setTemperatureUnit(unit: TemperatureUnit) {
        dataStore.edit { it[TEMPERATURE_UNIT] = unit.name }
    }

    suspend fun setDistanceUnit(unit: DistanceUnit) {
        dataStore.edit { it[DISTANCE_UNIT] = unit.name }
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
        }
    }

    suspend fun clearLocation() {
        dataStore.edit { prefs ->
            prefs.remove(LOCATION_LAT)
            prefs.remove(LOCATION_LON)
            prefs.remove(LOCATION_NAME)
        }
    }

    suspend fun setUseDeviceLocation(enabled: Boolean) {
        dataStore.edit { it[USE_DEVICE_LOCATION] = enabled }
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

    suspend fun setColorPalette(palette: ColorPalette) {
        dataStore.edit { it[COLOR_PALETTE] = palette.name }
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
        // Resolve units off the user's region — SYSTEM falls through to the
        // phone locale. Once they explicitly pick a unit it sticks regardless
        // of region.
        val regionLocale = region.toJavaLocale() ?: systemLocaleProvider()
        val temperatureUnit = this[TEMPERATURE_UNIT]?.let { runCatching { TemperatureUnit.valueOf(it) }.getOrNull() }
            ?: defaultTemperatureUnitFor(regionLocale)
        val distanceUnit = this[DISTANCE_UNIT]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
            ?: defaultDistanceUnitFor(regionLocale)
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
        val zone = zoneIdProvider()

        return UserPreferences(
            schedule = Schedule(time = time, days = days, zoneId = zone),
            deliveryMode = deliveryMode,
            region = region,
            temperatureUnit = temperatureUnit,
            distanceUnit = distanceUnit,
            themeMode = themeMode,
            clothesRules = rules,
            defaultBottom = defaultBottom,
            location = location,
            useDeviceLocation = useDeviceLocation,
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

    private fun parseLocation(prefs: Preferences): Location? {
        val lat = prefs[LOCATION_LAT] ?: return null
        val lon = prefs[LOCATION_LON] ?: return null
        return runCatching {
            Location(
                latitude = lat,
                longitude = lon,
                displayName = prefs[LOCATION_NAME],
            )
        }.getOrNull()
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
        private val USE_DEVICE_LOCATION = booleanPreferencesKey("use_device_location")
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

// Only the US sticks with imperial for weather-app distance contexts (wind
// speed in mph, precipitation in inches). UK uses miles for *roads* but km/h
// and mm for weather, so it lands on the metric default with everyone else.
internal fun defaultDistanceUnitFor(locale: Locale): DistanceUnit =
    if (locale.country == "US") DistanceUnit.MILES else DistanceUnit.KILOMETERS
