package app.clothescast.cast

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class Mp4EncoderTest {

    @Test
    fun `videoFrameCount covers the audio when no minimum is set`() {
        Mp4Encoder.videoFrameCount(audioDurationUs = 4_200_000L, minDurationUs = 0L) shouldBe 5
    }

    @Test
    fun `videoFrameCount holds the frame until the minimum when the audio is shorter`() {
        Mp4Encoder.videoFrameCount(audioDurationUs = 8_000_000L, minDurationUs = 120_000_000L) shouldBe 120
    }

    @Test
    fun `videoFrameCount follows audio longer than the minimum`() {
        Mp4Encoder.videoFrameCount(audioDurationUs = 130_500_000L, minDurationUs = 120_000_000L) shouldBe 131
    }

    @Test
    fun `videoFrameCount emits at least one frame for empty audio`() {
        Mp4Encoder.videoFrameCount(audioDurationUs = 0L, minDurationUs = 0L) shouldBe 1
    }
}
