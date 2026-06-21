package app.clothescast.core.domain.model

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Double.toUnit(unit: TemperatureUnit): Double = when (unit) {
    TemperatureUnit.CELSIUS -> this
    TemperatureUnit.FAHRENHEIT -> this * 9.0 / 5.0 + 32.0
}

/** Inverse of [toUnit]: interprets the receiver as already being in [unit] and converts to °C. */
fun Double.fromUnit(unit: TemperatureUnit): Double = when (unit) {
    TemperatureUnit.CELSIUS -> this
    TemperatureUnit.FAHRENHEIT -> (this - 32.0) * 5.0 / 9.0
}

fun TemperatureUnit.symbol(): String = when (this) {
    TemperatureUnit.CELSIUS -> "°C"
    TemperatureUnit.FAHRENHEIT -> "°F"
}

/**
 * Receiver is in km/h (Open-Meteo's native wind unit); converts to [unit].
 * Conversion factors are exact: 1 mph = 1.609344 km/h, 1 knot = 1.852 km/h
 * (one nautical mile per hour), 1 m/s = 3.6 km/h.
 */
fun Double.toWindSpeedUnit(unit: WindSpeedUnit): Double = when (unit) {
    WindSpeedUnit.KMH -> this
    WindSpeedUnit.MPH -> this / 1.609344
    WindSpeedUnit.KNOTS -> this / 1.852
    WindSpeedUnit.MS -> this / 3.6
}

fun WindSpeedUnit.symbol(): String = when (this) {
    WindSpeedUnit.KMH -> "km/h"
    WindSpeedUnit.MPH -> "mph"
    WindSpeedUnit.KNOTS -> "kn"
    WindSpeedUnit.MS -> "m/s"
}

/**
 * Hour+minute clock reading for sites that name an arbitrary minute — the
 * divergence-summary peak time, the schedule pickers, the "generated at"
 * footer, and the rationale "rain at HH:mm" prose.
 *
 * 12h English uses the lowercase no-space form ("8am" / "8:30pm") — the
 * convention en-GB / en-AU readers expect, and the form the InsightFormatter
 * already uses in spoken prose. Other 12h locales fall back to the standard
 * `DateTimeFormatter` "h:mm a" pattern, which respects each locale's marker.
 *
 * TODO(time-format-system-api): consider migrating to a system / ICU API
 *  that handles locale-aware clock formatting automatically (e.g.
 *  `android.icu.text.DateFormat.getInstanceForSkeleton("Hm" / "hm", locale)`
 *  or `android.text.format.DateFormat.getTimeFormat(context)` for the
 *  device's "Use 24-hour format" preference). The blocker is that neither
 *  emits the lowercase no-space "8pm" form en-GB / en-AU readers actually
 *  want — they default to "8 PM" or "8:00 PM". If ICU's pattern modifiers
 *  or a locale data update ever produces the tighter form we should
 *  collapse this manual path; until then we keep the explicit English
 *  branch so the chart subtitles read tight.
 */
fun TimeFormat.formatHourMinute(time: LocalTime, locale: Locale): String = when (this) {
    TimeFormat.TWENTY_FOUR_HOUR -> "%02d:%02d".format(time.hour, time.minute)
    TimeFormat.TWELVE_HOUR -> formatTwelveHour(time, locale, includeMinutes = true)
}

/**
 * Top-of-hour reading ("14:00" / "2pm") for chart peak-time subtitles and
 * the scrub readout. The indicator always snaps to a whole hour, so the 12h
 * English form drops the ":00" — "Peak 12 km/h at 2pm" reads as naturally
 * as the 24h "Peak 12 km/h at 14:00" and the lowercase no-space form keeps
 * the subtitle tight under narrow charts.
 */
fun TimeFormat.formatTopOfHour(time: LocalTime, locale: Locale): String = when (this) {
    TimeFormat.TWENTY_FOUR_HOUR -> "%02d:%02d".format(time.hour, time.minute)
    TimeFormat.TWELVE_HOUR -> formatTwelveHour(time, locale, includeMinutes = false)
}

private fun formatTwelveHour(time: LocalTime, locale: Locale, includeMinutes: Boolean): String {
    if (locale.language == "en") {
        val hour12 = ((time.hour + 11) % 12) + 1
        val suffix = if (time.hour < 12) "am" else "pm"
        return if (includeMinutes && time.minute != 0) {
            "%d:%02d%s".format(hour12, time.minute, suffix)
        } else {
            "$hour12$suffix"
        }
    }
    // Non-English: when the locale's natural SHORT pattern is already a 12h
    // form, use it directly so AM/PM placement and separators match the
    // local convention. zh_HK / zh_TW / ko_KR put the marker before the
    // hour ("ah:mm" / "a h:mm"), which a hardcoded "h:mm a" would invert
    // ("8:00 下午" instead of "下午8:00"). Fall back to a generic 12h
    // pattern (letting `DateTimeFormatter` substitute the locale's AM/PM
    // marker via `a`) when the locale's natural form is 24h and the user
    // has explicitly opted into 12h.
    //
    // [includeMinutes] is honoured for the English no-space tightening
    // ("8pm") only; outside English we always render the minute field
    // because reliably stripping ":mm" from an arbitrary locale pattern
    // is fragile, and the tighter "下午2" / "오후 2" forms aren't
    // idiomatic in those locales anyway.
    val localePattern = (java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale)
        as? java.text.SimpleDateFormat)?.toPattern()
    val pattern = if (localePattern != null && localePattern.containsTwelveHourPatternField()) {
        localePattern
    } else {
        "h:mm a"
    }
    return DateTimeFormatter.ofPattern(pattern, locale).format(time)
}

/**
 * Scans a `SimpleDateFormat` / `DateTimeFormatter` pattern for an unquoted
 * 12h-hour field (`h` = 1–12, `K` = 0–11). Single-quoted sections are
 * literals — `'h'` in `HH 'h' mm` (fr-CA) is the French "h" separator, not
 * an hour field — so a naive `pattern.any { it == 'h' }` misclassifies any
 * locale whose 24h pattern carries an `h`/`K` literal as 12h. Two
 * consecutive quotes (`''`) are an escaped single quote and stay inside
 * the literal.
 */
fun String.containsTwelveHourPatternField(): Boolean {
    var inQuote = false
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '\'') {
            if (i + 1 < length && this[i + 1] == '\'') {
                i += 2
                continue
            }
            inQuote = !inQuote
        } else if (!inQuote && (c == 'h' || c == 'K')) {
            return true
        }
        i++
    }
    return false
}
