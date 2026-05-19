package app.clothescast.cast

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
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
        val urls = server.publish(host = "127.0.0.1", video = mp4)

        val resp = fetch(urls.video)
        resp.status shouldBe 200
        resp.contentType shouldBe "video/mp4"
        resp.body shouldBe mp4
    }

    @Test
    fun `URL carries a 32 hex character path token`() {
        val urls = server.publish(host = "127.0.0.1", video = ByteArray(0))
        // http://127.0.0.1:<port>/<32 hex>/insight.mp4
        urls.video shouldMatch Regex("""http://127\.0\.0\.1:\d+/[0-9a-f]{32}/insight\.mp4""")
    }

    @Test
    fun `republish rotates the path token and old URLs stop serving`() {
        val first = server.publish(host = "127.0.0.1", video = "first".toByteArray())
        val second = server.publish(host = "127.0.0.1", video = "second".toByteArray())

        second.video shouldNotBe first.video

        // Old URL 404s — the previous publish's token no longer matches.
        fetch(first.video).status shouldBe 404

        // New URL serves the new buffer on the same port.
        fetch(second.video).body shouldBe "second".toByteArray()
        URL(second.video).port shouldBe URL(first.video).port
    }

    @Test
    fun `requests with a wrong token return 404`() {
        val mp4 = byteArrayOf(1, 2, 3, 4)
        val urls = server.publish(host = "127.0.0.1", video = mp4)
        val origin = URL(urls.video).let { "http://${it.host}:${it.port}" }

        fetch("$origin/deadbeefdeadbeefdeadbeefdeadbeef/insight.mp4").status shouldBe 404
    }

    @Test
    fun `unknown paths return 404`() {
        val urls = server.publish(host = "127.0.0.1", video = ByteArray(0))
        val origin = URL(urls.video).let { "http://${it.host}:${it.port}" }

        fetch("$origin/nope").status shouldBe 404
    }

    @Test
    fun `stop releases the port`() {
        val urls = server.publish(host = "127.0.0.1", video = ByteArray(0))
        val port = server.port()
        (port > 0) shouldBe true
        URL(urls.video).port shouldBe port

        server.stop()
        server.port() shouldBe 0
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
