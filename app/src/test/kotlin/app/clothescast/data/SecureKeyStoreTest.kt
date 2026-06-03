package app.clothescast.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.clothescast.core.data.insight.InvalidApiKeyException
import app.clothescast.core.data.insight.MissingApiKeyException
import com.google.crypto.tink.Aead
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class SecureKeyStoreTest {
    @TempDir lateinit var tempDir: Path

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var subject: SecureKeyStore

    @BeforeEach
    fun setUp() {
        // The :app module ships kotlinx-coroutines-android transitively (via lifecycle).
        // In unit tests, AndroidDispatcherFactory tries to call Looper.getMainLooper(),
        // which is unmocked and throws. Setting a test Main dispatcher up front shadows
        // that lookup. We pass an explicit TestCoroutineScheduler so the dispatcher
        // constructor doesn't itself read Dispatchers.Main (which would trigger the same
        // failure before setMain has a chance to install).
        Dispatchers.setMain(UnconfinedTestDispatcher(TestCoroutineScheduler()))
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir.toFile(), "secure_key.preferences_pb") },
        )
        subject = SecureKeyStore(aead = ReversibleFakeAead, dataStore = dataStore)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `set then get returns the same key`() = runTest {
        subject.set("AIzaSyExampleKeyValue123")

        subject.get() shouldBe "AIzaSyExampleKeyValue123"
    }

    @Test
    fun `get without prior set throws MissingApiKeyException`() = runTest {
        shouldThrow<MissingApiKeyException> { subject.get() }
    }

    @Test
    fun `clear removes the key`() = runTest {
        subject.set("AIzaSyExampleKeyValue123")
        subject.clear()

        shouldThrow<MissingApiKeyException> { subject.get() }
    }

    @Test
    fun `set replaces the previous key`() = runTest {
        subject.set("first")
        subject.set("second")

        subject.get() shouldBe "second"
    }

    @Test
    fun `stored ciphertext is not the plaintext key`() = runTest {
        val plaintext = "AIzaSyVerySecretValue"
        subject.set(plaintext)

        val storedB64 = dataStore.data.first()[GEMINI_KEY_PREFERENCE]
        // ReversibleFakeAead reverses the bytes, so the stored value must be a
        // base64 string that decodes to the reversed plaintext — confirming we
        // pass through the AEAD before persisting.
        storedB64 shouldBe java.util.Base64.getEncoder()
            .encodeToString(plaintext.toByteArray(Charsets.UTF_8).reversedArray())
    }

    @Test
    fun `corrupt ciphertext is cleared and reported as InvalidApiKeyException`() = runTest {
        // Simulate a Keystore master-key rotation: the stored ciphertext is no longer
        // decodeable. The store should drop the bad value and keep asking for re-entry.
        dataStore.edit { it[GEMINI_KEY_PREFERENCE] = "@@@not-valid-base64@@@" }

        shouldThrow<InvalidApiKeyException> { subject.get() }

        // And subsequent reads no longer see the corrupt value.
        dataStore.data.first()[GEMINI_KEY_PREFERENCE] shouldBe null
    }

    @Test
    fun `decrypt failure keeps reporting invalid key until re-entered`() = runTest {
        subject = SecureKeyStore(aead = DecryptFailsFakeAead, dataStore = dataStore)
        subject.set("AIzaSyExampleKeyValue123")

        shouldThrow<InvalidApiKeyException> { subject.get() }
        dataStore.data.first()[GEMINI_KEY_PREFERENCE] shouldBe null
        subject.geminiKeyNeedsReentryFlow.first() shouldBe true

        shouldThrow<InvalidApiKeyException> { subject.get() }

        subject = SecureKeyStore(aead = ReversibleFakeAead, dataStore = dataStore)
        subject.set("AIzaSyNewExampleKeyValue123")

        subject.geminiKeyNeedsReentryFlow.first() shouldBe false
        subject.get() shouldBe "AIzaSyNewExampleKeyValue123"
    }

    private companion object {
        // Mirrors SecureKeyStore's private Gemini preference key name. Kept in sync
        // deliberately so the test can assert against the on-disk preference name.
        val GEMINI_KEY_PREFERENCE = stringPreferencesKey("gemini_api_key_v1")
    }
}

/**
 * Test-only AEAD: encrypt = reverse the bytes; decrypt = reverse again.
 * Round-trip semantics are sufficient for SecureKeyStore's contract; we don't
 * test cryptographic strength here (Tink's own tests cover that).
 */
private object ReversibleFakeAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
        plaintext.reversedArray()

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
        ciphertext.reversedArray()
}

private object DecryptFailsFakeAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
        plaintext.reversedArray()

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
        throw java.security.GeneralSecurityException("Simulated decrypt failure")
}
