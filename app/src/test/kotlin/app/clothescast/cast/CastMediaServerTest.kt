package app.clothescast.cast

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URL

class CastMediaServerTest {

    private val server = CastMediaServer()

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `serves the published MP4 at the video path`() {
        // First eight bytes of an MP4: an `ftyp` box header. Magic is
        // arbitrary here — we just need bytes to round-trip.
        val mp4 = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70)
        val urls = server.publish(host = "127.0.0.1", media = mp4)

        val resp = fetch(urls.url)
        resp.status shouldBe 200
        resp.contentType shouldBe "video/mp4"
        resp.body shouldBe mp4
    }

    @Test
    fun `serves the published WAV with the audio content type at the wav path`() {
        // RIFF/WAVE header magic — arbitrary bytes; we just round-trip them.
        val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0)
        val urls = server.publish(host = "127.0.0.1", media = wav, kind = CastMediaKind.WAV)

        urls.url shouldMatch Regex("""http://127\.0\.0\.1:\d+/[0-9a-f]{32}/insight\.wav""")
        val resp = fetch(urls.url)
        resp.status shouldBe 200
        resp.contentType shouldBe "audio/wav"
        resp.body shouldBe wav
    }

    @Test
    fun `URL carries a 32 hex character path token`() {
        val urls = server.publish(host = "127.0.0.1", media = ByteArray(0))
        // http://127.0.0.1:<port>/<32 hex>/insight.mp4
        urls.url shouldMatch Regex("""http://127\.0\.0\.1:\d+/[0-9a-f]{32}/insight\.mp4""")
    }

    @Test
    fun `republish rotates the path token and old URLs stop serving`() {
        val first = server.publish(host = "127.0.0.1", media = "first".toByteArray())
        val second = server.publish(host = "127.0.0.1", media = "second".toByteArray())

        second.url shouldNotBe first.url

        // Old URL 404s — the previous publish's token no longer matches.
        fetch(first.url).status shouldBe 404

        // New URL serves the new buffer on the same port.
        fetch(second.url).body shouldBe "second".toByteArray()
        URL(second.url).port shouldBe URL(first.url).port
    }

    @Test
    fun `requests with a wrong token return 404`() {
        val mp4 = byteArrayOf(1, 2, 3, 4)
        val urls = server.publish(host = "127.0.0.1", media = mp4)
        val origin = URL(urls.url).let { "http://${it.host}:${it.port}" }

        fetch("$origin/deadbeefdeadbeefdeadbeefdeadbeef/insight.mp4").status shouldBe 404
    }

    @Test
    fun `unknown paths return 404`() {
        val urls = server.publish(host = "127.0.0.1", media = ByteArray(0))
        val origin = URL(urls.url).let { "http://${it.host}:${it.port}" }

        fetch("$origin/nope").status shouldBe 404
    }

    @Test
    fun `awaitFetch returns true once a receiver GETs the active URL`() = runBlocking<Unit> {
        val urls = server.publish(host = "127.0.0.1", media = byteArrayOf(1, 2, 3))

        // Await before the GET — the deferred must not be pre-completed.
        val awaiter = async { server.awaitFetch(timeoutMs = 5_000) }

        fetch(urls.url).status shouldBe 200

        awaiter.await() shouldBe true
    }

    @Test
    fun `awaitFetch returns false when no receiver fetches before the timeout`() = runTest {
        server.publish(host = "127.0.0.1", media = byteArrayOf(0))

        // Short timeout — nothing GETs the URL, so we expect a timeout
        // result rather than a hang. runTest's virtual time skips the
        // wall-clock delay so the test stays fast.
        server.awaitFetch(timeoutMs = 50) shouldBe false
    }

    @Test
    fun `awaitFetch returns false when no publish has been issued`() = runTest {
        server.awaitFetch(timeoutMs = 50) shouldBe false
    }

    @Test
    fun `awaitFetch is not satisfied by a wrong-token request`() = runBlocking<Unit> {
        val urls = server.publish(host = "127.0.0.1", media = byteArrayOf(7))
        val origin = URL(urls.url).let { "http://${it.host}:${it.port}" }

        fetch("$origin/deadbeefdeadbeefdeadbeefdeadbeef/insight.mp4").status shouldBe 404

        // A 404 doesn't count — only a successful serve of the active
        // token completes the deferred.
        server.awaitFetch(timeoutMs = 200) shouldBe false
    }

    @Test
    fun `awaitFetch returns true after a republish once the new URL is GET'd`() = runBlocking<Unit> {
        server.publish(host = "127.0.0.1", media = "first".toByteArray())
        // No GET on the first URL — its deferred never completes.

        val second = server.publish(host = "127.0.0.1", media = "second".toByteArray())
        val awaiter = async { server.awaitFetch(timeoutMs = 5_000) }

        fetch(second.url).body shouldBe "second".toByteArray()

        awaiter.await() shouldBe true
    }

    @Test
    fun `stop releases the port`() {
        val urls = server.publish(host = "127.0.0.1", media = ByteArray(0))
        val port = server.port()
        (port > 0) shouldBe true
        URL(urls.url).port shouldBe port

        server.stop()
        server.port() shouldBe 0
    }

    @Test
    fun `range requests are answered with 206 and the requested slice`() {
        // Cast receivers probe the MP4 tail for the moov atom (MediaMuxer
        // writes it after mdat) with a Range GET; the server must answer
        // 206 with just the slice, not the whole body from byte 0.
        val mp4 = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val urls = server.publish(host = "127.0.0.1", media = mp4)

        // Warm up the accept loop through the retrying helper first.
        fetch(urls.url).status shouldBe 200

        val conn = URL(urls.url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=6-")
        try {
            conn.responseCode shouldBe 206
            conn.inputStream.use { it.readBytes() } shouldBe byteArrayOf(6, 7, 8, 9)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `HEAD is answered without confirming the fetch`() = runBlocking<Unit> {
        // Some players lead with a HEAD probe. It must succeed (it 404'd
        // before AutoHeadResponse) but must NOT complete the fetch
        // confirmation — no media bytes crossed the LAN yet.
        val urls = server.publish(host = "127.0.0.1", media = byteArrayOf(1, 2, 3))
        fetch(urls.url).status shouldBe 200 // warm-up GET on the old token…
        val fresh = server.publish(host = "127.0.0.1", media = byteArrayOf(1, 2, 3))

        val conn = URL(fresh.url).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        try {
            conn.responseCode shouldBe 200
        } finally {
            conn.disconnect()
        }

        server.awaitFetch(timeoutMs = 100) shouldBe false
    }

    private data class Response(val status: Int, val contentType: String?, val body: ByteArray)

    private fun fetch(url: String): Response {
        // CIO binds asynchronously after start(wait = false); retry briefly so
        // the test isn't racing the accept loop coming up.
        var lastError: Exception? = null
        repeat(20) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 1_000
                conn.readTimeout = 1_000
                try {
                    val status = conn.responseCode
                    val body = if (status in 200..299) {
                        conn.inputStream.use { it.readBytes() }
                    } else {
                        ByteArray(0)
                    }
                    return Response(
                        status = status,
                        contentType = conn.contentType?.substringBefore(';'),
                        body = body,
                    )
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(25L * (attempt + 1))
            }
        }
        throw IllegalStateException("server did not accept connections in time", lastError)
    }
}
