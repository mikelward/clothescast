package app.clothescast.cast

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.ServerSocket

/**
 * Tiny HTTP server that exposes one outfit PNG and one TTS WAV to a Cast
 * device on the same LAN. Chromecast receivers fetch media by URL — they
 * don't accept raw bytes pushed from the sender — so the phone briefly hosts
 * the two payloads at fixed paths while the cast session is active.
 *
 * Lifecycle:
 *   1. [publish] writes the audio + image buffers into memory and lazily
 *      starts the server. Returns HTTP URLs the Cast device can fetch.
 *   2. The caller hands those URLs to a Cast `MediaInfo` and loads them via
 *      `RemoteMediaClient.load(...)`.
 *   3. Subsequent [publish] calls swap the buffers in place — the same URLs
 *      keep working, useful when the user refreshes the cast mid-session.
 *   4. [stop] when the cast session ends; the server thread exits and the
 *      buffers drop out of scope.
 *
 * Privacy / threat model:
 *   - The server only binds to the LAN interface the OS already grants the
 *     app via [android.Manifest.permission.INTERNET]. There's no NAT
 *     traversal, no auth, and unknown paths return 404. The two buffers
 *     served are the same content the phone already speaks aloud and
 *     displays in the notification, so any co-LAN observer with the URL
 *     sees only what's already on the screen. Cleartext is fine here —
 *     Cast devices won't trust a self-signed cert, and HTTP-to-LAN is the
 *     standard path for sender-hosted media.
 *   - Nothing is persisted: the buffers live entirely in heap, are
 *     dropped on [stop], and rotate on every [publish].
 *
 * Threading:
 *   - Ktor CIO runs its accept / handler loop on its own threads. The two
 *     buffer references are `@Volatile` so a handler racing a [publish]
 *     sees one buffer or the other, never a torn write.
 *   - [start] / [stop] / [publish] are intended to be called from a single
 *     thread (the cast session listener); concurrent calls aren't guarded.
 */
class CastMediaServer {

    private var server: EmbeddedServer<*, *>? = null
    private var port: Int = 0

    @Volatile
    private var audio: ByteArray? = null

    @Volatile
    private var image: ByteArray? = null

    /**
     * Replaces the in-memory audio + image buffers, ensures the server is
     * running, and returns the URLs the Cast device should fetch.
     *
     * @param host the LAN address the Cast device should reach the phone on
     *   (e.g. "192.168.1.42"). The caller resolves it from
     *   `ConnectivityManager.getLinkProperties`.
     */
    fun publish(host: String, audio: ByteArray, image: ByteArray): MediaUrls {
        this.audio = audio
        this.image = image
        if (server == null) start()
        return MediaUrls(
            audio = "http://$host:$port$AUDIO_PATH",
            image = "http://$host:$port$IMAGE_PATH",
        )
    }

    /** Stops the server, if running, and clears all buffered media. */
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        server = null
        audio = null
        image = null
        port = 0
    }

    /** Currently-bound port, or 0 if the server isn't running. Test hook. */
    internal fun port(): Int = port

    private fun start() {
        port = ServerSocket(0).use { it.localPort }
        val srv = embeddedServer(CIO, port = port) {
            routing {
                get(AUDIO_PATH) {
                    val buf = audio
                    if (buf != null) {
                        call.respondBytes(buf, AUDIO_CONTENT_TYPE)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
                get(IMAGE_PATH) {
                    val buf = image
                    if (buf != null) {
                        call.respondBytes(buf, ContentType.Image.PNG)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }
        srv.start(wait = false)
        server = srv
    }

    data class MediaUrls(val audio: String, val image: String)

    companion object {
        const val AUDIO_PATH = "/insight.wav"
        const val IMAGE_PATH = "/outfit.png"
        private val AUDIO_CONTENT_TYPE = ContentType("audio", "wav")
    }
}
