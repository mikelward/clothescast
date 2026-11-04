package app.clothescast.core.data.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unwraps the WAV file Gemini 3.8 TTS returns into the signed 16-bit mono PCM
 * the rest of the app plays ([PcmAudio]), the inverse of [WavEncoder].
 *
 * Earlier TTS models returned headerless PCM; 3.8 returns a complete RIFF/WAVE
 * file by default. Handing that file to AudioTrack as if it were PCM would play
 * the header as a click and trust a sample rate we never read, so the chunks are
 * walked properly: `fmt ` gives the format and rate, `data` the samples, and
 * anything else (`LIST`, `fact`, …) is skipped.
 */
object WavDecoder {

    private const val PCM_FORMAT = 1
    private const val EXTENSIBLE_FORMAT = 0xFFFE
    private const val RIFF_HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8

    /** True when [bytes] start with a RIFF/WAVE header. */
    fun isWav(bytes: ByteArray): Boolean =
        bytes.size >= RIFF_HEADER_BYTES &&
            bytes.ascii(0) == "RIFF" &&
            bytes.ascii(8) == "WAVE"

    /**
     * Returns the samples and sample rate of a 16-bit mono PCM WAV file.
     * Throws [UnsupportedTtsAudioException] for anything else, since playing
     * another encoding as 16-bit mono would be noise.
     */
    fun decode(bytes: ByteArray): PcmAudio {
        if (!isWav(bytes)) throw UnsupportedTtsAudioException("not a RIFF/WAVE file")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(RIFF_HEADER_BYTES)

        var sampleRate: Int? = null
        while (buf.remaining() >= CHUNK_HEADER_BYTES) {
            val id = bytes.ascii(buf.position())
            buf.position(buf.position() + 4)
            val declared = buf.int.toLong() and 0xFFFF_FFFFL
            // A streamed WAV can't know its length up front and writes a
            // placeholder; take what's actually there rather than overrun.
            val size = minOf(declared, buf.remaining().toLong()).toInt()
            val start = buf.position()
            when (id) {
                "fmt " -> sampleRate = readFormat(buf, size)
                "data" -> {
                    val rate = sampleRate
                        ?: throw UnsupportedTtsAudioException("data chunk before fmt chunk")
                    return PcmAudio(bytes = bytes.copyOfRange(start, start + size), sampleRate = rate)
                }
            }
            // Chunks are word-aligned: an odd-sized chunk is followed by a pad byte.
            val next = start.toLong() + size + (size and 1)
            if (next > bytes.size) break
            buf.position(next.toInt())
        }
        throw UnsupportedTtsAudioException("no data chunk")
    }

    private fun readFormat(buf: ByteBuffer, size: Int): Int {
        if (size < 16) throw UnsupportedTtsAudioException("fmt chunk is $size bytes")
        val format = buf.short.toInt() and 0xFFFF
        val channels = buf.short.toInt()
        val sampleRate = buf.int
        buf.int // byte rate
        buf.short // block align
        val bits = buf.short.toInt()
        if (format != PCM_FORMAT && format != EXTENSIBLE_FORMAT) {
            throw UnsupportedTtsAudioException("format $format is not PCM")
        }
        if (channels != 1 || bits != 16) {
            throw UnsupportedTtsAudioException("$channels channel(s) at $bits bits, not 16-bit mono")
        }
        if (sampleRate <= 0) throw UnsupportedTtsAudioException("sample rate $sampleRate")
        return sampleRate
    }

    private fun ByteArray.ascii(offset: Int): String =
        if (offset + 4 > size) "" else String(this, offset, 4, Charsets.US_ASCII)
}

/** Gemini returned audio in an encoding the player can't take. Not retryable. */
class UnsupportedTtsAudioException(detail: String) :
    IllegalStateException("Gemini TTS returned unsupported audio: $detail")
