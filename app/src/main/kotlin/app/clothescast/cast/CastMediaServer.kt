package app.clothescast.cast

import app.clothescast.diag.DiagLog
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Tiny HTTP server that exposes one media buffer to a Cast receiver on
 * the same LAN. Cast receivers fetch media by URL — they don't accept
 * raw bytes pushed from the sender — so the phone briefly hosts the
 * payload at a per-publish, token-gated path while the cast is active.
 *
 * Two media shapes flow through here ([CastMediaKind]): a display gets an
 * MP4 (outfit image as a static video track + TTS audio), so the Default
 * Media Receiver renders the outfit full-screen while the audio plays (a
 * metadata WebImage gets framed by audio-player chrome — a video track
 * doesn't); an audio-only speaker gets a bare WAV, since it has no screen
 * to show the image on.
 *
 * Lifecycle:
 *   1. [publish] writes the media buffer into memory, rotates a 128-bit
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
 *   - [stop] and [publish] are `@Synchronized`: in practice they arrive
 *     on different threads — [publish] on the worker's dispatcher (the
 *     controller deliberately keeps it off the main thread), [stop] on
 *     the main thread via the session listener's onSessionEnded — and
 *     a previous session's teardown can land mid-publish. The lock
 *     keeps a stop from clearing the token/buffer a publish just
 *     minted a URL for, and gives `server`/`port` (plain fields) their
 *     memory visibility.
 */
class CastMediaServer {

    private var server: EmbeddedServer<*, *>? = null
    private var port: Int = 0

    @Volatile
    private var buffer: ByteArray? = null

    @Volatile
    private var kind: CastMediaKind = CastMediaKind.MP4

    @Volatile
    private var pathToken: String = ""

    // Completes when the route handler finishes serving the bytes for the
    // active token — i.e. a Cast receiver successfully GET'd the URL. The
    // controller awaits this to confirm the bytes actually reached the
    // display: `RemoteMediaClient.load` returning success only proves the
    // receiver accepted the load command, not that it could reach the
    // phone-hosted URL (a LAN firewall between display and phone breaks
    // the latter while letting the former still succeed). Rotated on each
    // [publish] alongside the token; the handler captures the deferred at
    // the moment it matches a token so a later publish-while-serving still
    // completes the awaiter that originally issued that URL.
    @Volatile
    private var fetched: CompletableDeferred<Unit>? = null

    /**
     * Replaces the in-memory media buffer, rotates the path token,
     * ensures the server is running, and returns the URL the receiver
     * should fetch.
     *
     * @param host the LAN address the receiver should reach the phone on
     *   (e.g. "192.168.1.42"). The caller resolves it from
     *   `ConnectivityManager.getLinkProperties`.
     * @param kind the media shape — sets the URL suffix and the
     *   `Content-Type` the handler serves it with.
     */
    @Synchronized
    fun publish(host: String, media: ByteArray, kind: CastMediaKind = CastMediaKind.MP4): MediaUrl {
        this.buffer = media
        this.kind = kind
        this.pathToken = generateToken()
        this.fetched = CompletableDeferred()
        if (server == null) start()
        return MediaUrl(url = "http://$host:$port/$pathToken${kind.suffix}")
    }

    /**
     * Suspends until the active [publish]'s URL has been fetched by a
     * Cast receiver (i.e. the route handler completed a 200 response for
     * the matching token) or [timeoutMs] elapses. Returns true on a
     * confirmed fetch, false on timeout or when no publish has been
     * issued yet.
     *
     * A second call after a fetch returns true immediately (the deferred
     * is already completed). A call after a [stop] returns false.
     */
    suspend fun awaitFetch(timeoutMs: Long): Boolean {
        val d = fetched ?: return false
        return withTimeoutOrNull(timeoutMs) {
            d.await()
            true
        } ?: false
    }

    /** Stops the server, if running, and clears the buffered media + the path token. */
    @Synchronized
    fun stop() {
        val srv = server
        server = null
        buffer = null
        pathToken = ""
        port = 0
        // Drop the reference so any in-flight [awaitFetch] times out
        // rather than waiting forever after the session ends.
        fetched = null
        // Engine shutdown blocks the calling thread for up to its timeout,
        // and stop() arrives on the main thread (the Cast session listener's
        // onSessionEnded) — a 500 ms stall there is dropped frames right as
        // the user disconnects. Hand the engine to a background thread; the
        // token and buffer are already cleared above, so a request slipping
        // in while it winds down 404s, and a re-publish starts a fresh
        // server on a fresh port without waiting for this one.
        if (srv != null) {
            thread(name = "CastMediaServer.stop", isDaemon = true) {
                // An uncaught throw on a bare thread kills the process on
                // Android; a failed engine shutdown isn't worth that.
                runCatching { srv.stop(gracePeriodMillis = 0, timeoutMillis = 500) }
                    .onFailure { DiagLog.w(TAG, "Cast media server engine shutdown failed", it) }
            }
        }
    }

