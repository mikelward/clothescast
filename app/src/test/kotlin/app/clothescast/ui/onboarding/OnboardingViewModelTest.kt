package app.clothescast.ui.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.clothescast.core.data.location.OpenMeteoGeocodingClient
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import app.clothescast.data.SecureKeyStore
import app.clothescast.data.SettingsRepository
import com.google.crypto.tink.Aead
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json as KotlinxJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @TempDir lateinit var tempDir: Path

    // See SettingsViewModelTest for why we install an explicit test scheduler and
    // pin DataStore's scope to it (Android JVM Main-dispatcher lookups crash).
    private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var settingsDataStore: DataStore<Preferences>
    private lateinit var keyDataStore: DataStore<Preferences>
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var keyStore: SecureKeyStore

    private val rearmed = mutableListOf<Pair<Schedule, ForecastPeriod>>()
    private val canceled = mutableListOf<ForecastPeriod>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dataStoreScope = CoroutineScope(dispatcher + SupervisorJob())
        settingsDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tempDir.toFile(), "settings.preferences_pb") },
        )
        keyDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tempDir.toFile(), "key.preferences_pb") },
        )
        settingsRepository = SettingsRepository(settingsDataStore)
        keyStore = SecureKeyStore(IdentityFakeAead, keyDataStore)
    }

    @AfterEach
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel() = OnboardingViewModel(
        secureKeyStore = keyStore,
        settingsRepository = settingsRepository,
        geocodingClient = OpenMeteoGeocodingClient(emptyGeocoding()),
        rearmAlarm = { schedule, period -> rearmed += schedule to period },
        cancelAlarm = { period -> canceled += period },
    )

    @Test
    fun `granting notification permission keeps the morning slot on`() = runTest {
        viewModel().applyMorningSlotDefault(notificationGranted = true)

        settingsRepository.preferences.first().dailyEnabled shouldBe true
        rearmed.map { it.second } shouldBe listOf(ForecastPeriod.TODAY)
        canceled shouldBe emptyList()
    }

    @Test
    fun `declining notification permission turns the morning slot off`() = runTest {
        viewModel().applyMorningSlotDefault(notificationGranted = false)

        settingsRepository.preferences.first().dailyEnabled shouldBe false
        canceled shouldBe listOf(ForecastPeriod.TODAY)
        rearmed shouldBe emptyList()
    }

    private fun emptyGeocoding() = HttpClient(
        MockEngine {
            respond(
                content = ByteReadChannel("""{"results":[]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) { install(ContentNegotiation) { json(KotlinxJson { ignoreUnknownKeys = true }) } }
}

/** Pass-through AEAD: encrypt/decrypt are identity. Sufficient for these tests. */
private object IdentityFakeAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray = plaintext
    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray = ciphertext
}
