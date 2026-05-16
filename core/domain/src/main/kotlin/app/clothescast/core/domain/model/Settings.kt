package app.clothescast.core.domain.model

enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

enum class DistanceUnit { KILOMETERS, MILES }

/** User-facing unit preference, including [AUTO] to follow the device/region locale. */
enum class TemperatureUnitSetting { AUTO, CELSIUS, FAHRENHEIT }

/** User-facing unit preference, including [AUTO] to follow the device/region locale. */
enum class DistanceUnitSetting { AUTO, KILOMETERS, MILES }

/**
 * Wind-speed display unit. Currently derived from [DistanceUnit] at the call
 * site (see `DistanceUnit.windSpeedUnit()` in Units.kt) — a metric user sees
 * km/h, an imperial user sees mph. TODO: add `KNOTS` (and possibly `MS`) and
 * promote this to its own user-facing setting so sailors / pilots can pick a
 * wind unit independent of the distance preference.
 */
enum class WindSpeedUnit { KMH, MPH }

enum class DeliveryMode { NOTIFICATION_ONLY, TTS_ONLY, NOTIFICATION_AND_TTS }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Where the spoken-aloud audio comes from.
 *
 * - [DEVICE] uses Android's on-device TextToSpeech engine. Free, fully offline once
 *   voices are installed, but quality varies by vendor.
 * - [GEMINI] uses Gemini's audio-output model (`gemini-2.5-flash-preview-tts`) over
 *   the BYOK Gemini key. Near-human quality, requires network at speak-time, costs
 *   a small amount per character. Falls back to [DEVICE] if the call fails.
 */
enum class TtsEngine { DEVICE, GEMINI }

/**
 * Steers the global style preamble Gemini TTS prepends to every request.
 *
 * - [WEATHER_FORECASTER] reads the briefing in a national-news weather
 *   broadcast register — deliberate cadence, sentence-final lift, gentle
 *   emphasis on clothing advice. Default.
 * - [SCIENCE_TEACHER], [HISTORIAN], [SPORTSCASTER], [STADIUM_ANNOUNCER],
 *   [STORYTELLER], [FITNESS_INSTRUCTOR], [MORNING_PRESENTER] — persona
 *   registers that shape *delivery* without rewriting the text.
 * - [PIRATE] and [COWBOY] are novelty registers at the end of the picker;
 *   their directives permit brief in-character exclamations ("Arrr",
 *   "Howdy") so the result is more obviously playful.
 *
 * Only consulted when [TtsEngine] == [TtsEngine.GEMINI]; the on-device engine
 * doesn't accept style prompts.
 */
enum class TtsStyle {
    WEATHER_FORECASTER,
    SCIENCE_TEACHER,
    HISTORIAN,
    SPORTSCASTER,
    STADIUM_ANNOUNCER,
    STORYTELLER,
    FITNESS_INSTRUCTOR,
    MORNING_PRESENTER,
    PIRATE,
    COWBOY,
}

/**
 * User-selectable accent / language preference for spoken playback. Used by
 * each engine as best fits its capabilities:
 *
 *  - [TtsEngine.DEVICE] picks the actual voice to speak in (the Android
 *    TextToSpeech engine has separate en-US / en-GB / en-AU voices).
 *  - [TtsEngine.GEMINI] passes the locale through as a natural-language
 *    accent directive prepended to the prompt — Gemini's prebuilt voices are
 *    language-agnostic personalities and follow that direction.
 *
 * [SYSTEM] means "follow the phone's locale" — the right default for almost
 * everyone, since their device language already encodes their accent
 * preference. The explicit en-* options are for users whose phone locale
 * doesn't match the accent they want to hear (e.g. an en-AU speaker on an
 * en-US phone).
 */
