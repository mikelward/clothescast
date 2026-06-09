package app.clothescast.core.data.tts

import app.clothescast.core.data.insight.InvalidApiKeyException
import app.clothescast.core.data.insight.MissingApiKeyException
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

class GeminiTtsRetryClassifierTest {

    @Test
    fun `socket timeout is retryable`() {
        // The field report that motivated the retry: a transient
        // SocketTimeoutException downgraded the whole briefing to device TTS.
        isRetryableGeminiTtsFailure(SocketTimeoutException("timeout")) shouldBe true
    }

    @Test
    fun `generic network IO failure is retryable`() {
        isRetryableGeminiTtsFailure(IOException("connection reset")) shouldBe true
    }

    @Test
    fun `empty-candidate model hiccup is retryable`() {
        isRetryableGeminiTtsFailure(GeminiTtsEmptyResponseException(finishReason = null)) shouldBe true
    }

    @Test
    fun `5xx server error is retryable`() {
        val failure = GeminiTtsHttpException(HttpStatusCode.BadGateway, ByteArray(0))
        isRetryableGeminiTtsFailure(failure) shouldBe true
    }

    @Test
    fun `429 rate-limit is retryable`() {
        val failure = GeminiTtsHttpException(HttpStatusCode.TooManyRequests, ByteArray(0))
        isRetryableGeminiTtsFailure(failure) shouldBe true
    }

    @Test
    fun `4xx auth failure is not retryable`() {
        // Auth / bad-request won't change on a retry — fall back immediately.
        val failure = GeminiTtsHttpException(HttpStatusCode.Forbidden, ByteArray(0))
        isRetryableGeminiTtsFailure(failure) shouldBe false
    }

    @Test
    fun `spent daily quota is not retryable`() {
        val failure = GeminiTtsDailyQuotaExhaustedException(limit = 50, resetAtUtc = null)
        isRetryableGeminiTtsFailure(failure) shouldBe false
    }

    @Test
    fun `blocked prompt is not retryable`() {
        isRetryableGeminiTtsFailure(GeminiTtsBlockedException("blocked")) shouldBe false
    }

    @Test
    fun `missing BYOK key is not retryable`() {
        // A cleared key won't reappear between attempts — burning the retry
        // budget with backoff just delays the device-TTS fallback.
        isRetryableGeminiTtsFailure(MissingApiKeyException()) shouldBe false
    }

    @Test
    fun `unreadable BYOK key is not retryable`() {
        // Same as missing: only the user re-entering the key can fix it.
        isRetryableGeminiTtsFailure(InvalidApiKeyException()) shouldBe false
    }

    @Test
    fun `cancellation is never retryable`() {
        // Must propagate to unwind structured concurrency, not be retried.
        isRetryableGeminiTtsFailure(CancellationException("cancelled")) shouldBe false
    }
}
