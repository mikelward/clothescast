package app.clothescast.core.data.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps signed 16-bit mono PCM (as produced by [GeminiTtsClient.synthesize]) in a
 * canonical 44-byte RIFF/WAVE header so the payload can be played by any standard
 * audio player. HiveMQ has no notion of "this is PCM at 24 kHz" — handing
 * downstream consumers (Home Assistant's `media_player`, ffmpeg, browsers) a raw
 * PCM blob makes them guess at sample rate / channel layout and usually fail.
 * A WAV wrapper makes the payload self-describing.
 */
object WavEncoder {

    private const val HEADER_BYTES = 44
    private const val CHANNELS: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16
    private const val PCM_FORMAT: Short = 1

    fun encode(audio: PcmAudio): ByteArray {
        val pcm = audio.bytes
        val sampleRate = audio.sampleRate
        val byteRate = sampleRate * CHANNELS * (BITS_PER_SAMPLE / 8)
        val blockAlign: Short = (CHANNELS * (BITS_PER_SAMPLE / 8)).toShort()

        val out = ByteBuffer.allocate(HEADER_BYTES + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF chunk descriptor
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + pcm.size)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        // fmt sub-chunk
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)
        out.putShort(PCM_FORMAT)
        out.putShort(CHANNELS)
        out.putInt(sampleRate)
        out.putInt(byteRate)
        out.putShort(blockAlign)
        out.putShort(BITS_PER_SAMPLE)
        // data sub-chunk
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(pcm.size)
        out.put(pcm)
        return out.array()
    }
}
