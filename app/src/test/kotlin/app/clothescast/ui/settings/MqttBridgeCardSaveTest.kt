package app.clothescast.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Saving broker credentials must not pin the topic. The Save button commits
 * the whole form, topic field included, so a blank topic field has to reach
 * the repository as blank ("follow the Name"), never as the derived topic its
 * placeholder shows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w360dp-h2000dp-xhdpi")
class MqttBridgeCardSaveTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private data class Saved(val username: String, val topic: String, val password: String?)

    private fun render(topicOverride: String?, customName: String?, onSave: (Saved) -> Unit) {
        composeRule.setContent {
            MqttBridgeCard(
                enabled = true,
                host = "broker.example.com",
                port = 1883,
                useTls = false,
                username = "",
                topicOverride = topicOverride,
                customName = customName,
                passwordSet = false,
                lastError = null,
                lastErrorAt = 0L,
                lastPublishAt = 0L,
                publishing = false,
                skipPhoneSpeech = false,
                discoveryRunning = false,
                discoveredServices = emptyList(),
                onSetEnabled = {},
                onSaveConfig = { _, _, _, username, topic, password -> onSave(Saved(username, topic, password)) },
                onClearPassword = {},
                onPublishNow = {},
                onSetSkipPhoneSpeech = {},
                onStartDiscovery = {},
                onStopDiscovery = {},
                onUseDiscoveredService = {},
            )
        }
    }

    private fun enterCredentialsAndSave() {
        composeRule.onNodeWithText("Username (optional)").performTextInput("user")
        composeRule.onNodeWithText("Password (optional)").performTextInput("secret")
        composeRule.onNodeWithText("Save").performClick()
    }

    @Test
    fun `saving a username and password leaves the default topic unpinned`() {
        var saved: Saved? = null
        render(topicOverride = null, customName = null) { saved = it }

        enterCredentialsAndSave()

        saved shouldBe Saved(username = "user", topic = "", password = "secret")
    }

    @Test
    fun `saving credentials with a Name set leaves the topic following it`() {
        var saved: Saved? = null
        render(topicOverride = null, customName = "Alex's") { saved = it }

        enterCredentialsAndSave()

        saved?.topic shouldBe ""
    }

    @Test
    fun `saving credentials keeps a typed topic as it was`() {
        var saved: Saved? = null
        render(topicOverride = "home/kitchen", customName = null) { saved = it }

        enterCredentialsAndSave()

        saved?.topic shouldBe "home/kitchen"
    }
}
