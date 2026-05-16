package app.clothescast.core.data.tts

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavEncoderTest {

    @Test
    fun `encodes a canonical 44-byte header for 16-bit mono PCM`() {
        val pcm = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        val wav = WavEncoder.encode(PcmAudio(bytes = pcm, sampleRate = 24_000))

        wav.size shouldBe (44 + pcm.size)

        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buf.get(it) }
        riff.toString(Charsets.US_ASCII) shouldBe "RIFF"
        buf.int shouldBe (36 + pcm.size) // chunk size

        val wave = ByteArray(4).also { buf.get(it) }
        wave.toString(Charsets.US_ASCII) shouldBe "WAVE"

        val fmt = ByteArray(4).also { buf.get(it) }
        fmt.toString(Charsets.US_ASCII) shouldBe "fmt "
        buf.int shouldBe 16 // fmt sub-chunk size
        buf.short shouldBe 1.toShort() // PCM format
        buf.short shouldBe 1.toShort() // mono
        buf.int shouldBe 24_000 // sample rate
        buf.int shouldBe 24_000 * 2 // byte rate (sampleRate * channels * bytesPerSample)
        buf.short shouldBe 2.toShort() // block align (channels * bytesPerSample)
        buf.short shouldBe 16.toShort() // bits per sample

        val data = ByteArray(4).also { buf.get(it) }
        data.toString(Charsets.US_ASCII) shouldBe "data"
        buf.int shouldBe pcm.size

        val tail = ByteArray(pcm.size).also { buf.get(it) }
        (tail contentEquals pcm).shouldBeTrue()
    }

    @Test
    fun `byte rate scales with sample rate`() {
        val wav = WavEncoder.encode(PcmAudio(bytes = ByteArray(0), sampleRate = 48_000))
        // Byte rate sits at offset 28 (little-endian int).
        val byteRate = ByteBuffer.wrap(wav, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int
        byteRate shouldBe 48_000 * 2
    }

    @Test
    fun `empty PCM still produces a valid header`() {
        val wav = WavEncoder.encode(PcmAudio(bytes = ByteArray(0), sampleRate = 24_000))
        wav.size shouldBe 44
        ByteBuffer.wrap(wav, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int shouldBe 36
        ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int shouldBe 0
    }
}
