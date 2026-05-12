package app.clothescast.core.data.diag

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

class ApiCallLoggerTest {
    private class RecordingLogger : ApiCallLogger {
        val events = mutableListOf<ApiCallEvent>()
        override fun log(event: ApiCallEvent) {
            events += event
        }
    }

    private fun successClient(): HttpClient = HttpClient(
        MockEngine { respond(content = "ok", status = HttpStatusCode.OK) },
    )

    private fun statusClient(status: HttpStatusCode): HttpClient = HttpClient(
        MockEngine { respondError(status) },
    )

    @Test
    fun `success path logs status 200`() = runTest {
        val logger = RecordingLogger()
        val client = successClient()

        val result = logger.instrument("test_endpoint") {
            client.get("http://example/").status.value
        }

        result shouldBe 200
        logger.events.size shouldBe 1
        logger.events[0].endpoint shouldBe "test_endpoint"
        logger.events[0].httpStatus shouldBe 200
        logger.events[0].errorClass shouldBe null
    }

    @Test
    fun `4xx with expectSuccess logs the real status and rethrows`() = runTest {
        val logger = RecordingLogger()
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.TooManyRequests) })

        shouldThrow<ClientRequestException> {
            logger.instrument("rate_limited") {
                client.get("http://example/") { expectSuccess = true }
            }
        }

        logger.events.size shouldBe 1
        logger.events[0].httpStatus shouldBe 429
        logger.events[0].errorClass shouldBe null
    }

    @Test
    fun `5xx with expectSuccess logs the real status`() = runTest {
        val logger = RecordingLogger()
        val client = statusClient(HttpStatusCode.BadGateway)

        shouldThrow<ServerResponseException> {
            logger.instrument("upstream_blip") {
                client.get("http://example/") { expectSuccess = true }
            }
        }

        logger.events.size shouldBe 1
        logger.events[0].httpStatus shouldBe 502
    }

    @Test
    fun `non-HTTP throwable logs errorClass and null status`() = runTest {
        val logger = RecordingLogger()

        shouldThrow<IOException> {
            logger.instrument("io_failure") {
                throw IOException("boom")
            }
        }

        logger.events.size shouldBe 1
        logger.events[0].httpStatus shouldBe null
        logger.events[0].errorClass shouldBe "IOException"
    }

    @Test
    fun `CancellationException is not logged`() = runTest {
        val logger = RecordingLogger()

        shouldThrow<CancellationException> {
            logger.instrument("cancelled") {
                throw CancellationException("cancelled")
            }
        }

        // The whole point of skipping cancel is that it doesn't pollute the
        // analytics stream — the caller cancelled, the API didn't misbehave.
        logger.events.size shouldBe 0
    }

    @Test
    fun `latency is at least zero on synchronous success`() = runTest {
        val logger = RecordingLogger()
        val client = successClient()

        logger.instrument("latency_check") {
            client.get("http://example/")
        }

        // Wall-clock comparisons across the millisecond boundary can read as 0;
        // the assertion only guards against negative values that would indicate
        // we're recording `now - started` in the wrong order.
        (logger.events[0].latencyMs >= 0L) shouldBe true
    }

    @Test
    fun `ResponseException with extracted status takes precedence over errorClass`() = runTest {
        // Belt-and-braces: the helper has separate catches for ResponseException
        // and other Throwables. Confirm the ResponseException path doesn't also
        // fall through and double-log with errorClass populated.
        val logger = RecordingLogger()
        val client = statusClient(HttpStatusCode.Forbidden)

        shouldThrow<ResponseException> {
            logger.instrument("auth_failure") {
                client.get("http://example/") { expectSuccess = true }
            }
        }

        logger.events.size shouldBe 1
        logger.events[0].httpStatus shouldBe 403
        logger.events[0].errorClass shouldBe null
    }
}
