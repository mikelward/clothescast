package app.clothescast.ui.settings

import app.clothescast.core.domain.model.TtsEngine
import io.kotest.matchers.shouldBe
import org.junit.Test

class VoiceAccountSectionTest {

    @Test
    fun `a build with Google sign-in always shows the account section`() {
        // Signed out too, so a user who signed out can sign back in to delete
        // the account.
        voiceAccountSectionVisible(signedIn = false, googleSignInAvailable = true) shouldBe true
        voiceAccountSectionVisible(signedIn = true, googleSignInAvailable = true) shouldBe true
    }

    @Test
    fun `without sign-in in the build only a signed-in account shows it`() {
        voiceAccountSectionVisible(signedIn = true, googleSignInAvailable = false) shouldBe true
        voiceAccountSectionVisible(signedIn = false, googleSignInAvailable = false) shouldBe false
    }

    @Test
    fun `choosing Gemini signed out without a key starts the sign-in`() {
        geminiChoiceStartsSignIn(TtsEngine.GEMINI, signedIn = false, sharedTtsConfigured = true, geminiKeyConfigured = false) shouldBe true
        // Each condition alone turns it off.
        geminiChoiceStartsSignIn(TtsEngine.DEVICE, signedIn = false, sharedTtsConfigured = true, geminiKeyConfigured = false) shouldBe false
        geminiChoiceStartsSignIn(TtsEngine.GEMINI, signedIn = true, sharedTtsConfigured = true, geminiKeyConfigured = false) shouldBe false
        geminiChoiceStartsSignIn(TtsEngine.GEMINI, signedIn = false, sharedTtsConfigured = false, geminiKeyConfigured = false) shouldBe false
        geminiChoiceStartsSignIn(TtsEngine.GEMINI, signedIn = false, sharedTtsConfigured = true, geminiKeyConfigured = true) shouldBe false
    }

    @Test
    fun `turning Google on signed out without a key starts the sign-in`() {
        googleForecastStartsSignIn(signedIn = false, googleWeatherProxyConfigured = true, googleApiKeyConfigured = false) shouldBe true
        googleForecastStartsSignIn(signedIn = true, googleWeatherProxyConfigured = true, googleApiKeyConfigured = false) shouldBe false
        googleForecastStartsSignIn(signedIn = false, googleWeatherProxyConfigured = false, googleApiKeyConfigured = false) shouldBe false
        googleForecastStartsSignIn(signedIn = false, googleWeatherProxyConfigured = true, googleApiKeyConfigured = true) shouldBe false
    }
}
