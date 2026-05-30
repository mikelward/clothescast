package app.clothescast.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.clothescast.core.data.location.OpenMeteoGeocodingClient
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.data.SecureKeyStore
import app.clothescast.data.SettingsRepository
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnboardingState(
    val geminiKeyConfigured: Boolean = false,
    val location: Location? = null,
    val useDeviceLocation: Boolean = false,
    val locationDetecting: Boolean = false,
)

/**
 * Drives the first-run onboarding screen. Exposes the slice of settings the
 * onboarding's checkmarks track (Gemini key, location) and the setters its
 * inline editors need. Notification permission is handled directly in the
 * composable — it requires Context and lifecycle observation, which are
 * awkward to plumb through a ViewModel.
 */
class OnboardingViewModel(
    private val secureKeyStore: SecureKeyStore,
    private val settingsRepository: SettingsRepository,
    private val geocodingClient: OpenMeteoGeocodingClient,
    /**
     * Kicks off a one-shot worker run to resolve the device location and
     * write it through to settings. Triggered when the user grants
     * foreground location permission so the resolved city appears in
     * onboarding within seconds — otherwise the user has no way to know if
     * the device's idea of "here" is right and might continue to a manual
     * pick they don't actually need. Defaulted to a no-op so pure-VM tests
     * don't need WorkManager; the Activity wires
     * `FetchAndNotifyWorker.enqueueLocationCacheRefresh`.
     */
    private val refreshLocationCache: () -> Unit = {},
    /**
     * WorkManager for observing the cache-refresh job so onboarding can show
     * "Detecting…" while the worker resolves the fix, and cancel it when the
     * user toggles device location off mid-flight. Null in tests — JVM test
     * host has no Android services; [OnboardingState.locationDetecting] then
     * stays permanently false.
     */
    private val workManager: WorkManager? = null,
    /**
     * Process-lifetime scope for the skip write. [markSkipped] must use this
     * rather than [viewModelScope]: tapping Skip pops the onboarding
     * destination, which clears this ViewModel and cancels [viewModelScope]
     * before a DataStore write could commit. Defaults to [viewModelScope] for
     * pure-VM tests that don't exercise the teardown race.
     */
    private val externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val locationDetecting = MutableStateFlow(false)

    init {
        workManager?.let { wm ->
            viewModelScope.launch {
                wm.getWorkInfosForUniqueWorkFlow(FetchAndNotifyWorker.UNIQUE_WORK_NAME_LOCATION_CACHE)
                    .collect { infos ->
                        locationDetecting.value = infos.any { info ->
                            info.state == WorkInfo.State.ENQUEUED ||
                                info.state == WorkInfo.State.RUNNING ||
                                info.state == WorkInfo.State.BLOCKED
                        }
                    }
            }
        }
    }

    val state: StateFlow<OnboardingState> = combine(
        secureKeyStore.geminiKeyConfiguredFlow,
        settingsRepository.preferences,
        locationDetecting,
    ) { keyConfigured, prefs, detecting ->
        OnboardingState(
            geminiKeyConfigured = keyConfigured,
            location = prefs.location,
            useDeviceLocation = prefs.useDeviceLocation,
            locationDetecting = detecting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingState())

    fun setApiKey(key: String) {
        viewModelScope.launch {
            secureKeyStore.set(key.trim())
            // First-run flip: if the user hasn't picked a TTS engine yet,
            // default to Gemini now that they've configured a Gemini key.
            // setTtsEngineIfUnset is a no-op when an explicit choice exists.
            settingsRepository.setTtsEngineIfUnset(TtsEngine.GEMINI)
        }
    }

    fun setUseDeviceLocation(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUseDeviceLocation(enabled)
            if (enabled) {
                // Mirror SettingsViewModel: eagerly populate the device-location
                // cache so the user sees their city in onboarding within
                // seconds. Without this they'd have to either trust the
                // permission grant blindly or wait for the morning worker
                // before knowing whether the resolved city matches reality.
                refreshLocationCache()
            } else {
                // Cancel any in-flight cache-refresh job — without this an
                // on→off flip before the job starts still lets it run and
                // overwrite the user's manual pick via setLocation().
                workManager?.cancelUniqueWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME_LOCATION_CACHE)
            }
        }
    }

    fun selectLocation(location: Location) {
        viewModelScope.launch { settingsRepository.setLocation(location) }
    }

    /**
     * Persists that the user chose to skip onboarding so it doesn't reappear on
     * the next cold launch. Runs on [externalScope] (the app scope) rather than
     * [viewModelScope] because the caller pops the onboarding destination right
     * after, clearing this ViewModel — a [viewModelScope] write would be
     * canceled before it committed.
     */
    fun markSkipped() {
        (externalScope ?: viewModelScope).launch {
            settingsRepository.setOnboardingSkipped(true)
        }
    }

    suspend fun searchLocations(query: String): List<Location> = geocodingClient.search(query)

    class Factory(
        private val secureKeyStore: SecureKeyStore,
        private val settingsRepository: SettingsRepository,
        private val geocodingClient: OpenMeteoGeocodingClient,
        private val refreshLocationCache: () -> Unit,
        private val workManager: WorkManager?,
        private val externalScope: CoroutineScope,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OnboardingViewModel::class.java)) {
                "Unknown ViewModel: ${modelClass.name}"
            }
            return OnboardingViewModel(
                secureKeyStore = secureKeyStore,
                settingsRepository = settingsRepository,
                geocodingClient = geocodingClient,
                refreshLocationCache = refreshLocationCache,
                workManager = workManager,
                externalScope = externalScope,
            ) as T
        }
    }
}