enum class VoiceLocale(val bcp47: String?) {
    SYSTEM(null),
    EN_US("en-US"),
    EN_GB("en-GB"),
    EN_AU("en-AU"),
    EN_CA("en-CA"),
    DE_DE("de-DE"),
    DE_AT("de-AT"),
    DE_CH("de-CH"),
    FR_FR("fr-FR"),
    FR_CA("fr-CA"),
    IT_IT("it-IT"),
    ES_ES("es-ES"),
    ES_MX("es-MX"),
    CA_ES("ca-ES"),
    RU_RU("ru-RU"),
    PL_PL("pl-PL"),
    HR_HR("hr-HR"),
    SL_SI("sl-SI"),
    SR_RS("sr-Latn-RS"),
    SR_CYRL_RS("sr-Cyrl-RS"),
    BG_BG("bg-BG"),
    CS_CZ("cs-CZ"),
    SK_SK("sk-SK"),
    HU_HU("hu-HU"),
    RO_RO("ro-RO"),
    EL_GR("el-GR"),
    UK_UA("uk-UA"),
    PT_BR("pt-BR"),
    PT_PT("pt-PT"),
    NL_NL("nl-NL"),
    SV_SE("sv-SE"),
    DA_DK("da-DK"),
    NB_NO("nb-NO"),
    FI_FI("fi-FI"),
    ET_EE("et-EE"),
    LV_LV("lv-LV"),
    LT_LT("lt-LT"),
    TR_TR("tr-TR"),
    EN_ZA("en-ZA"),
    ID_ID("id-ID"),
    MS_MY("ms-MY"),
    FIL_PH("fil-PH"),
    SW_KE("sw-KE"),
    VI_VN("vi-VN"),
    TH_TH("th-TH"),
    ZH_CN("zh-CN"),
    ZH_TW("zh-TW"),
    HI_IN("hi-IN"),
    BN_BD("bn-BD"),
    JA_JP("ja-JP"),
    KO_KR("ko-KR"),
    AR_SA("ar-SA"),
    AR_EG("ar-EG"),
    AR_AE("ar-AE"),
    AR_MA("ar-MA"),
    HE_IL("he-IL"),
    FA_IR("fa-IR"),
    SQ_AL("sq-AL"),
    AM_ET("am-ET"),
}

/**
 * The user's region — drives the language used for rendered insight text and
 * the *default* unit choices for users who haven't explicitly picked units yet.
 *
 * [SYSTEM] (the default) means "follow the phone's locale": the right answer
 * for almost everyone, since their device language already encodes where they
 * are. The explicit en-* options are for users on a phone whose locale doesn't
 * match the region they want the app to behave as (e.g. an en-AU traveller on
 * an en-US phone).
 *
 * Distinct from [VoiceLocale]: that one is specifically about the *spoken
 * accent* of the audio playback — you might be in Australia but prefer a US
 * voice, or vice versa. Region is the higher-level "where am I" setting.
 */
enum class Region(val bcp47: String?) {
    SYSTEM(null),
    EN_US("en-US"),
    EN_GB("en-GB"),
    EN_AU("en-AU"),
    EN_CA("en-CA"),
    DE_DE("de-DE"),
    DE_AT("de-AT"),
    DE_CH("de-CH"),
    FR_FR("fr-FR"),
    FR_CA("fr-CA"),
    IT_IT("it-IT"),
    ES_ES("es-ES"),
    ES_MX("es-MX"),
    CA_ES("ca-ES"),
    RU_RU("ru-RU"),
    PL_PL("pl-PL"),
    HR_HR("hr-HR"),
    SL_SI("sl-SI"),
    SR_RS("sr-Latn-RS"),
    SR_CYRL_RS("sr-Cyrl-RS"),
    BG_BG("bg-BG"),
    CS_CZ("cs-CZ"),
    SK_SK("sk-SK"),
    HU_HU("hu-HU"),
    RO_RO("ro-RO"),
    EL_GR("el-GR"),
    UK_UA("uk-UA"),
    PT_BR("pt-BR"),
    PT_PT("pt-PT"),
    NL_NL("nl-NL"),
    SV_SE("sv-SE"),
    DA_DK("da-DK"),
    NB_NO("nb-NO"),
    FI_FI("fi-FI"),
    ET_EE("et-EE"),
    LV_LV("lv-LV"),
    LT_LT("lt-LT"),
    TR_TR("tr-TR"),
    EN_ZA("en-ZA"),
    ID_ID("id-ID"),
    MS_MY("ms-MY"),
    FIL_PH("fil-PH"),
    SW_KE("sw-KE"),
    VI_VN("vi-VN"),
    TH_TH("th-TH"),
    ZH_CN("zh-CN"),
    ZH_TW("zh-TW"),
    HI_IN("hi-IN"),
    BN_BD("bn-BD"),
    JA_JP("ja-JP"),
    KO_KR("ko-KR"),
    AR_SA("ar-SA"),
    HE_IL("he-IL"),
    FA_IR("fa-IR"),
    SQ_AL("sq-AL"),
    AM_ET("am-ET"),
}

