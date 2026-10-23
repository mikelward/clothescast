package app.clothescast.alerts

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AlertsBucketTest {
    @Test
    fun `the bucket is the proxy's project's, by convention`() {
        alertsBaseUrl("", "https://us-central1-demo-project.cloudfunctions.net/tts") shouldBe
            "https://storage.googleapis.com/demo-project-alerts"
        alertsBaseUrl("", "https://australia-southeast1-demo-123.cloudfunctions.net/tts") shouldBe
            "https://storage.googleapis.com/demo-123-alerts"
    }

    @Test
    fun `an explicit address wins, without a trailing slash`() {
        alertsBaseUrl(" https://storage.googleapis.com/other-alerts/ ", "https://us-central1-demo-project.cloudfunctions.net/tts") shouldBe
            "https://storage.googleapis.com/other-alerts"
    }

    @Test
    fun `no proxy, or one not on cloudfunctions, gives no bucket`() {
        alertsBaseUrl("", "").shouldBeNull()
        alertsBaseUrl("", "https://tts-abc123-uc.a.run.app").shouldBeNull()
        alertsBaseUrl("", "not a url at all ::").shouldBeNull()
        alertsBaseUrl("http://insecure.example.com", "").shouldBeNull()
    }
}
