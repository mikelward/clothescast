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
 * Tiny HTTP server that exposes one outfit PNG and one TTS WAV to a Cast
 * receiver on the same LAN. Cast receivers fetch media by URL — they
 * don't accept raw bytes pushed from the sender — so the phone briefly
 * hosts the two payloads at per-publish, token-gated paths while the
 * cast is active.
 *
 * Lifecycle:
 *   1. [publish] writes the audio + image buffers into memory, rotates a
 *      128-bit path token, and lazily starts the server. Returns HTTP
 *      URLs the receiver can fetch.
 *   2. The caller hands those URLs to a `MediaInfo` and loads them via
 *      `RemoteMediaClient.load(...)`.
 *   3. Subsequent [publish] calls swap the buffers and the path token in
 *      place — old URLs stop serving and the receiver picks up the new
 *      ones from the next `load(...)`.
 *   4. [stop] when the cast ends; the server thread exits, the buffers
 *      drop out of scope, and the token is cleared so any straggling
 *      request 404s.
 *
 * Privacy / threat model:
 *   - The server binds to the LAN interface the OS already grants the
 *     app via [android.Manifest.permission.INTERNET]. There's no NAT
 *     traversal and no auth beyond the per-publish path token.
 *   - **Path-token gating.** Each [publish] mints a fresh 128-bit
 *     [SecureRandom] secret and embeds it as the first path segment
 *     (e.g. `/<32 hex chars>/insight.wav`). Anything else — including
 *     known-suffix probes and the previous publish's token — returns
 *     404. A co-LAN device that scans the ephemeral port for open
 *     services can't enumerate the buffers without observing the
 *     issued URL.
 *   - Cleartext HTTP-to-LAN is the standard Cast path (receivers won't
 *     trust a self-signed cert).
 *   - Nothing is persisted: the buffers live entirely in heap, are
 *     dropped on [stop], and rotate on every [publish].
 *
 * Threading:
 *   - Ktor CIO runs its accept / handler loop on its own threads. The
 *     buffer references and the token are `@Volatile` so a handler
 *     racing a [publish] sees one set or the other, never a torn write.
 *   - [start] / [stop] / [publish] are intended to be called from a
 *     single thread (the cast session listener); concurrent calls
 *     aren't guarded.
 */
class CastMediaServer {

    private var server: EmbeddedServer<*, *>? = null
    private var port: Int = 0

    @Volatile
    private var audio: ByteArray? = null

    @Volatile
    private var image: ByteArray? = null

    @Volatile
    private var pathToken: String = ""

    /**
     * Replaces the in-memory audio + image buffers, rotates the path
     * token, ensures the server is running, and returns the URLs the
     * receiver should fetch.
     *
     * @param host the LAN address the receiver should reach the phone on
     *   (e.g. "192.168.1.42"). The caller resolves it from
     *   `ConnectivityManager.getLinkProperties`.
     */
    fun publish(host: String, audio: ByteArray, image: ByteArray): MediaUrls {
        this.audio = audio
        this.image = image
        this.pathToken = generateToken()
        if (server == null) start()
        return MediaUrls(
            audio = "http://$host:$port/$pathToken$AUDIO_SUFFIX",
            image = "http://$host:$port/$pathToken$IMAGE_SUFFIX",
        )
    }

    /** Stops the server, if running, and clears all buffered media + the path token. */
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        server = null
        audio = null
        image = null
        pathToken = ""
        port = 0
    }

    /** Currently-bound port, or 0 if the server isn't running. Test hook. */
    internal fun port(): Int = port

    private fun start() {
        port = ServerSocket(0).use { it.localPort }
        val srv = embeddedServer(CIO, port = port) {
            routing {
                get("/{token}/insight.wav") {
                    val buf = audio
                    val incoming = call.parameters["token"]
                    if (buf != null && tokenMatches(incoming)) {
                        call.respondBytes(buf, AUDIO_CONTENT_TYPE)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
                get("/{token}/outfit.png") {
                    val buf = image
                    val incoming = call.parameters["token"]
                    if (buf != null && tokenMatches(incoming)) {
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

    data class MediaUrls(val audio: String, val image: String)

    companion object {
        // 128 bits of entropy in the URL path. Enough that scanning the
        // open ephemeral port for the buffers is computationally hopeless
        // even on a fast LAN; same order as a UUID4.
        private const val TOKEN_BYTES = 16
        private const val AUDIO_SUFFIX = "/insight.wav"
        private const val IMAGE_SUFFIX = "/outfit.png"
        private val AUDIO_CONTENT_TYPE = ContentType("audio", "wav")
    }
}
