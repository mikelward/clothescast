package app.clothescast.ui.settings

import app.clothescast.core.domain.model.DeliveryMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NotificationBannerNeededTest {
    @Test
    fun `shown when an enabled slot posts a notification`() {
        notificationBannerNeeded(
            dailyEnabled = true,
            deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
            tonightEnabled = false,
            tonightDeliveryMode = DeliveryMode.SILENT,
        ) shouldBe true
    }

    @Test
    fun `shown for a notification-only evening slot even when morning is off`() {
        notificationBannerNeeded(
            dailyEnabled = false,
            deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
            tonightEnabled = true,
            tonightDeliveryMode = DeliveryMode.NOTIFICATION_ONLY,
        ) shouldBe true
    }

    @Test
    fun `hidden when both slots are off`() {
        notificationBannerNeeded(
            dailyEnabled = false,
            deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
            tonightEnabled = false,
            tonightDeliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        ) shouldBe false
    }

    @Test
    fun `hidden when the only enabled slot is silent or TTS-only`() {
        notificationBannerNeeded(
            dailyEnabled = true,
            deliveryMode = DeliveryMode.TTS_ONLY,
            tonightEnabled = true,
            tonightDeliveryMode = DeliveryMode.SILENT,
        ) shouldBe false
    }
}
