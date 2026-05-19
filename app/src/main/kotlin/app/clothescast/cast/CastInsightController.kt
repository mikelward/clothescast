package app.clothescast.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.clothescast.core.data.tts.GeminiTtsClient
import app.clothescast.core.data.tts.PcmAudio
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.diag.DiagLog
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives a Cast session for the Settings → "Cast now" test button (and,
 * once PR B lands, the worker). Synthesises the insight via Gemini TTS,
 * wraps the PCM in WAV, hands the buffers to [CastMediaServer] for LAN
 * hosting, and tells the connected Cast receiver to load the URLs.
 *
 * One instance per [app.clothescast.ClothesCastApplication]. Callers
 * register the controller via [bind] before kicking off a cast and
 * [unbind] it when the session ends — the controller's own
 * [SessionManagerListener] just stops the media server when the
 * receiver disconnects, so we're not leaving an open port behind.
 *
 * The "select a route then cast" orchestration lives in the caller —
 * the controller assumes a session is already active when [cast] is
 * called, and returns a [CastResult] describing why a cast was skipped
 * otherwise.
 */
class CastInsightController(
    private val context: Context,
    private val castContext: CastContext,
    private val ttsClient: GeminiTtsClient,
    private val applicationScope: CoroutineScope,
    private val resolveLanIp: (Context) -> String? = LanAddress::resolve,
    private val server: CastMediaServer = CastMediaServer(),
) {

    private val sessionManager: SessionManager get() = castContext.sessionManager

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            server.stop()
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            server.stop()
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            server.stop()
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private var bound = false

    fun bind() {
        if (bound) return
        sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        bound = true
    }

    fun unbind() {
        if (!bound) return
        sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
        server.stop()
        bound = false
    }

    /**
     * Casts the given insight to the currently-connected smart display, if
     * any. Returns [CastAttempt] describing the outcome of the pre-flight
     * checks; the [CastAttempt.job] is the synth + publish + load coroutine,
     * non-null only when [CastResult.Loaded] was returned.
     */
    fun cast(
        prose: String,
        locale: Locale,
        voiceName: String,
        style: TtsStyle,
        outfitPng: ByteArray,
        title: String,
        subtitle: String?,
    ): CastAttempt {
        val session = sessionManager.currentCastSession
        if (session == null || !session.isConnected) {
            return CastAttempt(CastResult.NoActiveSession, job = null)
        }
        val client = session.remoteMediaClient
            ?: return CastAttempt(CastResult.NoRemoteMediaClient, job = null)
        val host = resolveLanIp(context)
            ?: return CastAttempt(CastResult.NoLanAddress, job = null)

        val job = applicationScope.launch {
            try {
                val pcm = ttsClient.synthesize(
                    text = prose,
                    voiceName = voiceName,
                    locale = locale,
                    style = style,
                )
                val wav = WavEncoder.encode(padPcmToMinimumDuration(pcm))
                val mp4 = withContext(Dispatchers.Default) { Mp4Encoder.encode(outfitPng, wav) }
                val urls = server.publish(host = host, video = mp4)
                client.load(
                    MediaLoadRequestData.Builder()
                        .setMediaInfo(buildMediaInfo(urls, title, subtitle))
                        .build(),
                )
            } catch (t: Throwable) {
                DiagLog.e(TAG, "Cast publish failed", t)
                server.stop()
                throw t
            }
        }
        return CastAttempt(CastResult.Loaded, job = job)
    }

    sealed interface CastResult {
        data object Loaded : CastResult
        data object NoActiveSession : CastResult
        data object NoRemoteMediaClient : CastResult
        data object NoLanAddress : CastResult
    }

    data class CastAttempt(val result: CastResult, val job: Job?)

    /**
     * Selects the user's saved Cast route (by [routeId]), waits for the
     * resulting session to start, and casts the given insight. Used by
     * the Settings → "Cast now" test button.
     *
     * - Returns when [client.load] has been called (audio + image are
     *   loading on the smart display). The synth + WAV-wrap + publish
     *   chain runs inside the call before [client.load].
     * - Throws on timeout, on route-not-found (device off / out of range
     *   / different LAN), or on a session-start failure. The caller
     *   converts the throwable to a user-facing status string and
     *   persists it via [SettingsRepository.setCastLastError].
     */
    suspend fun castToSavedRoute(
        routeId: String,
        prose: String,
        locale: Locale,
        voiceName: String,
        style: TtsStyle,
        outfitPng: ByteArray,
        title: String,
        subtitle: String?,
        discoveryTimeoutMs: Long = 5_000,
        sessionTimeoutMs: Long = 10_000,
    ) {
        val route = findRoute(routeId, discoveryTimeoutMs)
            ?: throw CastFailure.DeviceNotFound
        val session = ensureSession(route, sessionTimeoutMs)
        val client = session.remoteMediaClient
            ?: throw CastFailure.NoRemoteMediaClient
        val host = resolveLanIp(context)
            ?: throw CastFailure.NoLanAddress

        val pcm = ttsClient.synthesize(
            text = prose,
            voiceName = voiceName,
            locale = locale,
            style = style,
        )
        val wav = WavEncoder.encode(padPcmToMinimumDuration(pcm))
        val mp4 = withContext(Dispatchers.Default) { Mp4Encoder.encode(outfitPng, wav) }
        val urls = server.publish(host = host, video = mp4)
        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(buildMediaInfo(urls, title, subtitle))
                .build(),
        )
    }

    /**
     * Outcome of the worker-driven cast attempt. Distinct from the
     * Settings "Cast now" path's typed exceptions because the worker
     * never throws on cast failure — the notification and MQTT
     * publishes have already fired by the time we get here, and the
     * spec is explicit that no destination's failure cancels another.
     */
    sealed interface CastWorkerOutcome {
        /** `RemoteMediaClient.load` accepted the media. */
        data object Success : CastWorkerOutcome

        /**
         * Route id no longer resolves on the LAN — display fully off,
         * mDNS lapsed, or the user replaced the saved id with one
         * that's no longer discoverable. Surfaces as a "Smart display
         * not reachable" status row in Settings.
         */
        data object SkippedNoRoute : CastWorkerOutcome

        /**
         * Any other failure (no LAN IPv4 on the phone, session start
         * rejected, receiver refused the load, etc.). [reason] is a
         * short user-facing string — pre-formatted, ready to drop
         * into `castLastError`.
         */
        data class Failed(val reason: String) : CastWorkerOutcome
    }

    /**
     * Worker entrypoint. Same select-route-and-load flow as
     * [castToSavedRoute], but accepts pre-rendered media so the
     * worker can synthesise and render pre-alignment and load the
     * exact same buffers it fed to MQTT.
     *
     * @param wav WAV-wrapped audio. Pass [silentWavStub] when no real
     *   audio is available (Gemini unavailable or synth failed) —
     *   Default Media Receiver requires some media to load, so the
     *   silent stub is the image-only path's loading carrier.
     *   Nothing audible plays on the receiver in that case.
     * @param hasRealAudio false on the image-only path, true when
     *   [wav] carries the synth result. The worker reads this to
     *   decide whether [castSkipPhoneSpeech] applies (image-only
     *   casts don't suppress phone speech because the display isn't
     *   doing the audio).
     *
     * TODO(cast-edge-cases): handle the cases where casting would
     *  be disruptive — destination is a TV in standby on a different
     *  HDMI input (CEC could force the TV on / switch input), or
     *  another app currently holds an active Cast session on the
     *  route. SPEC.md's original "Power on smart display" and
     *  "Interrupt if already playing" toggles were dropped pending a
     *  signal that reliably distinguishes "Nest Hub idling on
     *  ambient" (no active session, screen on, casting welcome)
     *  from "TV in standby" (no active session, screen off, casting
     *  disruptive). For now we always attempt the cast when a route
     *  is picked and the period toggle is on; users who don't want
     *  disruption can clear the picked display in Settings.
     */
    suspend fun castWithPreparedMedia(
        routeId: String,
        wav: ByteArray,
        @Suppress("UNUSED_PARAMETER") hasRealAudio: Boolean,
        png: ByteArray,
        title: String,
        subtitle: String?,
        discoveryTimeoutMs: Long = 5_000,
        sessionTimeoutMs: Long = 10_000,
        loadTimeoutMs: Long = 15_000,
    ): CastWorkerOutcome {
        return try {
            // MediaRouter / SessionManager / RemoteMediaClient calls
            // are documented main-thread-only. The worker reaches us
            // from Dispatchers.Default; Main.immediate is a no-op
            // when the Settings "Cast now" path enters from
            // viewModelScope (already on Main). Off-main work
            // (resolveLanIp → ConnectivityManager, server.publish →
            // Ktor) stays outside the hop so we don't block the UI
            // thread on network I/O.
            val routeAndClient = withContext(Dispatchers.Main.immediate) {
                val route = findRoute(routeId, discoveryTimeoutMs)
                    ?: return@withContext null
                val session = ensureSession(route, sessionTimeoutMs)
                session.remoteMediaClient
            }
            val client = when (routeAndClient) {
                null -> return CastWorkerOutcome.SkippedNoRoute
                else -> routeAndClient
            }
            val host = resolveLanIp(context)
                ?: return CastWorkerOutcome.Failed(CastFailure.NoLanAddress.message ?: "No LAN IP")
            val paddedWav = padWavToMinimumDuration(wav)
            val mp4 = withContext(Dispatchers.Default) { Mp4Encoder.encode(png, paddedWav) }
            val urls = server.publish(host = host, video = mp4)
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(buildMediaInfo(urls, title, subtitle))
                .build()
            // Await the receiver's load result rather than reporting
            // Success on enqueue. Without this, a receiver that
            // accepts the request but later fails to fetch / decode
            // (codec mismatch, LAN URL unreachable) would clear the
            // status row AND — when castSkipPhoneSpeech is on —
            // suppress phone TTS off a premature "success", leaving
            // the user with no spoken forecast.
            val result = awaitLoad(client, request, loadTimeoutMs)
                ?: return CastWorkerOutcome.Failed("Smart display did not respond to the load request")
            if (result.status.isSuccess) {
                CastWorkerOutcome.Success
            } else {
                val message = result.status.statusMessage?.takeIf { it.isNotBlank() }
                    ?: "Smart display rejected the media (code ${result.status.statusCode})"
                DiagLog.w(TAG, "Cast load rejected by receiver: $message")
                CastWorkerOutcome.Failed(message)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // CancellationException must propagate so WorkManager
            // stops unwind cleanly — same convention as
            // MqttPublisher.preparePublish.
            throw ce
        } catch (failure: CastFailure) {
            DiagLog.w(TAG, "Cast load failed: ${failure.message}", failure)
            CastWorkerOutcome.Failed(failure.message ?: "Cast failed")
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Cast load failed unexpectedly", t)
            CastWorkerOutcome.Failed(t.message ?: "Cast failed")
        }
    }

    /**
     * Wraps [RemoteMediaClient.load]'s [PendingResult][com.google.android.gms.common.api.PendingResult]
     * in a suspending call with a timeout, so the worker can decide
     * Success vs. Failed off the receiver's actual outcome rather
     * than off "request enqueued." Returns null on timeout so the
     * caller surfaces a no-response error instead of waiting
     * indefinitely.
     */
    private suspend fun awaitLoad(
        client: RemoteMediaClient,
        request: MediaLoadRequestData,
        timeoutMs: Long,
    ): RemoteMediaClient.MediaChannelResult? = withTimeoutOrNull(timeoutMs) {
        // RemoteMediaClient.load is documented main-thread-only; hop
        // here too rather than relying on the caller (Main.immediate
        // is a no-op when we already are on Main). The PendingResult
        // callback can fire on any thread per Cast SDK docs, so the
        // continuation resume from any thread is safe.
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val pending = client.load(request)
                pending.setResultCallback { result ->
                    if (cont.isActive) cont.resume(result)
                }
                cont.invokeOnCancellation { runCatching { pending.cancel() } }
            }
        }
    }

    private suspend fun findRoute(
        routeId: String,
        timeoutMs: Long,
    ): MediaRouter.RouteInfo? {
        val router = MediaRouter.getInstance(context)
        router.routes.firstOrNull { it.id == routeId }?.let { return it }

        val selector = MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                ),
            )
            .build()

        return withTimeoutOrNull(timeoutMs) {
            callbackFlow<MediaRouter.RouteInfo> {
                val callback = object : MediaRouter.Callback() {
                    override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                        if (route.id == routeId) trySend(route)
                    }
                    override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                        if (route.id == routeId) trySend(route)
                    }
                }
                router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
                // Re-check in case the route appeared between the snapshot
                // above and the callback registering.
                router.routes.firstOrNull { it.id == routeId }?.let { trySend(it) }
                awaitClose { router.removeCallback(callback) }
            }.first()
        }
    }

    private suspend fun ensureSession(
        route: MediaRouter.RouteInfo,
        timeoutMs: Long,
    ): CastSession {
        val router = MediaRouter.getInstance(context)
        val existing = sessionManager.currentCastSession
        // Reuse only if the existing session is connected AND on the route
        // the caller asked for. Otherwise a previous "Cast now" to display A
        // would silently load the new forecast onto display A even after the
        // user picked display B, leaking the insight prose / location to a
        // receiver they no longer want it on.
        if (existing != null && existing.isConnected && router.selectedRoute.id == route.id) {
            return existing
        }
        if (router.selectedRoute.id != route.id) {
            // selectRoute tears down any existing session on a different
            // route and starts a new one — the controller's [sessionListener]
            // catches the old session's onSessionEnded and stops the media
            // server before we publish the new bytes below.
            router.selectRoute(route)
        }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SessionManagerListener<CastSession> {
                    override fun onSessionStarting(session: CastSession) {}
                    override fun onSessionStarted(session: CastSession, sessionId: String) {
                        if (cont.isActive) cont.resume(session)
                    }
                    override fun onSessionStartFailed(session: CastSession, error: Int) {
                        if (cont.isActive) cont.resumeWithException(CastFailure.SessionStartFailed(error))
                    }
                    override fun onSessionEnding(session: CastSession) {}
                    override fun onSessionEnded(session: CastSession, error: Int) {}
                    override fun onSessionResuming(session: CastSession, sessionId: String) {}
                    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                        if (cont.isActive) cont.resume(session)
                    }
                    override fun onSessionResumeFailed(session: CastSession, error: Int) {
                        if (cont.isActive) cont.resumeWithException(CastFailure.SessionStartFailed(error))
                    }
                    override fun onSessionSuspended(session: CastSession, reason: Int) {}
                }
                sessionManager.addSessionManagerListener(listener, CastSession::class.java)
                cont.invokeOnCancellation {
                    sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
                }
            }
        } ?: throw CastFailure.SessionStartTimeout
    }

    /**
     * Typed failures the test-cast path can produce. The caller maps
     * each to a user-facing string and persists via
     * [app.clothescast.data.SettingsRepository.setCastLastError].
     */
    sealed class CastFailure(message: String) : Exception(message) {
        data object DeviceNotFound : CastFailure("Smart display not found on the network.")
        data object SessionStartTimeout : CastFailure("Smart display did not respond in time.")
        data class SessionStartFailed(val errorCode: Int) :
            CastFailure("Smart display rejected the session (code $errorCode).")
        data object NoRemoteMediaClient : CastFailure("Smart display did not accept playback.")
        data object NoLanAddress : CastFailure("Phone has no Wi-Fi address — connect to the same network as the smart display.")
    }

    companion object {
        private const val TAG = "CastInsightController"

        /**
         * 24 kHz mono 16-bit PCM, [MIN_CAST_DURATION_SECONDS] of zeros,
         * wrapped in a standard RIFF/WAVE header. Default Media
         * Receiver requires some media to load, so the image-only
         * cast path (Gemini unavailable, or synth failed) uses this
         * as the audio side of the muxed MP4 — nothing audible
         * plays on the receiver but the outfit image stays visible
         * for the duration of the video track. Cached after first
         * computation; the bytes are immutable.
         */
        val silentWavStub: ByteArray by lazy {
            val sampleRate = 24_000
            val sampleCount = sampleRate * MIN_CAST_DURATION_SECONDS
            val pcm = ByteArray(sampleCount * 2) // 16-bit
            WavEncoder.encode(PcmAudio(bytes = pcm, sampleRate = sampleRate))
        }

        /**
         * Minimum on-display time for any cast. Short TTS audio
         * gets trailing silence appended so the outfit image stays
         * on the smart display long enough for a glance from across
         * the room to register it; audio longer than this plays in
         * full and the cast naturally runs to the end of the speech.
         * Also sizes [silentWavStub] for the image-only fallback.
         */
        internal const val MIN_CAST_DURATION_SECONDS = 30

        /**
         * Pads [pcm] with trailing silence so the resulting buffer
         * carries at least [MIN_CAST_DURATION_SECONDS] of audio.
         * Returns the input unchanged when it's already long enough.
         */
        internal fun padPcmToMinimumDuration(pcm: PcmAudio): PcmAudio {
            // 16-bit mono → 2 bytes per frame.
            val minBytes = pcm.sampleRate * MIN_CAST_DURATION_SECONDS * 2
            if (pcm.bytes.size >= minBytes) return pcm
            val padded = ByteArray(minBytes)
            pcm.bytes.copyInto(padded)
            return PcmAudio(bytes = padded, sampleRate = pcm.sampleRate)
        }

        /**
         * WAV-level equivalent of [padPcmToMinimumDuration] for the
         * worker path, which holds the cast media as already-encoded
         * WAV bytes shared with MQTT — MQTT consumers want the raw
         * TTS length, so padding has to happen inside the cast
         * branch, not upstream.
         */
        internal fun padWavToMinimumDuration(wav: ByteArray): ByteArray {
            val info = Mp4Encoder.WavInfo.parse(wav)
            val minBytes = info.sampleRate * MIN_CAST_DURATION_SECONDS * 2 * info.channels
            if (info.pcm.size >= minBytes) return wav
            val padded = ByteArray(minBytes)
            info.pcm.copyInto(padded)
            return WavEncoder.encode(PcmAudio(bytes = padded, sampleRate = info.sampleRate))
        }

        internal fun buildMediaInfo(
            urls: CastMediaServer.MediaUrl,
            title: String,
            subtitle: String?,
        ): MediaInfo {
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, title)
                if (!subtitle.isNullOrBlank()) {
                    putString(MediaMetadata.KEY_SUBTITLE, subtitle)
                }
            }
            return MediaInfo.Builder(urls.video)
                .setContentType("video/mp4")
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(metadata)
                .build()
        }
    }
}
