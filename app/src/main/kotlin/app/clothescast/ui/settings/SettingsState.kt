package app.clothescast.ui.settings

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
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TemperatureUnitSetting
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.UpcomingCalendarEvent
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.data.defaultDistanceUnitFor
import app.clothescast.data.defaultTemperatureUnitFor
import app.clothescast.discovery.DiscoveredService
import app.clothescast.tts.DeviceVoice
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale

/** What the Settings sub-pages need to render. */
data class SettingsState(
    val scheduleTime: LocalTime = LocalTime.of(7, 0),
    val scheduleDays: Set<DayOfWeek> = Schedule.EVERY_DAY,
    val tonightTime: LocalTime = LocalTime.of(19, 0),
    val tonightDays: Set<DayOfWeek> = Schedule.EVERY_DAY,
    val tonightEnabled: Boolean = true,
    val tonightNotifyOnlyOnEvents: Boolean = false,
    val deliveryMode: DeliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
    val tonightDeliveryMode: DeliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
    val dailyMentionEveningEvents: Boolean = true,
    val clothesMentionMode: ClothesMentionMode = ClothesMentionMode.ALWAYS,
    val rangeFormat: RangeFormat = RangeFormat.DEGREES,
    val deltaThresholdC: Double? = 3.0,
    /**
     * Structured summary of the user's current cached forecast (page 1 of the
     * Today pager), or `null` when nothing's cached yet. The Format settings
     * page renders it through [app.clothescast.insight.InsightFormatter] beside
     * the synthetic example so the user sees their real ClothesCast respond to
     * the range-format setting live. Carries only the on-device summary — no
     * extra data leaves the device.
     */
    val currentInsightSummary: InsightSummary? = null,
    val region: Region = Region.SYSTEM,
    // Match SettingsRepository's locale-aware defaults so en-US devices don't
    // briefly render °C / km before the first DataStore emission overrides it.
    // Region.SYSTEM falls through to the phone locale, mirroring the repository.
    val temperatureUnit: TemperatureUnit = defaultTemperatureUnitFor(Locale.getDefault()),
    val distanceUnit: DistanceUnit = defaultDistanceUnitFor(Locale.getDefault()),
    val temperatureUnitSetting: TemperatureUnitSetting = TemperatureUnitSetting.AUTO,
    val distanceUnitSetting: DistanceUnitSetting = DistanceUnitSetting.AUTO,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorPalette: ColorPalette = ColorPalette.RAINBOW,
    /** User-picked fill colour overrides for each top-icon tier. Empty = baked-in defaults. */
    val outfitTopColors: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    /** Sibling of [outfitTopColors] for the bottom-icon tier. */
    val outfitBottomColors: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    /**
     * Country picker: Home / Current / All bucket flags plus per-country
     * AUTO / ON / OFF overrides ([HolidayCountrySelection.countryOverrides]).
     * Defaults to Home + Current on so a fresh install surfaces the locale
     * + weather-location country plus the universal globals (which ride
     * along automatically whenever any country is effectively enabled).
     */
    val holidayCountrySelection: HolidayCountrySelection = HolidayCountrySelection(),
    /**
     * Per-holiday explicit overrides. Missing entries are [HolidayOverride.AUTO]
     * (the default — follow the country picker). Only ON / OFF are stored.
     */
    val holidayOverrides: Map<HolidayId, HolidayOverride> = emptyMap(),
    /**
     * The effective enabled-country set the resolver uses today, derived
     * from [holidayCountrySelection] + the user's locale + the weather
     * location's country. Exposed to the UI so each per-holiday row can
     * label its "Auto" dropdown option with the currently-resolved state
     * ("Auto (on)" vs "Auto (off)").
     */
    val effectiveEnabledHolidayCountries: Set<String> = emptySet(),
    val clothesRules: List<ClothesRule> = ClothesRule.DEFAULTS,
    val defaultBottom: OutfitSuggestion.Bottom = OutfitSuggestion.Bottom.LONG_PANTS,
    val defaultTop: OutfitSuggestion.Top = OutfitSuggestion.Top.TSHIRT,
    val location: Location? = null,
    val useDeviceLocation: Boolean = false,
    val homeLocation: Location? = null,
    val skipTtsAtHome: Boolean = false,
    /**
     * True while a "Use my current location" tap on the home-pin card is
     * in flight — covers the permission prompt, the device-fix resolve, and
     * the reverse-geocode. The button is disabled and the row shows a
     * "Detecting…" label until the resolved Location lands via the
     * preferences flow.
     */
    val homeLocationResolving: Boolean = false,
    val ttsEngine: TtsEngine = TtsEngine.DEVICE,
    val geminiVoice: String = UserPreferences.DEFAULT_GEMINI_VOICE,
    val ttsStyle: TtsStyle = TtsStyle.WEATHER_FORECASTER,
    /**
     * On-device voice ID the user has pinned, or `null` for "auto-pick the
     * highest-quality voice for [voiceLocale]" (the default for installs
     * that haven't opened the device-voice picker).
     */
    val deviceVoice: String? = null,
    /**
     * Voices the device's TTS engine reports for the current [voiceLocale],
     * loaded eagerly by [SettingsViewModel] on first preferences emission
     * and refreshed on every locale change — *not* gated on DEVICE being
     * the currently-selected engine. Pre-loading means switching to DEVICE
     * doesn't briefly show "loading…", and the cost is one engine bind per
     * locale change, which is rare. Empty until enumeration completes —
     * the picker shows the pinned ID alone in that window, so users still
     * see what they previously selected.
     */
    val deviceVoices: List<DeviceVoice> = emptyList(),
    /**
     * What [app.clothescast.tts.AndroidTtsSpeaker] would speak right now —
     * the user's [deviceVoice] resolved against the engine's catalogue, or
     * the auto-pick if no pin. `null` while the enumeration is in flight or
     * if the engine reports no voices at all.
     */
    val effectiveDeviceVoice: DeviceVoice? = null,
    val voiceLocale: VoiceLocale = VoiceLocale.SYSTEM,
    val calendarEnabled: Boolean = false,
    val useCalendarEvents: Boolean = false,
    val themeFromCalendarHolidays: Boolean = false,
    val themeFromCalendarBirthdays: Boolean = false,
    /**
     * Birthdays + public holidays detected in the user's synced calendars over
     * the next year, listed (collapsed) on the Celebrations screen. `null` until
     * the first read completes — the UI shows a brief "checking…" line — then a
     * (possibly empty) list. Loaded lazily once READ_CALENDAR is granted and
     * never carried off device.
     */
    val calendarCelebrations: List<UpcomingCalendarEvent>? = null,
    val telemetryEnabled: Boolean = true,
    val apiKeyConfigured: Boolean = false,
    /**
     * True while the WorkManager location-cache-refresh job is ENQUEUED,
     * RUNNING, or BLOCKED. Drives "Detecting…" in the Location settings
     * card — the label only shows "Detecting…" while this is true, so it
     * can't get stuck after the worker finishes without resolving a fix.
     */
    val locationDetecting: Boolean = false,
    /**
     * The user's explicit [ForecastModel] selection, or `null` for "Auto"
     * (location-derived defaults). Auto is the default for fresh installs;
     * the first explicit pick in the Forecasters picker switches it to a
     * non-null Set; flipping the Auto switch on clears it back to null.
     * See [app.clothescast.core.domain.model.defaultsFor] for the region
     * mapping the Auto state resolves through.
     */
    val forecastModels: Set<ForecastModel>? = null,
    /**
     * Smart Home / Home Assistant MQTT bridge configuration. Master toggle
     * (`mqttBridgeEnabled`) gates the publisher; the rest mirror the values
     * in [UserPreferences] so the Smart Home settings card can render the
     * config without subscribing to a second flow. `mqttPasswordSet` is a
     * presence-only mirror of [SecureKeyStore.mqttPasswordConfiguredFlow] so
     * the "Password saved" indicator never decrypts the stored secret.
     */
    val mqttBridgeEnabled: Boolean = false,
    val mqttHost: String = "",
    val mqttPort: Int = UserPreferences.DEFAULT_MQTT_PORT,
    val mqttUseTls: Boolean = false,
    val mqttUsername: String = "",
    val mqttTopic: String = UserPreferences.DEFAULT_MQTT_TOPIC,
    val mqttPasswordSet: Boolean = false,
    /**
     * Error message from the last MQTT publish attempt, or null if the last
     * attempt succeeded (or no publish has been attempted yet). Persisted
     * across app launches so the user can see a previous failure without
     * waiting for the next scheduled refresh.
     */
    val mqttLastError: String? = null,
    /** Epoch-ms wall-clock of the last recorded publish outcome (0 = no record). */
    val mqttLastErrorAt: Long = 0L,
    /**
     * Epoch-ms wall-clock of the most recent successful MQTT publish (0 = no
     * success yet). Tracked separately from [mqttLastErrorAt] so a later
     * failure doesn't hide that we did succeed earlier.
     */
    val mqttLastPublishAt: Long = 0L,
    /** True while a "Publish now" action is in flight. */
    val mqttPublishing: Boolean = false,
    /**
     * True while a local-network mDNS scan for Home Assistant / MQTT brokers
     * is in flight (the user tapped "Scan local network" on the Smart Home
     * card). Discovery is cold — toggling this off cancels the underlying
     * NsdManager listeners.
     */
    val discoveryRunning: Boolean = false,
    /**
     * Most recent batch of mDNS hits from the in-flight scan, or the final
     * snapshot from the previous scan. Empty list before any scan and
     * cleared back to empty when the user starts a new one.
     */
    val discoveredServices: List<DiscoveredService> = emptyList(),

    // ─── Cast ───────────────────────────────────────────────────────────
    // The smart display the user picked. id stays in [UserPreferences];
    // [castRouteName] is held here so the row can read "Living-room display"
    // even when the device is powered off and live discovery is empty.
    val castRouteName: String? = null,
    /** True while the "Choose smart display" picker dialog is open. */
    val castPickerOpen: Boolean = false,
    /** Live snapshot of routes from the in-flight discovery scan. */
    val castDiscoveredRoutes: List<app.clothescast.cast.DiscoveredCastRoute> = emptyList(),
    /** True while a "Cast now" test cast is in flight. */
    val castInProgress: Boolean = false,
    /** Mirrors [mqttLastError] — last cast failure message, null on success / no record. */
    val castLastError: String? = null,
    /** Epoch-ms wall-clock of the last recorded cast outcome (0 = no record). */
    val castLastErrorAt: Long = 0L,
    /** Epoch-ms wall-clock of the most recent successful cast (0 = no success yet). */
    val castLastSuccessAt: Long = 0L,
    /**
     * True when [ClothesCastApplication.castContext] resolved — i.e. Google
     * Play Services Cast framework is available on this device. Cast-less
     * emulators and GMS-free AOSP builds return false; the UI hides the
     * whole Cast section in that case.
     */
    val castAvailable: Boolean = false,
    /** Per-period cast toggles; default true. */
    val castMorning: Boolean = true,
    val castTonight: Boolean = true,
    /** When true, audio-carrying cast suppresses the phone speaker. */
    val castSkipPhoneSpeech: Boolean = true,
)
