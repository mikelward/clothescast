package app.clothescast.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.clothescast.diag.DiagLog
import app.clothescast.core.data.tts.PcmAudio
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Plays a chunk of 16-bit signed mono PCM through [AudioTrack] in `MODE_STREAM`,
 * suspending until the playback head reaches the end of the buffer.
 *
 * Used by [GeminiTtsSpeaker] — Gemini returns 16-bit signed mono PCM with the
 * sample rate reported in the response mimeType.
 *
 * Uses `USAGE_ASSISTANT` to bypass the notification stream's compression/limiting,
 * which audibly distorts speech at 24 kHz. `MODE_STREAM` (rather than `MODE_STATIC`)
 * avoids end-of-buffer pops observed on some devices.
 */
internal object PcmAudioPlayer {

    private const val TAG = "PcmAudioPlayer"

    // Headroom past the clip's nominal duration before giving up on the
    // end-of-playback marker — covers the buffered tail plus scheduling
    // jitter on slow devices without meaningfully delaying a genuinely
    // missing marker.
    private const val MARKER_TIMEOUT_MARGIN_MS = 2_000L

    suspend fun play(audio: PcmAudio) {
        val pcm = audio.bytes
        if (pcm.isEmpty()) return
        // 16-bit mono → 2 bytes per frame. An odd payload means the response was
        // truncated mid-sample; setting a marker past the last whole frame would
        // either click or never fire.
        if (pcm.size % 2 != 0) {
            DiagLog.w(TAG, "PCM payload has odd byte count (%s); aborting playback", pcm.size)
            return
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            audio.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            DiagLog.w(TAG, "getMinBufferSize returned %s for %sHz; aborting playback", minBuffer, audio.sampleRate)
            return
        }
        // A few periods of headroom smooth over jitter on slower devices without
        // adding meaningful latency for short utterances.
        val bufferBytes = minBuffer * 2

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            val totalFrames = pcm.size / 2
            track.notificationMarkerPosition = totalFrames
            track.play()

            var offset = 0
            while (offset < pcm.size) {
                // Write one track-buffer's worth at a time, checking for
                // cancellation between chunks. AudioTrack.write is a blocking
                // JNI call (WRITE_BLOCKING), not a coroutine suspension point —
                // handing it the whole remaining PCM blocks here for nearly the
                // entire utterance, during which coroutine cancellation can't
                // land. That left the Stop affordances (settings voice preview
                // and the Today app-bar delivery cancel) unable to cut a long
                // Gemini cast short, since the cancellation hook only arms in
                // awaitMarker below — i.e. after the last byte is queued.
                // Bounding each write to the buffer keeps the blocking window
                // to a fraction of a second, so ensureActive() throws promptly
                // on cancel and the finally tears the track down.
                coroutineContext.ensureActive()
                val chunk = minOf(bufferBytes, pcm.size - offset)
                val written = track.write(pcm, offset, chunk)
                if (written <= 0) {
                    DiagLog.w(TAG, "AudioTrack.write returned %s at offset %s; aborting playback", written, offset)
                    return
                }
                offset += written
            }
            // Bound the wait: AudioTrack's end-of-buffer marker is flaky on
            // some devices (an underrun that stops the head *at* rather than
            // past the marker, or a dropped callback on the stop transition
            // never fires onMarkerReached). Un-bounded, that suspends this
            // coroutine forever — pinning the delivery worker until
            // WorkManager kills it, or hanging the Settings voice preview.
            // The full clip length plus a generous margin can only trip when
            // the marker genuinely went missing: by this point every byte is
            // queued, so at most one buffer of audio remains.
            val clipMillis = totalFrames * 1000L / audio.sampleRate
            val completed = withTimeoutOrNull(clipMillis + MARKER_TIMEOUT_MARGIN_MS) {
                awaitMarker(track, totalFrames)
            }
            if (completed == null) {
                DiagLog.w(
                    TAG,
                    "End-of-playback marker never fired for %s frames (~%sms); releasing the track anyway.",
                    totalFrames,
                    clipMillis,
                )
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private suspend fun awaitMarker(track: AudioTrack, markerInFrames: Int) {
        suspendCancellableCoroutine<Unit> { cont ->
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onPeriodicNotification(t: AudioTrack?) {}
                },
            )
            cont.invokeOnCancellation { runCatching { track.stop() } }
        }
        DiagLog.i(TAG, "Played %s PCM frames", markerInFrames)
    }
}
