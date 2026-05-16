package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.util.isWithin

/**
 * Decides whether the worker should speak the briefing. Pure-Kotlin so it
 * stays JVM-testable.
 *
 * Returns `false` only when *all* of the following are true:
 *  - TTS was requested by the user's delivery mode (`ttsRequested`).
 *  - The "only speak away from home" gate is on (`skipTtsAtHome`).
 *  - A home pin is configured (`homeLocation != null`).
 *  - The device's current location was resolved (`currentLocation != null`).
 *  - The current location is within [radiusMeters] of home.
 *
 * Every other path returns `ttsRequested` — fail open. If we can't tell
 * where the user is (permission denied, offline, network provider
 * unavailable), or they haven't set a home pin, we speak. The bad failure
 * mode is silence away from home; we never want that.
 */
fun shouldSpeak(
    ttsRequested: Boolean,
    skipTtsAtHome: Boolean,
    homeLocation: Location?,
    currentLocation: Location?,
    radiusMeters: Double = AT_HOME_RADIUS_METERS,
): Boolean {
    if (!ttsRequested) return false
    if (!skipTtsAtHome) return true
    if (homeLocation == null) return true
    if (currentLocation == null) return true
    return !currentLocation.isWithin(radiusMeters, of = homeLocation)
}

/**
 * Radius used by [shouldSpeak] to call the device "at home." Wide enough to
 * absorb the ~hundreds-of-metres slop of `LocationManager.NETWORK_PROVIDER`
 * (the only provider `LocationResolver` reads), narrow enough that a typical
 * workplace, cafe, or hotel registers as "not home."
 */
const val AT_HOME_RADIUS_METERS: Double = 1_000.0
