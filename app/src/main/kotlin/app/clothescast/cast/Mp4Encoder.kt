package app.clothescast.cast

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.graphics.scale
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max

/**
 * Muxes a still PNG + a WAV-wrapped PCM clip into an MP4 (H.264 video +
 * AAC audio). The Default Media Receiver renders the video track
 * full-screen on Cast targets — so packing the outfit PNG as a static
 * video frame and the TTS audio as the audio track gives us "full-screen
 * image + audio at the same time" without a custom receiver.
 *
 * The video is encoded at [VIDEO_FPS] fps with every frame identical to
 * the source PNG. Static content compresses to a handful of bytes per
 * P-frame, so the cost is dominated by the (single) I-frame and the AAC
 * audio.
 *
 * MediaCodec / MediaMuxer are Android-only; this lives in `:app` for
 * that reason. CPU-bound — call from
 * [kotlinx.coroutines.Dispatchers.Default].
 */
object Mp4Encoder {

    private const val VIDEO_BITRATE = 400_000
    private const val AUDIO_BITRATE = 64_000
    private const val VIDEO_FPS = 1
    private const val MAX_DIMENSION = 1280
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * Returns MP4 bytes muxing [png] as the video track and [wav]'s PCM
     * as the audio track.
     *
     * @throws IllegalArgumentException if [png] doesn't decode or [wav]
     *   isn't a recognisable RIFF/WAVE PCM clip.
     * @throws IllegalStateException if the platform encoder rejects the
     *   inputs (e.g. unsupported dimensions for the H.264 encoder).
     */
    fun encode(png: ByteArray, wav: ByteArray): ByteArray {
        val source = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IllegalArgumentException("Failed to decode PNG (${png.size} bytes)")
        val wavInfo = WavInfo.parse(wav)
        val frame = preparedFrame(source)
        if (frame !== source) source.recycle()

        val output = File.createTempFile("cast-insight", ".mp4")
        try {
            muxToFile(frame, wavInfo, output)
            return output.readBytes()
        } finally {
            output.delete()
            frame.recycle()
        }
    }

    /**
     * Scales the source to fit within [MAX_DIMENSION] and rounds
     * dimensions to even numbers — H.264 wants even width/height for
     * 4:2:0 chroma subsampling.
     */
    private fun preparedFrame(source: Bitmap): Bitmap {
        val maxDim = max(source.width, source.height)
        val scale = if (maxDim > MAX_DIMENSION) MAX_DIMENSION.toFloat() / maxDim else 1f
        val targetW = ((source.width * scale).toInt() / 2) * 2
        val targetH = ((source.height * scale).toInt() / 2) * 2
        if (targetW == source.width && targetH == source.height) return source
        return source.scale(targetW, targetH)
    }

    private fun muxToFile(frame: Bitmap, wav: WavInfo, output: File) {
        val width = frame.width
        val height = frame.height
        val durationUs = wav.durationUs()
        val frameCount = max(1, ceil(durationUs / 1_000_000.0 * VIDEO_FPS).toInt())
        val frameDurationUs = 1_000_000L / VIDEO_FPS
        val argb = IntArray(width * height)
        frame.getPixels(argb, 0, width, 0, 0, width, height)
        val yuv = argbToI420(argb, width, height)

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoEncoder = createVideoEncoder(width, height)
        val audioEncoder = createAudioEncoder(wav.sampleRate, wav.channels)
        videoEncoder.start()
        audioEncoder.start()

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false
        var videoInputDone = false
        var videoOutputDone = false
        var audioInputDone = false
        var audioOutputDone = false
        var framesQueued = 0
        var audioBytesQueued = 0
        val videoBufferInfo = MediaCodec.BufferInfo()
        val audioBufferInfo = MediaCodec.BufferInfo()
        val pendingVideo = ArrayDeque<EncodedSample>()
        val pendingAudio = ArrayDeque<EncodedSample>()
        val bytesPerSample = 2 * wav.channels
        val usPerByte = 1_000_000.0 / (wav.sampleRate.toDouble() * bytesPerSample)

        fun tryStartMuxer() {
            if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                muxer.start()
                muxerStarted = true
                drainPending(pendingVideo, videoTrackIndex, muxer)
                drainPending(pendingAudio, audioTrackIndex, muxer)
            }
        }

