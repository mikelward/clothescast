package app.clothescast.diag

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TelemetryRefreshOutcomeTest {

    @Test
    fun `no_location reason maps to dedicated bucket`() {
        // FetchAndNotifyWorker stamps REASON_NO_LOCATION when the user has no
        // saved location AND device location is unavailable / not permitted;
        // it's a user-actionable misconfig rather than a transient failure, so
        // the dashboard wants to see it separately from other_error.
        classifyDailyRefreshReason("no_location") shouldBe "no_location"
    }

    @Test
    fun `unexpected_http reason maps to forecast_error`() {
        // 4xx from OpenMeteo lands here — the request reached the upstream
        // but came back with a status we can't recover from. Distinguishing
        // it from network blips is the point of the bucket.
        classifyDailyRefreshReason("unexpected_http") shouldBe "forecast_error"
    }

    @Test
    fun `unhandled and unknown reasons collapse into other_error`() {
        classifyDailyRefreshReason("unhandled") shouldBe "other_error"
        classifyDailyRefreshReason("") shouldBe "other_error"
        classifyDailyRefreshReason(null) shouldBe "other_error"
        classifyDailyRefreshReason("some_future_reason_we_havent_added_yet") shouldBe "other_error"
    }
}
