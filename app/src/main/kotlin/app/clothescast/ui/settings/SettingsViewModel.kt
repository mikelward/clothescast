package app.clothescast.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.clothescast.core.data.location.OpenMeteoGeocodingClient
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.data.InsightCache
import app.clothescast.data.SecureKeyStore
import app.clothescast.data.SettingsRepository
import app.clothescast.work.FetchAndNotifyWorker
import app.clothescast.tts.TtsVoiceEnumerator
import app.clothescast.tts.resolve
import app.clothescast.tts.toJavaLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val keyStore: SecureKeyStore,
    private val rearmAlarm: (Schedule, ForecastPeriod) -> Unit,
    private val cancelAlarm: (ForecastPeriod) -> Unit,
    private val geocodingClient: OpenMeteoGeocodingClient,
    private val voiceEnumerator: TtsVoiceEnumerator,
    /**
     * Cache the home-screen / widget read [Insight.outfit] from. Settings VM
     * mutates it directly after each clothes-rule edit (add / replace / delete
     * a rule, flip the default-bottom picker) so the icon updates in the same
     * frame instead of waiting for the next scheduled or manual refresh.
     * Defaulted to a no-op for pure-VM tests that don't care about the cache;
     * the Activity wires the real [InsightCache].
     */
    private val refreshCachedOutfits: suspend () -> Unit = {},
    /**
     * Pushes the chosen [Region] into the platform locale machinery
     * (Locale.setDefault + LocaleManager / attachBaseContext cache) so the
     * whole UI re-renders in the chosen language. Defaulted to a no-op so
     * pure-VM tests don't need an Android Context; the Activity passes an
     * AppLocale-backed implementation.
     */
    private val applyAppLocale: (Region) -> Unit = {},
    /**
     * Kicks off a one-shot worker run to resolve the device location and
     * write it to settings as the new fallback. Triggered when the user
     * flips device-location ON so they see their city populate without
     * waiting for the next morning. Defaulted to a no-op so pure-VM tests
     * don't need a WorkManager; the Activity wires
     * `FetchAndNotifyWorker.enqueueOneShot`.
     */
    private val refreshLocationCache: () -> Unit = {},
    /**
     * WorkManager for observing the location-cache-refresh job state and for
     * cancelling it when device location is toggled off mid-flight. Null in
     * tests — JVM test host has no Android services; [locationDetecting] then
     * stays permanently false.
     */
    private val workManager: WorkManager? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /**
     * Tracks the most recent device-voice enumeration so we can cancel it
     * when the locale changes mid-load — avoids a stale en-US list landing
     * after the user has already switched to en-GB.
     */
    private var deviceVoiceLoadJob: Job? = null
    /**
     * The most recently enumerated effective locale, used to detect when
     * re-enumeration is needed. Stored as a resolved [Locale] rather than
     * the raw [VoiceLocale] enum so that a [VoiceLocale.SYSTEM] user who
     * changes their [Region] also triggers a fresh enumeration (the
     * effective locale changes even though the [VoiceLocale] enum value
     * didn't).
     */
    private var lastEnumeratedLocale: Locale? = null

    init {
        viewModelScope.launch {
            settingsRepository.preferences.collect { prefs ->
                _state.update {
                    it.copy(
                        scheduleTime = prefs.schedule.time,
                        scheduleDays = prefs.schedule.days,
                        tonightTime = prefs.tonightSchedule.time,
                        tonightDays = prefs.tonightSchedule.days,
                        tonightEnabled = prefs.tonightEnabled,
                        tonightNotifyOnlyOnEvents = prefs.tonightNotifyOnlyOnEvents,
                        deliveryMode = prefs.deliveryMode,
                        tonightDeliveryMode = prefs.tonightDeliveryMode,
                        dailyMentionEveningEvents = prefs.dailyMentionEveningEvents,
                        region = prefs.region,
                        temperatureUnit = prefs.temperatureUnit,
                        distanceUnit = prefs.distanceUnit,
                        themeMode = prefs.themeMode,
                        colorPalette = prefs.colorPalette,
                        clothesRules = prefs.clothesRules,
                        defaultBottom = prefs.defaultBottom,
                        location = prefs.location,
                        useDeviceLocation = prefs.useDeviceLocation,
                        ttsEngine = prefs.ttsEngine,
                        geminiVoice = prefs.geminiVoice,
                        ttsStyle = prefs.ttsStyle,
                        deviceVoice = prefs.deviceVoice,
                        voiceLocale = prefs.voiceLocale,
                        useCalendarEvents = prefs.useCalendarEvents,
                        telemetryEnabled = prefs.telemetryEnabled,
                    )
                }
                // Re-enumerate on first observation and whenever the effective
                // voice locale changes — from a voiceLocale flip *or* (when
                // voiceLocale is SYSTEM) a region change that shifts the
                // fallback locale.
                val regionLocale = prefs.region.toJavaLocale() ?: Locale.getDefault()
                val effectiveLocale = prefs.voiceLocale.resolve(regionLocale)
                if (lastEnumeratedLocale != effectiveLocale) {
                    lastEnumeratedLocale = effectiveLocale
                    refreshDeviceVoices(prefs.voiceLocale)
                }
            }
        }
        viewModelScope.launch { refreshApiKeyStatus() }
        workManager?.let { wm ->
            viewModelScope.launch {
                wm.getWorkInfosForUniqueWorkFlow(FetchAndNotifyWorker.UNIQUE_WORK_NAME_LOCATION_CACHE)
                    .collect { infos ->
                        val detecting = infos.any { info ->
                            info.state == WorkInfo.State.ENQUEUED ||
                                info.state == WorkInfo.State.RUNNING ||
                                info.state == WorkInfo.State.BLOCKED
                        }
                        _state.update { it.copy(locationDetecting = detecting) }
                    }
            }
        }
    }

    /**
     * Reloads the device-voice picker list for [locale] and resolves the
     * "currently using" indicator. [pinnedIdOverride] lets [setDeviceVoice]
     * pass the just-written pin so the indicator doesn't briefly resolve
     * against the previous pin while the preferences flow catches up; the
     * default reads the current state.
     */
    private fun refreshDeviceVoices(locale: VoiceLocale, pinnedIdOverride: String? = _state.value.deviceVoice) {
        deviceVoiceLoadJob?.cancel()
        deviceVoiceLoadJob = viewModelScope.launch {
            // All three enumerator calls bind the engine, which is JNI work
            // — keep them off the main dispatcher.
            val resolvedLocale = locale.resolve(_state.value.region.toJavaLocale() ?: Locale.getDefault())
            val voices = withContext(Dispatchers.IO) {
                runCatching { voiceEnumerator.listVoices(resolvedLocale) }.getOrDefault(emptyList())
            }
            val pinnedId = pinnedIdOverride
            val effective = if (pinnedId != null) {
                // Fast path: pin is in the locale-filtered list. Slow path:
                // pin is from a different locale variant within the same
                // language (the speaker accepts these too). We only fall
                // through to the second engine bind when the fast path
                // misses, which is rare — most pins match the current
                // locale.
                voices.firstOrNull { it.id == pinnedId }
                    ?: withContext(Dispatchers.IO) {
                        runCatching { voiceEnumerator.findVoice(pinnedId) }.getOrNull()
                    }
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { voiceEnumerator.resolveAutoPick(resolvedLocale) }.getOrNull()
                }
            }
            _state.update { it.copy(deviceVoices = voices, effectiveDeviceVoice = effective) }
        }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch {
            keyStore.set(key.trim())
            refreshApiKeyStatus()
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            keyStore.clear()
            refreshApiKeyStatus()
        }
    }

    fun setGeminiVoice(voice: String) {
        viewModelScope.launch { settingsRepository.setGeminiVoice(voice) }
    }

    fun setTtsStyle(style: TtsStyle) {
        viewModelScope.launch { settingsRepository.setTtsStyle(style) }
    }

    fun setDeviceVoice(voice: String?) {
        viewModelScope.launch {
            // Update _state synchronously first so the picker reflects the
            // new pin in the same frame, and so refreshDeviceVoices below
            // resolves the "currently using" line against the *new* pin
            // rather than the previous one. The DataStore emission that
            // arrives a few hops later is idempotent.
            _state.update { it.copy(deviceVoice = voice) }
            settingsRepository.setDeviceVoice(voice)
            refreshDeviceVoices(_state.value.voiceLocale, pinnedIdOverride = voice)
        }
    }

    fun setDeliveryMode(mode: DeliveryMode) {
        viewModelScope.launch { settingsRepository.setDeliveryMode(mode) }
    }

    fun setTonightDeliveryMode(mode: DeliveryMode) {
        viewModelScope.launch { settingsRepository.setTonightDeliveryMode(mode) }
    }

    fun setRegion(region: Region) {
        // Apply the locale up front so the UI recreates immediately; the
        // DataStore write happens in the background. The Application's
        // onCreate reconciler re-applies on next cold start, so the order
        // here can't drift out of sync.
        applyAppLocale(region)
        viewModelScope.launch { settingsRepository.setRegion(region) }
    }

    fun setTemperatureUnit(unit: TemperatureUnit) {
        viewModelScope.launch { settingsRepository.setTemperatureUnit(unit) }
    }

    fun setDistanceUnit(unit: DistanceUnit) {
        viewModelScope.launch { settingsRepository.setDistanceUnit(unit) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setColorPalette(palette: ColorPalette) {
        viewModelScope.launch { settingsRepository.setColorPalette(palette) }
    }

    fun addClothesRule(rule: ClothesRule) {
        viewModelScope.launch {
            settingsRepository.setClothesRules(_state.value.clothesRules + rule)
            refreshCachedOutfits()
        }
    }

    fun replaceClothesRule(index: Int, rule: ClothesRule) {
        viewModelScope.launch {
            val current = _state.value.clothesRules
            if (index !in current.indices) return@launch
            settingsRepository.setClothesRules(current.toMutableList().apply { this[index] = rule })
            refreshCachedOutfits()
        }
    }

    fun deleteClothesRule(index: Int) {
        viewModelScope.launch {
            val current = _state.value.clothesRules
            if (index !in current.indices) return@launch
            settingsRepository.setClothesRules(current.toMutableList().apply { removeAt(index) })
            refreshCachedOutfits()
        }
    }

    fun setDefaultBottom(bottom: OutfitSuggestion.Bottom) {
        viewModelScope.launch {
            settingsRepository.setDefaultBottom(bottom)
            refreshCachedOutfits()
        }
    }

    fun selectLocation(location: Location) {
        // A manual pick is the user's explicit "stop trusting the system"
        // signal — flip device-location off so the next worker run doesn't
        // immediately overwrite the picked city with the next device fix.
        // The Location page surfaces a disclosure ("Picking a city turns off
        // auto-detect") so the toggle doesn't appear to flip on its own.
        viewModelScope.launch {
            settingsRepository.setLocation(location)
            settingsRepository.setUseDeviceLocation(false)
        }
    }

    fun clearLocation() {
        viewModelScope.launch { settingsRepository.clearLocation() }
    }

    fun setUseDeviceLocation(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUseDeviceLocation(enabled)
            if (enabled) {
                // Eagerly populate the device-location cache so the user sees
                // their city in Settings within seconds, instead of waiting for
                // the morning worker run. Awaited *after* the toggle write so
                // the worker reads useDeviceLocation = true when it resolves.
                refreshLocationCache()
            } else {
                // Cancel any in-flight cache-refresh job. Without this, an
                // on→off flip before the job starts still lets it run and
                // persist a device fix via setLocation(), silently overwriting
                // the user's manual fallback city even though device location
                // is now off.
                workManager?.cancelUniqueWork(FetchAndNotifyWorker.UNIQUE_WORK_NAME_LOCATION_CACHE)
            }
        }
    }

    fun setTtsEngine(engine: TtsEngine) {
        viewModelScope.launch { settingsRepository.setTtsEngine(engine) }
    }

    fun setVoiceLocale(locale: VoiceLocale) {
        viewModelScope.launch { settingsRepository.setVoiceLocale(locale) }
    }

    fun setUseCalendarEvents(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setUseCalendarEvents(enabled) }
    }

    fun setTelemetryEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setTelemetryEnabled(enabled) }
    }

    /** Used by the data-sources page's location dialog; safe to call from any dispatcher. */
    suspend fun searchLocations(query: String): List<Location> = geocodingClient.search(query)

    fun setSchedule(time: LocalTime, days: Set<DayOfWeek>) {
        if (days.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.setSchedule(time, days)
            // Re-arm the alarm immediately so the next occurrence picks up the new wall-clock.
            // The repository resolves zoneId fresh on each emission, so the schedule we read
            // back is the new one with the current zone.
            val updated = settingsRepository.preferences.first().schedule
            rearmAlarm(updated, ForecastPeriod.TODAY)
        }
    }

    fun setTonightSchedule(time: LocalTime, days: Set<DayOfWeek>) {
        if (days.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.setTonightSchedule(time, days)
            val prefs = settingsRepository.preferences.first()
            // Don't arm the alarm if tonight is disabled — would just trigger an
            // ignored worker run.
            if (prefs.tonightEnabled) rearmAlarm(prefs.tonightSchedule, ForecastPeriod.TONIGHT)
        }
    }

    fun setTonightNotifyOnlyOnEvents(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setTonightNotifyOnlyOnEvents(enabled) }
    }

    fun setDailyMentionEveningEvents(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDailyMentionEveningEvents(enabled) }
    }

    fun setTonightEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTonightEnabled(enabled)
            val prefs = settingsRepository.preferences.first()
            if (prefs.tonightEnabled) {
                rearmAlarm(prefs.tonightSchedule, ForecastPeriod.TONIGHT)
            } else {
                cancelAlarm(ForecastPeriod.TONIGHT)
            }
        }
    }

    private suspend fun refreshApiKeyStatus() {
        val gemini = runCatching { keyStore.get().isNotBlank() }.getOrDefault(false)
        _state.update { it.copy(apiKeyConfigured = gemini) }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val keyStore: SecureKeyStore,
        private val rearmAlarm: (Schedule, ForecastPeriod) -> Unit,
        private val cancelAlarm: (ForecastPeriod) -> Unit,
        private val geocodingClient: OpenMeteoGeocodingClient,
        private val voiceEnumerator: TtsVoiceEnumerator,
        private val applyAppLocale: (Region) -> Unit,
        private val refreshLocationCache: () -> Unit,
        private val refreshCachedOutfits: suspend () -> Unit,
        private val workManager: WorkManager? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                "Unknown ViewModel: ${modelClass.name}"
            }
            return SettingsViewModel(
                settingsRepository = settingsRepository,
                keyStore = keyStore,
                rearmAlarm = rearmAlarm,
                cancelAlarm = cancelAlarm,
                geocodingClient = geocodingClient,
                voiceEnumerator = voiceEnumerator,
                refreshCachedOutfits = refreshCachedOutfits,
                applyAppLocale = applyAppLocale,
                refreshLocationCache = refreshLocationCache,
                workManager = workManager,
            ) as T
        }
    }

}
