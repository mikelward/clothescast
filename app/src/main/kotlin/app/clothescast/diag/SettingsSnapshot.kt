package app.clothescast.diag

/**
 * Aggregate-analytics view of the non-voice user settings, intended as the
 * params of a Firebase Analytics `settings_snapshot` event.
 *
 * The voice / region / TTS-engine settings live separately in
 * [SettingsAnalyticsSnapshot] as user properties so reports can break every
 * event down by them. The settings captured here are emitted as event params
 * instead because Firebase Analytics caps custom user properties at 25 per
 * app — comprehensive configuration breakdowns belong on a periodic event.
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
    val dailyTimeBucketHour: String,
    val dailyDaysCount: Int,
    val tonightEnabled: Boolean,
    val tonightTimeBucketHour: String,
    val tonightDaysCount: Int,
    val tonightNotifyOnlyOnEvents: Boolean,
    val dailyMentionEveningEvents: Boolean,
    val useCalendarEvents: Boolean,
)
