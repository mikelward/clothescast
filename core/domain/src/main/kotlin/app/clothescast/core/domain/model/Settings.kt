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

/**
 * Per-holiday firing state, with [AUTO] the default and [ON] / [OFF] as
 * explicit overrides:
 *
 *  - [AUTO] — derived from the country picker. The holiday fires only
 *    when at least one of its [HolidayTheme.countries] is in the user's
 *    effective enabled-country set.
 *  - [ON] — force on regardless of country picker (a user can pin
 *    Bastille Day even though they live in Australia).
 *  - [OFF] — force off regardless of country picker (a user can hide
 *    their own country's Anzac Day if they don't want the theme).
 *
 * The Settings UI presents this as a dropdown whose [AUTO] label
 * includes the currently-resolved value in parentheses ("Auto (on)" /
 * "Auto (off)") so the user can see what the country picker is doing
 * without flipping to the holiday's row in their head.
 */
enum class HolidayOverride { AUTO, ON, OFF }

/**
 * Country picker state. Two layers stack: top-level buckets ([home] /
 * [current] / [global] / [all]) drive each country's *auto* resolution,
 * and [countryOverrides] lets the user force an individual country on
 * or off regardless of those buckets.
 *
 *  - [home] — when on, the user's locale country (e.g. en-GB → "GB")
 *    resolves to effective-on under AUTO.
 *  - [current] — when on, the weather location's country (reverse-
 *    geocoded from the active forecast pin) resolves to effective-on
 *    under AUTO.
 *  - [global] — when on, the universal-holiday bucket
 *    ([HolidayCatalog.GLOBAL_COUNTRY]: Christmas, New Year's,
 *    Valentine's, Halloween) resolves to effective-on under AUTO.
 *    Peer to [home] / [current] rather than riding along
 *    automatically, so a user can mute every global holiday as a
 *    group without flipping per-holiday rows.
 *  - [funny] — when on, the Funny bucket
 *    ([HolidayCatalog.FUNNY]: Talk Like a Pirate Day) resolves to
 *    effective-on under AUTO. A peer toggle like [global], on by
 *    default, so a user can mute the playful observances as a group.
 *  - [christian] — when on, the Christian bucket
 *    ([HolidayCatalog.CHRISTIAN]: Ash Wednesday, Mardi Gras / Shrove
 *    Tuesday, Palm Sunday, Maundy Thursday, Ascension Day, Pentecost,
 *    Whit Monday, Corpus Christi) resolves to effective-on under AUTO.
 *    **Default-off** — these are religious-tradition observances
 *    rather than universal civic days, so we don't push them onto
 *    every user. Users in Christian-tradition countries with public
 *    holidays still get the relevant entries via their `home`
 *    country: each holiday is tagged BOTH with the CHRISTIAN
 *    sentinel AND with the ISO codes where it's a public holiday
 *    (e.g. Ascension fires for FR/DE/AT users via their home country
 *    without flipping this toggle). Opt in to extend coverage to
 *    the strictly-liturgical days (Ash Wed, Palm Sun, Maundy Thu)
 *    that aren't public holidays anywhere.
 *  - [orthodox] — when on, the Orthodox-Christian bucket
 *    ([HolidayCatalog.ORTHODOX]: Orthodox Christmas Jan 7) resolves to
 *    effective-on under AUTO. Default-off — most users aren't in the
 *    Orthodox Christian tradition, and a Russian / Greek / Serbian
 *    user picks it up automatically via their `home` country anyway.
 *  - [all] — short-circuits every country (including
 *    [HolidayCatalog.GLOBAL_COUNTRY]) to effective-on under AUTO.
 *    Per-country [HolidayOverride.OFF] still wins (a user can opt
 *    out of a single country even with All on).
 *  - [countryOverrides] — explicit per-country overrides. Missing /
 *    [HolidayOverride.AUTO] entries follow the buckets above;
 *    [HolidayOverride.ON] forces the country on regardless;
 *    [HolidayOverride.OFF] forces it off regardless. Keys are
 *    ISO 3166-1 alpha-2 uppercase, or [HolidayCatalog.GLOBAL_COUNTRY]
 *    for the universal-holiday bucket.
 *
 * Default ([home]=true, [current]=true, [global]=true,
 * [funny]=true) matches the previous "Auto" behaviour: locale +
 * weather location + universal holidays + playful observances.
 * [christian] and [orthodox] are opt-in religious buckets.
 */
