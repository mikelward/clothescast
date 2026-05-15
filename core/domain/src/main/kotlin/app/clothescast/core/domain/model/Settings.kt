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
) {
    companion object {
        const val DEFAULT_GEMINI_VOICE = "Despina"
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
