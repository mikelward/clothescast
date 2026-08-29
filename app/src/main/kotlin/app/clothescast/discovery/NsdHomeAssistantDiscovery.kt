package app.clothescast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import app.clothescast.diag.DiagLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [HomeAssistantDiscovery] backed by Android's [NsdManager].
 *
 * Subscribes to two DNS-SD service types in parallel and resolves each hit to
 * a host/port pair. Pre-API-34 `NsdManager.resolveService` only supports one
 * resolve at a time, so we serialise resolves through a single-flight queue.
 * Unsubscribing the flow stops both discovery listeners and drains the queue.
 */
internal class NsdHomeAssistantDiscovery(context: Context) : HomeAssistantDiscovery {

    private val nsd: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    override fun discover(): Flow<List<DiscoveredService>> = callbackFlow {
        val manager = nsd ?: run {
            // No NsdManager on this device (TV emulator without the service,
            // Robolectric without a shadow). Emit an empty list and idle —
            // the UI surface is "no devices found yet", same as a real
            // network with nothing to discover.
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        // Discovered services keyed by (type, name) so a re-announcement
        // replaces the previous entry instead of duplicating it. Iteration
        // order is insertion order, which keeps the UI list stable.
        val results = LinkedHashMap<Pair<ServiceType, String>, DiscoveredService>()
        // Keys NSD reported as lost *while still pending resolve*. The
        // resolve callback checks this set before inserting so a service
        // that disappeared mid-resolve (broker dropped off, Wi-Fi roam)
        // doesn't get re-added to the picker after the fact.
        val lostWhilePending = mutableSetOf<Pair<ServiceType, String>>()
        fun emit() {
            trySend(results.values.sortedBy { it.type.ordinal }.toList())
        }
        emit()

        // Serialise resolveService calls — multiple concurrent resolves are
        // unsupported on API 31-33 and return FAILURE_ALREADY_ACTIVE.
        val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)
        fun drainResolves() {
            if (!resolving.compareAndSet(false, true)) return
            val next = resolveQueue.poll()
            if (next == null) {
                resolving.set(false)
                return
            }
            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    DiagLog.d(TAG, "resolve failed: %s (code %s)", serviceInfo.serviceName, errorCode)
                    resolving.set(false)
                    drainResolves()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val type = serviceTypeOf(serviceInfo.serviceType)
                    val host = hostStringOf(serviceInfo)
                    if (type != null && host != null) {
                        val key = type to serviceInfo.serviceName
                        // Discard if NSD already reported this service lost
                        // while it was sitting in the resolve queue —
                        // otherwise we'd revive a dead service in the picker.
                        if (!lostWhilePending.remove(key)) {
                            results[key] = DiscoveredService(
                                type = type,
                                name = serviceInfo.serviceName,
                                host = host,
                                port = serviceInfo.port,
                            )
                            emit()
                        }
                    }
                    resolving.set(false)
                    drainResolves()
                }
            }
            runCatching {
                @Suppress("DEPRECATION") // resolveService is the only path on API < 34.
                manager.resolveService(next, resolveListener)
            }.onFailure { t ->
                DiagLog.w(TAG, t, "resolveService threw")
                resolving.set(false)
                drainResolves()
            }
        }

        // Count listeners that managed to actually start scanning; if every
        // one fails (NSD off, local-network permission denied on API 33+,
        // another active request, etc.) we close the flow so the collector
        // stops waiting on a dead scan instead of sitting on "Stop
        // searching" forever.
        val liveListeners = AtomicInteger(SERVICE_TYPES.size)
        fun onListenerDied() {
            if (liveListeners.decrementAndGet() == 0) {
                channel.close()
            }
        }

        val listeners = SERVICE_TYPES.map { dnsType ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    DiagLog.w(TAG, "discovery start failed: %s (code %s)", serviceType, errorCode)
                    onListenerDied()
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    DiagLog.d(TAG, "discovery stop failed: %s (code %s)", serviceType, errorCode)
                }
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    // A fresh announce supersedes any prior lost-while-pending
                    // mark — the user is seeing the service now, so let the
                    // upcoming resolve insert it normally.
                    serviceTypeOf(serviceInfo.serviceType)?.let { type ->
                        lostWhilePending.remove(type to serviceInfo.serviceName)
                    }
                    resolveQueue.add(serviceInfo)
                    drainResolves()
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val type = serviceTypeOf(serviceInfo.serviceType) ?: return
                    val key = type to serviceInfo.serviceName
                    // Always arm the lost-while-pending mark, not just when
                    // the key was never resolved: a re-announce can queue a
                    // second resolve while the entry is live, and a loss
                    // arriving before that resolve completes must keep it
                    // from re-inserting the dead service into the picker.
                    // The next onServiceFound clears the mark, so a service
                    // that genuinely comes back still inserts normally.
                    lostWhilePending.add(key)
                    if (results.remove(key) != null) {
                        emit()
                    }
                }
            }
            runCatching {
                manager.discoverServices(dnsType, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { t ->
                DiagLog.w(TAG, t, "discoverServices threw for %s", dnsType)
                onListenerDied()
            }
            listener
        }

        awaitClose {
            listeners.forEach { l ->
                runCatching { manager.stopServiceDiscovery(l) }
            }
            resolveQueue.clear()
        }
    }

    companion object {
        private const val TAG = "NsdDiscovery"

        // DNS-SD service type strings. NsdManager normalises both with and
        // without the trailing dot, but we pass the canonical form here.
        private const val HA_TYPE = "_home-assistant._tcp."
        private const val MQTT_TYPE = "_mqtt._tcp."

        private val SERVICE_TYPES = listOf(HA_TYPE, MQTT_TYPE)

        private fun serviceTypeOf(advertisedType: String?): ServiceType? {
            // NsdManager's serviceType field on resolved entries is reported
            // without the leading underscore on some OEMs and with a trailing
            // dot on others; normalise before comparing.
            val normalised = advertisedType?.trim('.', ' ')?.lowercase()?.removePrefix("_") ?: return null
            return when {
                normalised.startsWith("home-assistant._tcp") -> ServiceType.HOME_ASSISTANT
                normalised.startsWith("mqtt._tcp") -> ServiceType.MQTT
                else -> null
            }
        }

        private fun hostStringOf(info: NsdServiceInfo): String? {
            val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.hostAddresses
            } else {
                @Suppress("DEPRECATION") // Single-address `host` is the only pre-34 API.
                listOfNotNull(info.host)
            }
            // Prefer an IPv4 address: services often advertise both A and AAAA
            // records and Android's resolver can surface the IPv6 one first —
            // frequently a link-local `fe80::…`, which is unroutable once the
            // zone index below is stripped, so the prefilled host could never
            // connect even though the broker is reachable over IPv4. Failing
            // that, prefer a routable (non-link-local) IPv6 before falling
            // back to whatever was reported.
            val address = candidates.firstOrNull { it is Inet4Address }
                ?: candidates.firstOrNull { !it.isLinkLocalAddress }
                ?: candidates.firstOrNull()
                ?: return null
            // Strip the IPv6 zone index (`fe80::1%wlan0`) — useless once written
            // back as a plain string and broker libraries reject it.
            val raw = address.hostAddress ?: return null
            return if (address is Inet6Address) raw.substringBefore('%') else raw
        }
    }
}
