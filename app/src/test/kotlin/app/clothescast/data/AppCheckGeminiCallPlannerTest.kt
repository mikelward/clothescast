package app.clothescast.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.clothescast.core.data.insight.GeminiEndpoint
import app.clothescast.core.data.insight.InvalidApiKeyException
import com.google.crypto.tink.Aead
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.GeneralSecurityException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppCheckGeminiCallPlannerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(TestCoroutineScheduler()))
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(temporaryFolder.root, "secure_key.preferences_pb") },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `plan does not fall back to shared proxy after configured key cannot decrypt`() = runTest {
        val secureKeyStore = SecureKeyStore(aead = PlannerDecryptFailsFakeAead, dataStore = dataStore)
        secureKeyStore.set("AIzaSyExampleKeyValue123")
        val subject = AppCheckGeminiCallPlanner(
            secureKeyStore = secureKeyStore,
            proxyBaseUrl = "https://example.test/tts",
            sharedPathEnabled = true,
            model = "gemini-test",
        )

        shouldThrow<InvalidApiKeyException> { subject.plan() }
        shouldThrow<InvalidApiKeyException> { subject.plan() }
    }

    @Test
    fun `parseProxyEndpoint splits host and single path segment`() {
        val endpoint = AppCheckGeminiCallPlanner.parseProxyEndpoint(
            "https://us-central1-myproj.cloudfunctions.net/tts",
        )

        endpoint shouldBe GeminiEndpoint(
            host = "us-central1-myproj.cloudfunctions.net",
            apiVersion = "tts",
        )
    }

    @Test
    fun `parseProxyEndpoint tolerates trailing slash`() {
        val endpoint = AppCheckGeminiCallPlanner.parseProxyEndpoint(
            "https://example.test/tts/",
        )

        endpoint.apiVersion shouldBe "tts"
    }

    @Test
    fun `parseProxyEndpoint handles bare host without path`() {
        val endpoint = AppCheckGeminiCallPlanner.parseProxyEndpoint("https://example.test")

        endpoint shouldBe GeminiEndpoint(host = "example.test", apiVersion = "")
    }

    @Test
    fun `parseProxyEndpoint rejects multi-segment paths`() {
        shouldThrow<IllegalArgumentException> {
            AppCheckGeminiCallPlanner.parseProxyEndpoint("https://example.test/api/tts")
        }
    }
}

private object PlannerDecryptFailsFakeAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
        plaintext.reversedArray()

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
        throw GeneralSecurityException("Simulated decrypt failure")
}
