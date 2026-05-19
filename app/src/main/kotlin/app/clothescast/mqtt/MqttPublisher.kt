package app.clothescast.mqtt

import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.diag.DiagLog
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val maxAttempts: Int = DEFAULT_MAX_PUBLISH_ATTEMPTS,
) {

    /**
     * Publishes a delivery's prose, optional outfit image, and optional TTS
     * audio as a single coordinated bundle. The period-specific topics
     * (`${baseTopic}/${period.lowercased()}/{text,image,audio}`) fan out in
     * parallel — they're independent surfaces and a slow image upload
     * shouldn't hold up the prose.
     *
     * The `${baseTopic}/now/{text,image,audio}` mirrors are treated as an
     * atomic three-topic bundle. They fire only when **every** submitted
     * modality's period publish succeeded; then `now/image` and `now/audio`
     * publish in parallel and `now/text` lands **last**. The text-last
     * ordering matters because `now/text` is the natural state-change
     * trigger surface for an HA automation — having it update last
     * guarantees a consumer subscribing to `now/text` reads a `now/image`
     * and `now/audio` from the same forecast.
     *
     * Modalities absent from the current delivery (e.g. an outfit render
     * failed, or the user is on device TTS so no audio bytes exist) still
     * touch their now slot: an empty retained payload is published, the
     * MQTT convention for "delete the retained message". Without that
     * clear, a `now/text` consumer would read the previous bundle's image
     * or audio mixed with the new prose. `now/text` is additionally held
     * back if either `now/image` or `now/audio` publish fails, so a
     * `now/text` update always implies a coherent `now/image` and
     * `now/audio` settled successfully.
     *
     * If any submitted modality's *period* publish fails, the entire
     * `now` mirror is skipped: a stale-but-coherent retained bundle from
     * the last fully-successful run is more honest than a half-updated mix
     * of new and old payloads on the unified surface. Mirror failures on
     * any of the `now` topics themselves do not override the returned
     * prose outcome — the Settings UI status row continues to reflect only
     * the authoritative period prose publish, the modality the user
     * actually sees in notifications.
     *
     * **Limit of MQTT-level atomicity.** MQTT has no transactional primitive
     * across topics: once `now/image` has been accepted by the broker as a
     * retained message, a subsequent `now/audio` rejection (per-topic ACL,
     * payload-size limit) cannot un-publish it. For a continuously-
     * subscribed consumer this is fine — they watch `now/text`, which is
     * the trigger surface and stays held back when sibling mirrors fail —
     * but a *fresh-reconnect* consumer that reads all three retained
     * topics during a partial-failure window may briefly observe a new
     * `now/image` paired with an old `now/audio` and `now/text`. True
     * atomicity across the bundle would require a versioned-topic +
     * pointer protocol (publish to `now/v123/{text,image,audio}`, then
     * update a `now/version` pointer last) and a corresponding change to
     * the consumer contract; out of scope for the current "subscribe to
     * `now/text`" surface.
     *
     * Returns the prose primary's outcome. Network failures (DNS,
     * connection, broker rejection, timeout) are logged, swallowed, and
     * surfaced as [MqttPublishOutcome.Failure]; the caller's worker must
     * succeed regardless of broker reachability.
     */
    suspend fun publishIfEnabled(
        period: ForecastPeriod,
        prose: String,
        image: ByteArray? = null,
        audio: ByteArray? = null,
    ): MqttPublishOutcome = coroutineScope {
        val prepared = preparePublish(context = period.name.lowercase())
            ?: return@coroutineScope MqttPublishOutcome.NotConfigured
        val proseBytes = prose.toByteArray(Charsets.UTF_8)
        val proseTopic = topicFor(prepared.baseTopic, period)
        val imageTopic = imageTopicFor(prepared.baseTopic, period)
        val audioTopic = audioTopicFor(prepared.baseTopic, period)
        val nowTextTopic = nowTopicFor(prepared.baseTopic)
        val nowImageTopic = nowImageTopicFor(prepared.baseTopic)
        val nowAudioTopic = nowAudioTopicFor(prepared.baseTopic)
        DiagLog.i(
            TAG,
            "MQTT bridge enabled; publishing ${period.name.lowercase()} insight bundle to " +
                "${prepared.scheme}://${prepared.host}:${prepared.port}/$proseTopic " +
                "(image=${image != null}, audio=${audio != null}, " +
                "mirrored to $nowTextTopic with $nowImageTopic / $nowAudioTopic landing first, " +
                "auth=${!prepared.config.username.isNullOrBlank()}, " +
                "password=${if (prepared.config.password != null) "set" else "none"}, " +
                "payload=${prose.length} chars).",
        )

        val proseDeferred = async { executePublish(prepared, proseTopic, proseBytes) }
        val imageDeferred = image?.let { bytes -> async { executePublish(prepared, imageTopic, bytes) } }
        val audioDeferred = audio?.let { bytes -> async { executePublish(prepared, audioTopic, bytes) } }
        val proseOutcome = proseDeferred.await()
        val imageOutcome = imageDeferred?.await()
        val audioOutcome = audioDeferred?.await()

        val everySubmittedSucceeded = proseOutcome is MqttPublishOutcome.Success &&
            (imageOutcome == null || imageOutcome is MqttPublishOutcome.Success) &&
            (audioOutcome == null || audioOutcome is MqttPublishOutcome.Success)

        if (everySubmittedSucceeded) {
            // The now surface is treated as an atomic three-topic bundle. Any
            // modality absent from this delivery clears its retained slot via
            // an empty-payload publish (the MQTT convention for "delete the
            // retained message") so a now/text-triggered consumer can't read
            // a stale image or audio left over from a previous bundle that
            // *did* carry one. now/text is then held back until both image
            // and audio mirrors settle successfully, so an automation
            // subscribing to now/text never sees the trigger advance before
            // the rest of the now bundle is coherent.
            val emptyPayload = ByteArray(0)
            val nowImageMirror = async {
                executePublish(prepared, nowImageTopic, image ?: emptyPayload)
            }
            val nowAudioMirror = async {
                executePublish(prepared, nowAudioTopic, audio ?: emptyPayload)
            }
            val nowImageOutcome = nowImageMirror.await()
            val nowAudioOutcome = nowAudioMirror.await()
            if (nowImageOutcome is MqttPublishOutcome.Success &&
                nowAudioOutcome is MqttPublishOutcome.Success
            ) {
                executePublish(prepared, nowTextTopic, proseBytes)
            } else {
                DiagLog.i(
                    TAG,
                    "Holding back $nowTextTopic mirror for ${period.name.lowercase()} " +
                        "bundle (now/image=$nowImageOutcome, now/audio=$nowAudioOutcome); " +
                        "trigger topic stays at the previous bundle to keep the now set coherent.",
                )
            }
        } else {
            DiagLog.i(
                TAG,
                "Skipping /now mirror for ${period.name.lowercase()} bundle " +
                    "(prose=$proseOutcome, image=$imageOutcome, audio=$audioOutcome); " +
                    "previous retained /now bundle stays intact.",
            )
        }
        proseOutcome
    }

    /**
     * Publishes a test message to `${baseTopic}/test` and returns the outcome.
     * Intended for the "Publish now" button — uses a dedicated topic suffix so
     * the retained forecast on `${baseTopic}/<period>/text` is never
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
    ): MqttPublishOutcome {
        // Retry once on failure to absorb transient broker unreachability —
        // the most common report path is the morning alarm firing while
        // Wi-Fi is still re-associating from doze, where the first connect
        // times out but the second a second later succeeds. A
        // CancellationException still unwinds the worker immediately because
        // attemptPublish re-raises it before this loop sees an outcome.
        var lastFailure: MqttPublishOutcome.Failure? = null
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            if (attempt > 0) {
                DiagLog.i(
                    TAG,
                    "Retrying MQTT publish (attempt ${attempt + 1}/$maxAttempts) to " +
                        "${prepared.scheme}://${prepared.host}:${prepared.port}/$topic " +
                        "after ${retryDelayMs}ms",
                )
                delay(retryDelayMs)
            }
            when (val outcome = attemptPublish(prepared, topic, payload)) {
                is MqttPublishOutcome.Success -> return outcome
                is MqttPublishOutcome.Failure -> lastFailure = outcome
                is MqttPublishOutcome.NotConfigured -> return outcome
            }
        }
        return lastFailure ?: MqttPublishOutcome.Failure("no attempts")
    }

    private suspend fun attemptPublish(
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

        // Two attempts caps the cost of a permanently-down broker at
        // ~2 × DEFAULT_PUBLISH_TIMEOUT_MS + DEFAULT_RETRY_DELAY_MS (~11 s) per
        // publish — still within the 60-second alignment window the worker
        // uses before notification delivery, even with subsequent image / TTS
        // publishes on the same hot path.
        const val DEFAULT_MAX_PUBLISH_ATTEMPTS = 2
        const val DEFAULT_RETRY_DELAY_MS = 750L

        // Inner HiveMQ timeouts kept tighter than [DEFAULT_PUBLISH_TIMEOUT_MS]
        // so the connect phase fails cleanly (with a ConnectionFailedException
        // we can log) before the outer withTimeoutOrNull has to abort. Without
        // these, a black-holed broker host would let TCP connect block past the
        // outer timeout — coroutine cancellation can't interrupt a blocked
        // socket call by itself.
        internal const val SOCKET_CONNECT_TIMEOUT_MS = 4_000L
        internal const val MQTT_CONNECT_TIMEOUT_MS = 4_000L

        // Prose / image / audio all live under `<base>/<period>/<kind>` so
        // each payload modality is independently subscribable from HA. The
        // text suffix keeps the prose topic symmetrical with its image and
        // audio siblings rather than living at a bare `<base>/<period>`.
        fun topicFor(baseTopic: String, period: ForecastPeriod): String =
            periodTopicFor(baseTopic, period, "text")

        fun imageTopicFor(baseTopic: String, period: ForecastPeriod): String =
            periodTopicFor(baseTopic, period, "image")

        fun audioTopicFor(baseTopic: String, period: ForecastPeriod): String =
            periodTopicFor(baseTopic, period, "audio")

        // `<base>/now/<kind>` mirrors the most recently published period
        // payload (today or tonight) so a consumer can subscribe to a single
        // topic without having to reason about which window is "current" or
        // chase recency timestamps across the two period topics.
        fun nowTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "text")

        fun nowImageTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "image")

        fun nowAudioTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "audio")

        private fun periodTopicFor(baseTopic: String, period: ForecastPeriod, kind: String): String {
            val trimmed = baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
            return "$trimmed/${period.name.lowercase()}/$kind"
        }

        private fun nowTopicForKind(baseTopic: String, kind: String): String {
            val trimmed = baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
            return "$trimmed/now/$kind"
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
