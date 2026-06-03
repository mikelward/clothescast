package app.clothescast.mqtt

import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.diag.DiagLog
import app.clothescast.net.NetworkErrorKind
import app.clothescast.net.classifyNetworkError
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
     * Publishes a delivery's prose, optional outfit image, optional TTS
     * audio, and optional muxed video (PNG frame + TTS audio as an MP4) as a
     * single coordinated bundle. The period-specific topics
     * (`${baseTopic}/${period.lowercased()}/{text,image,audio,video}`) fan out
     * in parallel — they're independent surfaces and a slow image upload
     * shouldn't hold up the prose.
     *
     * The `${baseTopic}/now/{text,image,audio,video,timestamp}` mirrors are
     * treated as an atomic bundle. They fire only when every **gating**
     * modality's period publish (prose + any submitted image / audio)
     * succeeded; then `now/image`, `now/audio` and `now/video` publish in
     * parallel, `now/text` lands after them, and `now/timestamp` lands
     * **last** of all. `now/timestamp` is the bundle's commit marker —
     * epoch-millis written only once the gating content mirrors have settled,
     * so its update is the single signal that the `now` set is in place. A
     * consumer triggers on `now/timestamp` and dedupes on its value: a new
     * timestamp guarantees a coherent `now/image` / `now/audio` / `now/text`
     * from the same forecast, and a value it has already acted on means skip —
     * so a retained bundle isn't replayed on reconnect. (MQTT 3.1.1 carries no
     * native publish timestamp, hence the explicit topic.)
     *
     * `now/video` **always** gates the commit marker: its payload is the video
     * when one was produced and an empty "delete retained" clear when not.
     * Gating on the clear too is what keeps the set coherent — were it
     * best-effort, a failed clear would leave a stale video from a prior bundle
     * paired with the fresh timestamp. The cost is that a broker whose ACL
     * denies `now/video` stalls the bundle; that's a hand-rolled per-topic ACL
     * the setup guide never recommends (it grants `<prefix>/#`), and any ACL
     * that ever accepted a real `now/video` accepts the clear too. A timestamp
     * publish that fails after the content mirrors advanced is logged (the
     * marker silently lagging its content is the one missed-update case the
     * trigger contract must surface) but does not change the returned outcome.
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
        video: ByteArray? = null,
    ): MqttPublishOutcome = coroutineScope {
        val prepared = preparePublish(context = period.name.lowercase())
            ?: return@coroutineScope MqttPublishOutcome.NotConfigured
        val proseBytes = prose.toByteArray(Charsets.UTF_8)
        val proseTopic = topicFor(prepared.baseTopic, period)
        val imageTopic = imageTopicFor(prepared.baseTopic, period)
        val audioTopic = audioTopicFor(prepared.baseTopic, period)
        val videoTopic = videoTopicFor(prepared.baseTopic, period)
        val nowTextTopic = nowTopicFor(prepared.baseTopic)
        val nowImageTopic = nowImageTopicFor(prepared.baseTopic)
        val nowAudioTopic = nowAudioTopicFor(prepared.baseTopic)
        val nowVideoTopic = nowVideoTopicFor(prepared.baseTopic)
        val nowTimestampTopic = nowTimestampTopicFor(prepared.baseTopic)
        // MQTT 3.1.1 carries no native publish timestamp (and the MQTT 5
        // user-property route isn't available on this v3 client), so we
        // publish one ourselves: epoch-millis as a decimal string on the
        // /now/timestamp surface. A retained consumer (e.g. HA casting the
        // video to a Home Hub) reads it to dedupe — on reconnect it sees the
        // same timestamp it already played and skips replaying the clip
        // rather than re-announcing a stale bundle.
        val timestampBytes = System.currentTimeMillis().toString().toByteArray(Charsets.UTF_8)
        DiagLog.i(
            TAG,
            "MQTT bridge enabled; publishing ${period.name.lowercase()} insight bundle to " +
                "${prepared.scheme}://${prepared.host}:${prepared.port}/$proseTopic " +
                "(image=${image != null}, audio=${audio != null}, video=${video != null}, " +
                "mirrored to /now as $nowImageTopic / $nowAudioTopic / $nowVideoTopic, " +
                "then $nowTextTopic, then $nowTimestampTopic last as the commit marker, " +
                "auth=${!prepared.config.username.isNullOrBlank()}, " +
                "password=${if (prepared.config.password != null) "set" else "none"}, " +
                "payload=${prose.length} chars).",
        )

        val proseDeferred = async { executePublish(prepared, proseTopic, proseBytes) }
        val imageDeferred = image?.let { bytes -> async { executePublish(prepared, imageTopic, bytes) } }
        val audioDeferred = audio?.let { bytes -> async { executePublish(prepared, audioTopic, bytes) } }
        // The period today/video publish is attempted only when a video was
        // produced this delivery (videoOutcome stays null otherwise) and gates
        // the now mirror the same way image/audio do. The now/video clear for a
        // no-video delivery — and its gating — lives in the now block below.
        val videoDeferred = video?.let { bytes -> async { executePublish(prepared, videoTopic, bytes) } }
        val proseOutcome = proseDeferred.await()
        val imageOutcome = imageDeferred?.await()
        val audioOutcome = audioDeferred?.await()
        val videoOutcome = videoDeferred?.await()

        val everySubmittedSucceeded = proseOutcome is MqttPublishOutcome.Success &&
            (imageOutcome == null || imageOutcome is MqttPublishOutcome.Success) &&
            (audioOutcome == null || audioOutcome is MqttPublishOutcome.Success) &&
            (videoOutcome == null || videoOutcome is MqttPublishOutcome.Success)

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
            // now/video always gates the commit marker: its payload is the
            // video when we have one and an empty clear (the MQTT "delete
            // retained" convention) when we don't. Gating on the clear too is
            // what keeps the set coherent — if a no-video delivery's clear were
            // best-effort and failed, a stale video from a prior bundle would
            // outlive it and pair with the fresh timestamp. The cost is that a
            // broker whose ACL denies now/video stalls the bundle; that's a
            // hand-rolled per-topic ACL we don't document (the guide grants
            // `<prefix>/#`), and any ACL that ever accepted a real now/video
            // accepts the clear too, so the practical blast radius is small.
            val nowVideoMirror = async {
                executePublish(prepared, nowVideoTopic, video ?: emptyPayload)
            }
            val nowImageOutcome = nowImageMirror.await()
            val nowAudioOutcome = nowAudioMirror.await()
            val nowVideoOutcome = nowVideoMirror.await()
            if (nowImageOutcome is MqttPublishOutcome.Success &&
                nowAudioOutcome is MqttPublishOutcome.Success &&
                nowVideoOutcome is MqttPublishOutcome.Success
            ) {
                val nowTextOutcome = executePublish(prepared, nowTextTopic, proseBytes)
                if (nowTextOutcome is MqttPublishOutcome.Success) {
                    // now/timestamp is the very last write, after every gating
                    // content mirror (image/audio/text) has settled, so its
                    // update is the single "bundle complete" marker. A consumer
                    // triggers on now/timestamp and dedupes on its value: a new
                    // timestamp guarantees the rest of the now bundle is already
                    // in place, and the same value it last acted on means skip —
                    // don't replay a retained announcement on reconnect.
                    val nowTimestampOutcome = executePublish(prepared, nowTimestampTopic, timestampBytes)
                    if (nowTimestampOutcome !is MqttPublishOutcome.Success) {
                        // The content mirrors already advanced but the commit
                        // marker didn't, so timestamp-triggered consumers won't
                        // act on this bundle until a later publish moves the
                        // marker. Surface it: a marker that silently lags its
                        // content is exactly the missed-update case the trigger
                        // contract has to make debuggable. (executePublish has
                        // already retried per its policy.)
                        DiagLog.w(
                            TAG,
                            "Commit marker $nowTimestampTopic failed for " +
                                "${period.name.lowercase()} bundle ($nowTimestampOutcome); " +
                                "now/* content advanced but the trigger marker did not, so " +
                                "timestamp-triggered consumers will skip this bundle until " +
                                "the next successful publish.",
                        )
                    }
                } else {
                    DiagLog.i(
                        TAG,
                        "Holding back $nowTimestampTopic for ${period.name.lowercase()} " +
                            "bundle (now/text=$nowTextOutcome); the commit marker stays at the " +
                            "previous bundle so a consumer doesn't act on a half-updated set.",
                    )
                }
            } else {
                DiagLog.i(
                    TAG,
                    "Holding back $nowTextTopic and $nowTimestampTopic mirrors for " +
                        "${period.name.lowercase()} bundle (now/image=$nowImageOutcome, " +
                        "now/audio=$nowAudioOutcome, now/video=$nowVideoOutcome, " +
                        "videoSubmitted=${video != null}); the now set stays at the previous " +
                        "bundle to keep it coherent.",
                )
            }
        } else {
            // The per-topic failures already logged their cause; here we only
            // need to note that the /now mirror is being held back so a reader
            // can correlate "no /now update" with "primary publishes failed."
            val failed = listOfNotNull(
                if (proseOutcome !is MqttPublishOutcome.Success) "prose" else null,
                if (imageOutcome != null && imageOutcome !is MqttPublishOutcome.Success) "image" else null,
                if (audioOutcome != null && audioOutcome !is MqttPublishOutcome.Success) "audio" else null,
                if (videoOutcome != null && videoOutcome !is MqttPublishOutcome.Success) "video" else null,
            ).joinToString("/")
            DiagLog.i(
                TAG,
                "Skipping /now mirror for ${period.name.lowercase()} bundle " +
                    "($failed failed); previous retained /now bundle stays intact.",
            )
        }
        if (proseOutcome is MqttPublishOutcome.Success) {
            publishHomeAssistantDiscovery(prepared)
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
            // Carry a user-readable Failure message but keep it short — it's
            // surfaced on the Settings status row, the Today error banner, and
            // in the bug report. When the cause is a recognisable connection
            // failure (no route, refused, timeout, DNS, TLS), lead with a
            // plain-English hint that helps the user tell a network / firewall
            // problem from a broker one; otherwise fall back to the raw
            // class+message (e.g. an ACL rejection). We don't attach `t` to the
            // log line: the HiveMQ/Netty trace is ~4 lines per topic per
            // attempt (32 lines for one unreachable-broker bundle) and the
            // message string already names the cause.
            val raw = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            val msg = networkHintFor(classifyNetworkError(t), prepared.host, prepared.port)
                ?.let { "$it ($raw)" }
                ?.let { it.take(250) }
                ?: raw.take(250)
            DiagLog.w(TAG, "MQTT publish failed to ${prepared.scheme}://${prepared.host}:${prepared.port}/$topic ($msg)")
            result = MqttPublishOutcome.Failure(msg)
        }
        result
    } ?: run {
        DiagLog.w(TAG, "MQTT publish timed out after ${publishTimeoutMs}ms to ${prepared.scheme}://${prepared.host}:${prepared.port}/$topic")
        MqttPublishOutcome.Failure(
            "Couldn't reach the broker at ${prepared.host}:${prepared.port} within " +
                "${publishTimeoutMs}ms — check it's online and that nothing's blocking the port.",
        )
    }

    private suspend fun publishHomeAssistantDiscovery(prepared: PreparedPublish) {
        homeAssistantDiscoveryEntries(prepared.baseTopic).forEach { entry ->
            val outcome = executePublish(
                prepared = prepared,
                topic = entry.configTopic,
                payload = entry.payload.toByteArray(Charsets.UTF_8),
            )
            if (outcome !is MqttPublishOutcome.Success) {
                DiagLog.w(
                    TAG,
                    "Home Assistant discovery config ${entry.configTopic} failed ($outcome); " +
                        "forecast topics remain published but HA may not auto-create this entity.",
                )
            }
        }
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

        // The muxed PNG-frame + TTS-audio MP4 — a single media item a
        // consumer (e.g. HA) can hand straight to a Cast receiver / Home Hub
        // so the outfit card and announcement play together.
        fun videoTopicFor(baseTopic: String, period: ForecastPeriod): String =
            periodTopicFor(baseTopic, period, "video")

        // `<base>/now/<kind>` mirrors the most recently published period
        // payload (today or tonight) so a consumer can subscribe to a single
        // topic without having to reason about which window is "current" or
        // chase recency timestamps across the two period topics.
        fun nowTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "text")

        fun nowImageTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "image")

        fun nowAudioTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "audio")

        fun nowVideoTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "video")

        // Epoch-millis (decimal string) of the most recent /now bundle. Not a
        // payload modality — it's the recency marker consumers use to dedupe a
        // retained bundle they've already acted on.
        fun nowTimestampTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "timestamp")

        internal fun homeAssistantDiscoveryEntries(baseTopic: String): List<HomeAssistantDiscoveryEntry> {
            val base = baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
            val suffix = discoveryIdSuffix(base)
            val idPrefix = if (base == UserPreferences.DEFAULT_MQTT_TOPIC) {
                "clothescast"
            } else {
                "clothescast_$suffix"
            }
            val device = JsonObject(
                mapOf(
                    "identifiers" to JsonArray(listOf(JsonPrimitive(idPrefix))),
                    "name" to JsonPrimitive("ClothesCast"),
                    "manufacturer" to JsonPrimitive("ClothesCast"),
                    "model" to JsonPrimitive("Android app"),
                ),
            )
            fun sensor(
                id: String,
                name: String,
                stateTopic: String,
                icon: String? = null,
                deviceClass: String? = null,
                valueTemplate: String? = null,
            ) = HomeAssistantDiscoveryEntry(
                configTopic = "homeassistant/sensor/${idPrefix}_$id/config",
                payload = buildJsonObject {
                    put("name", name)
                    put("unique_id", "${idPrefix}_$id")
                    put("default_entity_id", "sensor.${idPrefix}_$id")
                    put("state_topic", stateTopic)
                    put("device", device)
                    if (icon != null) put("icon", icon)
                    if (deviceClass != null) put("device_class", deviceClass)
                    if (valueTemplate != null) put("value_template", valueTemplate)
                }.toString(),
            )
            fun image(id: String, name: String, imageTopic: String) = HomeAssistantDiscoveryEntry(
                configTopic = "homeassistant/image/${idPrefix}_$id/config",
                payload = buildJsonObject {
                    put("name", name)
                    put("unique_id", "${idPrefix}_$id")
                    put("default_entity_id", "image.${idPrefix}_$id")
                    put("image_topic", imageTopic)
                    put("content_type", "image/png")
                    put("device", device)
                }.toString(),
            )
            return listOf(
                sensor("today", "Today", topicFor(base, ForecastPeriod.TODAY), icon = "mdi:tshirt-crew"),
                sensor("tonight", "Tonight", topicFor(base, ForecastPeriod.TONIGHT), icon = "mdi:tshirt-crew"),
                sensor("now", "Now", nowTopicFor(base), icon = "mdi:tshirt-crew"),
                sensor(
                    id = "now_updated",
                    name = "Now updated",
                    stateTopic = nowTimestampTopicFor(base),
                    icon = "mdi:clock-outline",
                    deviceClass = "timestamp",
                    valueTemplate = "{{ as_datetime((value | int) / 1000) }}",
                ),
                image("today_image", "Today image", imageTopicFor(base, ForecastPeriod.TODAY)),
                image("tonight_image", "Tonight image", imageTopicFor(base, ForecastPeriod.TONIGHT)),
                image("now_image", "Now image", nowImageTopicFor(base)),
            )
        }

        private fun periodTopicFor(baseTopic: String, period: ForecastPeriod, kind: String): String {
            val trimmed = baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
            return "$trimmed/${period.name.lowercase()}/$kind"
        }

        private fun nowTopicForKind(baseTopic: String, kind: String): String {
            val trimmed = baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
            return "$trimmed/now/$kind"
        }

        private fun discoveryIdSuffix(baseTopic: String): String =
            baseTopic.trim().trim('/')
                .ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else '_' }
                .joinToString("")
                .replace(Regex("_+"), "_")
                .trim('_')
                .ifBlank { "default" }
    }
}

