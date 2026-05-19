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
import java.security.SecureRandom

/**
 * Tiny HTTP server that exposes one MP4 (outfit image as a static video
 * track + TTS audio as the audio track) to a Cast receiver on the same
 * LAN. Cast receivers fetch media by URL — they don't accept raw bytes
 * pushed from the sender — so the phone briefly hosts the payload at a
 * per-publish, token-gated path while the cast is active.
 *
 * Packing image + audio into a single MP4 lets the Default Media Receiver
 * render the outfit full-screen while the audio plays (a metadata
 * WebImage gets framed by audio-player chrome — a video track doesn't).
 *
 * Lifecycle:
 *   1. [publish] writes the MP4 buffer into memory, rotates a 128-bit
 *      path token, and lazily starts the server. Returns an HTTP URL
 *      the receiver can fetch.
 *   2. The caller hands that URL to a `MediaInfo` and loads it via
 *      `RemoteMediaClient.load(...)`.
 *   3. Subsequent [publish] calls swap the buffer and the path token in
 *      place — old URLs stop serving and the receiver picks up the new
 *      one from the next `load(...)`.
 *   4. [stop] when the cast ends; the server thread exits, the buffer
 *      drops out of scope, and the token is cleared so any straggling
 *      request 404s.
 *
 * Privacy / threat model:
 *   - The server binds to the LAN interface the OS already grants the
 *     app via [android.Manifest.permission.INTERNET]. There's no NAT
 *     traversal and no auth beyond the per-publish path token.
 *   - **Path-token gating.** Each [publish] mints a fresh 128-bit
 *     [SecureRandom] secret and embeds it as the first path segment
 *     (e.g. `/<32 hex chars>/insight.mp4`). Anything else — including
 *     known-suffix probes and the previous publish's token — returns
 *     404. A co-LAN device that scans the ephemeral port for open
 *     services can't enumerate the buffer without observing the
 *     issued URL.
 *   - Cleartext HTTP-to-LAN is the standard Cast path (receivers won't
 *     trust a self-signed cert).
 *   - Nothing is persisted: the buffer lives entirely in heap, is
 *     dropped on [stop], and rotates on every [publish].
 *
 * Threading:
 *   - Ktor CIO runs its accept / handler loop on its own threads. The
 *     buffer reference and the token are `@Volatile` so a handler
 *     racing a [publish] sees one set or the other, never a torn write.
 *   - [start] / [stop] / [publish] are intended to be called from a
 *     single thread (the cast session listener); concurrent calls
 *     aren't guarded.
 */
class CastMediaServer {

    private var server: EmbeddedServer<*, *>? = null
    private var port: Int = 0

    @Volatile
    private var video: ByteArray? = null

    @Volatile
    private var pathToken: String = ""

    /**
     * Replaces the in-memory MP4 buffer, rotates the path token, ensures
     * the server is running, and returns the URL the receiver should
     * fetch.
     *
     * @param host the LAN address the receiver should reach the phone on
     *   (e.g. "192.168.1.42"). The caller resolves it from
     *   `ConnectivityManager.getLinkProperties`.
     */
    fun publish(host: String, video: ByteArray): MediaUrl {
        this.video = video
        this.pathToken = generateToken()
        if (server == null) start()
        return MediaUrl(video = "http://$host:$port/$pathToken$VIDEO_SUFFIX")
    }

    /** Stops the server, if running, and clears the buffered media + the path token. */
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        server = null
        video = null
        pathToken = ""
        port = 0
    }

    /** Currently-bound port, or 0 if the server isn't running. Test hook. */
    internal fun port(): Int = port

    private fun start() {
        port = ServerSocket(0).use { it.localPort }
        val srv = embeddedServer(CIO, port = port) {
            routing {
                get("/{token}/insight.mp4") {
                    val buf = video
                    val incoming = call.parameters["token"]
                    if (buf != null && tokenMatches(incoming)) {
                        call.respondBytes(buf, VIDEO_CONTENT_TYPE)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }
        srv.start(wait = false)
        server = srv
    }

    /**
     * Constant-time-ish token check. Plain `==` works on Kotlin/JVM
     * strings, and the LAN-only attack surface doesn't warrant a
     * MessageDigest.isEqual dance, but require both sides be non-empty
     * so a cleared token (post-[stop]) doesn't accidentally accept an
     * empty path segment from a wildcard URL.
     */
    private fun tokenMatches(incoming: String?): Boolean =
        !incoming.isNullOrEmpty() && pathToken.isNotEmpty() && incoming == pathToken

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class MediaUrl(val video: String)

    companion object {
        // 128 bits of entropy in the URL path. Enough that scanning the
        // open ephemeral port for the buffer is computationally hopeless
        // even on a fast LAN; same order as a UUID4.
        private const val TOKEN_BYTES = 16
        private const val VIDEO_SUFFIX = "/insight.mp4"
        private val VIDEO_CONTENT_TYPE = ContentType("video", "mp4")
    }
}