data class UserPreferences(
    val schedule: Schedule,
    val deliveryMode: DeliveryMode,
    val region: Region = Region.SYSTEM,
    val temperatureUnit: TemperatureUnit,
    val distanceUnit: DistanceUnit,
    val temperatureUnitSetting: TemperatureUnitSetting = TemperatureUnitSetting.AUTO,
    val distanceUnitSetting: DistanceUnitSetting = DistanceUnitSetting.AUTO,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val clothesRules: List<ClothesRule>,
    /**
     * Which bottom garment the home-screen outfit picker falls back to when no
     * shorts / skirt / jeans rule fires — i.e. the user's "standard" trousers.
     * Defaults to [OutfitSuggestion.Bottom.LONG_PANTS]; a denim-everyday user
     * can flip it to [OutfitSuggestion.Bottom.JEANS] so the home-screen icon
     * matches what they actually wear most days. Only LONG_PANTS and JEANS are
     * surfaced in the Settings picker — SHORTS and SKIRT already have their
     * own rule-driven paths in the picker, and using either as a fallback
     * would defeat their warm-weather purpose.
     */
    val defaultBottom: OutfitSuggestion.Bottom = OutfitSuggestion.Bottom.LONG_PANTS,
    /**
     * The fixed location to fetch weather for when [useDeviceLocation] is false (or as a
     * fallback when device location can't be resolved). Null when the user has not
     * configured one.
     */
    val location: Location? = null,
    /**
     * When true, the worker tries to read the device's coarse location at notify time
     * (network provider — no GPS hardware fix needed) and falls back to [location] /
     * platform default if the read fails or permission is not granted.
     */
    val useDeviceLocation: Boolean = false,
    /**
     * Optional "home" pin used by [skipTtsAtHome] to decide whether the device is
     * physically at home at speak time. Stored at fine precision — the picker
     * yields a specific lat/lon (reverse-geocoded "use current location" or a
     * named search result) so the displayName stays precision-consistent with
     * the coordinates. The lat/lon never leaves the device; the displayName is
     * a UI label only and is not used for matching event-location strings.
     * Independent of [location] (the weather-fallback pin), which the user may
     * have set to a different place (e.g. travel destination, hometown).
     */
    val homeLocation: Location? = null,
    /**
     * When true, the worker suppresses TTS playback when the device is within
     * the at-home radius of [homeLocation]. Notifications are unaffected. Fail
     * open: if no home is configured or the device location can't be resolved,
     * we still speak — silence away from home is the worst case we're trying
     * to avoid. Off by default; the user enables it when they have a separate
     * at-home announcer (e.g. Home Assistant) and don't want the phone to
     * duplicate it.
     */
    val skipTtsAtHome: Boolean = false,
    val ttsEngine: TtsEngine = TtsEngine.DEVICE,
    /**
     * Prebuilt Gemini voice name (e.g. "Erinome", "Kore"). Only consulted when
     * [ttsEngine] == [TtsEngine.GEMINI]. Stored as a free-form string so adding
     * voices doesn't require a domain enum migration.
     */
    val geminiVoice: String = DEFAULT_GEMINI_VOICE,
    /**
     * Steers the style preamble for Gemini TTS. Default is
     * [TtsStyle.WEATHER_FORECASTER] — national-news broadcast delivery.
     * Users can switch to any of the character registers (pirate, cowboy,
     * etc.). Only consulted when [ttsEngine] == [TtsEngine.GEMINI].
     */
    val ttsStyle: TtsStyle = TtsStyle.WEATHER_FORECASTER,
    /**
     * On-device TextToSpeech voice ID (e.g. "en-us-x-tpc-network"). Only
     * consulted when [ttsEngine] == [TtsEngine.DEVICE]. `null` (the default)
     * means "auto-pick the highest-quality voice for [voiceLocale]" — the
     * existing behaviour for installs that predate the device-voice picker.
     * Stored as a free-form string so the device's installed-voice catalogue
     * doesn't have to round-trip through a domain enum.
     */
    val deviceVoice: String? = null,
    val voiceLocale: VoiceLocale = VoiceLocale.SYSTEM,
    /**
     * When true, the worker reads today's calendar events (via `READ_CALENDAR`)
     * and feeds them into the insight summary so the rendered string can tie a
     * clothes suggestion to a specific event ("bring an umbrella for your 3pm
     * standup"). Off by default — the user must both enable the toggle and grant
     * the runtime permission for events to actually be read.
     */
    val useCalendarEvents: Boolean = false,
    /**
     * When the evening / "tonight" insight should fire. Distinct from [schedule]
     * (the morning slot) so the user can keep the morning at 07:00 and still
     * tweak the evening time independently. Default is 19:00 every day.
     */
    val tonightSchedule: Schedule = Schedule.defaultTonight(schedule.zoneId),
    /**
     * Master switch for the evening / "tonight" insight. On by default — the
     * tonight notifier is silent when there are no calendar events for the
     * evening, so it's not noisy out of the box. The user can disable it from
     * the schedule settings page.
     */
    val tonightEnabled: Boolean = true,
    /**
     * Delivery mode for the evening / "tonight" insight. Distinct from
     * [deliveryMode] (the morning slot) so the user can keep the morning as a
     * silent notification and have the evening read itself out, or vice versa.
     * The repository falls the stored tonight value back to [deliveryMode] when
     * absent so existing installs keep their old "shared mode" behaviour until
     * the user explicitly diverges them.
     */
    val tonightDeliveryMode: DeliveryMode = deliveryMode,
    /**
     * When true, the nightly insight only posts a notification (and only speaks
     * via TTS) on evenings with calendar events; on event-free evenings it
     * still refreshes silently — caches the insight and updates the widget /
     * Today card — but skips the notification entirely. When false (default),
     * the silent notification channel still posts a no-sound notification on
     * empty evenings, which is what the existing tonight notifier did.
     */
    val tonightNotifyOnlyOnEvents: Boolean = false,
    /**
     * When true, the morning insight tacks on a brief mention of any evening
     * calendar events with a clothing tip keyed to the *evening* forecast — e.g.
     * "Bring a jacket for your 9pm dinner." The tip is gated on
     * [useCalendarEvents] (no events without that), and only fires when at least
     * one clothes rule triggers against the evening hourly slice. On by default.
     */
    val dailyMentionEveningEvents: Boolean = true,
    /**
     * Master switch for sending Firebase Analytics + Crashlytics payloads off
     * device. Default on so crash reports for the long tail of installs reach
     * the developer without each user finding the toggle, but a non-blocking
     * banner on Today on first launch surfaces the setting and a one-tap path
     * to flip it off (see Privacy in Settings). The contract for what may /
     * may not appear in those payloads is in PRIVACY.md — calendar event
     * data, location, insight prose, and API keys are all out of scope.
     */
    val telemetryEnabled: Boolean = true,
    /**
     * True once the user has dismissed the one-time telemetry-notice banner on
     * Today. The banner exists to make the default-on choice transparent — it
     * disappears for good after a single tap, regardless of whether the user
     * left telemetry on or flipped it off in Settings. Stored separately from
     * [telemetryEnabled] so a user who turns telemetry off and on again
     * doesn't see the banner re-surface; once they've seen it once, it's done.
     */
    val telemetryNoticeAcked: Boolean = false,
    /**
     * Which app-specific colour palette to use for the per-model chart
     * overlays and the confidence-chip / low-confidence-callout backgrounds.
     * Defaults to [ColorPalette.RAINBOW] — the existing pink / orange /
     * green model lines and Material teal / red confidence tints. The user
     * can pick [ColorPalette.ACCESSIBLE] in Display settings to swap both
     * for an Okabe-Ito-derived palette that stays distinguishable under
     * deuteranopia, protanopia, and tritanopia.
     */
    val colorPalette: ColorPalette = ColorPalette.RAINBOW,
    /**
     * User overrides for the fill colour of each top-garment icon (the
     * shirt / sweater / jacket pictures shown on Today, the home-screen
     * widget, and notification large icons). Keyed by [OutfitSuggestion.Top]
     * since that's the rendered icon tier — `Garment` (the rule-list
     * catalogue) has entries like `hoodie` and `shirt` that share an icon
     * with `sweater` / `polo`, so colouring per icon is what matches the
     * user's mental model of "the jacket icon."
     *
     * Stored as packed ARGB ([Int.toLong] of an ARGB int — a `Long` so the
     * top byte's alpha doesn't sign-extend). A missing entry means "use the
     * baked-in default colour from the XML," so existing installs read empty
     * and render byte-identical to today. The stroke is auto-derived as a
     * darker shade of the chosen fill at render time; only the fill is
     * persisted.
     */
    val outfitTopColors: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    /** Sibling of [outfitTopColors] for the bottom-garment icons. */
    val outfitBottomColors: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    /**
     * Which numerical-weather-prediction models the multi-model confidence
     * fetcher consults — or `null` for "auto, derive from current location"
     * (see [ForecastModel.defaultsFor]). Fresh installs default to null so
     * a user who never opens the Forecasters picker gets a region-appropriate
     * trio (UKMO + ECMWF + ICON over the British Isles, JMA over Japan,
     * GFS + GEM + ECMWF over North America, etc.) that follows them if they
     * move. The first explicit pick in the picker switches this to a non-null
     * Set; flipping the Auto switch back on clears it to null again.
     *
     * Stored as a [Set] because order doesn't matter — the URL builder
     * sorts to a deterministic comma-separated list, and the confidence
     * chip / per-model chart don't depend on a particular ordering.
     */
    val forecastModels: Set<ForecastModel>? = null,
    /**
     * Optional Smart Home / Home Assistant MQTT bridge. When [mqttBridgeEnabled]
     * is true and [mqttHost] is set, the worker publishes the rendered insight
     * prose to a retained topic on the user's MQTT broker after each twice-daily
     * refresh, so Home Assistant (or any MQTT-aware consumer) can speak it on
     * a sensor trigger without reimplementing the clothes / insight logic in HA.
     * Off by default — this relaxes the "insight prose never leaves the device"
     * guarantee, so it's opt-in and surfaced in Settings → Smart Home with an
     * inline privacy note that links to PRIVACY.md.
     *
     * Topics are derived from [mqttTopic] as the prefix + the lowercased
     * [ForecastPeriod] name (e.g. `clothescast/insight/today` and
     * `clothescast/insight/tonight`), so morning and evening insights are
     * separately addressable from HA automations.
     */
    val mqttBridgeEnabled: Boolean = false,
    val mqttHost: String? = null,
    val mqttPort: Int = DEFAULT_MQTT_PORT,
    val mqttUseTls: Boolean = false,
    val mqttUsername: String? = null,
    val mqttTopic: String = DEFAULT_MQTT_TOPIC,
) {
    companion object {
        const val DEFAULT_GEMINI_VOICE = "Despina"
        const val DEFAULT_MQTT_PORT = 1883
        const val DEFAULT_MQTT_TLS_PORT = 8883
        const val DEFAULT_MQTT_TOPIC = "clothescast/insight"
    }
}

