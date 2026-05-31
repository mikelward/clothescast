package app.clothescast.data

import app.clothescast.core.data.insight.GeminiEndpoint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppCheckGeminiCallPlannerTest {

    @Test
    fun `parseProxyEndpoint splits host and single path segment`() {
        val endpoint = AppCheckGeminiCallPlanner.parseProxyEndpoint(
            "https://us-central1-myproj.cloudfunctions.net/tts",
        )

        endpoint shouldBe GeminiEndpoint(
            host = "us-central1-myproj.cloudfunctions.net",
            apiVersion = "tts",
        )
    }

    @Test
    fun `parseProxyEndpoint tolerates trailing slash`() {
        val endpoint = AppCheckGeminiCallPlanner.parseProxyEndpoint(
            "https://example.test/tts/",
        )

        endpoint.apiVersion shouldBe "tts"
    }

    @Test
    fun `parseProxyEndpoint handles bare host without path`() {
        val endpoint = AppCheckGeminiCallPlanner.parseProxyEndpoint("https://example.test")

        endpoint shouldBe GeminiEndpoint(host = "example.test", apiVersion = "")
    }

    @Test
    fun `parseProxyEndpoint rejects multi-segment paths`() {
        shouldThrow<IllegalArgumentException> {
            AppCheckGeminiCallPlanner.parseProxyEndpoint("https://example.test/api/tts")
        }
    }
}
