package app.clothescast.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

/**
 * Best-effort "is the device currently online?" check. Returns true when the
 * active network reports both INTERNET capability and VALIDATED — i.e. an
 * actual end-to-end path, not just a captive-portal Wi-Fi that handshakes.
 *
 * The API-call analytics sink uses this to drop "offline" failures from the
 * rate-limit / transient-error signal: an IOException with no active network
 * is almost certainly airplane mode or a dead radio, not Open-Meteo / Gemini
 * misbehaving. We don't want those noise events drowning out real 429s.
 *
 * Falls back to `false` when ConnectivityManager isn't available (Robolectric
 * with no shadows wired, headless emulator without networking). "Probably
 * offline" is the conservative classification here — better to drop a real
 * failure than to report a spurious one.
 */
internal fun isLikelyOnline(context: Context): Boolean {
    val cm = context.getSystemService<ConnectivityManager>() ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
