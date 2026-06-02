package app.clothescast.ui.settings

import app.clothescast.core.domain.model.TtsEngine
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SettingsStateTest {
    @Test
    fun `device engine counts as configured without any Gemini setup`() {
        val state = SettingsState(
            ttsEngine = TtsEngine.DEVICE,
            apiKeyConfigured = false,
            sharedTtsAvailable = false,
        )
        state.speechConfigured shouldBe true
    }

    @Test
    fun `gemini is configured once a BYOK key is set`() {
        val state = SettingsState(
            ttsEngine = TtsEngine.GEMINI,
            apiKeyConfigured = true,
            sharedTtsAvailable = false,
        )
        state.speechConfigured shouldBe true
    }

    @Test
    fun `gemini is configured when the shared proxy is available`() {
        val state = SettingsState(
            ttsEngine = TtsEngine.GEMINI,
            apiKeyConfigured = false,
            sharedTtsAvailable = true,
        )
        state.speechConfigured shouldBe true
    }

    @Test
    fun `gemini without a key or proxy is not configured`() {
        val state = SettingsState(
            ttsEngine = TtsEngine.GEMINI,
            apiKeyConfigured = false,
            sharedTtsAvailable = false,
        )
        state.speechConfigured shouldBe false
    }
}
