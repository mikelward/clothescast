package app.clothescast.cast

import app.clothescast.core.data.tts.PcmAudio
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps the raw PCM buffer returned by Gemini TTS in a RIFF/WAVE container so
 * Chromecast (and any other plain media client) can play it without first
 * having to be told the sample rate / channel layout out of band.
 *
 * Gemini TTS returns signed 16-bit mono little-endian PCM at a per-response
 * sample rate (24000 Hz at time of writing). Those parameters are hard-coded
 * into the header below; if Gemini ever starts returning stereo or a different
 * bit depth, [PcmAudio] would need to grow channel / bit-depth fields and
 * this encoder would need to follow.
 *
 * The output is a single contiguous `ByteArray` (header + samples) of size
 * `44 + audio.bytes.size`. Cast's Default Media Receiver streams it as
 * `audio/wav`.
 */
object WavEncoder {

    private const val HEADER_SIZE = 44
    private const val FORMAT_PCM: Short = 1
    private const val CHANNELS: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16

    fun encode(audio: PcmAudio): ByteArray {
        val pcm = audio.bytes
        val sampleRate = audio.sampleRate
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign: Short = (CHANNELS * BITS_PER_SAMPLE / 8).toShort()

        val out = ByteArray(HEADER_SIZE + pcm.size)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + pcm.size)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))

        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(FORMAT_PCM)
        buf.putShort(CHANNELS)
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign)
        buf.putShort(BITS_PER_SAMPLE)

        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(pcm.size)
        buf.put(pcm)

        return out
    }
}