/**
 * Numerical weather prediction model the multi-model confidence fetcher can
 * consult. Each entry's [openMeteoId] is the string Open-Meteo's `models=`
 * parameter expects — the data layer reads it when building the URL, so the
 * domain doesn't have to leak Open-Meteo specifics anywhere else.
 *
 * The set the user has enabled lives at [UserPreferences.forecastModels].
 * Default is the trio that's been shipping: ECMWF, GFS, ICON — three
 * independent global majors (European, American, German), chosen for
 * dynamical-core diversity rather than for each being the most accurate
 * individually. Users who care about the spread (or whose region has a
 * stronger regional model) can swap in others via the Forecasters picker.
 *
 * Note on temporal cadence: ECMWF IFS, GEM, UKMO, JMA, and BOM are 3- or
 * 6-hourly on the open-data feed Open-Meteo distributes, which Open-Meteo
 * interpolates to hourly. ICON, GFS, and ARPEGE are natively hourly. This
 * matters for the per-model chart's hour-by-hour curves but not for the
 * daily-aggregate confidence chip.
 */
enum class ForecastModel(val openMeteoId: String) {
    // Order matters: the chart's MODEL_DRAW_ORDER and the per-model legend
    // derive from `entries`, so the per-overlay z-order on Today's charts is
    // best_match first (drawn underneath as a thicker reference baseline)
    // then the entry order below on top. Keep the original shipping trio
    // (ECMWF / GFS / ICON) at the head of the enum so the consulted-model
    // layering stays pixel-identical to pre-picker builds; new models
    // append in geographical-spread order (NA → Europe → Asia-Pacific).
    ECMWF_IFS04("ecmwf_ifs04"),
    GFS_SEAMLESS("gfs_seamless"),
    ICON_SEAMLESS("icon_seamless"),
    GEM_SEAMLESS("gem_seamless"),
    METEOFRANCE_SEAMLESS("meteofrance_seamless"),
    UKMO_SEAMLESS("ukmo_seamless"),
    JMA_SEAMLESS("jma_seamless");
    // BOM_ACCESS_GLOBAL deliberately excluded — Open-Meteo's BOM docs note
    // that "BOM is currently upgrading its key platforms and services. During
    // this process, open-data delivery has been temporarily suspended." With
    // open-data suspended, requesting BOM returns no usable fields and the
    // model silently disappears from the chart. Re-add the enum entry +
    // palette colours when BOM resumes delivery (the Forecasters picker
    // already shows a disabled BOM row with an "under maintenance" subtitle
    // so the option's discoverable while we wait).
    // See https://open-meteo.com/en/docs/bom-api