data class HolidayCountrySelection(
    val home: Boolean = true,
    val current: Boolean = true,
    val global: Boolean = true,
    val funny: Boolean = true,
    val christian: Boolean = false,
    val orthodox: Boolean = false,
    val all: Boolean = false,
    val countryOverrides: Map<String, HolidayOverride> = emptyMap(),
) {
    /**
     * Effective state a country would have if its [HolidayOverride] were
     * [HolidayOverride.AUTO] — i.e. whether the [home] / [current] /
     * [all] buckets pick it up. Used to label the per-country dropdown's
     * `Auto` option as "Auto (on)" / "Auto (off)".
     */
    fun countryAutoEffective(
        code: String,
        localeCountry: String?,
        weatherLocationCountry: String?,
    ): Boolean {
        if (all) return true
        val normalised = code.trim().takeIf { it.isNotEmpty() }?.uppercase() ?: return false
        if (normalised == HolidayCatalog.GLOBAL_COUNTRY) return global
        if (normalised == HolidayCatalog.FUNNY) return funny
        if (normalised == HolidayCatalog.CHRISTIAN) return christian
        if (normalised == HolidayCatalog.ORTHODOX) return orthodox
        if (home && normalised == localeCountry?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()) {
            return true
        }
        if (current && normalised == weatherLocationCountry?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()) {
            return true
        }
        return false
    }

    /**
     * Effective state for a country, honouring [countryOverrides] first
     * and falling through to [countryAutoEffective] when the override is
     * [HolidayOverride.AUTO] or absent.
     */
    fun countryEffective(
        code: String,
        localeCountry: String?,
        weatherLocationCountry: String?,
    ): Boolean {
        val normalised = code.trim().takeIf { it.isNotEmpty() }?.uppercase() ?: return false
        return when (countryOverrides[normalised] ?: HolidayOverride.AUTO) {
            HolidayOverride.ON -> true
            HolidayOverride.OFF -> false
            HolidayOverride.AUTO -> countryAutoEffective(normalised, localeCountry, weatherLocationCountry)
        }
    }

    /**
     * Resolves the effective enabled-country set used by [HolidayResolver]
     * (and by the Settings UI to render the per-holiday Auto subtitle).
     * Centralised so SettingsViewModel and TodayViewModel compute the same
     * thing from the same inputs.
     *
     * Country codes are ISO 3166-1 alpha-2 uppercase plus the sentinel
     * [HolidayCatalog.GLOBAL_COUNTRY]. [localeCountry] and
     * [weatherLocationCountry] are case-insensitive — null or blank values
     * are ignored. [HolidayCatalog.GLOBAL_COUNTRY] is gated by its own
     * [global] bucket, and [HolidayCatalog.FUNNY] by its own [funny]
     * bucket (or an explicit [countryOverrides] entry), the same
     * way ISO codes are gated by [home] / [current].
     */
    fun resolveEnabledCountries(
        localeCountry: String?,
        weatherLocationCountry: String?,
        allCountries: Set<String>,
    ): Set<String> = buildSet {
        for (code in allCountries) {
            if (countryEffective(code, localeCountry, weatherLocationCountry)) add(code)
        }
        // Pick up ON-override countries that aren't in `allCountries` (e.g.
        // a forward-compat catalog entry we haven't shipped yet). OFF entries
        // are never added.
        for ((code, override) in countryOverrides) {
            if (override == HolidayOverride.ON) add(code)
        }
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * How the insight prose renders the temperature-range clause ("…it will be …").
 *
 *  - [NONE] drops the range sentence entirely, folding its "Today" / "Tonight"
 *    lead into the next clause ("Today, wear a sweater."). This is the old
 *    `omitTemperatureRange = true` behaviour.
 *  - [DEGREES] (default) renders the numeric feels-like range in the user's unit
 *    ("Today, it will be 14° to 20°.").
 *  - [BANDS] renders the [TemperatureBand] classification as words
 *    ("Today, it will be cool to mild.").
 */
enum class RangeFormat { NONE, DEGREES, BANDS }

/**
 * How the insight prose renders the clothes clause ("Wear …").
 *
 *  - [ITEMS] (default) names each triggered garment — the historical
 *    behaviour: "Wear a sweater.", "Wear a sweater and shorts."
 *  - [LAYER_COUNT] collapses the top garments to a perceived-warmth count
 *    (see [Garment.layerCount]) and renders "Wear 2 layers." Bottoms are
 *    suppressed in this mode — the whole point is a single warmth signal,
 *    so a trailing "and shorts" adds noise. The wear clause is dropped
 *    entirely when only a bottom rule fires (no top to count). The count
 *    is the *max* layer count across firing tops, not a sum — wearing a
 *    sweater (2) under a jacket (3) lands at 3 because the jacket defines
 *    the warmth tier, not 5.
 */
enum class ClothesFormat { ITEMS, LAYER_COUNT }

/**
 * Controls whether the morning insight's spoken clothes clause ("Wear a
 * jacket.") is emitted.
 *
 *  - [ALWAYS] (default) names the triggered clothing every morning — the
 *    historical behaviour.
 *  - [IF_CHANGED] names clothing only when today's recommended items differ
 *    from yesterday's, so an unchanged forecast stays silent on clothes.
 *  - [NEVER] never puts clothing in the morning prose.
 *
 * Affects the spoken/prose clause only — the outfit card, recommended items,
 * and "Why this outfit?" rationale are unaffected. Morning ([ForecastPeriod.TODAY])
 * only: yesterday's overnight data isn't available for a tonight comparison,
 * so the tonight insight always names clothing.
 */
enum class ClothesMentionMode { ALWAYS, IF_CHANGED, NEVER }

/**
 * Optional wet-weather accessory the user wants named alongside the rain mention.
 *
 *  - [NONE] (default) keeps the historical behaviour — the precip clause names
 *    rain but never an accessory, and `ClothesRule.DEFAULTS` ships no umbrella
 *    rule.
 *  - [UMBRELLA] folds "bring an umbrella" into both the morning precip clause
 *    and the evening event tie-in whenever rain is detected at or above the
 *    POSSIBLE threshold (≥ 30%).
 *
 * The enum shape leaves room to grow (rain jacket, hood, rain boots, …) without
 * a DataStore migration. Until then the dropdown ships only NONE / UMBRELLA.
 *
 * TODO(rain-accessory-tiers): support different accessories for different
 *  probability tiers (e.g. umbrella ≥ 30%, rain jacket ≥ 70%) by storing a
 *  list of (threshold, accessory) pairs rather than a single enum.
 */
enum class RainAccessory { NONE, UMBRELLA }

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
 * - [PIRATE], [COWBOY] and [SCIFI_NARRATOR] are non-seasonal novelty
 *   registers; the playful ones permit brief in-character exclamations
 *   ("Arrr", "Howdy") and [SCIFI_NARRATOR] is a dry, deadpan British
 *   science-fiction guidebook voice (no named IP — just the register).
 * - [FATHER_CHRISTMAS], [SPOOKY_NARRATOR], [NEW_YEARS_HOST], [LEPRECHAUN],
 *   [KING], [QUEEN] and [PRESIDENT] are seasonal novelty registers ([KING] /
 *   [QUEEN] evoke King Charles III / Queen Elizabeth II for occasions with a
 *   royal in the name; [PRESIDENT] evokes the solemn oratory of George
 *   Washington / Abraham Lincoln for Presidents' Day and the like). They're
 *   always available in the picker, and `holidayTtsStyle` (see HolidayVoice.kt)
 *   auto-selects the matching one around its holiday (Christmas, Halloween,
 *   New Year's, St Patrick's Day, the King's birthday, Presidents' Day, plus
 *   Talk Like a Pirate Day and Towel Day) for users who haven't picked a
 *   deliberate non-default style.
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
    SCIFI_NARRATOR,
    FATHER_CHRISTMAS,
    SPOOKY_NARRATOR,
    NEW_YEARS_HOST,
    LEPRECHAUN,
    KING,
    QUEEN,
    PRESIDENT,
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
     * Defaults to [OutfitSuggestion.Bottom.LONG_PANTS]; the Settings "If no
     * rules match" card lets the user pick any of the [OutfitSuggestion.Bottom]
     * tiers so the home-screen icon matches what they actually wear when no
     * rule fires.
     */
    val defaultBottom: OutfitSuggestion.Bottom = OutfitSuggestion.Bottom.LONG_PANTS,
    /**
     * Which top garment the home-screen outfit picker falls back to when no
     * cold-weather rule fires. Defaults to [OutfitSuggestion.Top.TSHIRT]; the
     * Settings "If no rules match" card lets the user pick any of the
     * [OutfitSuggestion.Top] tiers (e.g. a polo-shirt-everyday user can flip
     * the fallback to [OutfitSuggestion.Top.POLO]).
     */
    val defaultTop: OutfitSuggestion.Top = OutfitSuggestion.Top.TSHIRT,
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
     * Master switch for all calendar access. When off, nothing reads the
     * device calendar regardless of the per-feature toggles below — event
     * mentions, holiday / birthday theming, and the Celebrations listing all
     * stay dark. The `READ_CALENDAR` prompt hangs off this toggle (on the
     * Calendar settings page). Off by default for fresh installs; the
     * repository derives `true` on read for existing installs that already had
     * any per-feature toggle on, so an upgrade never silently disables a
     * calendar feature the user had enabled. The per-feature toggles
     * ([useCalendarEvents], [themeFromCalendarHolidays],
     * [themeFromCalendarBirthdays]) are only consulted when this is on — see
     * [calendarEventMentionsActive] and friends. Turning the master off clears
     * those toggles (atomically, in the repository) so that re-enabling one
     * feature later — which flips the master back on — doesn't silently revive
     * the others.
     */
    val calendarEnabled: Boolean = false,
    /**
     * When true, the worker reads today's calendar events (via `READ_CALENDAR`)
     * and feeds them into the insight summary so the rendered string can
     * surface a heads-up motivated by an upcoming event (e.g. "Bring an
     * umbrella." when rain peaks during an event window). Event titles and
     * times never appear in the rendered prose — they only gate emission —
     * because the prose flows to Gemini TTS off-device. Gated by
     * [calendarEnabled] — see [calendarEventMentionsActive]. Off by default —
     * the user must both enable the toggle and grant the runtime permission
     * for events to actually be read.
     */
    val useCalendarEvents: Boolean = false,
    /**
     * Opt-in: theme the Today screen on holidays detected in synced Google
     * calendars (Diwali, Eid, Lunar New Year, etc. — gaps in the curated
     * [HolidayCatalog]). Curated holiday theming is unaffected and keeps
     * firing by default; this toggle only controls Google-calendar
     * augmentation. Reading the calendar requires `READ_CALENDAR`, prompted
     * by the toggle on the Holiday settings screen.
     */
    val themeFromCalendarHolidays: Boolean = false,
    /**
     * Opt-in: theme the Today screen on birthdays detected in synced
     * calendars (Google's auto-Birthdays calendar, the new Birthday event
     * type, or user-entered "<name>'s birthday" events). Off by default.
     * Like [themeFromCalendarHolidays], requires `READ_CALENDAR`.
     */
    val themeFromCalendarBirthdays: Boolean = false,
    /**
     * Set to true once the user dismisses the Today-screen "Celebration
     * themes" promo card via its X button. Hides the card permanently even
     * if both theming toggles remain off — same pattern as
     * [telemetryNoticeAcked]. The card also auto-hides when either theming
     * toggle is on.
     */
    val celebrationCardDismissed: Boolean = false,
    /**
     * Bumped to `System.currentTimeMillis()` whenever the user re-grants
     * `READ_CALENDAR` via the in-app permission flow. Exists purely to
     * force the [SettingsRepository.preferences] flow to re-emit so any
     * downstream consumer that derives state from a calendar read (today
     * the [HolidayResolver]+[ThemeForToday] combine in `TodayViewModel`)
     * picks up the new permission state immediately, instead of waiting
     * for the next pref edit or midnight rollover. Carries no other
     * semantic — readers should not branch on its value.
     */
    val calendarPermissionRecheckTick: Long = 0L,
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
     * When true, the morning insight tacks on a clothing tip keyed to any
     * evening calendar event's *evening* forecast slice — e.g. "Tonight, bring
     * a jacket." The event itself is never named in the prose (it stays on
     * device). The tip is gated on [useCalendarEvents] (no events without
     * that), and only fires when at least one clothes rule triggers against
     * the evening hourly slice. On by default.
     */
    val dailyMentionEveningEvents: Boolean = true,
    /**
     * Whether — and when — the morning insight names the clothing its rules
     * trigger. See [ClothesMentionMode]. Morning ([ForecastPeriod.TODAY]) and
     * prose only; defaults to [ClothesMentionMode.ALWAYS] to preserve the
     * existing behaviour.
     */
    val clothesMentionMode: ClothesMentionMode = ClothesMentionMode.ALWAYS,
    /**
     * How the insight prose renders the temperature-range clause: dropped
     * ([RangeFormat.NONE]), numeric ([RangeFormat.DEGREES], default), or band
     * words ([RangeFormat.BANDS]). The numbers still show beside the thermometer
     * on the smart-display outfit card regardless — those come straight from the
     * hourly forecast, not the prose. See [InsightFormatter] for the rendering.
     */
    val rangeFormat: RangeFormat = RangeFormat.DEGREES,
    /**
     * How the insight prose renders the clothes clause: list of items
     * ([ClothesFormat.ITEMS], default — "Wear a sweater.") or perceived
     * layer count ([ClothesFormat.LAYER_COUNT] — "Wear 2 layers."). The
     * outfit card and item icons are unaffected — only the prose changes.
     * See [InsightFormatter] and [Garment.layerCount].
     */
    val clothesFormat: ClothesFormat = ClothesFormat.ITEMS,
    /**
     * Optional wet-weather accessory named alongside the rain mention in both
     * the morning precip clause and the evening event tie-in. Defaults to
     * [RainAccessory.NONE] so existing installs see byte-identical prose; the
     * setting opts in to "bring an umbrella." See [RainAccessory] and
     * [InsightFormatter].
     */
    val rainAccessory: RainAccessory = RainAccessory.NONE,
    /**
     * Feels-like delta (in °C) the day must differ from yesterday by before the
     * "…warmer/cooler than yesterday" clause is emitted. `null` turns the clause
     * off entirely. Defaults to 3.0°C, the historical hard-coded threshold. See
     * [app.clothescast.core.domain.usecase.RenderInsightSummary].
     */
    val deltaThresholdC: Double? = 3.0,
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
     * Holiday themes the user wants to participate in. When today matches an
     * enabled holiday's date predicate, the Today screen recolours the outfit
     * preview to the holiday palette and shows a small banner. Default is
     * all-on — every holiday surfaces until the user opts out; the per-holiday
     * checkboxes in Settings → Holidays make the list discoverable so a user
     * who only cares about Christmas can pare it down to one. No region gate:
     * the toggle is the user's signal, not their locale.
     *
     * Stored as the enum-name [Set] (DataStore stringSet); unknown names on
     * read are dropped silently so a forward-compat downgrade doesn't crash
     * the flow. Missing key on first read seeds the default (all on).
     */
    /**
     * Country picker for the holiday filter. Default ([home]=true,
     * [current]=true) shows locale + weather-location country + universal
     * holidays. Per-country ON / OFF overrides
     * ([HolidayCountrySelection.countryOverrides]) layer on top — a user
     * can pin France even from Australia or hide their own country.
     * Drives the auto resolution of every holiday's [HolidayOverride.AUTO]
     * state.
     */
    val holidayCountrySelection: HolidayCountrySelection = HolidayCountrySelection(),
    /**
     * Per-holiday explicit overrides. Missing entries default to
     * [HolidayOverride.AUTO] — holidays follow the country picker. Only
     * [HolidayOverride.ON] and [HolidayOverride.OFF] are persisted; the
     * map is sparse so a fresh install carries no per-holiday state at
     * all and Auto is implicit. Stored as `ID:STATE` pairs in a
     * stringSet so unknown holiday ids (from a downgrade across a
     * future-added entry) drop silently on read.
     */
    val holidayOverrides: Map<HolidayId, HolidayOverride> = emptyMap(),
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
     * [ForecastPeriod] name + a payload-kind suffix (`text`, `image`,
     * `audio`), so morning and evening insights are separately addressable
     * from HA automations and each modality lives on its own retained
     * topic — e.g. `clothescast/default/today/text`,
     * `clothescast/default/today/image`, `clothescast/default/today/audio`.
     */
    val mqttBridgeEnabled: Boolean = false,
    val mqttHost: String? = null,
    val mqttPort: Int = DEFAULT_MQTT_PORT,
    val mqttUseTls: Boolean = false,
    val mqttUsername: String? = null,
    val mqttTopic: String = DEFAULT_MQTT_TOPIC,
    /**
     * The Cast receiver the user picked in Settings → Smart Home → Cast.
     * Null when no device has been chosen — the cast pipeline no-ops until
     * the user picks one from the discovered routes. Stable across reboots;
     * the friendly name is cached separately in [castRouteName] because the
     * route only re-appears in the discovery list when the device is on the
     * LAN, and we want the Settings row to keep showing the chosen device
     * even while it's powered off.
     */
    val castRouteId: String? = null,
    /**
     * Friendly display name for [castRouteId], captured at pick time so the
     * Settings row reads "Living-room display" even when the device is off
     * and live discovery returns nothing. Refreshed opportunistically when
     * the device next surfaces in a discovery scan.
     */
    val castRouteName: String? = null,
    /**
     * Per-period cast toggles. When off, the worker doesn't cast at that
     * period even if a route is picked — useful for users who want the
     * morning forecast on the smart display but not a tonight follow-up
     * (or vice versa). Default on for both; the picker itself is the
     * primary on/off switch.
     */
    val castMorning: Boolean = true,
    val castTonight: Boolean = true,
    /**
     * When on (default), suppresses the phone speaker when an
     * audio-carrying cast actually plays. The smart display is handling
     * the audio; doubling up on the phone is redundant. Image-only casts
     * (Gemini unavailable, smart display showing the outfit PNG silently)
     * don't trigger the suppression — the phone speaker is what speaks in
     * that path. The phone *notification* still posts per [deliveryMode];
     * only TTS playback is affected.
     */
    val castSkipPhoneSpeech: Boolean = true,
) {
    /**
     * Effective per-feature flags: a feature reads the calendar only when the
     * master [calendarEnabled] switch is on *and* its own toggle is on. Every
     * calendar consumer should branch on these rather than the raw toggles so
     * the master switch can't be bypassed.
     */
    val calendarEventMentionsActive: Boolean get() = calendarEnabled && useCalendarEvents
    val calendarHolidayThemingActive: Boolean get() = calendarEnabled && themeFromCalendarHolidays
    val calendarBirthdayThemingActive: Boolean get() = calendarEnabled && themeFromCalendarBirthdays

    companion object {
        const val DEFAULT_GEMINI_VOICE = "Despina"
        const val DEFAULT_MQTT_PORT = 1883
        const val DEFAULT_MQTT_TLS_PORT = 8883
        const val DEFAULT_MQTT_TOPIC = "clothescast/default"
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
