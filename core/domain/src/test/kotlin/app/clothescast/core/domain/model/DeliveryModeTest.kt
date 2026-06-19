package app.clothescast.core.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Locks the two derived predicates on [DeliveryMode] that gate delivery
 * fan-out: [postsNotification] (which modes post a phone notification) and
 * [playsSpeech] (which modes speak aloud, gating the playback foreground
 * service in FetchAndNotifyWorker).
 */
class DeliveryModeTest {

    @Test
    fun `postsNotification covers the two notification-posting modes`() {
        assertEquals(
            setOf(DeliveryMode.NOTIFICATION_ONLY, DeliveryMode.NOTIFICATION_AND_TTS),
            DeliveryMode.entries.filter { it.postsNotification }.toSet(),
        )
    }

    @Test
    fun `playsSpeech covers the two speaking modes`() {
        assertEquals(
            setOf(DeliveryMode.TTS_ONLY, DeliveryMode.NOTIFICATION_AND_TTS),
            DeliveryMode.entries.filter { it.playsSpeech }.toSet(),
        )
    }
}
