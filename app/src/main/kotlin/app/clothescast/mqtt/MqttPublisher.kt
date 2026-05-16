package app.clothescast.mqtt

import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.diag.DiagLog
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Publishes the rendered insight prose to a user-hosted MQTT broker as a
 * retained message, so Home Assistant (or any other MQTT consumer) can read
 * the latest "what to wear today" sentence and speak it on a trigger of the
 * user's choice. The bridge is opt-in and configured in
 * Settings → Smart Home; see PRIVACY.md for the data-handling boundary.
 *
 * The publisher reads the latest [UserPreferences] from the injected flow on
 * every call so settings edits take effect on the next refresh without
 * reconnect plumbing — connections are short-lived (connect → publish →
 * disconnect, twice a day), which is battery-cheap and avoids holding network
 * state in the worker.
 */
class MqttPublisher(
    private val preferences: Flow<UserPreferences>,
    private val passwordProvider: suspend () -> String?,
    private val publish: suspend (config: MqttConfig, topic: String, payload: String) -> Unit = ::publishWithHiveMq,
    private val publishTimeoutMs: Long = DEFAULT_PUBLISH_TIMEOUT_MS,
) {

    /**
     * Fire-and-forget publish to `${baseTopic}/${period.lowercased()}`. No-op
     * when the bridge is disabled or no host is configured. Any failure (DNS,
     * connection, broker rejection, timeout) is logged and swallowed — the
     * caller's worker must succeed regardless of broker reachability.
     */
    suspend fun publishIfEnabled(period: ForecastPeriod, prose: String) {
        // CancellationException must propagate at every catch point so a
        // WorkManager stop or withTimeoutOrNull abort unwinds the worker
        // cleanly instead of being silently logged as an MQTT failure (which
        // would then let deliver() report success on cancelled work).
        val prefs = try {
            preferences.first()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Failed to read settings for ${period.name.lowercase()} insight MQTT publish; skipping.", t)
            return
        }
        // Bridge-off is the silent-no-op path for users who haven't opted in;
        // not logged to keep the diag stream clean for the 99% no-bridge case.
        if (!prefs.mqttBridgeEnabled) return
        val host = prefs.mqttHost?.takeIf { it.isNotBlank() } ?: run {
            DiagLog.w(
                TAG,
                "MQTT bridge is enabled but no broker host is configured; " +
                    "${period.name.lowercase()} insight not published. " +
                    "Set the host in Settings → Smart Home.",
            )
            return
        }
        val password = if (prefs.mqttUsername.isNullOrBlank()) {
            null
        } else {
            try {
                passwordProvider()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                DiagLog.w(TAG, "Failed to read MQTT password from keystore; attempting anonymous connect.", t)
                null
            }
        }
        val config = MqttConfig(
            host = host,
            port = prefs.mqttPort,
            useTls = prefs.mqttUseTls,
            username = prefs.mqttUsername,
            password = password,
        )
        val topic = topicFor(prefs.mqttTopic, period)
        val scheme = if (prefs.mqttUseTls) "mqtts" else "mqtt"
        // Entry log: fires only when the bridge is enabled and the host is set.
        // If you see this line in the diag log, the publisher reached the
        // network attempt; if you don't, the bridge is either off or the host
        // is blank (and the warn above will tell you which).
        DiagLog.i(
            TAG,
            "MQTT bridge enabled; publishing ${period.name.lowercase()} insight to " +
                "$scheme://$host:${prefs.mqttPort}/$topic " +
                "(auth=${!prefs.mqttUsername.isNullOrBlank()}, " +
                "password=${if (password != null) "set" else "none"}, " +
                "payload=${prose.length} chars).",
        )
        withTimeoutOrNull(publishTimeoutMs) {
            try {
                publish(config, topic, prose)
                DiagLog.i(TAG, "Published ${period.name.lowercase()} insight to $scheme://$host:${prefs.mqttPort}/$topic")
            } catch (ce: CancellationException) {
                // Re-raise so withTimeoutOrNull sees a timeout (returns null
                // and we log "timed out" below) and so a WorkManager-issued
                // cancel propagates out of the worker rather than being
                // shadowed by a misleading "MQTT publish failed" line.
                throw ce
            } catch (t: Throwable) {
                DiagLog.w(TAG, "MQTT publish failed to $scheme://$host:${prefs.mqttPort}/$topic", t)
            }
        } ?: DiagLog.w(TAG, "MQTT publish timed out after ${publishTimeoutMs}ms to $scheme://$host:${prefs.mqttPort}/$topic")
    }

    companion object {
        private const val TAG = "MqttPublisher"
        const val DEFAULT_PUBLISH_TIMEOUT_MS = 5_000L

        // Inner HiveMQ timeouts kept tighter than [DEFAULT_PUBLISH_TIMEOUT_MS]
        // so the connect phase fails cleanly (with a ConnectionFailedException
        // we can log) before the outer withTimeoutOrNull has to abort. Without
        // these, a black-holed broker host would let TCP connect block past the
        // outer timeout — coroutine cancellation can't interrupt a blocked
        // socket call by itself.
        internal const val SOCKET_CONNECT_TIMEOUT_MS = 4_000L
        internal const val MQTT_CONNECT_TIMEOUT_MS = 4_000L

        fun topicFor(baseTopic: String, period: ForecastPeriod): String {
            val trimmed = baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
            return "$trimmed/${period.name.lowercase()}"
        }
    }
}

