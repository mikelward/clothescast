package app.clothescast.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Returns `true` when the app is running on an Android TV / Google TV device. */
fun isTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * True when typing a long API key directly on this device is painful, so the
 * "Pair from phone" handoff is worth surfacing: an Android TV, or any device
 * with no touchscreen (typically driven by a D-pad / remote, where on-screen
 * keyboard entry of a 39-char key is slow and error-prone). Touch devices
 * (phones, tablets) can paste or type a key easily, so they don't need the
 * extra affordance.
 */
fun prefersRemoteKeyEntry(context: Context): Boolean =
    isTelevision(context) ||
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
