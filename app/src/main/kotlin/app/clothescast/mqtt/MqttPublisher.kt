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
 * One retained MQTT message in a publish batch. The publisher bundles the
 * Home Assistant discovery config and the state (prose or PNG) into a single
 * connection so a refresh costs one TCP/MQTT round-trip rather than two.
 * Payload is [ByteArray] so the same plumbing carries UTF-8 text and binary
 * image data without duplication.
 */
data class MqttMessage(val topic: String, val payload: ByteArray, val retain: Boolean = true) {
    // ByteArray's default equals/hashCode are reference-identity; override so
    // the data-class contract is meaningful when tests / callers compare
    // captured publish batches.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MqttMessage) return false
        return topic == other.topic && retain == other.retain && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int {
        var result = topic.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + retain.hashCode()
        return result
    }
}

/**
 * Publishes the rendered insight prose and outfit-card PNG to a user-hosted
 * MQTT broker as retained messages, so Home Assistant (or any other MQTT
 * consumer) can read the latest forecast and either speak it on a trigger
 * of the user's choice or push the image to a Nest Hub. The bridge is opt-in
 * and configured in Settings → Smart Home; see PRIVACY.md for the
 * data-handling boundary.
 *
 * Each refresh also re-publishes the Home Assistant MQTT Discovery configs
 * for the today / tonight sensor and image entities (retained on
 * `homeassistant/<component>/clothescast_<period>[_image]/config`) so HA's
 * MQTT integration auto-creates the entities — users don't need to hand-add
 * any `mqtt:` blocks to `configuration.yaml`. Discovery payloads are config
 * metadata only (topic name, entity label, device card name); no user content
 * rides along.
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
    private val publish: suspend (config: MqttConfig, messages: List<MqttMessage>) -> Unit = ::publishWithHiveMq,
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
        val stateTopic = topicFor(prepared.baseTopic, period)
        val discoveryTopic = discoveryTopicFor(period, Component.SENSOR)
        val discoveryPayload = sensorDiscoveryPayloadFor(period, stateTopic)
        DiagLog.i(
            TAG,
            "MQTT bridge enabled; publishing ${period.name.lowercase()} insight to " +
                "${prepared.scheme}://${prepared.host}:${prepared.port}/$stateTopic " +
                "(auth=${!prepared.config.username.isNullOrBlank()}, " +
                "password=${if (prepared.config.password != null) "set" else "none"}, " +
                "payload=${prose.length} chars, +HA discovery).",
        )
        return executePublish(
            prepared,
            messages = listOf(
                MqttMessage(topic = discoveryTopic, payload = discoveryPayload.toByteArray(Charsets.UTF_8)),
                MqttMessage(topic = stateTopic, payload = prose.toByteArray(Charsets.UTF_8)),
            ),
            describeTopic = stateTopic,
        )
    }

    /**
     * Fire-and-forget publish of a PNG outfit image to
     * `${baseTopic}/${period.lowercased()}/image`. Piggybacks on the same
     * MQTT bridge toggle as [publishIfEnabled] — no separate setting needed.
     * HA's `image.mqtt` integration subscribes to this topic and surfaces
     * the outfit as an `image.*` entity, which a downstream automation can
     * push to a Nest Hub via `media_player.play_media`. The image entity's
     * discovery config is re-emitted alongside the bytes so HA auto-creates
     * the entity without a hand-edited YAML block.
     */
    suspend fun publishImageIfEnabled(period: ForecastPeriod, imageBytes: ByteArray) {
        val prepared = preparePublish(context = "${period.name.lowercase()} outfit image") ?: return
        val imageTopic = imageTopicFor(prepared.baseTopic, period)
        val discoveryTopic = discoveryTopicFor(period, Component.IMAGE)
        val discoveryPayload = imageDiscoveryPayloadFor(period, imageTopic)
        DiagLog.i(
            TAG,
            "MQTT bridge enabled; publishing ${period.name.lowercase()} outfit image " +
                "(${imageBytes.size} bytes) to " +
                "${prepared.scheme}://${prepared.host}:${prepared.port}/$imageTopic (+HA discovery).",
        )
        executePublish(
            prepared,
            messages = listOf(
                MqttMessage(topic = discoveryTopic, payload = discoveryPayload.toByteArray(Charsets.UTF_8)),
                MqttMessage(topic = imageTopic, payload = imageBytes),
            ),
            describeTopic = imageTopic,
        )
    }

    /**
     * Publishes a test message to `${baseTopic}/test` and returns the outcome.
     * Intended for the "Publish now" button — uses a dedicated topic suffix so
     * the retained forecast on `${baseTopic}/today` and `tonight` is never
     * overwritten by a connectivity probe. Also re-publishes the HA discovery
     * configs for every sensor + image entity so a freshly-configured bridge
     * surfaces its entities in HA immediately, without waiting for the next
     * scheduled refresh. Returns [MqttPublishOutcome.NotConfigured] when the
     * bridge is disabled or no host is configured.
     */
    suspend fun publishTest(): MqttPublishOutcome {
        val prepared = preparePublish(context = "test") ?: return MqttPublishOutcome.NotConfigured
        val baseTrimmed = prepared.baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
        val testTopic = "$baseTrimmed/test"
        val messages = buildList {
            for (period in ForecastPeriod.entries) {
                val stateTopic = topicFor(prepared.baseTopic, period)
                add(
                    MqttMessage(
                        topic = discoveryTopicFor(period, Component.SENSOR),
                        payload = sensorDiscoveryPayloadFor(period, stateTopic).toByteArray(Charsets.UTF_8),
                    ),
                )
            }
            for (period in ForecastPeriod.entries) {
                val imageTopic = imageTopicFor(prepared.baseTopic, period)
                add(
                    MqttMessage(
                        topic = discoveryTopicFor(period, Component.IMAGE),
                        payload = imageDiscoveryPayloadFor(period, imageTopic).toByteArray(Charsets.UTF_8),
                    ),
                )
            }
            add(
                MqttMessage(
                    topic = testTopic,
                    payload = "ClothesCast: connection test".toByteArray(Charsets.UTF_8),
                ),
            )
        }
        DiagLog.i(
            TAG,
            "MQTT test publish to ${prepared.scheme}://${prepared.host}:${prepared.port}/$testTopic " +
                "(+HA discovery for ${ForecastPeriod.entries.size * 2} entities).",
        )
        return executePublish(prepared, messages = messages, describeTopic = testTopic)
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
        messages: List<MqttMessage>,
        describeTopic: String,
    ): MqttPublishOutcome = withTimeoutOrNull(publishTimeoutMs) {
        var result: MqttPublishOutcome = MqttPublishOutcome.Success
        try {
            publish(prepared.config, messages)
            DiagLog.i(TAG, "Published to ${prepared.scheme}://${prepared.host}:${prepared.port}/$describeTopic")
        } catch (ce: CancellationException) {
            // Re-raise so withTimeoutOrNull sees a timeout (returns null
            // and we return Failure below) and so a WorkManager-issued
            // cancel propagates out of the worker rather than being
            // shadowed by a misleading "MQTT publish failed" line.
            throw ce
        } catch (t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}".take(250)
            DiagLog.w(TAG, "MQTT publish failed to ${prepared.scheme}://${prepared.host}:${prepared.port}/$describeTopic", t)
            result = MqttPublishOutcome.Failure(msg)
        }
        result
    } ?: run {
        DiagLog.w(TAG, "MQTT publish timed out after ${publishTimeoutMs}ms to ${prepared.scheme}://${prepared.host}:${prepared.port}/$describeTopic")
        MqttPublishOutcome.Failure("Connection timed out (>${publishTimeoutMs}ms)")
    }

    private data class PreparedPublish(
        val config: MqttConfig,
        val baseTopic: String,
        val scheme: String,
        val host: String,
        val port: Int,
    )

    /**
     * HA MQTT Discovery component domain — `sensor` for the prose entity,
     * `image` for the outfit-card PNG entity. The component name slots into
     * the discovery topic shape `<prefix>/<component>/<object_id>/config`.
     */
    enum class Component(val ha: String) { SENSOR("sensor"), IMAGE("image") }

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

        /**
         * Home Assistant MQTT Discovery prefix. HA defaults to `homeassistant`
         * and only power users change it; we publish under that fixed prefix.
         * If a user has remapped their discovery prefix in HA, they'll need to
         * fall back to the manual YAML setup until we add a setting.
         */
        const val DISCOVERY_PREFIX = "homeassistant"

        /**
         * Stable HA device identifier the discovery payloads use to group every
         * sensor and image entity under one "ClothesCast" device card.
         * Single-tenant: one ClothesCast install per HA. Multiple phones
         * publishing to the same broker would collide — acceptable for a
         * single-household app.
         */
        const val DEVICE_IDENTIFIER = "clothescast"

        fun topicFor(baseTopic: String, period: ForecastPeriod): String {
            val trimmed = baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
            return "$trimmed/${period.name.lowercase()}"
        }

        fun imageTopicFor(baseTopic: String, period: ForecastPeriod): String =
            "${topicFor(baseTopic, period)}/image"

        /**
         * Discovery config topic per HA's `<prefix>/<component>/<object_id>/config`
         * shape — e.g. `homeassistant/sensor/clothescast_today/config` for the
         * prose entity, `homeassistant/image/clothescast_today_image/config` for
         * the outfit PNG. Retained payload on this topic is what HA's MQTT
         * integration consumes to register the entity.
         */
        fun discoveryTopicFor(period: ForecastPeriod, component: Component): String {
            val suffix = when (component) {
                Component.SENSOR -> period.name.lowercase()
                Component.IMAGE -> "${period.name.lowercase()}_image"
            }
            return "$DISCOVERY_PREFIX/${component.ha}/${DEVICE_IDENTIFIER}_$suffix/config"
        }

        /**
         * Hand-rolled JSON payload describing the prose sensor — kept inline so
         * the publisher doesn't need a serializable model just for two static
         * shapes. HA composes the entity name as `<device name> <name>`, so
         * the entities surface as "ClothesCast Today" / "ClothesCast Tonight"
         * in the HA UI.
         *
         * The `unique_id` matches the value the previous manual-YAML
         * instructions used (`clothescast_today` / `clothescast_tonight`).
         * Users who hand-added that YAML will see the discovery message
         * conflict with their existing entity and HA will keep the YAML
         * version; they can remove their YAML to migrate to the discovered
         * entity at any time.
         */
        internal fun sensorDiscoveryPayloadFor(period: ForecastPeriod, stateTopic: String): String {
            val periodLabel = when (period) {
                ForecastPeriod.TODAY -> "Today"
                ForecastPeriod.TONIGHT -> "Tonight"
            }
            val objectId = "${DEVICE_IDENTIFIER}_${period.name.lowercase()}"
            // JSON keys / static strings only — no user content interpolated,
            // so naive string concatenation is safe (no escaping needed). The
            // state-topic value is the user-configured prefix + a literal
            // suffix; MQTT topics permit JSON-safe characters by spec.
            return """{"name":"$periodLabel","unique_id":"$objectId","state_topic":"$stateTopic","value_template":"{{ value }}","device":${deviceBlock()}}"""
        }

        /**
         * Hand-rolled JSON payload describing the outfit-card image entity. HA's
         * `image.mqtt` schema uses `image_topic` (raw bytes; the alternative
         * `url_topic` is for fetched URLs) and `content_type` for the MIME so HA
         * can render the binary blob.
         *
         * The `unique_id` matches the value the previous manual-YAML
         * instructions used (`clothescast_today_outfit` /
         * `clothescast_tonight_outfit`) so existing automations that target
         * `image.clothescast_today_outfit` keep working when the user removes
         * their manual YAML to migrate to the discovered entity.
         */
        internal fun imageDiscoveryPayloadFor(period: ForecastPeriod, imageTopic: String): String {
            val periodLabel = when (period) {
                ForecastPeriod.TODAY -> "Today outfit"
                ForecastPeriod.TONIGHT -> "Tonight outfit"
            }
            val objectId = "${DEVICE_IDENTIFIER}_${period.name.lowercase()}_outfit"
            return """{"name":"$periodLabel","unique_id":"$objectId","image_topic":"$imageTopic","content_type":"image/png","device":${deviceBlock()}}"""
        }

        private fun deviceBlock(): String =
            """{"identifiers":["$DEVICE_IDENTIFIER"],"manufacturer":"ClothesCast","model":"Forecast bridge","name":"ClothesCast"}"""
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
    messages: List<MqttMessage>,
) {
    if (messages.isEmpty()) return
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
        for (message in messages) {
            client.publishWith()
                .topic(message.topic)
                .payload(message.payload)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(message.retain)
                .send()
                .await()
        }
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
