package app.clothescast.mqtt

import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.diag.DiagLog
import app.clothescast.diag.safe
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
     * audio, optional muxed video (PNG frame + TTS audio as an MP4), and a
     * [hasEvents] flag as a single coordinated bundle. The period-specific
     * topics (`${baseTopic}/{day,night}/{text,image,audio,video,has_events}` —
     * the segment maps from [ForecastPeriod] via [topicSegment]) fan out in
     * parallel — they're independent surfaces and a slow image upload shouldn't
     * hold up the prose. [hasEvents] is published as `"true"`/`"false"` so a
     * Home Assistant automation can tell an event-free evening (where
     * `tonightNotifyOnlyOnEvents` keeps the phone quiet) from a real outage
     * without parsing the prose; it gates the commit marker like every other
     * modality, so a fresh timestamp implies a fresh flag.
     *
     * [mirrorToNow] controls the `/now` mirror. The run's *current* period
     * publishes with `mirrorToNow = true` (the default) so the `/now` topics
     * reflect the cast in play; the *next* window publishes with `mirrorToNow = false`
     * so its bundle lands only on its own day/night segment and leaves `/now`
     * pointing at the current period.
     *
     * Every period topic is always written so the per-period bundle is
     * self-coherent: prose carries its payload, and `image` / `audio` / `video`
     * carry their bytes when present or an empty retained clear when absent.
     * A `${baseTopic}/<period>/timestamp` commit marker is then written **last**,
     * and only when text + image + audio + video all settled — the day/night
     * equivalent of `now/timestamp`. A consumer reading a day/night bundle
     * directly (the next window has no `/now` backstop) triggers on that marker
     * and dedupes on its value, so it never pairs fresh text with a previous
     * run's leftover audio or video clip (e.g. a failed media clear), and
     * doesn't replay a retained bundle on reconnect. The marker is held back
     * whenever any period publish fails.
     *
     * The `${baseTopic}/now/{text,image,audio,video,timestamp}` mirrors are
     * treated as an atomic bundle. They fire only when the whole period
     * bundle — prose plus every image / audio / video publish, payloads and
     * absent-media clears alike — succeeded; then `now/image`, `now/audio` and
     * `now/video` publish in
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
     * If any period publish (a payload or an absent-media clear) fails, the
     * entire `now` mirror is skipped: a stale-but-coherent retained bundle from
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
        hasEvents: Boolean = false,
        mirrorToNow: Boolean = true,
    ): MqttPublishOutcome = coroutineScope {
        val prepared = preparePublish(context = period.name.lowercase())
            ?: return@coroutineScope MqttPublishOutcome.NotConfigured
        val proseBytes = prose.toByteArray(Charsets.UTF_8)
        val proseTopic = topicFor(prepared.baseTopic, period)
        val imageTopic = imageTopicFor(prepared.baseTopic, period)
        val audioTopic = audioTopicFor(prepared.baseTopic, period)
        val videoTopic = videoTopicFor(prepared.baseTopic, period)
        val hasEventsTopic = hasEventsTopicFor(prepared.baseTopic, period)
        val periodTimestampTopic = timestampTopicFor(prepared.baseTopic, period)
        val nowTextTopic = nowTopicFor(prepared.baseTopic)
        val nowImageTopic = nowImageTopicFor(prepared.baseTopic)
        val nowAudioTopic = nowAudioTopicFor(prepared.baseTopic)
        val nowVideoTopic = nowVideoTopicFor(prepared.baseTopic)
        val nowHasEventsTopic = nowHasEventsTopicFor(prepared.baseTopic)
        val nowTimestampTopic = nowTimestampTopicFor(prepared.baseTopic)
        // MQTT 3.1.1 carries no native publish timestamp (and the MQTT 5
        // user-property route isn't available on this v3 client), so we
        // publish one ourselves: epoch-millis as a decimal string on the
        // /now/timestamp surface. A retained consumer (e.g. HA casting the
        // video to a Home Hub) reads it to dedupe — on reconnect it sees the
        // same timestamp it already played and skips replaying the clip
        // rather than re-announcing a stale bundle.
        val timestampBytes = System.currentTimeMillis().toString().toByteArray(Charsets.UTF_8)
        val hasEventsBytes = (if (hasEvents) "true" else "false").toByteArray(Charsets.UTF_8)
        DiagLog.i(
            TAG,
            "MQTT bridge enabled; publishing %s insight bundle to %s://%s:%s/%s (image=%s, audio=%s, video=%s, hasEvents=%s, mirrored to /now as %s / %s / %s, then %s, then %s last as the commit marker, auth=%s, password=%s, payload=%s chars).",
            safe(period.name.lowercase()),
            prepared.scheme,
            prepared.host,
            prepared.port,
            proseTopic,
            image != null,
            audio != null,
            video != null,
            hasEvents,
            nowImageTopic,
            nowAudioTopic,
            nowVideoTopic,
            nowTextTopic,
            nowTimestampTopic,
            !prepared.config.username.isNullOrBlank(),
            if (prepared.config.password != null) "set" else "none",
            prose.length,
        )

        // Every period topic is always written so the `<period>/*` bundle is
        // self-coherent: prose carries the payload, and image/audio/video carry
        // their bytes when present or an empty retained payload (the MQTT
        // "delete the retained message" convention) when absent. Without the
        // clears, a later bundle with no audio/video — engine switched to device
        // TTS, a synth/quota failure, a render miss — would leave the *previous*
        // run's clip on `<period>/audio` or `<period>/video`, and a consumer
        // reading the day/night bundle directly (the next window has no `/now`
        // backstop) would play stale media for the new forecast. `/now` applies
        // the same clear convention in its block below.
        val emptyPayload = ByteArray(0)
        val proseDeferred = async { executePublish(prepared, proseTopic, proseBytes) }
        val imageDeferred = async { executePublish(prepared, imageTopic, image ?: emptyPayload) }
        val audioDeferred = async { executePublish(prepared, audioTopic, audio ?: emptyPayload) }
        val videoDeferred = async { executePublish(prepared, videoTopic, video ?: emptyPayload) }
        val hasEventsDeferred = async { executePublish(prepared, hasEventsTopic, hasEventsBytes) }
        val proseOutcome = proseDeferred.await()
        val imageOutcome = imageDeferred.await()
        val audioOutcome = audioDeferred.await()
        val videoOutcome = videoDeferred.await()
        val hasEventsOutcome = hasEventsDeferred.await()

        // /now mirrors only when the whole period bundle — payloads and clears
        // alike — published cleanly, so the mirror never advances past a period
        // surface that's itself half-updated.
        val everyPeriodPublishSucceeded = proseOutcome is MqttPublishOutcome.Success &&
            imageOutcome is MqttPublishOutcome.Success &&
            audioOutcome is MqttPublishOutcome.Success &&
            videoOutcome is MqttPublishOutcome.Success &&
            hasEventsOutcome is MqttPublishOutcome.Success

        // Per-period commit marker, written **last** of the `<period>/*` bundle
        // and only once text + image + audio + video (payloads and clears) have
        // all settled. It gives the day/night surface the same trigger contract
        // as `/now/timestamp`: a consumer reading `<prefix>/<period>` directly —
        // the next window has no `/now` backstop — triggers on
        // `<prefix>/<period>/timestamp` and dedupes on its value, so it never
        // acts on a half-updated bundle (e.g. fresh text still paired with a
        // failed audio/video clear) and doesn't replay a retained bundle on
        // reconnect. Held back when any period publish failed, so the marker
        // never advances past an incoherent `<period>/*` set.
        if (everyPeriodPublishSucceeded) {
            val periodTimestampOutcome = executePublish(prepared, periodTimestampTopic, timestampBytes)
            if (periodTimestampOutcome !is MqttPublishOutcome.Success) {
                DiagLog.w(
                    TAG,
                    "Period commit marker %s failed for %s bundle (%s); the %s/* content advanced but its marker did not, so consumers triggering on it will skip this bundle until the next publish.",
                    periodTimestampTopic,
                    safe(period.name.lowercase()),
                    periodTimestampOutcome,
                    topicSegment(period),
                )
            }
            // One coalesced line for the whole `<period>/*` bundle instead of a
            // line per topic. Failures are still surfaced individually above /
            // below, so this success summary stays terse.
            val periodKinds = succeededKinds(
                "text" to proseOutcome,
                "image" to imageOutcome,
                "audio" to audioOutcome,
                "video" to videoOutcome,
                "timestamp" to periodTimestampOutcome,
            )
            DiagLog.i(
                TAG,
                "Published %s bundle to %s://%s:%s/%s: %s (%s topics).",
                safe(period.name.lowercase()),
                prepared.scheme,
                prepared.host,
                prepared.port,
                proseTopic.substringBeforeLast('/'),
                periodKinds.joinToString(", "),
                periodKinds.size,
            )
        } else {
            DiagLog.i(
                TAG,
                "Holding back %s for %s bundle (prose=%s, image=%s, audio=%s, video=%s, has_events=%s); the %s/* marker stays at the previous bundle so a consumer doesn't act on a half-updated set.",
                periodTimestampTopic,
                safe(period.name.lowercase()),
                proseOutcome,
                imageOutcome,
                audioOutcome,
                videoOutcome,
                hasEventsOutcome,
                topicSegment(period),
            )
        }

        if (!mirrorToNow) {
            // Next-window publish: only the period topics (day/night) are
            // written, never `/now`. `/now` is reserved for the *current*
            // period the run is actually delivering, so a consumer subscribed
            // to a single `/now/*` surface keeps seeing the active cast while
            // the upcoming window lands on its own segment.
            DiagLog.i(
                TAG,
                "Published %s (%s) bundle without /now mirror (mirrorToNow=false); /now stays on the current period.",
                safe(period.name.lowercase()),
                topicSegment(period),
            )
        } else if (everyPeriodPublishSucceeded) {
            // The now surface is treated as an atomic three-topic bundle. Any
            // modality absent from this delivery clears its retained slot via
            // an empty-payload publish (the MQTT convention for "delete the
            // retained message") so a now/text-triggered consumer can't read
            // a stale image or audio left over from a previous bundle that
            // *did* carry one. now/text is then held back until both image
            // and audio mirrors settle successfully, so an automation
            // subscribing to now/text never sees the trigger advance before
            // the rest of the now bundle is coherent.
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
            // now/has_events mirrors the period flag and gates now/text like the
            // other content mirrors, so a now/text-triggered automation reads a
            // has_events value that matches the prose it's about to act on.
            val nowHasEventsMirror = async {
                executePublish(prepared, nowHasEventsTopic, hasEventsBytes)
            }
            val nowImageOutcome = nowImageMirror.await()
            val nowAudioOutcome = nowAudioMirror.await()
            val nowVideoOutcome = nowVideoMirror.await()
            val nowHasEventsOutcome = nowHasEventsMirror.await()
            if (nowImageOutcome is MqttPublishOutcome.Success &&
                nowAudioOutcome is MqttPublishOutcome.Success &&
                nowVideoOutcome is MqttPublishOutcome.Success &&
                nowHasEventsOutcome is MqttPublishOutcome.Success
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
                            "Commit marker %s failed for %s bundle (%s); now/* content advanced but the trigger marker did not, so timestamp-triggered consumers will skip this bundle until the next successful publish.",
                            nowTimestampTopic,
                            safe(period.name.lowercase()),
                            nowTimestampOutcome,
                        )
                    }
                    // One coalesced line for the whole `/now` mirror instead of a
                    // line per topic, matching the `<period>/*` summary above.
                    val nowKinds = succeededKinds(
                        "image" to nowImageOutcome,
                        "audio" to nowAudioOutcome,
                        "video" to nowVideoOutcome,
                        "text" to nowTextOutcome,
                        "timestamp" to nowTimestampOutcome,
                    )
                    DiagLog.i(
                        TAG,
                        "Mirrored %s bundle to %s: %s (%s topics).",
                        safe(period.name.lowercase()),
                        nowTextTopic.substringBeforeLast('/'),
                        nowKinds.joinToString(", "),
                        nowKinds.size,
                    )
                } else {
                    DiagLog.i(
                        TAG,
                        "Holding back %s for %s bundle (now/text=%s); the commit marker stays at the previous bundle so a consumer doesn't act on a half-updated set.",
                        nowTimestampTopic,
                        safe(period.name.lowercase()),
                        nowTextOutcome,
                    )
                }
            } else {
                DiagLog.i(
                    TAG,
                    "Holding back %s and %s mirrors for %s bundle (now/image=%s, now/audio=%s, now/video=%s, now/has_events=%s, videoSubmitted=%s); the now set stays at the previous bundle to keep it coherent.",
                    nowTextTopic,
                    nowTimestampTopic,
                    safe(period.name.lowercase()),
                    nowImageOutcome,
                    nowAudioOutcome,
                    nowVideoOutcome,
                    nowHasEventsOutcome,
                    video != null,
                )
            }
        } else {
            // The per-topic failures already logged their cause; here we only
            // need to note that the /now mirror is being held back so a reader
            // can correlate "no /now update" with "primary publishes failed."
            val failed = listOfNotNull(
                if (proseOutcome !is MqttPublishOutcome.Success) "prose" else null,
                if (imageOutcome !is MqttPublishOutcome.Success) "image" else null,
                if (audioOutcome !is MqttPublishOutcome.Success) "audio" else null,
                if (videoOutcome !is MqttPublishOutcome.Success) "video" else null,
                if (hasEventsOutcome !is MqttPublishOutcome.Success) "has_events" else null,
            ).joinToString("/")
            DiagLog.i(
                TAG,
                "Skipping /now mirror for %s bundle (%s failed); previous retained /now bundle stays intact.",
                safe(period.name.lowercase()),
                failed,
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
            "MQTT test publish to %s://%s:%s/%s.",
            prepared.scheme,
            prepared.host,
            prepared.port,
            topic,
        )
        val outcome = executePublish(prepared, topic, "ClothesCast: connection test".toByteArray(Charsets.UTF_8))
        if (outcome is MqttPublishOutcome.Success) {
            // executePublish no longer logs per-topic success, so confirm the
            // probe landed — the "Publish now" button's whole point is to see
            // that the broker is reachable.
            DiagLog.i(
                TAG,
                "MQTT test publish succeeded to %s://%s:%s/%s.",
                prepared.scheme,
                prepared.host,
                prepared.port,
                topic,
            )
        }
        return outcome
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
            DiagLog.w(TAG, t, "Failed to read settings for %s MQTT publish; skipping.", context)
            return null
        }
        // Bridge-off is the silent-no-op path for users who haven't opted in;
        // not logged to keep the diag stream clean for the 99% no-bridge case.
        if (!prefs.mqttBridgeEnabled) return null
        val host = prefs.mqttHost?.takeIf { it.isNotBlank() } ?: run {
            DiagLog.w(
                TAG,
                "MQTT bridge is enabled but no broker host is configured; %s message not published. Set the host in Settings → Smart Home.",
                context,
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
                DiagLog.w(TAG, t, "Failed to read MQTT password from keystore; attempting anonymous connect.")
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

    /**
     * The `kind` label of each topic whose publish succeeded, preserving
     * argument order — used to build the coalesced "Published … (N topics)"
     * summary lines so a successful bundle costs one diag line per group
     * instead of one per topic.
     */
    private fun succeededKinds(vararg topics: Pair<String, MqttPublishOutcome>): List<String> =
        topics.filter { it.second is MqttPublishOutcome.Success }.map { it.first }

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
                    "Retrying MQTT publish (attempt %s/%s) to %s://%s:%s/%s after %sms",
                    attempt + 1,
                    maxAttempts,
                    prepared.scheme,
                    prepared.host,
                    prepared.port,
                    topic,
                    retryDelayMs,
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
            // Success is intentionally silent here: a single bundle fans out to
            // ~25 topics (period + /now + Home Assistant discovery), so a line
            // per topic floods the 300-line diag snapshot. Callers log one
            // coalesced summary per group instead; only failures, timeouts, and
            // retries — the lines worth reading — are logged per topic below.
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
            DiagLog.w(
                TAG,
                "MQTT publish failed to %s://%s:%s/%s (%s)",
                prepared.scheme,
                prepared.host,
                prepared.port,
                topic,
                msg,
            )
            result = MqttPublishOutcome.Failure(msg)
        }
        result
    } ?: run {
        DiagLog.w(
            TAG,
            "MQTT publish timed out after %sms to %s://%s:%s/%s",
            publishTimeoutMs,
            prepared.scheme,
            prepared.host,
            prepared.port,
            topic,
        )
        MqttPublishOutcome.Failure(
            "Couldn't reach the broker at ${prepared.host}:${prepared.port} within " +
                "${publishTimeoutMs}ms — check it's online and that nothing's blocking the port.",
        )
    }

    private suspend fun publishHomeAssistantDiscovery(prepared: PreparedPublish) {
        val entries = homeAssistantDiscoveryEntries(prepared.baseTopic)
        var configsPublished = 0
        entries.forEach { entry ->
            val outcome = executePublish(
                prepared = prepared,
                topic = entry.configTopic,
                payload = entry.payload.toByteArray(Charsets.UTF_8),
            )
            if (outcome is MqttPublishOutcome.Success) {
                configsPublished++
            } else {
                DiagLog.w(
                    TAG,
                    "Home Assistant discovery config %s failed (%s); forecast topics remain published but HA may not auto-create this entity.",
                    entry.configTopic,
                    outcome,
                )
            }
        }
        // Clear the pre-rename today/tonight discovery configs (empty retained
        // payload = HA "remove entity") so upgrading users don't keep orphaned
        // today/tonight entities alongside the new day/night ones.
        val tombstones = homeAssistantDiscoveryTombstones(prepared.baseTopic)
        var staleConfigsCleared = 0
        tombstones.forEach { topic ->
            val outcome = executePublish(prepared = prepared, topic = topic, payload = ByteArray(0))
            if (outcome is MqttPublishOutcome.Success) {
                staleConfigsCleared++
            } else {
                DiagLog.w(
                    TAG,
                    "Clearing stale discovery config %s failed (%s); an orphaned today/tonight entity may linger in Home Assistant.",
                    topic,
                    outcome,
                )
            }
        }
        // One coalesced line for the discovery fan-out (per-topic failures, if
        // any, are logged above) so it doesn't add ~17 lines to the snapshot.
        DiagLog.i(
            TAG,
            "Published Home Assistant discovery: %s/%s entity configs, cleared %s/%s stale today/tonight configs.",
            configsPublished,
            entries.size,
            staleConfigsCleared,
            tombstones.size,
        )
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

        // Boolean ("true"/"false") flag: did this period's window carry a
        // located, timed calendar event? Lets a Home Assistant automation tell
        // an event-free evening (where the phone may stay quiet) from an outage
        // without parsing the prose.
        fun hasEventsTopicFor(baseTopic: String, period: ForecastPeriod): String =
            periodTopicFor(baseTopic, period, "has_events")

        // Epoch-millis (decimal string) commit marker for the `<period>/*`
        // bundle, written last and only when the whole bundle settled — the
        // day/night equivalent of `now/timestamp`. A consumer reading a day or
        // night bundle directly triggers on this and dedupes on its value.
        fun timestampTopicFor(baseTopic: String, period: ForecastPeriod): String =
            periodTopicFor(baseTopic, period, "timestamp")

        // `<base>/now/<kind>` mirrors the period payload of the *current*
        // window (day or night) so a consumer can subscribe to a single topic
        // without having to reason about which window is "current" or chase
        // recency timestamps across the two period topics. The next window is
        // published to its own segment but never mirrored here.
        fun nowTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "text")

        fun nowImageTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "image")

        fun nowAudioTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "audio")

        fun nowVideoTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "video")

        fun nowHasEventsTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "has_events")

        // Epoch-millis (decimal string) of the most recent /now bundle. Not a
        // payload modality — it's the recency marker consumers use to dedupe a
        // retained bundle they've already acted on.
        fun nowTimestampTopicFor(baseTopic: String): String = nowTopicForKind(baseTopic, "timestamp")

        internal fun homeAssistantDiscoveryEntries(baseTopic: String): List<HomeAssistantDiscoveryEntry> {
            val base = baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
            val idPrefix = discoveryIdPrefix(baseTopic)
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
            fun binarySensor(id: String, name: String, stateTopic: String) = HomeAssistantDiscoveryEntry(
                configTopic = "homeassistant/binary_sensor/${idPrefix}_$id/config",
                payload = buildJsonObject {
                    put("name", name)
                    put("unique_id", "${idPrefix}_$id")
                    put("default_entity_id", "binary_sensor.${idPrefix}_$id")
                    put("state_topic", stateTopic)
                    put("payload_on", "true")
                    put("payload_off", "false")
                    put("icon", "mdi:calendar-check")
                    put("device", device)
                }.toString(),
            )
            return listOf(
                sensor("day", "Day", topicFor(base, ForecastPeriod.TODAY), icon = "mdi:tshirt-crew"),
                sensor("night", "Night", topicFor(base, ForecastPeriod.TONIGHT), icon = "mdi:tshirt-crew"),
                sensor("now", "Now", nowTopicFor(base), icon = "mdi:tshirt-crew"),
                sensor(
                    id = "now_updated",
                    name = "Now updated",
                    stateTopic = nowTimestampTopicFor(base),
                    icon = "mdi:clock-outline",
                    deviceClass = "timestamp",
                    valueTemplate = "{{ as_datetime((value | int) / 1000) }}",
                ),
                sensor(
                    id = "day_updated",
                    name = "Day updated",
                    stateTopic = timestampTopicFor(base, ForecastPeriod.TODAY),
                    icon = "mdi:clock-outline",
                    deviceClass = "timestamp",
                    valueTemplate = "{{ as_datetime((value | int) / 1000) }}",
                ),
                sensor(
                    id = "night_updated",
                    name = "Night updated",
                    stateTopic = timestampTopicFor(base, ForecastPeriod.TONIGHT),
                    icon = "mdi:clock-outline",
                    deviceClass = "timestamp",
                    valueTemplate = "{{ as_datetime((value | int) / 1000) }}",
                ),
                image("day_image", "Day image", imageTopicFor(base, ForecastPeriod.TODAY)),
                image("night_image", "Night image", imageTopicFor(base, ForecastPeriod.TONIGHT)),
                image("now_image", "Now image", nowImageTopicFor(base)),
                binarySensor("day_has_events", "Day has events", hasEventsTopicFor(base, ForecastPeriod.TODAY)),
                binarySensor("night_has_events", "Night has events", hasEventsTopicFor(base, ForecastPeriod.TONIGHT)),
                binarySensor("now_has_events", "Now has events", nowHasEventsTopicFor(base)),
            )
        }

        private fun periodTopicFor(baseTopic: String, period: ForecastPeriod, kind: String): String {
            val trimmed = baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
            return "$trimmed/${topicSegment(period)}/$kind"
        }

        // The topic segment for a period is decoupled from the enum name so the
        // wire surface reads as wall-clock windows — `day` (the daytime cast)
        // and `night` (the overnight cast) — rather than the `today`/`tonight`
        // the enum constants are named for. Each scheduled run publishes both:
        // the active period on `/now`, the next window on its own segment, so a
        // consumer always finds the current and upcoming cast on stable topics.
        internal fun topicSegment(period: ForecastPeriod): String = when (period) {
            ForecastPeriod.TODAY -> "day"
            ForecastPeriod.TONIGHT -> "night"
        }

        private fun nowTopicForKind(baseTopic: String, kind: String): String {
            val trimmed = baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
            return "$trimmed/now/$kind"
        }

        private fun discoveryIdPrefix(baseTopic: String): String {
            val base = baseTopic.trim().trim('/').ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
            return if (base == UserPreferences.DEFAULT_MQTT_TOPIC) {
                "clothescast"
            } else {
                "clothescast_${discoveryIdSuffix(base)}"
            }
        }

        // Config topics for the pre-rename today/tonight discovery entities.
        // Published with an empty retained payload — Home Assistant's "remove
        // this entity" signal — so an upgrading user doesn't keep orphaned
        // today/tonight entities (subscribed to topics we no longer update)
        // alongside the new day/night ones. Harmless no-ops on a fresh install
        // that never had them.
        internal fun homeAssistantDiscoveryTombstones(baseTopic: String): List<String> {
            val idPrefix = discoveryIdPrefix(baseTopic)
            return listOf(
                "homeassistant/sensor/${idPrefix}_today/config",
                "homeassistant/sensor/${idPrefix}_tonight/config",
                "homeassistant/image/${idPrefix}_today_image/config",
                "homeassistant/image/${idPrefix}_tonight_image/config",
            )
        }

        // ASCII [a-z0-9] only — not isLetterOrDigit, which is Unicode-aware.
        // The suffix lands in Home Assistant discovery topic segments and
        // default_entity_id, both of which HA validates as slugs; a base
        // topic like `wetter/küche` would otherwise pass `ü` through and HA
        // would reject every discovery config (publishes "succeed", no
        // entities ever appear).
        private fun discoveryIdSuffix(baseTopic: String): String =
            baseTopic.trim().trim('/')
                .ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC }
                .lowercase()
                .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
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

// HiveMQ's builders are fluent and mutate in place, returning the receiver;
// sslWithDefaultConfig() / password() inside the apply{} blocks below take
// effect even though their return value is discarded.
@Suppress("CheckResult")
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
