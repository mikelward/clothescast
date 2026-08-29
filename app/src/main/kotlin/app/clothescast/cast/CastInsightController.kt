package app.clothescast.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.clothescast.core.data.insight.MissingApiKeyException
import app.clothescast.core.data.tts.GeminiTtsClient
import app.clothescast.core.data.tts.PcmAudio
import app.clothescast.core.data.tts.WavEncoder
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.diag.DiagLog
import app.clothescast.net.NetworkErrorKind
import app.clothescast.net.classifyNetworkError
import app.clothescast.tts.prepareForTts
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
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
 * The "select a route then cast" orchestration lives in
 * [castToSavedRoute] (throws [CastFailure] describing why a cast was
 * skipped) and [castWithPreparedMedia] (returns a [CastWorkerOutcome]).
 */
class CastInsightController(
    private val context: Context,
    private val castContext: CastContext,
    private val ttsClient: GeminiTtsClient,
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
     * Selects the user's saved Cast route (by [routeId]), waits for the
     * resulting session to start, and casts the given insight. Used by
     * the Settings → "Cast now" test button.
     *
     * - Returns once the receiver has accepted the load AND the phone's
     *   media server has confirmed the receiver actually fetched the
     *   URL — the two together are the real "the display played it"
     *   signal. The synth + WAV-wrap + publish chain runs inside the
     *   call before the load.
     * - Throws on timeout, on route-not-found (device off / out of range
     *   / different LAN), on a session-start failure, on the receiver
     *   rejecting or never responding to the load, or on the receiver
     *   accepting the load but never fetching the URL (the firewall
     *   case the GET-confirmation check exists to catch). The caller
     *   converts the throwable to a user-facing status string and
     *   persists it via [SettingsRepository.setCastResult].
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
        loadTimeoutMs: Long = 15_000,
        fetchTimeoutMs: Long = 15_000,
    ) {
        val route = findRoute(routeId, discoveryTimeoutMs)
            ?: throw CastFailure.DeviceNotFound
        val deviceClass = classifyRoute(route)
        val session = ensureSession(route, sessionTimeoutMs)
        val client = session.remoteMediaClient
            ?: throw CastFailure.NoRemoteMediaClient
        val host = resolveLanIp(context)
            ?: throw CastFailure.NoLanAddress

        // Speech on a cast device needs Gemini (the device voice can't be
        // muxed into the cast media). Without a key, a display still casts
        // the outfit image with a silent track instead of failing — mirrors
        // the worker's image-only fallback — but a speaker has no screen, so
        // there's nothing to play; surface the missing voice as a cast error.
        // Other synth failures (network, etc.) still surface as a cast error
        // so the user knows audio genuinely broke.
        val pcm = try {
            // Same spoken-text transform every other speech path applies
            // (AndroidTtsSpeaker, GeminiTtsSpeaker — see the TtsSpeaker
            // contract): brand pronounced as two syllables, English degree
            // glyphs spelled out. Without it the "Cast now" audio diverges
            // from the scheduled cast it exists to preview.
            ttsClient.synthesize(
                text = prepareForTts(prose, locale),
                voiceName = voiceName,
                locale = locale,
                style = style,
            )
        } catch (_: MissingApiKeyException) {
            null
        }
        val request = when (mediaPlanFor(deviceClass, hasRealAudio = pcm != null)) {
            CastMediaPlan.SkipNoAudio -> throw CastFailure.NoAudioForSpeaker
            CastMediaPlan.Wav -> {
                val wav = WavEncoder.encode(pcm!!)
                // This path enters from viewModelScope (Settings "Cast now"),
                // i.e. on Main — publish binds a socket and blocks on the CIO
                // engine start, so hop it off the UI thread (matching the
                // Threading contract documented on [CastMediaServer]).
                val urls = withContext(Dispatchers.IO) {
                    server.publish(host = host, media = wav, kind = CastMediaKind.WAV)
                }
                DiagLog.i(
                    TAG,
                    "Cast test: hosting %sB wav on http://%s:%s/<token>/insight.wav for speaker " +
                        "route %s; sending load command.",
                    wav.size,
                    host,
                    server.port(),
                    routeId,
                )
                MediaLoadRequestData.Builder()
                    .setMediaInfo(buildAudioMediaInfo(urls.url, title, subtitle))
                    .build()
            }
            CastMediaPlan.Mp4 -> {
                val wav = if (pcm != null) {
                    WavEncoder.encode(padPcmToMinimumDuration(pcm))
                } else {
                    DiagLog.i(TAG, "Cast test: no Gemini key; casting image-only (silent).")
                    silentWavStub
                }
                val mp4 = withContext(Dispatchers.Default) { Mp4Encoder.encode(outfitPng, wav) }
                // Off-main for the socket bind — see the WAV branch above.
                val urls = withContext(Dispatchers.IO) {
                    server.publish(host = host, media = mp4, kind = CastMediaKind.MP4)
                }
                DiagLog.i(
                    TAG,
                    "Cast test: hosting %sB mp4 on http://%s:%s/<token>/insight.mp4 for route %s; " +
                        "sending load command.",
                    mp4.size,
                    host,
                    server.port(),
                    routeId,
                )
                MediaLoadRequestData.Builder()
                    .setMediaInfo(buildMediaInfo(urls.url, title, subtitle))
                    .build()
            }
        }
        val result = awaitLoad(client, request, loadTimeoutMs)
            ?: run {
                DiagLog.w(TAG, "Cast test: receiver did not respond to load within %sms.", loadTimeoutMs)
                throw CastFailure.LoadResponseTimeout
            }
        if (!result.status.isSuccess) {
            val message = result.status.statusMessage?.takeIf { it.isNotBlank() }
                ?: "Smart display rejected the media (code ${result.status.statusCode})."
            DiagLog.w(TAG, "Cast test: load rejected by receiver: %s", message)
            throw CastFailure.LoadRejected(message)
        }
        DiagLog.i(TAG, "Cast test: load accepted; awaiting URL fetch (timeout=%sms).", fetchTimeoutMs)
        if (!server.awaitFetch(fetchTimeoutMs)) {
            DiagLog.w(
                TAG,
                "Cast test: receiver accepted load but never fetched the hosted URL within %sms — " +
                    "likely a LAN firewall between the display and the phone.",
                fetchTimeoutMs,
            )
            throw CastFailure.FetchNotConfirmed
        }
        DiagLog.i(TAG, "Cast test: receiver fetched the hosted URL; cast confirmed fetched.")
    }

    /**
     * Outcome of the worker-driven cast attempt. Distinct from the
     * Settings "Cast now" path's typed exceptions because the worker
     * never throws on cast failure — the notification and MQTT
     * publishes have already fired by the time we get here, and the
     * spec is explicit that no destination's failure cancels another.
     */
    sealed interface CastWorkerOutcome {
        /**
         * Receiver accepted the load AND the phone's media server saw
         * the receiver fetch the hosted URL. Both signals are required:
         * load-accept alone leaves false positives whenever a LAN
         * firewall keeps the display from reaching the phone (the load
         * command goes via Google's cloud relay and still succeeds, but
         * the bytes never transfer).
         */
        data object Success : CastWorkerOutcome

        /**
         * Receiver ACK'd the load command but never fetched the hosted
         * URL within the fetch window — the load surface succeeded,
         * the bytes never crossed. Distinct from [Failed] because the
         * caller needs to know the load did succeed (so the "last
         * published" timestamp still advances) even though "last
         * fetched" doesn't.
         * Typically a LAN firewall between the display and the phone.
         */
        data class PublishedButNotFetched(val reason: String) : CastWorkerOutcome

        /**
         * Route id no longer resolves on the LAN — device fully off,
         * mDNS lapsed, or the user replaced the saved id with one
         * that's no longer discoverable. Surfaces as a "Smart device
         * not reachable" status row in Settings.
         */
        data object SkippedNoRoute : CastWorkerOutcome

        /**
         * The picked device is an audio-only speaker but no spoken
         * forecast was available (Gemini unavailable or synth failed).
         * A display would still get the silent image-only carrier, but a
         * speaker has nothing to play, so the load is skipped. Distinct
         * from [SkippedNoRoute] (the device was reachable) and from
         * [Failed] (nothing actually broke) so Settings can explain that
         * the user needs a Gemini voice to cast to a speaker.
         */
        data object SkippedNoAudio : CastWorkerOutcome

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
     * @param wav WAV-wrapped audio. On a **display** route, pass
     *   [silentWavStub] when no real audio is available (Gemini
     *   unavailable or synth failed) — Default Media Receiver requires
     *   some media to load, so the silent stub is the image-only path's
     *   loading carrier and the outfit image still shows. On a
     *   **speaker** route a silent stub is meaningless, so the no-audio
     *   case ([hasRealAudio] false) skips the load entirely — see
     *   [CastWorkerOutcome.SkippedNoAudio].
     * @param hasRealAudio false on the image-only path, true when
     *   [wav] carries the synth result. Selects the speaker no-audio
     *   skip, and the worker reads it to decide whether
     *   [castSkipPhoneSpeech] applies (image-only / skipped casts don't
     *   suppress phone speech because the device isn't doing the audio).
     * @param png the outfit image to mux into the MP4 on a **display**
     *   route. Null is allowed: a speaker plays audio only and ignores
     *   it, so a missing render doesn't block the spoken forecast there;
     *   only the display (MP4) branch requires it and fails with
     *   "Outfit render unavailable" when it's absent.
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
        hasRealAudio: Boolean,
        png: ByteArray?,
        title: String,
        subtitle: String?,
        discoveryTimeoutMs: Long = 5_000,
        sessionTimeoutMs: Long = 10_000,
        loadTimeoutMs: Long = 15_000,
        fetchTimeoutMs: Long = 15_000,
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
            val resolved = withContext(Dispatchers.Main.immediate) {
                val route = findRoute(routeId, discoveryTimeoutMs)
                    ?: return@withContext RouteResolution.NotFound
                val session = ensureSession(route, sessionTimeoutMs)
                val client = session.remoteMediaClient
                    ?: return@withContext RouteResolution.NoRemoteMediaClient
                RouteResolution.Ready(ResolvedRoute(client, classifyRoute(route)))
            }
            val ready = when (resolved) {
                RouteResolution.NotFound -> {
                    DiagLog.w(
                        TAG,
                        "Cast route %s not discovered within %sms; skipping cast.",
                        routeId,
                        discoveryTimeoutMs,
                    )
                    return CastWorkerOutcome.SkippedNoRoute
                }
                // Distinct from NotFound: the session *started* — the device
                // was reachable and is now sitting on the receiver splash —
                // but exposed no RemoteMediaClient to load into. Reporting
                // it as SkippedNoRoute pointed the Settings status row at
                // the network when the problem is the playback layer
                // (matches castToSavedRoute's NoRemoteMediaClient failure).
                RouteResolution.NoRemoteMediaClient -> {
                    DiagLog.w(TAG, "Cast route %s session started but exposed no RemoteMediaClient.", routeId)
                    return CastWorkerOutcome.Failed(
                        CastFailure.NoRemoteMediaClient.message ?: "Smart device did not accept playback",
                    )
                }
                is RouteResolution.Ready -> resolved.route
            }
            val client = ready.client
            val host = resolveLanIp(context)
                ?: run {
                    DiagLog.w(TAG, "No LAN IPv4 on the phone; cannot host cast media.")
                    return CastWorkerOutcome.Failed(CastFailure.NoLanAddress.message ?: "No LAN IP")
                }
            // Concrete URLs are sensitive (path token), so log only the
            // origin + bytes so a bug report can correlate the worker's
            // publish with later route-handler entries without leaking
            // the secret that gates the buffer.
            val request = when (mediaPlanFor(ready.deviceClass, hasRealAudio = hasRealAudio)) {
                CastMediaPlan.SkipNoAudio -> {
                    // Audio-only device with no spoken forecast: a silent
                    // track would just be dead air on a speaker. Skip the
                    // load (and leave phone speech untouched — the worker
                    // only suppresses it on a Success with real audio).
                    DiagLog.i(
                        TAG,
                        "Cast route %s is a speaker but no spoken audio is available; skipping cast.",
                        routeId,
                    )
                    return CastWorkerOutcome.SkippedNoAudio
                }
                CastMediaPlan.Wav -> {
                    val urls = server.publish(host = host, media = wav, kind = CastMediaKind.WAV)
                    DiagLog.i(
                        TAG,
                        "Cast hosting %sB wav on http://%s:%s/<token>/insight.wav for speaker " +
                            "route %s; sending load command.",
                        wav.size,
                        host,
                        server.port(),
                        routeId,
                    )
                    MediaLoadRequestData.Builder()
                        .setMediaInfo(buildAudioMediaInfo(urls.url, title, subtitle))
                        .build()
                }
                CastMediaPlan.Mp4 -> {
                    // The display path needs the outfit image to mux into
                    // the MP4. A speaker would have taken the Wav branch
                    // above; reaching Mp4 with no PNG means a display (or
                    // unknown route) with nothing to show, so fail rather
                    // than load a blank video.
                    if (png == null) {
                        DiagLog.w(
                            TAG,
                            "Cast route %s needs an outfit image but none was rendered; skipping cast.",
                            routeId,
                        )
                        return CastWorkerOutcome.Failed("Outfit render unavailable")
                    }
                    val paddedWav = padWavToMinimumDuration(wav)
                    val mp4 = withContext(Dispatchers.Default) { Mp4Encoder.encode(png, paddedWav) }
                    val urls = server.publish(host = host, media = mp4, kind = CastMediaKind.MP4)
                    DiagLog.i(
                        TAG,
                        "Cast hosting %sB mp4 on http://%s:%s/<token>/insight.mp4 for route %s; " +
                            "sending load command.",
                        mp4.size,
                        host,
                        server.port(),
                        routeId,
                    )
                    MediaLoadRequestData.Builder()
                        .setMediaInfo(buildMediaInfo(urls.url, title, subtitle))
                        .build()
                }
            }
            // Await the receiver's load result rather than reporting
            // Success on enqueue. Without this, a receiver that
            // accepts the request but later fails to fetch / decode
            // (codec mismatch, LAN URL unreachable) would clear the
            // status row AND — when castSkipPhoneSpeech is on —
            // suppress phone TTS off a premature "success", leaving
            // the user with no spoken forecast.
            val result = awaitLoad(client, request, loadTimeoutMs)
                ?: run {
                    DiagLog.w(TAG, "Cast receiver did not respond to load within %sms.", loadTimeoutMs)
                    return CastWorkerOutcome.Failed("Smart device did not respond to the load request")
                }
            if (!result.status.isSuccess) {
                val message = result.status.statusMessage?.takeIf { it.isNotBlank() }
                    ?: "Smart device rejected the media (code ${result.status.statusCode})"
                DiagLog.w(TAG, "Cast load rejected by receiver: %s", message)
                return CastWorkerOutcome.Failed(message)
            }
            DiagLog.i(TAG, "Cast load accepted by receiver; awaiting URL fetch (timeout=%sms).", fetchTimeoutMs)
            // Cast load commands route through Google's cloud relay, so a
            // receiver on the LAN can ACK the load even when it can't
            // reach the phone's media server (e.g. a firewall rule
            // blocking the receiver → phone direction). The only direct
            // signal that the bytes actually transferred is a successful
            // GET on the hosted URL — wait for it before claiming
            // Success. Distinguish PublishedButNotFetched from Failed so
            // the "last published" timestamp still advances and the
            // user can tell "we never managed to publish" apart from
            // "we published but the display couldn't reach us."
            if (!server.awaitFetch(fetchTimeoutMs)) {
                DiagLog.w(
                    TAG,
                    "Cast receiver accepted load but never fetched the hosted URL within %sms — " +
                        "likely a LAN firewall between the display and the phone.",
                    fetchTimeoutMs,
                )
                return CastWorkerOutcome.PublishedButNotFetched(
                    "Smart device didn't fetch the media — check that it can reach the phone on the LAN.",
                )
            }
            DiagLog.i(TAG, "Cast receiver fetched the hosted URL; cast confirmed fetched.")
            CastWorkerOutcome.Success
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // CancellationException must propagate so WorkManager
            // stops unwind cleanly — same convention as
            // MqttPublisher.preparePublish.
            throw ce
        } catch (failure: CastFailure) {
            DiagLog.w(TAG, failure, "Cast load failed: %s", failure.message)
            CastWorkerOutcome.Failed(failure.message ?: "Cast failed")
        } catch (t: Throwable) {
            DiagLog.w(TAG, t, "Cast load failed unexpectedly")
            // An unexpected failure on the cast hot path (media-server bind,
            // LAN host/encode step) can surface a raw socket exception; lead
            // with a network hint when we recognise one so "couldn't cast" is
            // actionable rather than an opaque stack-trace message.
            val hint = classifyNetworkError(t)?.let { castNetworkHintFor(it) }
            CastWorkerOutcome.Failed(hint ?: t.message ?: "Cast failed")
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
        val routeAlreadySelected = router.selectedRoute.id == route.id
        if (!routeAlreadySelected) {
            // selectRoute tears down any existing session on a different
            // route and starts a new one — the controller's [sessionListener]
            // catches the old session's onSessionEnded and stops the media
            // server before we publish the new bytes below.
            router.selectRoute(route)
        }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SessionManagerListener<CastSession> {
                    // Unregister on every terminal callback, not just on
                    // cancellation: the SessionManager lives for the process,
                    // so a listener left behind after a *successful* start
                    // accumulates — one per scheduled cast, each retaining its
                    // continuation and invoked on the main thread for all
                    // future session events.
                    private fun complete(block: () -> Unit) {
                        if (!cont.isActive) return
                        sessionManager.removeSessionManagerListener(this, CastSession::class.java)
                        block()
                    }
                    override fun onSessionStarting(session: CastSession) {}
                    override fun onSessionStarted(session: CastSession, sessionId: String) {
                        complete { cont.resume(session) }
                    }
                    override fun onSessionStartFailed(session: CastSession, error: Int) {
                        complete { cont.resumeWithException(CastFailure.SessionStartFailed(error)) }
                    }
                    override fun onSessionEnding(session: CastSession) {}
                    override fun onSessionEnded(session: CastSession, error: Int) {}
                    override fun onSessionResuming(session: CastSession, sessionId: String) {}
                    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                        complete { cont.resume(session) }
                    }
                    override fun onSessionResumeFailed(session: CastSession, error: Int) {
                        complete { cont.resumeWithException(CastFailure.SessionStartFailed(error)) }
                    }
                    override fun onSessionSuspended(session: CastSession, reason: Int) {}
                }
                sessionManager.addSessionManagerListener(listener, CastSession::class.java)
                cont.invokeOnCancellation {
                    sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
                }
                if (routeAlreadySelected) {
                    // Close the registration race: a session can connect
                    // between the [existing] snapshot above and the listener
                    // registering, in which case no further callback fires and
                    // the wait would time out despite a live session. Only
                    // safe to read when no selectRoute teardown is in flight
                    // (the route was already selected) — right after a
                    // selectRoute the still-connected *old* session could
                    // otherwise be returned and the insight would load on the
                    // wrong device.
                    sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { live ->
                        if (cont.isActive) {
                            sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
                            cont.resume(live)
                        }
                    }
                }
            }
        } ?: throw CastFailure.SessionStartTimeout
    }

    /**
     * Typed failures the test-cast path can produce. The caller maps
     * each to a user-facing string and persists via
     * [app.clothescast.data.SettingsRepository.setCastResult].
     * [FetchNotConfirmed] is special: load succeeded, so the caller
     * still advances the "last published" timestamp even while recording
     * an error.
     */
    sealed class CastFailure(message: String) : Exception(message) {
        data object DeviceNotFound : CastFailure("Smart device not found on the network.")
        data object SessionStartTimeout : CastFailure("Smart device did not respond in time.")
        data class SessionStartFailed(val errorCode: Int) :
            CastFailure("Smart device rejected the session (code $errorCode).")
        data object NoRemoteMediaClient : CastFailure("Smart device did not accept playback.")
        data object NoLanAddress : CastFailure("Phone has no Wi-Fi address — connect to the same network as the smart device.")
        data object LoadResponseTimeout : CastFailure("Smart device did not respond to the load request.")
        data class LoadRejected(val reason: String) : CastFailure(reason)
        // A speaker has no screen, so the image-only fallback used for
        // displays (silent track + outfit picture) has nothing to play.
        // Surface the missing voice instead of casting dead air.
        data object NoAudioForSpeaker : CastFailure(
            "Add a Gemini voice to cast to a speaker — there's nothing to play without spoken audio.",
        )
        // The bytes never transferred even though the receiver ACK'd the
        // load — typically a LAN firewall blocking the device → phone
        // direction. Distinguished from the load-level failures because
        // the symptom is "loading spinner forever" rather than an error
        // code from the receiver.
        data object FetchNotConfirmed : CastFailure(
            "Smart device didn't fetch the media — check that it can reach the phone on the LAN.",
        )
    }

    companion object {
        private const val TAG = "CastInsightController"

        /**
         * Plain-English hint for an *unexpected* cast failure that bottomed out
         * in a connection-layer error (the phone's media server couldn't bind /
         * be reached on the LAN), or null when the cause isn't a recognisable
         * network problem so the caller falls back to the raw message. The typed
         * [CastFailure] cases already carry their own network wording — this only
         * covers the generic catch-all path. `internal` for test visibility.
         */
        internal fun castNetworkHintFor(kind: NetworkErrorKind?): String? = when (kind) {
            NetworkErrorKind.UNKNOWN_HOST,
            NetworkErrorKind.NO_ROUTE,
            NetworkErrorKind.CONNECTION_REFUSED,
            NetworkErrorKind.TIMEOUT,
            ->
                "Couldn't reach the smart device on the network — make sure the " +
                    "phone and the device are on the same Wi-Fi and nothing's blocking the LAN."
            NetworkErrorKind.TLS ->
                "Couldn't establish a secure connection to the smart device on the network."
            null -> null
        }

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
            // WavEncoder writes a mono header unconditionally, so re-encoding
            // a multi-channel payload here would relabel stereo as mono —
            // half-speed playback with corrupted interleave. No producer
            // sends stereo today (Gemini TTS is mono); if one ever does,
            // skip the padding rather than corrupt the audio.
            if (info.channels != 1) {
                DiagLog.w(
                    TAG,
                    "Skipping minimum-duration pad for %s-channel WAV; WavEncoder only writes mono headers.",
                    info.channels,
                )
                return wav
            }
            val minBytes = info.sampleRate * MIN_CAST_DURATION_SECONDS * 2
            if (info.pcm.size >= minBytes) return wav
            val padded = ByteArray(minBytes)
            info.pcm.copyInto(padded)
            return WavEncoder.encode(PcmAudio(bytes = padded, sampleRate = info.sampleRate))
        }

        /** Display path: the muxed outfit image + audio as a movie. */
        internal fun buildMediaInfo(
            url: String,
            title: String,
            subtitle: String?,
        ): MediaInfo {
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, title)
                if (!subtitle.isNullOrBlank()) {
                    putString(MediaMetadata.KEY_SUBTITLE, subtitle)
                }
            }
            return MediaInfo.Builder(url)
                .setContentType("video/mp4")
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(metadata)
                .build()
        }

        /**
         * Speaker path: the spoken forecast as a bare audio track. No
         * outfit image — an audio-only device has no screen to show it,
         * and skipping the poster keeps the PNG on-device (it never
         * crosses the LAN to the receiver) and keeps [CastMediaServer]
         * single-buffer.
         */
        internal fun buildAudioMediaInfo(
            url: String,
            title: String,
            subtitle: String?,
        ): MediaInfo {
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(MediaMetadata.KEY_TITLE, title)
                if (!subtitle.isNullOrBlank()) {
                    putString(MediaMetadata.KEY_SUBTITLE, subtitle)
                }
            }
            return MediaInfo.Builder(url)
                .setContentType("audio/wav")
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(metadata)
                .build()
        }
    }

    /** Resolved [RemoteMediaClient] for a route plus its [CastDeviceClass]. */
    private data class ResolvedRoute(
        val client: RemoteMediaClient,
        val deviceClass: CastDeviceClass,
    )

    /**
     * Outcome of the main-thread route/session/client resolution in
     * [castWithPreparedMedia]. Two distinct failure shapes so the caller
     * can report "device not reachable" (route never discovered) apart
     * from "device reachable but playback unavailable" (session started,
     * no [RemoteMediaClient]).
     */
    private sealed interface RouteResolution {
        data object NotFound : RouteResolution
        data object NoRemoteMediaClient : RouteResolution
        data class Ready(val route: ResolvedRoute) : RouteResolution
    }
}
