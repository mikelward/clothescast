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
 * Outcome of a [MqttPublisher.publishIfEnabled] call. Callers use this to
 * persist the last publish result for display in the Smart Home settings UI.
 */
sealed interface MqttPublishOutcome {
    /** Bridge not enabled or no host configured — intentional no-op, not an error. */
    data object NotConfigured : MqttPublishOutcome
    /** Message published successfully. */
    data object Success : MqttPublishOutcome
    /** Publish was attempted but failed — [message] is a short, user-readable description. */
    data class Failure(val message: String) : MqttPublishOutcome
}

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
    private val publish: suspend (config: MqttConfig, topic: String, payload: ByteArray) -> Unit = ::publishWithHiveMq,
    private val publishTimeoutMs: Long = DEFAULT_PUBLISH_TIMEOUT_MS,
) {

    /**
     * Publishes to `${baseTopic}/${period.lowercased()}` and returns an
     * [MqttPublishOutcome] so the caller can persist the result for display.
     * Returns [MqttPublishOutcome.NotConfigured] (silently) when the bridge is
     * disabled or no host is set. Any network failure (DNS, connection, broker
     * rejection, timeout) is logged, swallowed, and returned as
     * [MqttPublishOutcome.Failure] — the caller's worker must succeed regardless
     * of broker reachability.
     */
    suspend fun publishIfEnabled(period: ForecastPeriod, prose: String): MqttPublishOutcome {
        val prepared = preparePublish(context = period.name.lowercase()) ?: return MqttPublishOutcome.NotConfigured
        val topic = topicFor(prepared.baseTopic, period)
        DiagLog.i(
            TAG,
            "MQTT bridge enabled; publishing ${period.name.lowercase()} insight to " +
                "${prepared.scheme}://${prepared.host}:${prepared.port}/$topic " +
                "(auth=${!prepared.config.username.isNullOrBlank()}, " +
                "password=${if (prepared.config.password != null) "set" else "none"}, " +
                "payload=${prose.length} chars).",
        )
        return executePublish(prepared, topic, prose.toByteArray(Charsets.UTF_8))
    }

    /**
     * Publishes a test message to `${baseTopic}/test` and returns the outcome.
     * Intended for the "Publish now" button — uses a dedicated topic suffix so
     * the retained forecast on `${baseTopic}/today` and `tonight` is never
     * overwritten by a connectivity probe. Returns
     * [MqttPublishOutcome.NotConfigured] when the bridge is disabled or no host
     * is configured.
     */
    suspend fun publishTest(): MqttPublishOutcome {
        val prepared = preparePublish(context = "test") ?: return MqttPublishOutcome.NotConfigured
        val baseTrimmed = prepared.baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
        val topic = "$baseTrimmed/test"
        DiagLog.i(
            TAG,
            "MQTT test publish to ${prepared.scheme}://${prepared.host}:${prepared.port}/$topic.",
        )
        return executePublish(prepared, topic, "ClothesCast: connection test".toByteArray(Charsets.UTF_8))
    }

    /**
     * Fire-and-forget publish of a PNG outfit image to
     * `${baseTopic}/${period.lowercased()}/image`. Piggybacks on the same
     * MQTT bridge toggle as [publishIfEnabled] — no separate setting needed.
     * HA's `image.mqtt` integration subscribes to this topic and surfaces
     * the outfit as an `image.*` entity, which a downstream automation can
     * push to a Nest Hub via `media_player.play_media`.
     */
    suspend fun publishImageIfEnabled(period: ForecastPeriod, imageBytes: ByteArray) {
        val prepared = preparePublish(context = "${period.name.lowercase()} outfit image") ?: return
        val topic = imageTopicFor(prepared.baseTopic, period)
        DiagLog.i(
            TAG,
            "MQTT bridge enabled; publishing ${period.name.lowercase()} outfit image " +
                "(${imageBytes.size} bytes) to " +
                "${prepared.scheme}://${prepared.host}:${prepared.port}/$topic.",
        )
        executePublish(prepared, topic, imageBytes)
    }

    /**
     * Fire-and-forget publish of a WAV-wrapped TTS clip to
     * `${baseTopic}/${period.lowercased()}/audio`. Same bridge toggle as the
     * prose and image topics — no separate setting. Only emitted when the
     * Gemini engine actually synthesised audio for local playback (device
     * TTS doesn't expose bytes), so the published clip matches what the
     * phone speaks. The companion HA automation can either fetch this as a
     * media URL or use the retained payload to trigger `media_player`.
     */
    suspend fun publishAudioIfEnabled(period: ForecastPeriod, wavBytes: ByteArray) {
        val prepared = preparePublish(context = "${period.name.lowercase()} TTS audio") ?: return
        val topic = audioTopicFor(prepared.baseTopic, period)
        DiagLog.i(
            TAG,
            "MQTT bridge enabled; publishing ${period.name.lowercase()} TTS audio " +
                "(${wavBytes.size} bytes) to " +
                "${prepared.scheme}://${prepared.host}:${prepared.port}/$topic.",
        )
        executePublish(prepared, topic, wavBytes)
    }

    /**
     * Reads the current preferences and resolves credentials; returns null
     * (with appropriate logging) when the bridge is not configured. [context]
     * is a short label used in warning messages only (e.g. "today", "test").
     */
    private suspend fun preparePublish(context: String): PreparedPublish? {
        // CancellationException must propagate at every catch point so a
        // WorkManager stop or withTimeoutOrNull abort unwinds the worker
        // cleanly instead of being silently logged as an MQTT failure (which
        // would then let deliver() report success on cancelled work).
        val prefs = try {
            preferences.first()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Failed to read settings for $context MQTT publish; skipping.", t)
            return null
        }
        // Bridge-off is the silent-no-op path for users who haven't opted in;
        // not logged to keep the diag stream clean for the 99% no-bridge case.
        if (!prefs.mqttBridgeEnabled) return null
        val host = prefs.mqttHost?.takeIf { it.isNotBlank() } ?: run {
            DiagLog.w(
                TAG,
                "MQTT bridge is enabled but no broker host is configured; " +
                    "$context message not published. " +
                    "Set the host in Settings → Smart Home.",
            )
            return null
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
        return PreparedPublish(
            config = MqttConfig(
                host = host,
                port = prefs.mqttPort,
                useTls = prefs.mqttUseTls,
                username = prefs.mqttUsername,
                password = password,
            ),
            baseTopic = prefs.mqttTopic,
            scheme = if (prefs.mqttUseTls) "mqtts" else "mqtt",
            host = host,
            port = prefs.mqttPort,
        )
    }

    private suspend fun executePublish(
        prepared: PreparedPublish,
        topic: String,
        payload: ByteArray,
    ): MqttPublishOutcome = withTimeoutOrNull(publishTimeoutMs) {
        var result: MqttPublishOutcome = MqttPublishOutcome.Success
        try {
            publish(prepared.config, topic, payload)
            DiagLog.i(TAG, "Published to ${prepared.scheme}://${prepared.host}:${prepared.port}/$topic")
        } catch (ce: CancellationException) {
            // Re-raise so withTimeoutOrNull sees a timeout (returns null
            // and we return Failure below) and so a WorkManager-issued
            // cancel propagates out of the worker rather than being
            // shadowed by a misleading "MQTT publish failed" line.
            throw ce
        } catch (t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}".take(250)
            DiagLog.w(TAG, "MQTT publish failed to ${prepared.scheme}://${prepared.host}:${prepared.port}/$topic", t)
            result = MqttPublishOutcome.Failure(msg)
        }
        result
    } ?: run {
        DiagLog.w(TAG, "MQTT publish timed out after ${publishTimeoutMs}ms to ${prepared.scheme}://${prepared.host}:${prepared.port}/$topic")
        MqttPublishOutcome.Failure("Connection timed out (>${publishTimeoutMs}ms)")
    }

    private data class PreparedPublish(
        val config: MqttConfig,
        val baseTopic: String,
        val scheme: String,
        val host: String,
        val port: Int,
    )

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

        fun imageTopicFor(baseTopic: String, period: ForecastPeriod): String =
            "${topicFor(baseTopic, period)}/image"

        fun audioTopicFor(baseTopic: String, period: ForecastPeriod): String =
            "${topicFor(baseTopic, period)}/audio"
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
    payload: ByteArray,
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
            .payload(payload)
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
