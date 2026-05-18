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
    fun `serves the published WAV at the audio path`() {
        val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4)
        val urls = server.publish(host = "127.0.0.1", audio = wav, image = ByteArray(0))

        val resp = fetch(urls.audio)
        resp.status shouldBe 200
        resp.contentType shouldBe "audio/wav"
        resp.body shouldBe wav
    }

    @Test
    fun `serves the published PNG at the image path`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val urls = server.publish(host = "127.0.0.1", audio = ByteArray(0), image = png)

        val resp = fetch(urls.image)
        resp.status shouldBe 200
        resp.contentType shouldBe "image/png"
        resp.body shouldBe png
    }

    @Test
    fun `URLs carry a 32 hex character path token`() {
        val urls = server.publish(host = "127.0.0.1", audio = ByteArray(0), image = ByteArray(0))
        // http://127.0.0.1:<port>/<32 hex>/insight.wav
        urls.audio shouldMatch Regex("""http://127\.0\.0\.1:\d+/[0-9a-f]{32}/insight\.wav""")
        urls.image shouldMatch Regex("""http://127\.0\.0\.1:\d+/[0-9a-f]{32}/outfit\.png""")
    }

    @Test
    fun `republish rotates the path token and old URLs stop serving`() {
        val first = server.publish(
            host = "127.0.0.1",
            audio = "first-audio".toByteArray(),
            image = "first-image".toByteArray(),
        )
        val second = server.publish(
            host = "127.0.0.1",
            audio = "second-audio".toByteArray(),
            image = "second-image".toByteArray(),
        )

        second.audio shouldNotBe first.audio
        second.image shouldNotBe first.image

        // Old URLs 404 — the previous publish's token no longer matches.
        fetch(first.audio).status shouldBe 404
        fetch(first.image).status shouldBe 404

        // New URLs serve the new buffers on the same port.
        fetch(second.audio).body shouldBe "second-audio".toByteArray()
        fetch(second.image).body shouldBe "second-image".toByteArray()
        URL(second.audio).port shouldBe URL(first.audio).port
    }

    @Test
    fun `requests with a wrong token return 404`() {
        val wav = byteArrayOf(1, 2, 3, 4)
        val urls = server.publish(host = "127.0.0.1", audio = wav, image = byteArrayOf(5, 6))
        val origin = URL(urls.audio).let { "http://${it.host}:${it.port}" }

        fetch("$origin/deadbeefdeadbeefdeadbeefdeadbeef/insight.wav").status shouldBe 404
        fetch("$origin/deadbeefdeadbeefdeadbeefdeadbeef/outfit.png").status shouldBe 404
    }

    @Test
    fun `unknown paths return 404`() {
        val urls = server.publish(host = "127.0.0.1", audio = ByteArray(0), image = ByteArray(0))
        val origin = URL(urls.audio).let { "http://${it.host}:${it.port}" }

        fetch("$origin/nope").status shouldBe 404
    }

    @Test
    fun `stop releases the port`() {
        val urls = server.publish(host = "127.0.0.1", audio = ByteArray(0), image = ByteArray(0))
        val port = server.port()
        (port > 0) shouldBe true
        URL(urls.audio).port shouldBe port

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