/**
 * Minimal value type for a per-publish MQTT connection. Decoupled from
 * [UserPreferences] so the publish strategy is unit-testable against a stub
 * without dragging a full preferences instance into the test.
 */
data class MqttConfig(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val username: String?,
    val password: String?,
)

private suspend fun publishWithHiveMq(
    config: MqttConfig,
    topic: String,
    payload: String,
) {
    // Async client + awaited CompletableFutures (rather than the blocking
    // client wrapped in Dispatchers.IO) so the outer withTimeoutOrNull and any
    // WorkManager-issued cancel actually unwind the publish path. Coroutine
    // cancellation cannot interrupt a blocked socket call from the blocking
    // client; awaiting HiveMQ's CompletableFutures via kotlinx.coroutines.future
    // is suspension-aware. The HiveMQ-level timeouts below cap the TCP +
    // MQTT-CONNECT phase independently so a black-holed host fails clean even
    // before the outer timeout fires.
    val client: Mqtt3AsyncClient = MqttClient.builder()
        .useMqttVersion3()
        .identifier("clothescast-${UUID.randomUUID()}")
        .serverHost(config.host)
        .serverPort(config.port)
        .apply { if (config.useTls) sslWithDefaultConfig() }
        .transportConfig()
            .mqttConnectTimeout(MqttPublisher.MQTT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .socketConnectTimeout(MqttPublisher.SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .applyTransportConfig()
        .buildAsync()
    try {
        val connect = client.connectWith()
        if (!config.username.isNullOrBlank()) {
            connect.simpleAuth()
                .username(config.username)
                .apply { if (!config.password.isNullOrEmpty()) password(config.password.toByteArray(Charsets.UTF_8)) }
                .applySimpleAuth()
        }
        connect.send().await()
        client.publishWith()
            .topic(topic)
            .payload(payload.toByteArray(Charsets.UTF_8))
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .send()
            .await()
    } finally {
        // Run disconnect in a NonCancellable context so that when the outer
        // coroutine is cancelled mid-publish we still send a clean DISCONNECT
        // (no half-open connection left on the broker) before unwinding.
        withContext(NonCancellable) {
            try {
                client.disconnect().await()
            } catch (_: Throwable) {
                // Best-effort cleanup; nothing actionable for the caller here.
            }
        }
    }
}

