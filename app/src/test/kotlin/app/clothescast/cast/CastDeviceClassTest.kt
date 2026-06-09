package app.clothescast.cast

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CastDeviceClassTest {

    @Test
    fun `display gets the muxed MP4 regardless of audio`() {
        mediaPlanFor(CastDeviceClass.DISPLAY, hasRealAudio = true) shouldBe CastMediaPlan.Mp4
        // No real audio on a display still loads the image-only MP4 (silent
        // carrier keeps the outfit picture up).
        mediaPlanFor(CastDeviceClass.DISPLAY, hasRealAudio = false) shouldBe CastMediaPlan.Mp4
    }

    @Test
    fun `unknown routes fall back to the display MP4 path`() {
        mediaPlanFor(CastDeviceClass.UNKNOWN, hasRealAudio = true) shouldBe CastMediaPlan.Mp4
        mediaPlanFor(CastDeviceClass.UNKNOWN, hasRealAudio = false) shouldBe CastMediaPlan.Mp4
    }

    @Test
    fun `speaker with audio gets a bare WAV`() {
        mediaPlanFor(CastDeviceClass.AUDIO_ONLY, hasRealAudio = true) shouldBe CastMediaPlan.Wav
    }

    @Test
    fun `speaker without audio is skipped rather than playing silence`() {
        mediaPlanFor(CastDeviceClass.AUDIO_ONLY, hasRealAudio = false) shouldBe
            CastMediaPlan.SkipNoAudio
    }

    @Test
    fun `media kinds carry distinct suffixes and content types`() {
        CastMediaKind.MP4.suffix shouldBe "/insight.mp4"
        CastMediaKind.MP4.contentType.toString() shouldBe "video/mp4"
        CastMediaKind.WAV.suffix shouldBe "/insight.wav"
        CastMediaKind.WAV.contentType.toString() shouldBe "audio/wav"
    }
}
