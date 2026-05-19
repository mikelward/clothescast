package app.clothescast.cast

import android.content.Context
import android.net.Uri
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.clothescast.core.data.tts.GeminiTtsClient
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
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
                val wav = WavEncoder.encode(pcm)
                val urls = server.publish(host = host, audio = wav, image = outfitPng)
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
        val wav = WavEncoder.encode(pcm)
        val urls = server.publish(host = host, audio = wav, image = outfitPng)
        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(buildMediaInfo(urls, title, subtitle))
                .build(),
        )
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

        internal fun buildMediaInfo(
            urls: CastMediaServer.MediaUrls,
            title: String,
            subtitle: String?,
        ): MediaInfo {
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
                putString(MediaMetadata.KEY_TITLE, title)
                if (!subtitle.isNullOrBlank()) {
                    putString(MediaMetadata.KEY_SUBTITLE, subtitle)
                }
                addImage(WebImage(Uri.parse(urls.image)))
            }
            return MediaInfo.Builder(urls.audio)
                .setContentType("audio/wav")
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(metadata)
                .build()
        }
    }
}
