package app.clothescast.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import app.clothescast.core.data.location.OpenMeteoGeocodingClient
import app.clothescast.core.data.weather.GoogleWeatherProbe
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.PreambleVisibility
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.WindSpeedUnit
import app.clothescast.core.domain.model.WindSpeedUnitSetting
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TemperatureUnitSetting
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TimeFormatSetting
import app.clothescast.core.domain.model.UpcomingCalendarEvent
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.data.SecureKeyStore
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.data.SettingsRepository
import app.clothescast.discovery.DiscoveredService
import app.clothescast.discovery.HomeAssistantDiscovery
import app.clothescast.discovery.ServiceType
import app.clothescast.tts.DeviceVoice
import app.clothescast.tts.TtsVoiceEnumerator
import java.util.Locale
import com.google.crypto.tink.Aead
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json as KotlinxJson
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class SettingsViewModelTest {
    @TempDir lateinit var tempDir: Path

    // Explicit scheduler avoids the dispatcher's internal Dispatchers.Main lookup, which
    // crashes on Android JVM unit tests because AndroidDispatcherFactory calls the
    // unmocked Looper.getMainLooper(). See SecureKeyStoreTest for the same workaround.
    private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())
    // DataStore's default scope is `Dispatchers.IO + SupervisorJob()` — its internal
    // actor coroutine outlives the test body and, when it resumes after `resetMain`,
    // can't dispatch back through TestMainDispatcher (which delegates to the now-
    // missing Dispatchers.Main) and crashes the JVM with "Module with the Main
    // dispatcher is missing". Pinning DataStore to our test dispatcher and cancelling
    // the scope in tearDown ensures DataStore's lifecycle is bounded by the test.
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var settingsDataStore: DataStore<Preferences>
    private lateinit var keyDataStore: DataStore<Preferences>
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var keyStore: SecureKeyStore
    private lateinit var subject: SettingsViewModel
    // SettingsState's auto-unit defaults read Locale.getDefault(), so an en-US JVM
    // (e.g. the Cursor Cloud VM) flips the unit defaults to Fahrenheit / miles and
    // breaks the "initial state reflects repository defaults" assertion. Pin a
    // metric locale for the test class so the defaults are deterministic.
    private lateinit var originalLocale: Locale
    // viewModelScope owns an infinite preferences.collect loop and the device-voice
    // load job. Without bounding them to the test, those coroutines can outlive
    // runTest's body and resume with an exception (after the test's scopes are
    // cancelled in tearDown), surfacing as UncaughtExceptionsBeforeTest on the next
    // test. ViewModelStore.clear() cancels every registered viewmodel's scope in one
    // call; tearDown then drains the scheduler so that cancellation completes before
    // resetMain(), which closes the race deterministically.
    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey = 0
    private fun <T : ViewModel> track(vm: T): T = vm.also {
        viewModelStore.put("vm-${nextViewModelKey++}", it)
    }

    @BeforeEach
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
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
        // Geocoding client wired with a Mock engine that always returns an empty response;
        // the tests in this class don't exercise location lookup.
        val emptyGeocoding = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel("""{"results":[]}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) { install(ContentNegotiation) { json(KotlinxJson { ignoreUnknownKeys = true }) } }
        subject = track(
            SettingsViewModel(
                settingsRepository,
                keyStore,
                rearmAlarm = { _, _ -> },
                cancelAlarm = { _ -> },
                geocodingClient = OpenMeteoGeocodingClient(emptyGeocoding),
                voiceEnumerator = EmptyVoiceEnumerator,
                voiceEnumerationDispatcher = dispatcher,
            ),
        )
    }

    /**
     * The ViewModel calls this from its preferences flow on every locale
     * change; the existing tests don't care about device-voice enumeration
     * so we hand back empty results without any Android plumbing.
     */
    private object EmptyVoiceEnumerator : TtsVoiceEnumerator {
        override suspend fun listVoices(locale: Locale): List<DeviceVoice> = emptyList()
        override suspend fun resolveAutoPick(locale: Locale): DeviceVoice? = null
        override suspend fun findVoice(id: String): DeviceVoice? = null
    }

    @AfterEach
    fun tearDown() {
        // Cancel the viewmodel + DataStore scopes, then drain the shared test
        // scheduler so their cancellation runs to completion WHILE Main is still
        // set. The viewmodel's infinite preferences.collect, its device-voice
        // load job, and DataStore's actor all live on `dispatcher`; if any is
        // mid-flight when the test body ends, cancelling without draining lets it
        // resume after resetMain(), fail to dispatch through the now-missing Main
        // dispatcher, and surface as UncaughtExceptionsBeforeTest against the
        // next test. Draining before resetMain() makes teardown deterministic.
        viewModelStore.clear()
        dataStoreScope.cancel()
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `initial state reflects repository defaults`() = runTest {
        // The initial MutableStateFlow value matches our defaults; await the first
        // collector emission so anything from DataStore that disagreed would surface here.
        val state = subject.state.first {
            !it.apiKeyConfigured && it.deliveryMode == DeliveryMode.NOTIFICATION_AND_TTS
        }
        state.temperatureUnit shouldBe TemperatureUnit.CELSIUS
        state.windSpeedUnit shouldBe WindSpeedUnit.KMH
    }

    @Test
    fun `setApiKey persists to keystore and updates state`() = runTest {
        subject.setApiKey("AIzaSyTestKeyValue")
        subject.state.first { it.apiKeyConfigured }
        keyStore.get() shouldBe "AIzaSyTestKeyValue"
    }

    @Test
    fun `setApiKey trims whitespace`() = runTest {
        subject.setApiKey("   AIzaSyKey   ")
        subject.state.first { it.apiKeyConfigured }
        keyStore.get() shouldBe "AIzaSyKey"
    }

    @Test
    fun `clearApiKey removes the stored key and updates state`() = runTest {
        subject.setApiKey("AIzaSyKey")
        subject.state.first { it.apiKeyConfigured }
        subject.clearApiKey()
        subject.state.first { !it.apiKeyConfigured }
    }

    @Test
    fun `clearGoogleApiKey drops the Google forecaster from a custom selection`() = runTest {
        // Google needs its own key; clearing it must remove Google so the
        // selection doesn't keep a forecaster that can no longer contribute.
        // Clearing the Gemini key, by contrast, leaves the selection alone.
        subject.setGoogleApiKey("AIzaSyKey")
        subject.state.first { it.googleApiKeyConfigured }
        subject.setForecastModels(
            setOf(ForecastModel.ECMWF_IFS025, ForecastModel.GFS_SEAMLESS, ForecastModel.GOOGLE_WEATHER),
        )
        settingsRepository.preferences.first {
            it.forecastModels?.contains(ForecastModel.GOOGLE_WEATHER) == true
        }

        subject.clearGoogleApiKey()

        settingsRepository.preferences
            .first { it.forecastModels == setOf(ForecastModel.ECMWF_IFS025, ForecastModel.GFS_SEAMLESS) }
            .forecastModels shouldBe setOf(ForecastModel.ECMWF_IFS025, ForecastModel.GFS_SEAMLESS)
    }

    @Test
    fun `clearApiKey leaves the Google forecaster selection alone`() = runTest {
        // The Gemini key no longer powers Google, so clearing it must not touch
        // the Forecasters selection.
        subject.setGoogleApiKey("AIzaGoogle")
        subject.state.first { it.googleApiKeyConfigured }
        subject.setForecastModels(
            setOf(ForecastModel.ECMWF_IFS025, ForecastModel.GFS_SEAMLESS, ForecastModel.GOOGLE_WEATHER),
        )
        settingsRepository.preferences.first {
            it.forecastModels?.contains(ForecastModel.GOOGLE_WEATHER) == true
        }

        subject.setApiKey("AIzaGemini")
        subject.state.first { it.apiKeyConfigured }
        subject.clearApiKey()
        subject.state.first { !it.apiKeyConfigured }

        settingsRepository.preferences.first().forecastModels shouldBe
            setOf(ForecastModel.ECMWF_IFS025, ForecastModel.GFS_SEAMLESS, ForecastModel.GOOGLE_WEATHER)
    }

    @Test
    fun `clearGoogleApiKey falls back to Auto when dropping Google would leave too few models`() = runTest {
        // Google + one Open-Meteo model: removing Google leaves a single model,
        // below the two-model consensus floor, so the selection reverts to Auto.
        subject.setGoogleApiKey("AIzaSyKey")
        subject.state.first { it.googleApiKeyConfigured }
        subject.setForecastModels(setOf(ForecastModel.ECMWF_IFS025, ForecastModel.GOOGLE_WEATHER))
        settingsRepository.preferences.first {
            it.forecastModels?.contains(ForecastModel.GOOGLE_WEATHER) == true
        }

        subject.clearGoogleApiKey()

        settingsRepository.preferences.first { it.forecastModels == null }
            .forecastModels shouldBe null
    }

    @Test
    fun `probeGoogleWeather surfaces the probe result in state`() = runTest {
        val vm = track(
            SettingsViewModel(
                settingsRepository,
                keyStore,
                rearmAlarm = { _, _ -> },
                cancelAlarm = { _ -> },
                geocodingClient = OpenMeteoGeocodingClient(
                    HttpClient(MockEngine {
                        respond(
                            content = ByteReadChannel("""{"results":[]}"""),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }) { install(ContentNegotiation) { json(KotlinxJson { ignoreUnknownKeys = true }) } },
                ),
                voiceEnumerator = EmptyVoiceEnumerator,
                voiceEnumerationDispatcher = dispatcher,
                googleWeatherProbe = { GoogleWeatherProbe.Forbidden },
            ),
        )

        vm.probeGoogleWeather()

        vm.state.first { it.googleProbeResult == GoogleWeatherProbe.Forbidden }
        vm.state.first { !it.googleProbeRunning }
    }

    @Test
    fun `setGoogleApiKey re-probes Google when it is selected so a replaced key refreshes the status`() = runTest {
        var probeCount = 0
        val vm = track(
            SettingsViewModel(
                settingsRepository,
                keyStore,
                rearmAlarm = { _, _ -> },
                cancelAlarm = { _ -> },
                geocodingClient = OpenMeteoGeocodingClient(
                    HttpClient(MockEngine {
                        respond(
                            content = ByteReadChannel("""{"results":[]}"""),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }) { install(ContentNegotiation) { json(KotlinxJson { ignoreUnknownKeys = true }) } },
                ),
                voiceEnumerator = EmptyVoiceEnumerator,
                voiceEnumerationDispatcher = dispatcher,
                googleWeatherProbe = { probeCount++; GoogleWeatherProbe.Reachable(hours = 12) },
            ),
        )
        vm.setGoogleApiKey("AIzaFirst")
        vm.state.first { it.googleApiKeyConfigured }
        vm.setForecastModels(setOf(ForecastModel.ECMWF_IFS025, ForecastModel.GOOGLE_WEATHER))
        vm.state.first { it.forecastModels?.contains(ForecastModel.GOOGLE_WEATHER) == true }

        // Selecting Google doesn't probe (that's the UI LaunchedEffect's job);
        // replacing the key while Google is selected must, so the recovery flow
        // doesn't leave a stale verdict against the old key.
        val before = probeCount
        vm.setGoogleApiKey("AIzaReplacement")
        vm.state.first { it.googleProbeResult == GoogleWeatherProbe.Reachable(hours = 12) }
        (probeCount > before) shouldBe true
    }

    @Test
    fun `setGoogleApiKey does not probe Google when it is not selected`() = runTest {
        var probeCount = 0
        val vm = track(
            SettingsViewModel(
                settingsRepository,
                keyStore,
                rearmAlarm = { _, _ -> },
                cancelAlarm = { _ -> },
                geocodingClient = OpenMeteoGeocodingClient(
                    HttpClient(MockEngine {
                        respond(
                            content = ByteReadChannel("""{"results":[]}"""),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }) { install(ContentNegotiation) { json(KotlinxJson { ignoreUnknownKeys = true }) } },
                ),
                voiceEnumerator = EmptyVoiceEnumerator,
                voiceEnumerationDispatcher = dispatcher,
                googleWeatherProbe = { probeCount++; GoogleWeatherProbe.Reachable(hours = 12) },
            ),
        )
        vm.setGoogleApiKey("AIzaKey")
        vm.state.first { it.googleApiKeyConfigured }
        probeCount shouldBe 0
    }

    @Test
    fun `probeGoogleWeather supersedes an in-flight probe so a key swap mid-probe wins`() = runTest {
        // First probe blocks on a gate (mimics an old-key request still in
        // flight); the second must cancel it and land its own verdict, and the
        // first's stale result must never overwrite the fresh one.
        val gate = CompletableDeferred<Unit>()
        var starts = 0
        val vm = track(
            SettingsViewModel(
                settingsRepository,
                keyStore,
                rearmAlarm = { _, _ -> },
                cancelAlarm = { _ -> },
                geocodingClient = OpenMeteoGeocodingClient(
                    HttpClient(MockEngine {
                        respond(
                            content = ByteReadChannel("""{"results":[]}"""),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }) { install(ContentNegotiation) { json(KotlinxJson { ignoreUnknownKeys = true }) } },
                ),
                voiceEnumerator = EmptyVoiceEnumerator,
                voiceEnumerationDispatcher = dispatcher,
                googleWeatherProbe = {
                    starts++
                    if (starts == 1) {
                        gate.await() // old-key probe hangs, then would return a stale verdict
                        GoogleWeatherProbe.Forbidden
                    } else {
                        GoogleWeatherProbe.Reachable(hours = 12)
                    }
                },
            ),
        )

        vm.probeGoogleWeather()
        vm.state.first { it.googleProbeRunning }
        vm.probeGoogleWeather() // supersede the gated first probe

        vm.state.first { it.googleProbeResult == GoogleWeatherProbe.Reachable(hours = 12) }
        starts shouldBe 2
        // Releasing the cancelled first probe must not resurrect its 403.
        gate.complete(Unit)
        vm.state.first { !it.googleProbeRunning }
        vm.state.first().googleProbeResult shouldBe GoogleWeatherProbe.Reachable(hours = 12)
    }

    @Test
    fun `probeGoogleWeather no-ops without a probe backend`() = runTest {
        // The default subject is wired without a probe (pure-VM); the call is
        // inert and the status stays unset rather than throwing.
        subject.probeGoogleWeather()
        subject.state.first().googleProbeResult shouldBe null
    }

    @Test
    fun `setDeliveryMode persists`() = runTest {
        subject.setDeliveryMode(DeliveryMode.NOTIFICATION_AND_TTS)
        subject.state.first { it.deliveryMode == DeliveryMode.NOTIFICATION_AND_TTS }
        settingsRepository.preferences.first().deliveryMode shouldBe DeliveryMode.NOTIFICATION_AND_TTS
    }

    @Test
    fun `setTonightDeliveryMode persists independently of deliveryMode`() = runTest {
        subject.setDeliveryMode(DeliveryMode.NOTIFICATION_ONLY)
        subject.setTonightDeliveryMode(DeliveryMode.TTS_ONLY)
        subject.state.first {
            it.deliveryMode == DeliveryMode.NOTIFICATION_ONLY &&
                it.tonightDeliveryMode == DeliveryMode.TTS_ONLY
        }
        val prefs = settingsRepository.preferences.first()
        prefs.deliveryMode shouldBe DeliveryMode.NOTIFICATION_ONLY
        prefs.tonightDeliveryMode shouldBe DeliveryMode.TTS_ONLY
    }

    @Test
    fun `setUseCalendarEvents persists and surfaces in state`() = runTest {
        subject.setUseCalendarEvents(true)
        subject.state.first { it.useCalendarEvents }
        settingsRepository.preferences.first().useCalendarEvents shouldBe true

        subject.setUseCalendarEvents(false)
        subject.state.first { !it.useCalendarEvents }
        settingsRepository.preferences.first().useCalendarEvents shouldBe false
    }

    @Test
    fun `setClothesMentionMode persists and surfaces in state`() = runTest {
        subject.state.first { it.clothesMentionMode == ClothesMentionMode.ALWAYS }

        subject.setClothesMentionMode(ClothesMentionMode.IF_CHANGED)
        subject.state.first { it.clothesMentionMode == ClothesMentionMode.IF_CHANGED }
        settingsRepository.preferences.first().clothesMentionMode shouldBe ClothesMentionMode.IF_CHANGED

        subject.setClothesMentionMode(ClothesMentionMode.NEVER)
        subject.state.first { it.clothesMentionMode == ClothesMentionMode.NEVER }
        settingsRepository.preferences.first().clothesMentionMode shouldBe ClothesMentionMode.NEVER
    }

    @Test
    fun `setPeriodPreamble persists and surfaces in state`() = runTest {
        subject.state.first { it.periodPreamble == PreambleVisibility.ALWAYS }

        subject.setPeriodPreamble(PreambleVisibility.NEVER)
        subject.state.first { it.periodPreamble == PreambleVisibility.NEVER }
        settingsRepository.preferences.first().periodPreamble shouldBe PreambleVisibility.NEVER
    }

    @Test
    fun `setWearPreamble persists and surfaces in state`() = runTest {
        subject.state.first { it.wearPreamble == PreambleVisibility.ALWAYS }

        subject.setWearPreamble(PreambleVisibility.SPEECH_ONLY)
        subject.state.first { it.wearPreamble == PreambleVisibility.SPEECH_ONLY }
        settingsRepository.preferences.first().wearPreamble shouldBe PreambleVisibility.SPEECH_ONLY
    }

    @Test
    fun `enabling a sub-feature switches the master on`() = runTest {
        subject.state.first { !it.calendarEnabled }

        subject.setThemeFromCalendarBirthdays(true)

        val state = subject.state.first { it.themeFromCalendarBirthdays }
        state.calendarEnabled shouldBe true
        // The other sub-features stay off — enabling one doesn't enable the rest.
        state.useCalendarEvents shouldBe false
        state.themeFromCalendarHolidays shouldBe false
    }

    @Test
    fun `disabling the master switch clears the per-feature toggles`() = runTest {
        subject.setUseCalendarEvents(true)
        subject.setThemeFromCalendarHolidays(true)
        subject.setThemeFromCalendarBirthdays(true)
        subject.state.first {
            it.calendarEnabled && it.useCalendarEvents &&
                it.themeFromCalendarHolidays && it.themeFromCalendarBirthdays
        }

        subject.setCalendarEnabled(false)

        val off = subject.state.first { !it.calendarEnabled }
        off.useCalendarEvents shouldBe false
        off.themeFromCalendarHolidays shouldBe false
        off.themeFromCalendarBirthdays shouldBe false

        // Re-enabling a single feature flips the master back on without
        // silently reviving the features that were cleared.
        subject.setThemeFromCalendarBirthdays(true)
        val back = subject.state.first { it.themeFromCalendarBirthdays }
        back.calendarEnabled shouldBe true
        back.useCalendarEvents shouldBe false
        back.themeFromCalendarHolidays shouldBe false
    }

    @Test
    fun `loadCalendarCelebrations collapses only true duplicates, keeping same-name events on different dates`() = runTest {
        // Regression for the dedupe key: two contacts who share a name with
        // different birthdays (or a same-named holiday recurring on different
        // dates) must both stay listed; only the same event imported into two
        // synced calendars — identical (date, title, kind) — folds together.
        val june = java.time.LocalDate.of(2026, 6, 1)
        val september = java.time.LocalDate.of(2026, 9, 1)
        val reader = object : CalendarEventReader {
            override suspend fun eventsForDay(
                date: java.time.LocalDate,
                zoneId: java.time.ZoneId,
            ): List<CalendarEvent> = emptyList()

            override suspend fun upcomingCelebrations(
                startInclusive: java.time.LocalDate,
                endExclusive: java.time.LocalDate,
                zoneId: java.time.ZoneId,
            ): List<UpcomingCalendarEvent> = listOf(
                UpcomingCalendarEvent(june, "Alex’s birthday", EventKind.BIRTHDAY),
                UpcomingCalendarEvent(june, "Alex’s birthday", EventKind.BIRTHDAY), // exact dup → collapse
                UpcomingCalendarEvent(september, "Alex’s birthday", EventKind.BIRTHDAY), // same name, later date → keep
                UpcomingCalendarEvent(june, "Christmas Day", EventKind.PUBLIC_HOLIDAY),
            )
        }
        val vm = track(
            SettingsViewModel(
                settingsRepository = settingsRepository,
                keyStore = keyStore,
                rearmAlarm = { _, _ -> },
                cancelAlarm = { _ -> },
                geocodingClient = OpenMeteoGeocodingClient(
                    HttpClient(MockEngine { respond("""{"results":[]}""") }) {
                        install(ContentNegotiation) { json(KotlinxJson { ignoreUnknownKeys = true }) }
                    },
                ),
                voiceEnumerator = EmptyVoiceEnumerator,
                voiceEnumerationDispatcher = dispatcher,
                calendarEventReader = reader,
            ),
        )

        vm.loadCalendarCelebrations()
        val state = vm.state.first { it.calendarCelebrations != null }

        state.calendarCelebrations!! shouldHaveSize 3
        state.calendarCelebrations!!.count { it.title == "Alex’s birthday" } shouldBe 2
    }

    @Test
    fun `selectLocation also disables device location so the manual pick sticks`() = runTest {
        // Without this, the next worker run's device-resolve would write
        // through and immediately overwrite the city the user just picked —
        // defeating the override. The Location page surfaces a disclosure
        // ("Picking a city turns off auto-detect") so the toggle doesn't
        // appear to flip on its own.
        subject.setUseDeviceLocation(true)
        subject.state.first { it.useDeviceLocation }

        subject.selectLocation(
            app.clothescast.core.domain.model.Location(
                latitude = 51.5074,
                longitude = -0.1278,
                displayName = "London",
            ),
        )

        val state = subject.state.first { !it.useDeviceLocation && it.location != null }
        state.location?.displayName shouldBe "London"
        val prefs = settingsRepository.preferences.first()
        prefs.useDeviceLocation shouldBe false
        prefs.location?.displayName shouldBe "London"
    }

    @Test
    fun `setUseDeviceLocation triggers the eager cache refresh only when enabled`() = runTest {
        // The Activity wires this lambda to `FetchAndNotifyWorker.enqueueOneShot`
        // so the user sees their detected city populate within seconds of
        // toggling device location ON, instead of waiting for the next
        // morning worker run. Toggle-off must NOT enqueue — there's nothing
        // for the worker to refresh once device-location is off.
        var refreshCount = 0
        val refreshSubject = track(
            SettingsViewModel(
                settingsRepository = settingsRepository,
                keyStore = keyStore,
                rearmAlarm = { _, _ -> },
                cancelAlarm = { _ -> },
                geocodingClient = OpenMeteoGeocodingClient(
                    HttpClient(MockEngine { respond("""{"results":[]}""") }) {
                        install(ContentNegotiation) { json(KotlinxJson { ignoreUnknownKeys = true }) }
                    },
                ),
                voiceEnumerator = EmptyVoiceEnumerator,
                voiceEnumerationDispatcher = dispatcher,
                refreshLocationCache = { refreshCount++ },
            ),
        )

        refreshSubject.setUseDeviceLocation(true)
        refreshSubject.state.first { it.useDeviceLocation }
        refreshCount shouldBe 1

        refreshSubject.setUseDeviceLocation(false)
        refreshSubject.state.first { !it.useDeviceLocation }
        refreshCount shouldBe 1
    }

    // Widget repaint is no longer pushed from each setter — ClothesCastApplication
    // observes the widget-relevant slice of preferences and repaints on change.
    // WidgetInputsTest guards which fields belong in that slice.

    @Test
    fun `setTemperatureUnitSetting and setWindSpeedUnitSetting persist independently`() = runTest {
        subject.setTemperatureUnitSetting(TemperatureUnitSetting.FAHRENHEIT)
        subject.setWindSpeedUnitSetting(WindSpeedUnitSetting.MPH)

        val state = subject.state.first {
            it.temperatureUnit == TemperatureUnit.FAHRENHEIT && it.windSpeedUnit == WindSpeedUnit.MPH
        }
        state.temperatureUnit shouldBe TemperatureUnit.FAHRENHEIT
        state.windSpeedUnit shouldBe WindSpeedUnit.MPH
        state.temperatureUnitSetting shouldBe TemperatureUnitSetting.FAHRENHEIT
        state.windSpeedUnitSetting shouldBe WindSpeedUnitSetting.MPH
    }

    @Test
    fun `re-enumerates device voices when region changes while voiceLocale is SYSTEM`() = runTest {
        // Pin the JVM default so the SYSTEM-fallback locale on the initial
        // enumeration is guaranteed to differ from en-AU below — without this,
        // a CI runner that happened to report en-AU as its default would short
        // the second emission's "effective locale changed" check.
        val originalDefault = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            // A Channel rather than a plain counter: receive() suspends until
            // an enumeration actually fires, which dodges the race where
            // state.first matches the MutableStateFlow's seed value (region is
            // SYSTEM by default in SettingsState) before the preferences flow
            // has emitted and triggered the first enumeration.
            val enumerated = Channel<Locale>(capacity = Channel.UNLIMITED)
            val countingEnumerator = object : TtsVoiceEnumerator {
                override suspend fun listVoices(locale: Locale): List<DeviceVoice> {
                    enumerated.send(locale)
                    return emptyList()
                }
                override suspend fun resolveAutoPick(locale: Locale): DeviceVoice? = null
                override suspend fun findVoice(id: String): DeviceVoice? = null
            }
            val countingSubject = track(
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    keyStore = keyStore,
                    rearmAlarm = { _, _ -> },
                    cancelAlarm = { _ -> },
                    geocodingClient = OpenMeteoGeocodingClient(
                        HttpClient(MockEngine { respond("""{"results":[]}""") }) {
                            install(ContentNegotiation) { json(KotlinxJson { ignoreUnknownKeys = true }) }
                        },
                    ),
                    voiceEnumerator = countingEnumerator,
                    voiceEnumerationDispatcher = dispatcher,
                ),
            )
            // Drain the initial enumeration triggered by the first prefs
            // emission (effective locale is the JVM default while region is
            // SYSTEM).
            enumerated.receive() shouldBe Locale.US

            // Changing region while voiceLocale stays SYSTEM changes the
            // effective locale — the device-voice list should be refreshed
            // for en-AU.
            countingSubject.setRegion(Region.EN_AU)

            enumerated.receive() shouldBe Locale.forLanguageTag("en-AU")
        } finally {
            Locale.setDefault(originalDefault)
        }
    }

    @Test
    fun `discovery surfaces NSD hits as state and clears on a fresh scan`() = runTest {
        val discovery = FakeDiscovery()
        val vm = track(buildSubject(discovery = discovery))

        vm.startDiscovery()
        vm.state.first { it.discoveryRunning }.discoveredServices shouldBe emptyList()

        val ha = DiscoveredService(
            type = ServiceType.HOME_ASSISTANT,
            name = "Home Assistant",
            host = "192.168.1.20",
            port = 8123,
        )
        val mqtt = DiscoveredService(
            type = ServiceType.MQTT,
            name = "mosquitto",
            host = "192.168.1.20",
            port = 1883,
        )
        discovery.emissions.value = listOf(ha, mqtt)
        vm.state.first { it.discoveredServices.size == 2 }.discoveredServices shouldBe listOf(ha, mqtt)

        // Restarting the scan blanks the previous batch — otherwise stale hits
        // from a different network would linger in the picker.
        discovery.emissions.value = emptyList()
        vm.stopDiscovery()
        vm.state.first { !it.discoveryRunning }
        vm.startDiscovery()
        vm.state.first { it.discoveryRunning }.discoveredServices shouldBe emptyList()
    }

    @Test
    fun `useDiscoveredService writes MQTT host and port and stops the scan`() = runTest {
        val discovery = FakeDiscovery()
        val vm = track(buildSubject(discovery = discovery))
        val mqtt = DiscoveredService(
            type = ServiceType.MQTT,
            name = "mosquitto",
            host = "192.168.1.30",
            port = 8883,
        )

        vm.startDiscovery()
        discovery.emissions.value = listOf(mqtt)
        vm.state.first { it.discoveredServices.size == 1 }

        vm.useDiscoveredService(mqtt)
        val applied = vm.state.first { it.mqttHost == "192.168.1.30" }
        applied.mqttPort shouldBe 8883
        applied.discoveryRunning shouldBe false
    }

    @Test
    fun `discoveryRunning resets when the source flow completes naturally`() = runTest {
        // Mirrors the NSD-startup-failed path Codex flagged on PR #517:
        // when the underlying discovery source closes the channel (every
        // discoverServices call rejected, NSD off, etc.), the VM must
        // reset discoveryRunning so the UI doesn't sit on "Stop searching"
        // indefinitely.
        val vm = track(buildSubject(discovery = ImmediatelyEndingDiscovery()))

        vm.startDiscovery()
        vm.state.first { !it.discoveryRunning }
    }

    @Test
    fun `stop then immediately start preserves discoveryRunning against the stale finally`() = runTest {
        // Regression for the race Codex flagged on PR #517: when a scan is
        // stopped and a new one starts before the cancelled collector's
        // finally has run, the stale finally must NOT clear
        // discoveryRunning out from under the new scan.
        val discovery = FakeDiscovery()
        val vm = track(buildSubject(discovery = discovery))

        vm.startDiscovery()
        vm.state.first { it.discoveryRunning }
        vm.stopDiscovery()
        vm.startDiscovery()
        // Let any pending continuations resolve.
        kotlinx.coroutines.yield()
        vm.state.first { it.discoveryRunning }.discoveryRunning shouldBe true
    }

    @Test
    fun `setMqttBridgeEnabled false cancels an in-flight discovery scan`() = runTest {
        // The picker UI is gated on bridgeEnabled, so without this the
        // listener leak Codex flagged on PR #517 (NSD keeps running after
        // the user toggles the bridge off) sneaks past until the route
        // unmounts.
        val discovery = FakeDiscovery()
        val vm = track(buildSubject(discovery = discovery))

        vm.startDiscovery()
        vm.state.first { it.discoveryRunning }

        vm.setMqttBridgeEnabled(false)
        vm.state.first { !it.discoveryRunning }
    }

    @Test
    fun `useDiscoveredService for Home Assistant keeps existing port`() = runTest {
        val discovery = FakeDiscovery()
        val vm = track(buildSubject(discovery = discovery))
        // Seed a non-default port so we can prove the HA pick doesn't clobber it.
        settingsRepository.setMqttConfig(host = "old", port = 9999, useTls = false, username = null, topic = "x")
        vm.state.first { it.mqttPort == 9999 }

        val ha = DiscoveredService(
            type = ServiceType.HOME_ASSISTANT,
            name = "Home Assistant",
            host = "homeassistant.local",
            port = 8123,
        )
        vm.useDiscoveredService(ha)
        val applied = vm.state.first { it.mqttHost == "homeassistant.local" }
        applied.mqttPort shouldBe 9999
    }

    private fun buildSubject(discovery: HomeAssistantDiscovery): SettingsViewModel {
        val emptyGeocoding = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel("""{"results":[]}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) { install(ContentNegotiation) { json(KotlinxJson { ignoreUnknownKeys = true }) } }
        return SettingsViewModel(
            settingsRepository = settingsRepository,
            keyStore = keyStore,
            rearmAlarm = { _, _ -> },
            cancelAlarm = { _ -> },
            geocodingClient = OpenMeteoGeocodingClient(emptyGeocoding),
            voiceEnumerator = EmptyVoiceEnumerator,
            voiceEnumerationDispatcher = dispatcher,
            discovery = discovery,
        )
    }
}

/** Pass-through AEAD: encrypt/decrypt are identity. Sufficient for round-trip tests. */
private object IdentityFakeAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray = plaintext
    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray = ciphertext
}

/**
 * Hand-controlled [HomeAssistantDiscovery] for the discovery tests below.
 * `emissions` is a hot flow the test pushes into; the VM observes whatever
 * the latest emission is. Cancelling the VM's collect cancels the underlying
 * collector, mirroring the real NsdManager-backed flow's cold semantics.
 */
private class FakeDiscovery : HomeAssistantDiscovery {
    val emissions = MutableStateFlow<List<DiscoveredService>>(emptyList())
    override fun discover(): Flow<List<DiscoveredService>> = emissions
}

/**
 * Discovery whose flow completes immediately — the natural shape of an
 * NsdManager-backed source whose every `discoverServices` call fails to
 * start (NSD off, permission denied, etc.). The VM should treat this the
 * same as a stopped scan: clear `discoveryRunning` so the UI doesn't sit on
 * "Stop searching" forever.
 */
private class ImmediatelyEndingDiscovery : HomeAssistantDiscovery {
    override fun discover(): Flow<List<DiscoveredService>> = kotlinx.coroutines.flow.emptyFlow()
}