        try {
            while (!videoOutputDone || !audioOutputDone) {
                if (!videoInputDone) {
                    val inIdx = videoEncoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        if (framesQueued < frameCount) {
                            val image = videoEncoder.getInputImage(inIdx)
                                ?: throw IllegalStateException("Video input image unavailable at $inIdx")
                            writeI420ToImage(image, yuv, width, height)
                            val ptsUs = framesQueued * frameDurationUs
                            videoEncoder.queueInputBuffer(inIdx, 0, yuv.size, ptsUs, 0)
                            framesQueued++
                        } else {
                            videoEncoder.queueInputBuffer(
                                inIdx, 0, 0, framesQueued * frameDurationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            videoInputDone = true
                        }
                    }
                }
                if (!audioInputDone) {
                    val inIdx = audioEncoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val inBuf = audioEncoder.getInputBuffer(inIdx)
                            ?: throw IllegalStateException("Audio input buffer null at $inIdx")
                        inBuf.clear()
                        val remaining = wav.pcm.size - audioBytesQueued
                        if (remaining <= 0) {
                            audioEncoder.queueInputBuffer(
                                inIdx, 0, 0, durationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            audioInputDone = true
                        } else {
                            val toCopy = minOf(remaining, inBuf.capacity())
                            inBuf.put(wav.pcm, audioBytesQueued, toCopy)
                            val ptsUs = (audioBytesQueued.toDouble() * usPerByte).toLong()
                            audioEncoder.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0)
                            audioBytesQueued += toCopy
                        }
                    }
                }

                if (!videoOutputDone) {
                    val outIdx = videoEncoder.dequeueOutputBuffer(videoBufferInfo, DEQUEUE_TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (videoTrackIndex >= 0) error("Video format changed twice")
                            videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
                            tryStartMuxer()
                        }
                        outIdx >= 0 -> {
                            val buf = videoEncoder.getOutputBuffer(outIdx)
                                ?: throw IllegalStateException("Video output buffer null at $outIdx")
                            handleEncodedSample(
                                buf = buf,
                                info = videoBufferInfo,
                                trackIndex = videoTrackIndex,
                                muxerStarted = muxerStarted,
                                muxer = muxer,
                                pending = pendingVideo,
                            )
                            videoEncoder.releaseOutputBuffer(outIdx, false)
                            if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                videoOutputDone = true
                            }
                        }
                        else -> Unit // INFO_TRY_AGAIN_LATER or deprecated codes
                    }
                }

                if (!audioOutputDone) {
                    val outIdx = audioEncoder.dequeueOutputBuffer(audioBufferInfo, DEQUEUE_TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (audioTrackIndex >= 0) error("Audio format changed twice")
                            audioTrackIndex = muxer.addTrack(audioEncoder.outputFormat)
                            tryStartMuxer()
                        }
                        outIdx >= 0 -> {
                            val buf = audioEncoder.getOutputBuffer(outIdx)
                                ?: throw IllegalStateException("Audio output buffer null at $outIdx")
                            handleEncodedSample(
                                buf = buf,
                                info = audioBufferInfo,
                                trackIndex = audioTrackIndex,
                                muxerStarted = muxerStarted,
                                muxer = muxer,
                                pending = pendingAudio,
                            )
                            audioEncoder.releaseOutputBuffer(outIdx, false)
                            if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                audioOutputDone = true
                            }
                        }
                        else -> Unit
                    }
                }
            }
        } finally {
            runCatching { videoEncoder.stop() }
            runCatching { videoEncoder.release() }
            runCatching { audioEncoder.stop() }
            runCatching { audioEncoder.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        if (videoTrackIndex < 0 || audioTrackIndex < 0 || !muxerStarted) {
            throw IllegalStateException(
                "Mp4 mux incomplete (video=$videoTrackIndex audio=$audioTrackIndex started=$muxerStarted)",
            )
        }
    }

    private fun handleEncodedSample(
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
        trackIndex: Int,
        muxerStarted: Boolean,
        muxer: MediaMuxer,
        pending: ArrayDeque<EncodedSample>,
    ) {
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
        if (info.size <= 0) return
        if (muxerStarted && trackIndex >= 0) {
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            muxer.writeSampleData(trackIndex, buf, info)
        } else {
            val copy = ByteArray(info.size)
            buf.position(info.offset)
            buf.get(copy)
            pending.addLast(EncodedSample(copy, info.presentationTimeUs, info.flags))
        }
    }

    private fun drainPending(
        pending: ArrayDeque<EncodedSample>,
        trackIndex: Int,
        muxer: MediaMuxer,
    ) {
        val info = MediaCodec.BufferInfo()
        while (pending.isNotEmpty()) {
            val sample = pending.removeFirst()
            val buf = ByteBuffer.wrap(sample.bytes)
            info.set(0, sample.bytes.size, sample.ptsUs, sample.flags)
            muxer.writeSampleData(trackIndex, buf, info)
        }
    }

    private fun createVideoEncoder(width: Int, height: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    private fun createAudioEncoder(sampleRate: Int, channels: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    /**
     * Writes an I420 YUV buffer into the encoder's input [Image],
     * honouring per-plane row and pixel strides — devices vary on
     * internal subformat (I420 vs. NV12 vs. semiplanar quirks).
     */
    private fun writeI420ToImage(image: Image, yuv: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        val planes = image.planes

        copyPlane(
            src = yuv, srcOffset = 0, srcStride = width,
            plane = planes[0], width = width, height = height,
        )
        copyPlane(
            src = yuv, srcOffset = ySize, srcStride = width / 2,
            plane = planes[1], width = width / 2, height = height / 2,
        )
        copyPlane(
            src = yuv, srcOffset = ySize + ySize / 4, srcStride = width / 2,
            plane = planes[2], width = width / 2, height = height / 2,
        )
    }

    private fun copyPlane(
        src: ByteArray,
        srcOffset: Int,
        srcStride: Int,
        plane: Image.Plane,
        width: Int,
        height: Int,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        // Fast path: tightly-packed I420 plane that matches our source.
        if (pixelStride == 1 && rowStride == width && srcStride == width) {
            buffer.position(0)
            buffer.put(src, srcOffset, width * height)
            return
        }
        for (y in 0 until height) {
            val srcRowStart = srcOffset + y * srcStride
            val dstRowStart = y * rowStride
            if (pixelStride == 1) {
                // Padded plane (rowStride > width); write only the [width] bytes
                // we own and leave the trailing pad alone.
                buffer.position(dstRowStart)
                buffer.put(src, srcRowStart, width)
            } else {
                // Interleaved chroma (NV12/NV21). The sibling plane's bytes sit
                // between ours in the same buffer, so we must write absolutely
                // at every (dstRowStart + x*pixelStride) and never touch the
                // bytes in between.
                for (x in 0 until width) {
                    buffer.put(dstRowStart + x * pixelStride, src[srcRowStart + x])
                }
            }
        }
    }

    /**
     * ARGB → I420 (planar Y, U, V) using BT.601 full-range coefficients.
     * Full range keeps the visible brightness of the outfit card intact
     * (limited-range would crush highlights / lift blacks).
     */
    private fun argbToI420(argb: IntArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val out = ByteArray(frameSize * 3 / 2)
        val uStart = frameSize
        val vStart = frameSize + frameSize / 4

        var yIndex = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val argbValue = argb[j * width + i]
                val r = (argbValue shr 16) and 0xFF
                val g = (argbValue shr 8) and 0xFF
                val b = argbValue and 0xFF
                val y = clamp((77 * r + 150 * g + 29 * b + 128) shr 8)
                out[yIndex++] = y.toByte()
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = clamp(((-43 * r - 84 * g + 127 * b) shr 8) + 128)
                    val v = clamp(((127 * r - 106 * g - 21 * b) shr 8) + 128)
                    val chromaIndex = (j / 2) * (width / 2) + (i / 2)
                    out[uStart + chromaIndex] = u.toByte()
                    out[vStart + chromaIndex] = v.toByte()
                }
            }
        }
        return out
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private data class EncodedSample(val bytes: ByteArray, val ptsUs: Long, val flags: Int)

    /**
     * Minimum metadata we pull from a WAV byte array: PCM payload, the
     * sample rate, and the channel count. Anything fancier (24-bit,
     * float, multi-chunk) isn't what Gemini TTS produces, so we don't
     * support it.
     */
    internal data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val pcm: ByteArray,
    ) {
        fun durationUs(): Long {
            val bytesPerSample = 2L * channels
            val frames = pcm.size / bytesPerSample
            return frames * 1_000_000L / sampleRate
        }

        companion object {
            private const val CHUNK_RIFF = 0x46464952 // "RIFF" LE
            private const val CHUNK_WAVE = 0x45564157 // "WAVE" LE
            private const val CHUNK_FMT = 0x20746D66  // "fmt " LE
            private const val CHUNK_DATA = 0x61746164 // "data" LE

            fun parse(wav: ByteArray): WavInfo {
                require(wav.size >= 44) { "WAV too short: ${wav.size}" }
                val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
                require(buf.int == CHUNK_RIFF) { "Missing RIFF header" }
                buf.int // file size, ignored
                require(buf.int == CHUNK_WAVE) { "Missing WAVE marker" }

                var sampleRate = 0
                var channels = 0
                var pcm: ByteArray? = null
                while (buf.remaining() >= 8) {
                    val chunkId = buf.int
                    val chunkSize = buf.int
                    val chunkStart = buf.position()
                    when (chunkId) {
                        CHUNK_FMT -> {
                            val audioFormat = buf.short.toInt() and 0xFFFF
                            require(audioFormat == 1) { "Non-PCM WAV (format=$audioFormat)" }
                            channels = buf.short.toInt() and 0xFFFF
                            sampleRate = buf.int
                            buf.int // byte rate
                            buf.short // block align
                            val bits = buf.short.toInt() and 0xFFFF
                            require(bits == 16) { "Unsupported bit depth $bits" }
                        }
                        CHUNK_DATA -> {
                            val size = minOf(chunkSize, buf.remaining())
                            val bytes = ByteArray(size)
                            buf.get(bytes)
                            pcm = bytes
                        }
                    }
                    val nextPos = chunkStart + chunkSize
                    if (nextPos > buf.limit()) break
                    buf.position(nextPos)
                }

                val data = pcm
                require(data != null) { "WAV missing data chunk" }
                require(sampleRate > 0 && channels > 0) { "WAV missing fmt chunk" }
                return WavInfo(sampleRate = sampleRate, channels = channels, pcm = data)
            }
        }
    }
}