    /** Currently-bound port, or 0 if the server isn't running. Test hook. */
    @Synchronized
    internal fun port(): Int = port

    private fun start() {
        val srv = embeddedServer(CIO, port = 0) {
            // Cast receivers probe MP4s with Range GETs — MediaMuxer writes
            // the moov atom after mdat (no faststart), so the player seeks
            // to the file tail before the first frame. Without 206 support
            // every probe gets the whole body from byte 0: buffering the
            // full resource at best, a failed load on stricter receiver
            // firmware at worst. PartialContent turns the channel-backed
            // response below into a range-aware one; AutoHeadResponse
            // answers the HEAD some players lead with (it 404'd before).
            install(PartialContent)
            install(AutoHeadResponse)
            routing {
                // Wildcard filename so the same route serves whichever
                // suffix the active [CastMediaKind] minted (insight.mp4 /
                // insight.wav). The receiver only ever requests the exact
                // URL we handed it, so the token gate — not the filename —
                // is what authorizes the serve.
                get("/{token}/{file}") {
                    val buf = buffer
                    val incoming = call.parameters["token"]
                    // Capture the active buffer kind + fetch deferred at
                    // match time rather than re-reading post-respond —
                    // a concurrent publish would otherwise serve the new
                    // content type or redirect the signal onto the new
                    // awaiter instead of the one whose URL was just served.
                    val capturedKind = kind
                    val capturedFetched = fetched
                    if (buf != null && tokenMatches(incoming)) {
                        // ReadChannelContent, not respondBytes: PartialContent
                        // only rewrites channel-backed content into 206s —
                        // a plain ByteArrayContent bypasses it and every
                        // Range probe would get the whole body as a 200.
                        call.respond(MediaBufferContent(buf, capturedKind.contentType))
                        // Only a real GET confirms the fetch: AutoHeadResponse
                        // routes HEAD probes through this handler with the
                        // body discarded, and a HEAD moves no media bytes —
                        // completing the deferred on one would fake the
                        // "bytes actually crossed the LAN" signal awaitFetch
                        // exists to provide. (A Range GET still counts; bytes
                        // flow on it.) Read the raw method off request.local:
                        // AutoHeadResponse rewrites the origin method to GET
                        // before the handler runs (that's how the get() route
                        // matches at all), so request.httpMethod reads GET
                        // even for a HEAD probe — verified against Ktor 3.5.
                        if (call.request.local.method == HttpMethod.Get) {
                            capturedFetched?.complete(Unit)
                        }
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }
        srv.start(wait = false)
        // Bind port 0 and read the kernel-assigned port back, rather than
        // probing a free port with a throwaway ServerSocket and re-binding
        // it — any other socket on the device could claim the probed port
        // in that window and fail the publish. resolvedConnectors suspends
        // until the engine is actually bound; [publish] is documented
        // off-main (see Threading above), so the brief blocking wait here
        // replaces the equally blocking probe bind it removes.
        port = runBlocking { srv.engine.resolvedConnectors().first().port }
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

    data class MediaUrl(val url: String)

    /**
     * Channel-backed wrapper for the in-memory media buffer. Exists so
     * [PartialContent] can slice it: the plugin only rewrites
     * [OutgoingContent.ReadChannelContent] responses into 206s (its
     * range-cut path goes through [readFrom]), so a `respondBytes`
     * ByteArrayContent would silently opt the serve out of range support.
     */
    private class MediaBufferContent(
        private val bytes: ByteArray,
        override val contentType: ContentType,
    ) : OutgoingContent.ReadChannelContent() {
        override val contentLength: Long = bytes.size.toLong()
        override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
    }

    companion object {
        private const val TAG = "CastMediaServer"

        // 128 bits of entropy in the URL path. Enough that scanning the
        // open ephemeral port for the buffer is computationally hopeless
        // even on a fast LAN; same order as a UUID4.
        private const val TOKEN_BYTES = 16
    }
}

/**
 * The media shape a [CastMediaServer.publish] hosts. Pairs the URL suffix
 * (so the receiver fetches `…/insight.mp4` vs `…/insight.wav`) with the
 * `Content-Type` the handler serves it under. MP4 carries the muxed
 * outfit image + audio for smart displays; WAV carries the bare spoken
 * forecast for audio-only speakers.
 */
enum class CastMediaKind(val suffix: String, val contentType: ContentType) {
    MP4("/insight.mp4", ContentType("video", "mp4")),
    WAV("/insight.wav", ContentType("audio", "wav")),
}
