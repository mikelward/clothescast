package app.clothescast.cast

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Picks the phone's LAN IPv4 address — the host string we hand the
 * smart display so it can reach [CastMediaServer]. Cast receivers can't
 * dial loopback / VPN / cellular addresses, so the pick is restricted
 * to site-local IPv4 (RFC 1918) on the currently-active network.
 *
 * Returns `null` when the phone has no LAN-routable address — most
 * commonly when the user is on cellular, or when the active network is
 * a VPN the smart display isn't on. The caller surfaces that to the
 * user rather than guessing.
 */
object LanAddress {

    fun resolve(context: Context): String? {
        val cm = context.getSystemService<ConnectivityManager>() ?: return null
        val active = cm.activeNetwork ?: return null
        val link = cm.getLinkProperties(active) ?: return null
        return pickSiteLocal(link.linkAddresses.map { it.address })
    }

    /**
     * Picks the first IPv4 site-local address from [addresses]. Pure
     * helper; the Android-coupled wiring lives in [resolve].
     */
    internal fun pickSiteLocal(addresses: List<InetAddress>): String? =
        addresses
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
}
