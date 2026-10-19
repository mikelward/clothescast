package app.clothescast.ui.settings

import app.clothescast.R
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
    fun `Google is reachable with a key of your own, or signed in through the proxy`() {
        googleForecastReachable(googleApiKeyConfigured = true, googleWeatherProxyConfigured = false, signedIn = false) shouldBe true
        googleForecastReachable(googleApiKeyConfigured = false, googleWeatherProxyConfigured = true, signedIn = true) shouldBe true
        // Signed out with no key, the page shows the set the fetch uses: Google left out.
        googleForecastReachable(googleApiKeyConfigured = false, googleWeatherProxyConfigured = true, signedIn = false) shouldBe false
        googleForecastReachable(googleApiKeyConfigured = false, googleWeatherProxyConfigured = false, signedIn = true) shouldBe false
    }

    @Test
    fun `Google's switch holds a slot against the cap even while unreachable`() {
        forecasterSlots(openMeteoCount = 4, googleForecast = true) shouldBe 5
        forecasterSlots(openMeteoCount = 4, googleForecast = false) shouldBe 4
    }

    @Test
    fun `the Google subtitle mentions sign-in only where the build has it`() {
        googleForecasterSubtitle(googleWeatherProxyConfigured = true) shouldBe R.string.settings_forecasters_google_subtitle
        googleForecasterSubtitle(googleWeatherProxyConfigured = false) shouldBe R.string.settings_forecasters_google_subtitle_key_only
    }

    @Test
    fun `turning Google on signed out without a key starts the sign-in`() {
        googleForecastStartsSignIn(signedIn = false, googleWeatherProxyConfigured = true, googleApiKeyConfigured = false) shouldBe true
        googleForecastStartsSignIn(signedIn = true, googleWeatherProxyConfigured = true, googleApiKeyConfigured = false) shouldBe false
        googleForecastStartsSignIn(signedIn = false, googleWeatherProxyConfigured = false, googleApiKeyConfigured = false) shouldBe false
        googleForecastStartsSignIn(signedIn = false, googleWeatherProxyConfigured = true, googleApiKeyConfigured = true) shouldBe false
    }
}
