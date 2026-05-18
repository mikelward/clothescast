package app.clothescast.cast

import android.content.Context
import android.net.Uri
import app.clothescast.core.data.tts.GeminiTtsClient
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.diag.DiagLog
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
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Drives a Cast session for the Today screen: synthesises the insight via
 * Gemini TTS, wraps the PCM in WAV, hands the buffers to [CastMediaServer]
 * for LAN hosting, and tells the connected Cast device to load the URLs.
 *
 * One instance per [ClothesCastApplication]. The Today screen registers it
 * via [bind] while in the foreground and calls [cast] from its own session
 * listener once a Cast session is established.
 *
 * The session listener owned here is the *fallback* lifecycle owner: it
 * just stops the media server when a session ends, so we're not leaving
 * an open port behind. Auto-publishing on session start happens at the UI
 * layer because that's where the current Insight state lives.
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
     * Casts the given insight to the currently-connected Cast device, if any.
     * Returns [CastResult] describing why a cast was skipped, or
     * [CastResult.Loaded] on success. Synthesis runs on [applicationScope] —
     * the returned [Job] lets the caller observe completion or cancel.
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
