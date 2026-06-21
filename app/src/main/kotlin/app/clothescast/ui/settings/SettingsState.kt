package app.clothescast.ui.settings

import app.clothescast.core.domain.model.AccessoriesFormat
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.DeltaFormat
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
import app.clothescast.core.domain.model.HomeSection
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.PreambleVisibility
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TemperatureUnitSetting
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.TimeFormatSetting
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.CalendarInfo
import app.clothescast.core.domain.model.UpcomingCalendarEvent
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.data.defaultDistanceUnitFor
import app.clothescast.data.defaultTemperatureUnitFor
import app.clothescast.data.defaultTimeFormatFor
import app.clothescast.discovery.DiscoveredService
import app.clothescast.tts.DeviceVoice
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale

/** What the Settings sub-pages need to render. */
data class SettingsState(
    val scheduleTime: LocalTime = LocalTime.of(7, 0),
    val scheduleDays: Set<DayOfWeek> = Schedule.EVERY_DAY,
    // On by default to mirror the repository (see UserPreferences.dailyEnabled,
    // where an absent key resolves to enabled): the morning cast ships on out of
    // the box, so the first render before DataStore emits must not show the
    // switch off, or the user could leave Schedule believing the morning cast is
    // off while the app has already scheduled it.
    val dailyEnabled: Boolean = true,
    val tonightTime: LocalTime = LocalTime.of(19, 0),
    val tonightDays: Set<DayOfWeek> = Schedule.EVERY_DAY,
    val tonightEnabled: Boolean = false,
    val tonightNotifyOnlyOnEvents: Boolean = false,
    val deliveryMode: DeliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
    val tonightDeliveryMode: DeliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
    // Off by default to mirror the repository (see
    // UserPreferences.dailyMentionEveningEvents, where an absent key resolves to
    // disabled): the evening extras stays opt-in, so the first render before
    // DataStore emits must not show the switch on.
    val dailyMentionEveningEvents: Boolean = false,
    val clothesMentionMode: ClothesMentionMode = ClothesMentionMode.ALWAYS,
    val rangeFormat: RangeFormat = RangeFormat.DEGREES,
    val clothesFormat: ClothesFormat = ClothesFormat.ITEMS,
    val bottomsFormat: BottomsFormat = BottomsFormat.IF_GARMENTS,
    val accessoriesFormat: AccessoriesFormat = AccessoriesFormat.ALWAYS,
    val periodPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
    val wearPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
    val deltaThresholdC: Double? = 3.0,
    val deltaFormat: DeltaFormat = DeltaFormat.DEGREES,
    /**
     * Structured summary of the user's current cached forecast (page 1 of the
     * Today pager), or `null` when nothing's cached yet. The Format settings
     * page renders it through [app.clothescast.insight.InsightFormatter] beside
     * the synthetic example so the user sees their real ClothesCast respond to
     * the range-format setting live. Carries only the on-device summary — no
     * extra data leaves the device.
     */
    val currentInsightSummary: InsightSummary? = null,
    /**
     * A TODAY-period [InsightSummary] for the Voice settings Test voice
     * preview to speak — picked from whichever cache slot
     * ([InsightCache.Slot.THIS_PERIOD] or [InsightCache.Slot.NEXT_PERIOD])
     * happens to hold a daytime snapshot, so the preview always sounds like
     * a morning briefing rather than "Tonight will be …". May reach forwards
     * (tomorrow's TODAY, pre-cached by the evening fetch) or backwards
     * (today's TODAY, still in THIS_PERIOD after the morning fetch).
     * `null` when nothing's cached yet — `runTtsPreview` falls back to its
     * built-in `SAMPLE_SUMMARY` in that case.
     */
    val voicePreviewInsightSummary: InsightSummary? = null,
    val region: Region = Region.SYSTEM,
    // Match SettingsRepository's locale-aware defaults so en-US devices don't
    // briefly render °C / km before the first DataStore emission overrides it.
    // Region.SYSTEM falls through to the phone locale, mirroring the repository.
    val temperatureUnit: TemperatureUnit = defaultTemperatureUnitFor(Locale.getDefault()),
    val distanceUnit: DistanceUnit = defaultDistanceUnitFor(Locale.getDefault()),
    val temperatureUnitSetting: TemperatureUnitSetting = TemperatureUnitSetting.AUTO,
    val distanceUnitSetting: DistanceUnitSetting = DistanceUnitSetting.AUTO,
    val timeFormat: TimeFormat = defaultTimeFormatFor(Locale.getDefault()),
    val timeFormatSetting: TimeFormatSetting = TimeFormatSetting.AUTO,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorPalette: ColorPalette = ColorPalette.RAINBOW,
    /** User-picked fill colour overrides for each top-icon tier. Empty = baked-in defaults. */
    val outfitTopColors: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    /** Sibling of [outfitTopColors] for the bottom-icon tier. */
    val outfitBottomColors: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    /** Sibling of [outfitTopColors] for the optional gloves (hands) overlay. */
    val outfitHandsColors: Map<OutfitSuggestion.Hands, Long> = emptyMap(),
    /** Sibling of [outfitTopColors] for the optional carried umbrella overlay. */
    val outfitCarriedColors: Map<OutfitSuggestion.Carried, Long> = emptyMap(),
    /** Sibling of [outfitTopColors] for the optional rain-jacket outer overlay. */
    val outfitOuterColors: Map<OutfitSuggestion.Outer, Long> = emptyMap(),
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
     * Per-calendar enable/disable overrides, keyed by stable provider id.
     * Sparse: present = explicit choice, absent = follow the calendar's
     * visibility in the host app.
     */
    val calendarOverrides: Map<String, Boolean> = emptyMap(),
    /**
     * The effective enabled-country set the resolver uses today, derived
     * from [holidayCountrySelection] + the user's locale + the weather
     * location's country. Exposed to the UI so each per-holiday row can
     * label its "Auto" dropdown option with the currently-resolved state
     * ("Auto (on)" vs "Auto (off)").
     */
    val effectiveEnabledHolidayCountries: Set<String> = emptySet(),
    val clothesRules: List<ClothesRule> = ClothesRule.DEFAULTS,
    val homeSectionOrder: List<HomeSection> = HomeSection.DEFAULTS,
    val defaultBottom: OutfitSuggestion.Bottom = OutfitSuggestion.Bottom.LONG_PANTS,
    val defaultTop: OutfitSuggestion.Top = OutfitSuggestion.Top.TSHIRT,
    val location: Location? = null,
    val useDeviceLocation: Boolean = false,
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
    /**
     * Calendars present on the device, for the per-calendar enable/disable
     * setting. `null` until the first enumeration completes; loaded lazily
     * once READ_CALENDAR is granted and never carried off device.
     */
    val availableCalendars: List<CalendarInfo>? = null,
    val telemetryEnabled: Boolean = true,
    val apiKeyConfigured: Boolean = false,
    /**
     * True when this build can synthesise Gemini TTS through the developer's
     * Cloud Function proxy (Firebase initialised + `GEMINI_PROXY_URL` set).
     * The Voice screen ORs this with [apiKeyConfigured] so users without a
     * BYOK key can still preview / use Gemini when the shared path is live.
     * Process-stable, set once when the ViewModel is constructed.
     */
    val sharedTtsAvailable: Boolean = false,
    /**
     * True while the WorkManager location-cache-refresh job is ENQUEUED,
     * RUNNING, or BLOCKED. Drives "Detecting…" in the Location settings
     * card — the label only shows "Detecting…" while this is true, so it
     * can't get stuck after the worker finishes without resolving a fix.
     */
    val locationDetecting: Boolean = false,
    /**
     * True while any forecast/play worker is ENQUEUED, RUNNING, or BLOCKED on
     * the daily, tonight, or play queues. Gates the Schedule "Play now" buttons
     * the same way [app.clothescast.ui.today.TodayState.anyWorkActive] gates the
     * Today top-bar Play button — so a preview can't start a second concurrent
     * delivery (overlapping TTS, duplicate notification/MQTT/cast, competing
     * cache writes) on top of a scheduled run, manual refresh, or another play.
     */
    val anyWorkActive: Boolean = false,
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
    /** When true, a successful MQTT publish with audio suppresses the phone speaker. */
    val mqttSkipPhoneSpeech: Boolean = true,
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
    /**
     * Epoch-ms wall-clock of the most recent successful `RemoteMediaClient.load`
     * accept (0 = none). Advances even when the receiver later fails to
     * fetch the hosted URL, so the row can show "Last published at X"
     * alongside a "didn't fetch" error.
     */
    val castLastPublishedAt: Long = 0L,
    /**
     * Epoch-ms wall-clock of the most recent confirmed URL fetch on the
     * phone's media server (0 = none). The stricter "the display
     * actually pulled the bytes" timestamp the status row reads as
     * "Last fetched".
     */
    val castLastFetchedAt: Long = 0L,
    /**
     * True when [ClothesCastApplication.castContext] resolved — i.e. Google
     * Play Services Cast framework is available on this device. Cast-less
     * emulators and GMS-free AOSP builds return false; the UI hides the
     * whole Cast section in that case.
     */
    val castAvailable: Boolean = false,
    /** Master switch: when off, scheduled runs skip the cast destination. */
    val castEnabled: Boolean = true,
    /** Per-period cast toggles; default true. */
    val castMorning: Boolean = true,
    val castTonight: Boolean = true,
    /** When true, audio-carrying cast suppresses the phone speaker. */
    val castSkipPhoneSpeech: Boolean = true,
) {
    /**
     * True when enabling "Speak" delivery would already produce audio with
     * nothing left to set up: device TTS is always available on the phone, and
     * Gemini is usable once there's a BYOK key or the shared proxy. The Schedule
     * screen reads this to avoid bouncing the user to Voice settings when speech
     * is already configured — there'd be nothing for them to do there.
     */
    val speechConfigured: Boolean
        get() = when (ttsEngine) {
            TtsEngine.DEVICE -> true
            TtsEngine.GEMINI -> apiKeyConfigured || sharedTtsAvailable
        }
}