internal data class HomeAssistantDiscoveryEntry(
    val configTopic: String,
    val payload: String,
)

/**
 * A plain-English hint for an MQTT publish that failed at the connection layer,
 * or null when the failure wasn't a recognisable network problem (e.g. a broker
 * ACL rejection) so the caller surfaces the raw error instead. Mentions the
 * `host:port` so the user can sanity-check it against their broker config.
 *
 * `internal` so [MqttPublisherTest] can assert the wording without driving a
 * real socket failure.
 */
internal fun networkHintFor(kind: NetworkErrorKind?, host: String, port: Int): String? = when (kind) {
    NetworkErrorKind.UNKNOWN_HOST ->
        "Couldn't find the broker \"$host\" — check the address, and that this " +
            "phone is on the same network as your broker."
    NetworkErrorKind.NO_ROUTE ->
        "Couldn't reach the broker at $host:$port — the phone may be on a " +
            "different network, or a firewall is blocking it."
    NetworkErrorKind.CONNECTION_REFUSED ->
        "The broker at $host:$port refused the connection — check it's running " +
            "and listening on port $port."
    NetworkErrorKind.TIMEOUT ->
        "Timed out connecting to $host:$port — the broker may be offline, or a " +
            "firewall is silently dropping the connection."
    NetworkErrorKind.TLS ->
        "Couldn't establish a secure (TLS) connection to $host:$port — check the " +
            "\"Use TLS\" setting matches your broker and that its certificate is valid."
    null -> null
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
        // TODO: revisit retention / QoS / TTL strategy now that the bundle
        // carries audio + video. Everything is published QoS 1, retained — good
        // for the image/text a dashboard reads on reconnect, but a retained
        // audio/video clip means a freshly-reconnecting consumer can replay a
        // stale announcement. The /now/timestamp topic is the current dedupe
        // hook; longer term consider MQTT 5 (message-expiry as a real TTL,
        // user-property timestamp) and per-kind retain/QoS rather than one
        // policy for the whole bundle.
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