    companion object {
        /**
         * The three models the confidence chip has shipped with — ECMWF, GFS,
         * ICON. The location-agnostic fallback used by [defaultsFor] when no
         * location is known yet (fresh install before first GPS fix) or when
         * the location doesn't sit in any of the regional branches, and as
         * the recovery set when the user has somehow deselected everything
         * (the picker's UI prevents that, but a hand-edited DataStore could).
         */
        val DEFAULTS: Set<ForecastModel> = setOf(ECMWF_IFS04, GFS_SEAMLESS, ICON_SEAMLESS)
    }
}

/**
 * App-specific colour palette for the chart overlays and confidence cards.
 * The name carries the user-facing semantics rather than a "colourblind
 * accommodation" framing: [RAINBOW] is the saturated pink / orange / green
 * trio the app shipped with, [ACCESSIBLE] is an Okabe-Ito-derived palette
 * designed to stay distinguishable for users with red-green or blue-yellow
 * colour vision deficiencies — but readable to everyone, so it isn't pitched
 * as a CB-only mode — and [HIGHLIGHTER] is a magenta / lime / cyan neon
 * triad (Tron-vibe) for users who want a chart that's *obviously* different
 * from Rainbow at a glance. CVD-safe under all three common profiles by
 * routing the trio around the deutan / protan / tritan collision axes.
 */
enum class ColorPalette { RAINBOW, ACCESSIBLE, HIGHLIGHTER }
