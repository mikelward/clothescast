package app.clothescast.cast

import app.clothescast.core.data.tts.PcmAudio
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavEncoderTest {

    @Test
    fun `header describes signed 16-bit mono PCM at the audio's sample rate`() {
        val pcm = ByteArray(8) { (it + 1).toByte() }
        val wav = WavEncoder.encode(PcmAudio(bytes = pcm, sampleRate = 24_000))

        // 44 byte header + PCM payload.
        wav.size shouldBe 44 + pcm.size

        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val read4 = { i: Int -> String(wav, i, 4, Charsets.US_ASCII) }

        read4(0) shouldBe "RIFF"
        buf.getInt(4) shouldBe 36 + pcm.size       // RIFF chunk size
        read4(8) shouldBe "WAVE"

        read4(12) shouldBe "fmt "
        buf.getInt(16) shouldBe 16                  // fmt chunk size for PCM
        buf.getShort(20) shouldBe 1.toShort()       // PCM
        buf.getShort(22) shouldBe 1.toShort()       // mono
        buf.getInt(24) shouldBe 24_000              // sample rate
        buf.getInt(28) shouldBe 24_000 * 2          // byte rate
        buf.getShort(32) shouldBe 2.toShort()       // block align
        buf.getShort(34) shouldBe 16.toShort()      // bits per sample

        read4(36) shouldBe "data"
        buf.getInt(40) shouldBe pcm.size

        // PCM payload follows the header unchanged.
        wav.copyOfRange(44, wav.size) shouldBe pcm
    }

    @Test
    fun `sample rate is taken from the audio buffer`() {
        val wav = WavEncoder.encode(PcmAudio(bytes = ByteArray(2), sampleRate = 48_000))
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        buf.getInt(24) shouldBe 48_000
        buf.getInt(28) shouldBe 48_000 * 2
    }

    @Test
    fun `empty PCM still produces a valid header`() {
        val wav = WavEncoder.encode(PcmAudio(bytes = ByteArray(0), sampleRate = 24_000))
        wav.size shouldBe 44
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40) shouldBe 0
    }
}
