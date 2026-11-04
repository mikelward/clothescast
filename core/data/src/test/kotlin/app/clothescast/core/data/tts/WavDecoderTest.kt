package app.clothescast.core.data.tts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavDecoderTest {

    private val samples = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)

    @Test
    fun `reads back what the encoder wrote`() {
        val audio = WavDecoder.decode(WavEncoder.encode(PcmAudio(bytes = samples, sampleRate = 24_000)))

        audio shouldBe PcmAudio(bytes = samples, sampleRate = 24_000)
    }

    @Test
    fun `skips chunks it doesn't need, pad byte included`() {
        val wav = wav(
            chunk("fmt ", format(sampleRate = 48_000)),
            chunk("LIST", byteArrayOf(1, 2, 3)), // odd size, so a pad byte follows
            chunk("data", samples),
        )

        WavDecoder.decode(wav) shouldBe PcmAudio(bytes = samples, sampleRate = 48_000)
    }

    @Test
    fun `takes the samples that are there when the declared size overruns`() {
        // A streamed WAV writes a placeholder length before it knows the real one.
        val wav = wav(chunk("fmt ", format()), chunk("data", samples, declaredSize = -1))

        WavDecoder.decode(wav).bytes.toList() shouldBe samples.toList()
    }

    @Test
    fun `tells wav from headerless pcm`() {
        WavDecoder.isWav(WavEncoder.encode(PcmAudio(samples, 24_000))) shouldBe true
        WavDecoder.isWav(samples) shouldBe false
    }

    @Test
    fun `refuses audio it can't play as 16-bit mono`() {
        shouldThrow<UnsupportedTtsAudioException> {
            WavDecoder.decode(wav(chunk("fmt ", format(channels = 2)), chunk("data", samples)))
        }.message!!.shouldContain("2 channel")
        shouldThrow<UnsupportedTtsAudioException> {
            WavDecoder.decode(wav(chunk("fmt ", format(bits = 8)), chunk("data", samples)))
        }
        shouldThrow<UnsupportedTtsAudioException> {
            WavDecoder.decode(wav(chunk("fmt ", format(audioFormat = 3)), chunk("data", samples)))
        }.message!!.shouldContain("not PCM")
    }

    @Test
    fun `refuses a file with no samples or no format`() {
        shouldThrow<UnsupportedTtsAudioException> { WavDecoder.decode(wav(chunk("fmt ", format()))) }
        shouldThrow<UnsupportedTtsAudioException> { WavDecoder.decode(wav(chunk("data", samples))) }
        shouldThrow<UnsupportedTtsAudioException> { WavDecoder.decode(samples) }
    }

    private fun format(
        audioFormat: Int = 1,
        channels: Int = 1,
        sampleRate: Int = 24_000,
        bits: Int = 16,
    ): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(audioFormat.toShort())
        .putShort(channels.toShort())
        .putInt(sampleRate)
        .putInt(sampleRate * channels * bits / 8)
        .putShort((channels * bits / 8).toShort())
        .putShort(bits.toShort())
        .array()

    private fun chunk(id: String, body: ByteArray, declaredSize: Int = body.size): ByteArray {
        val pad = body.size and 1
        return ByteBuffer.allocate(8 + body.size + pad).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII))
            .putInt(declaredSize)
            .put(body)
            .array()
    }

    private fun wav(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        return ByteBuffer.allocate(12 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt(4 + body.size)
            .put("WAVE".toByteArray(Charsets.US_ASCII))
            .put(body)
            .array()
    }
}
