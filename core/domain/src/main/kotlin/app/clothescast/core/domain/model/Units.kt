package app.clothescast.core.domain.model

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

/** Receiver is in km/h (Open-Meteo's native wind unit); converts to [unit]. */
fun Double.toWindSpeedUnit(unit: WindSpeedUnit): Double = when (unit) {
    WindSpeedUnit.KMH -> this
    WindSpeedUnit.MPH -> this / 1.609344
}

fun WindSpeedUnit.symbol(): String = when (this) {
    WindSpeedUnit.KMH -> "km/h"
    WindSpeedUnit.MPH -> "mph"
}

fun DistanceUnit.symbol(): String = when (this) {
    DistanceUnit.KILOMETERS -> "km"
    DistanceUnit.MILES -> "mi"
}

/**
 * Picks the wind-speed unit that pairs naturally with a chosen distance unit:
 * metric users get km/h, imperial users get mph. Kept as a derivation rather
 * than a separate stored setting until knots / m/s join the picker.
 */
fun DistanceUnit.windSpeedUnit(): WindSpeedUnit = when (this) {
    DistanceUnit.KILOMETERS -> WindSpeedUnit.KMH
    DistanceUnit.MILES -> WindSpeedUnit.MPH
}
