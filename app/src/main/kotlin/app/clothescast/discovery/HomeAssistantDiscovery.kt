package app.clothescast.discovery

import kotlinx.coroutines.flow.Flow

/**
 * Local-network discovery for Home Assistant and MQTT brokers via mDNS / DNS-SD.
 *
 * The Smart Home settings page subscribes to [discover] when the user taps
 * "Scan local network" so we can pre-fill the broker host (and port) instead
 * of asking them to type `homeassistant.local` from memory. Two service types
 * are advertised on real networks:
 *
 *   * `_home-assistant._tcp.` — published by Home Assistant Core / OS itself.
 *     We treat the host as a likely MQTT broker too, because the typical HA
 *     OS install runs the Mosquitto add-on on the same machine; the user can
 *     still override the port if their broker lives elsewhere.
 *   * `_mqtt._tcp.` — published by some MQTT brokers (rare; Mosquitto does
 *     not advertise by default). When present, the resolved port is exactly
 *     the broker port, so picking one of these prefills both host and port.
 *
 * Implementations return a cold [Flow]: subscription starts the underlying
 * discovery, unsubscription stops it. Emissions are deduplicated lists in
 * (HA-first, then MQTT) display order — the UI just renders what it sees.
 */
interface HomeAssistantDiscovery {
    fun discover(): Flow<List<DiscoveredService>>
}

/** A resolved mDNS service hit. */
data class DiscoveredService(
    val type: ServiceType,
    /** Display name advertised by the service (e.g. "Home Assistant"). */
    val name: String,
    /** Resolved host — either an mDNS hostname (`*.local`) or a raw IP. */
    val host: String,
    val port: Int,
)

enum class ServiceType {
    HOME_ASSISTANT,
    MQTT,
}
