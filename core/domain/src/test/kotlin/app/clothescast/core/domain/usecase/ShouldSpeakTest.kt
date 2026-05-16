package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.Location
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ShouldSpeakTest {

    private val home = Location(51.5074, -0.1278, "Home")

    // ~50m from home — well inside the 1km at-home radius.
    private val atHome = Location(51.50785, -0.1278)

    // ~4km away — well outside the 1km at-home radius.
    private val away = Location(51.5055, -0.0754)

    @Test
    fun `does not speak when TTS not requested`() {
        shouldSpeak(
            ttsRequested = false,
            skipTtsAtHome = false,
            homeLocation = null,
            currentLocation = null,
        ) shouldBe false
    }

    @Test
    fun `speaks when TTS requested and gate is off`() {
        shouldSpeak(
            ttsRequested = true,
            skipTtsAtHome = false,
            homeLocation = home,
            currentLocation = atHome,
        ) shouldBe true
    }

    @Test
    fun `fails open when no home configured`() {
        shouldSpeak(
            ttsRequested = true,
            skipTtsAtHome = true,
            homeLocation = null,
            currentLocation = atHome,
        ) shouldBe true
    }

    @Test
    fun `fails open when current location cannot be resolved`() {
        shouldSpeak(
            ttsRequested = true,
            skipTtsAtHome = true,
            homeLocation = home,
            currentLocation = null,
        ) shouldBe true
    }

    @Test
    fun `suppresses TTS when at home and gate is on`() {
        shouldSpeak(
            ttsRequested = true,
            skipTtsAtHome = true,
            homeLocation = home,
            currentLocation = atHome,
        ) shouldBe false
    }

    @Test
    fun `speaks when away from home and gate is on`() {
        shouldSpeak(
            ttsRequested = true,
            skipTtsAtHome = true,
            homeLocation = home,
            currentLocation = away,
        ) shouldBe true
    }

    @Test
    fun `custom radius respects threshold`() {
        // Same fix, two radii — narrows the gate so the same nearby point
        // now reads as "not at home." Locks in that the radius parameter is
        // actually consulted rather than ignored.
        val justOutside = Location(51.5074 + 0.01, -0.1278) // ~1.1 km north
        shouldSpeak(
            ttsRequested = true,
            skipTtsAtHome = true,
            homeLocation = home,
            currentLocation = justOutside,
            radiusMeters = 500.0,
        ) shouldBe true
        shouldSpeak(
            ttsRequested = true,
            skipTtsAtHome = true,
            homeLocation = home,
            currentLocation = justOutside,
            radiusMeters = 2_000.0,
        ) shouldBe false
    }
}
