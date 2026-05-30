package app.clothescast.diag

/**
 * Aggregate-analytics view of the non-voice user settings. Fanned out by
 * [app.clothescast.diag.Telemetry] into one Firebase Analytics event per
 * Settings page — `settings_schedule`, `settings_clothes`, `settings_format`,
 * `settings_region`, `settings_display`, `settings_calendar` — so each event
 * name mirrors the screen the user edited. The split keeps each event under
 * Firebase's 25-param-per-event cap, which a single combined event had
 * reached; this type stays one cohesive snapshot regardless.
 *
 * The voice / region / TTS-engine settings live separately in
 * [SettingsAnalyticsSnapshot] as user properties so reports can break every
 * event down by them. The settings captured here are emitted as event params
 * instead because Firebase Analytics caps custom user properties at 25 per
 * app — comprehensive configuration breakdowns belong on periodic events.
 *
 * Schedule times are bucketed to the hour ("00".."23") rather than exact
 * local times; the day-of-week shape is captured only as a count. No user
 * content, no identifiers — every value is a short enum name, integer count,
 * or two-digit hour string.
 */
data class SettingsSnapshot(
    val temperatureUnitSetting: String,
    val temperatureUnitEffective: String,
    val distanceUnitSetting: String,
    val distanceUnitEffective: String,
    val deliveryModeDaily: String,
    val deliveryModeTonight: String,
    val themeMode: String,
    val colorPalette: String,
    val defaultBottom: String,
    val defaultTop: String,
    val dailyEnabled: Boolean,
    val dailyTimeBucketHour: String,
    val dailyDaysCount: Int,
    val tonightEnabled: Boolean,
    val tonightTimeBucketHour: String,
    val tonightDaysCount: Int,
    val tonightNotifyOnlyOnEvents: Boolean,
    val dailyMentionEveningEvents: Boolean,
    /** When the morning insight names clothing: ALWAYS / IF_CHANGED / NEVER. */
    val clothesMentionMode: String,
    /** How the insight prose renders the temperature range: NONE / DEGREES / BANDS. */
    val rangeFormat: String,
    /** How the clothes clause renders: ITEMS / LAYER_COUNT. */
    val clothesFormat: String,
    /** Whether bottoms appear in the wear clause: ALWAYS / IF_GARMENTS / NEVER. */
    val bottomsFormat: String,
    /** Significant-change threshold in whole °C, or -1 when the clause is off. */
    val deltaThresholdC: Int,
    /** How the change clause is phrased: DEGREES / BANDS. */
    val deltaFormat: String,
    val useCalendarEvents: Boolean,
)
