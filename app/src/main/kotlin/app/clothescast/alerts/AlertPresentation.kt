package app.clothescast.alerts

import android.content.Context
import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.clothescast.R
import app.clothescast.core.domain.model.AlertHazard
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.AlertTitles
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.model.formatHourMinute
import app.clothescast.core.domain.usecase.RelevantAlert
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How an alert level looks everywhere it's drawn: its icon (one shape per
 * level, so it reads without color or text) and the fixed tints that hold at
 * least 3:1 against both themes' surfaces (docs/ALERTS.md → What the user sees).
 */
internal enum class AlertIcon(
    @DrawableRes val drawable: Int,
    /** The drawable's path, for surfaces drawn on a canvas (the conditions strip). */
    val pathData: String,
    val lightArgb: Int,
    val darkArgb: Int,
    @StringRes val description: Int,
) {
    INFO(
        R.drawable.ic_alert_info,
        "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
            "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z",
        0xFF6E6A75.toInt(),
        0xFFCAC4D0.toInt(),
        R.string.weather_alert_level_info_description,
    ),
    YELLOW(
        R.drawable.ic_alert_yellow,
        "M12,5.99L19.53,19H4.47L12,5.99M12,2L1,21h22L12,2zM13,16h-2v2h2v-2zM13,10h-2v4h2v-4z",
        0xFFB58800.toInt(),
        0xFFFFD54F.toInt(),
        R.string.weather_alert_level_yellow_description,
    ),
    ORANGE(
        R.drawable.ic_alert_orange,
        "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z",
        0xFFE65100.toInt(),
        0xFFFFB74D.toInt(),
        R.string.weather_alert_level_orange_description,
    ),
    RED(
        R.drawable.ic_alert_red,
        "M15.73,3H8.27L3,8.27v7.46L8.27,21h7.46L21,15.73V8.27L15.73,3zM12,17.3c-0.72,0 -1.3,-0.58 -1.3,-1.3 " +
            "0,-0.72 0.58,-1.3 1.3,-1.3 0.72,0 1.3,0.58 1.3,1.3 0,0.72 -0.58,1.3 -1.3,1.3zM13,13h-2V7h2v6z",
        0xFFC62828.toInt(),
        0xFFFF8A80.toInt(),
        R.string.weather_alert_level_red_description,
    ),
    ;

    fun argb(darkTheme: Boolean): Int = if (darkTheme) darkArgb else lightArgb

    companion object {
        fun of(level: AlertLevel): AlertIcon = when (level) {
            AlertLevel.INFO -> INFO
            AlertLevel.YELLOW -> YELLOW
            AlertLevel.ORANGE -> ORANGE
            AlertLevel.RED -> RED
        }
    }
}

/**
 * The alert's title: "{color} {hazard} warning" ("Amber wind warning") where
 * the issuer grades by color and the hazard is known (maintainer, 2026-10-22);
 * otherwise the issuer's own title ("High Wind Warning"); otherwise a plain
 * fallback by level.
 */
internal fun alertTitle(context: Context, alert: WeatherAlert): String = alertTitle(context.resources, alert)

/** [alertTitle] in [resources]' language, for prose spoken in a voice locale. */
internal fun alertTitle(resources: Resources, alert: WeatherAlert): String {
    val color = AlertTitles.colorGraded(alert.issuerTitle)
    val hazard = alert.hazard
    if (color != null && hazard != null) {
        return resources.getString(
            R.string.weather_alert_title,
            resources.getString(colorWord(color)),
            resources.getString(hazardWord(hazard)),
        )
    }
    if (alert.issuerTitle.isNotBlank()) return alert.issuerTitle
    return resources.getString(
        when (alert.level) {
            AlertLevel.RED -> R.string.weather_alert_title_fallback_extreme
            AlertLevel.ORANGE -> R.string.weather_alert_title_fallback_severe
            AlertLevel.YELLOW, AlertLevel.INFO -> R.string.weather_alert_title_fallback
        },
    )
}

@StringRes
private fun colorWord(color: AlertTitles.Color): Int = when (color) {
    AlertTitles.Color.YELLOW -> R.string.weather_alert_level_yellow
    AlertTitles.Color.AMBER -> R.string.weather_alert_level_amber
    AlertTitles.Color.ORANGE -> R.string.weather_alert_level_orange
    AlertTitles.Color.RED -> R.string.weather_alert_level_red
}

@StringRes
private fun hazardWord(hazard: AlertHazard): Int = when (hazard) {
    AlertHazard.WIND -> R.string.weather_alert_hazard_wind
    AlertHazard.RAIN -> R.string.weather_alert_hazard_rain
    AlertHazard.SNOW_AND_ICE -> R.string.weather_alert_hazard_snow_and_ice
    AlertHazard.THUNDERSTORM -> R.string.weather_alert_hazard_thunderstorm
    AlertHazard.FOG -> R.string.weather_alert_hazard_fog
    AlertHazard.HEAT -> R.string.weather_alert_hazard_heat
    AlertHazard.COLD -> R.string.weather_alert_hazard_cold
    AlertHazard.COASTAL -> R.string.weather_alert_hazard_coastal
    AlertHazard.FLOOD -> R.string.weather_alert_hazard_flood
    AlertHazard.WILDFIRE -> R.string.weather_alert_hazard_wildfire
    AlertHazard.AVALANCHE -> R.string.weather_alert_hazard_avalanche
    AlertHazard.STORM -> R.string.weather_alert_hazard_storm
    AlertHazard.TORNADO -> R.string.weather_alert_hazard_tornado
    AlertHazard.HAIL -> R.string.weather_alert_hazard_hail
    AlertHazard.DUST -> R.string.weather_alert_hazard_dust
}

/**
 * When the alert ends, for the one-row card and the notification: "Until
 * 9 PM" today, "Until Fri 9 PM" on a later day, in the user's 12/24-hour
 * setting. Null when the issuer gave no end time. Only the end: Google's start
 * time looks like the issue time, not when the weather arrives.
 */
internal fun alertUntil(
    context: Context,
    alert: WeatherAlert,
    now: Instant,
    timeFormat: TimeFormat,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val end = alert.endTime?.atZone(zone) ?: return null
    val time = timeFormat.formatHourMinute(end.toLocalTime(), locale)
    val label = if (end.toLocalDate() == now.atZone(zone).toLocalDate()) {
        time
    } else {
        "${DateTimeFormatter.ofPattern("EEE", locale).format(end)} $time"
    }
    return context.getString(R.string.weather_alert_until, label)
}

/** "Until Fri 9 PM · Met Office": the notification's text and the card's expanded line. */
internal fun alertUntilAndIssuer(
    context: Context,
    alert: WeatherAlert,
    now: Instant,
    timeFormat: TimeFormat,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val until = alertUntil(context, alert, now, timeFormat, locale, zone)
    val issuer = alert.issuer
    return when {
        until != null && issuer != null -> context.getString(R.string.weather_alert_detail, until, issuer)
        else -> until ?: issuer
    }
}

/** The level the app draws for [relevant], as its icon. */
internal val RelevantAlert.icon: AlertIcon get() = AlertIcon.of(level)
