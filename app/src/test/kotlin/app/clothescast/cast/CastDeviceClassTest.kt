package app.clothescast.cast

import androidx.mediarouter.media.MediaRouter.RouteInfo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CastDeviceClassTest {

    @Test
    fun `a cast device reporting video-out is a display`() {
        classifyDevice(
            hasCastDevice = true,
            hasVideoOut = true,
            isGroup = false,
            deviceType = RouteInfo.DEVICE_TYPE_TV,
        ) shouldBe CastDeviceClass.DISPLAY
    }

    @Test
    fun `a cast device with no video-out is audio-only`() {
        classifyDevice(
            hasCastDevice = true,
            hasVideoOut = false,
            isGroup = false,
            deviceType = RouteInfo.DEVICE_TYPE_SPEAKER,
        ) shouldBe CastDeviceClass.AUDIO_ONLY
    }

    @Test
    fun `a group route with no cast device extras is audio-only, not unknown`() {
        // Speaker groups often expose null extras, so the video-out check is
        // skipped — the group signal must still route them to the WAV path
        // rather than the padded image+audio MP4.
        classifyDevice(
            hasCastDevice = false,
            hasVideoOut = false,
            isGroup = true,
            deviceType = RouteInfo.DEVICE_TYPE_GROUP,
        ) shouldBe CastDeviceClass.AUDIO_ONLY
    }

    @Test
    fun `a speaker device type with no cast device is audio-only`() {
        classifyDevice(
            hasCastDevice = false,
            hasVideoOut = false,
            isGroup = false,
            deviceType = RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER,
        ) shouldBe CastDeviceClass.AUDIO_ONLY
    }

    @Test
    fun `an unidentifiable route falls back to unknown`() {
        classifyDevice(
            hasCastDevice = false,
            hasVideoOut = false,
            isGroup = false,
            deviceType = RouteInfo.DEVICE_TYPE_UNKNOWN,
        ) shouldBe CastDeviceClass.UNKNOWN
    }

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
