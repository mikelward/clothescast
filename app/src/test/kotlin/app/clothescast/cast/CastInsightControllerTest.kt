package app.clothescast.cast

import app.clothescast.core.data.tts.PcmAudio
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CastInsightControllerTest {

    @Test
    fun `padPcmToMinimumDuration pads short audio with trailing silence to the minimum`() {
        val sampleRate = 24_000
        val originalSeconds = 5
        val originalBytes = ByteArray(sampleRate * originalSeconds * 2) { (it % 127).toByte() }
        val pcm = PcmAudio(bytes = originalBytes, sampleRate = sampleRate)

        val padded = CastInsightController.padPcmToMinimumDuration(pcm)

        padded.sampleRate shouldBe sampleRate
        padded.bytes.size shouldBe sampleRate * CastInsightController.MIN_CAST_DURATION_SECONDS * 2
        padded.bytes.copyOfRange(0, originalBytes.size) shouldBe originalBytes
        // Trailing padding is silent (all zero bytes).
        padded.bytes.copyOfRange(originalBytes.size, padded.bytes.size)
            .all { it == 0.toByte() } shouldBe true
    }

    @Test
    fun `padPcmToMinimumDuration returns audio at the minimum unchanged`() {
        val sampleRate = 24_000
        val bytes = ByteArray(sampleRate * CastInsightController.MIN_CAST_DURATION_SECONDS * 2) { 1 }
        val pcm = PcmAudio(bytes = bytes, sampleRate = sampleRate)

        val padded = CastInsightController.padPcmToMinimumDuration(pcm)

        padded shouldBe pcm
    }

    @Test
    fun `padPcmToMinimumDuration leaves longer audio unchanged`() {
        val sampleRate = 24_000
        val bytes = ByteArray(sampleRate * (CastInsightController.MIN_CAST_DURATION_SECONDS + 30) * 2) { 1 }
        val pcm = PcmAudio(bytes = bytes, sampleRate = sampleRate)

        val padded = CastInsightController.padPcmToMinimumDuration(pcm)

        padded shouldBe pcm
    }

    @Test
    fun `padWavToMinimumDuration pads short WAV with trailing silence to the minimum`() {
        val sampleRate = 24_000
        val originalSeconds = 5
        val originalPcm = ByteArray(sampleRate * originalSeconds * 2) { (it % 127).toByte() }
        val wav = WavEncoder.encode(PcmAudio(bytes = originalPcm, sampleRate = sampleRate))

        val padded = CastInsightController.padWavToMinimumDuration(wav)
        val paddedInfo = Mp4Encoder.WavInfo.parse(padded)

        paddedInfo.sampleRate shouldBe sampleRate
        paddedInfo.channels shouldBe 1
        paddedInfo.pcm.size shouldBe sampleRate * CastInsightController.MIN_CAST_DURATION_SECONDS * 2
        paddedInfo.pcm.copyOfRange(0, originalPcm.size) shouldBe originalPcm
        paddedInfo.pcm.copyOfRange(originalPcm.size, paddedInfo.pcm.size)
            .all { it == 0.toByte() } shouldBe true
    }

    @Test
    fun `padWavToMinimumDuration leaves the silent stub unchanged`() {
        val stub = CastInsightController.silentWavStub

        val padded = CastInsightController.padWavToMinimumDuration(stub)

        padded shouldBe stub
    }
}
