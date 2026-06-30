package app.clothescast.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.clothescast.core.data.location.OpenMeteoGeocodingClient
import app.clothescast.core.data.weather.GoogleWeatherProbe
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.AccessoriesFormat
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.DeltaFormat
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.WindSpeedUnitSetting
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.PreambleVisibility
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnitSetting
import app.clothescast.core.domain.model.TimeFormatSetting
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.data.InsightCache
import app.clothescast.data.SecureKeyStore
import app.clothescast.data.SettingsRepository
import app.clothescast.cast.CastRouteDiscovery
import app.clothescast.cast.CastTestOutcome
import app.clothescast.cast.DiscoveredCastRoute
import app.clothescast.discovery.DiscoveredService
import app.clothescast.discovery.HomeAssistantDiscovery
import app.clothescast.discovery.ServiceType
import app.clothescast.mqtt.MqttPublishOutcome
import app.clothescast.mqtt.MqttPublisher
import app.clothescast.work.FetchAndNotifyWorker
import app.clothescast.tts.TtsVoiceEnumerator
import app.clothescast.tts.resolve
import app.clothescast.tts.toJavaLocale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** True if any work in the list is ENQUEUED, RUNNING, or BLOCKED. */
private fun List<WorkInfo>.hasActiveWork(): Boolean = any { info ->
    info.state == WorkInfo.State.ENQUEUED ||
        info.state == WorkInfo.State.RUNNING ||
        info.state == WorkInfo.State.BLOCKED
}

/**
 * Smallest forecaster selection the confidence spread can be computed from
 * (mirrors the Forecasters picker's MIN_MODELS). Used when clearing the Gemini
 * key drops the Google forecaster: if too few models would remain, fall back to
 * Auto rather than persist a one-model selection.
 */
private const val MIN_CONSENSUS_MODELS = 2

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val keyStore: SecureKeyStore,
    private val rearmAlarm: (Schedule, ForecastPeriod) -> Unit,
    private val cancelAlarm: (ForecastPeriod) -> Unit,
    private val geocodingClient: OpenMeteoGeocodingClient,
    private val voiceEnumerator: TtsVoiceEnumerator,
    /**
     * Dispatcher the device-voice enumeration hops to for its JNI engine
     * binds (see [refreshDeviceVoices]). Defaults to [Dispatchers.IO] in
     * production; tests inject their own test dispatcher so the enumeration
     * stays on the test scheduler and can be drained deterministically in
     * teardown — otherwise the real-IO hop outlives the test, resumes after
     * `Dispatchers.resetMain()`, and crashes trying to dispatch back onto the
     * now-missing Main dispatcher (surfacing as a flaky
     * `UncaughtExceptionsBeforeTest` against the next test).
     */
    private val voiceEnumerationDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
    /**
     * Publisher used by the "Publish now" button to test the current saved
     * MQTT configuration on demand. Null in pure-VM tests that don't need
     * network; the Activity/Application wires the real publisher.
     */
    private val mqttPublisher: MqttPublisher? = null,
    /**
     * Full prose + image publish using the most recently cached insight.
     * When non-null, [publishNow] calls this instead of [MqttPublisher.publishTest];
     * falls back to the connectivity probe when null (pure-VM tests).
     */
    private val fullPublish: (suspend () -> MqttPublishOutcome)? = null,
    /**
     * mDNS / DNS-SD source for Home Assistant + MQTT broker hits on the
     * local network. Null in pure-VM tests that don't need an Android
     * NsdManager; the Activity wires the real [HomeAssistantDiscovery]. When
     * null, "Find broker" is a no-op (the UI hides the affordance via
     * [SettingsState.discoveryRunning] never flipping true).
     */
    private val discovery: HomeAssistantDiscovery? = null,
    /**
     * MediaRouter wrapper for the Settings → Cast picker. Null in pure-VM
     * tests; the Activity wires the real [CastRouteDiscovery]. When null,
     * the picker stays empty (the UI hides the section via
     * [SettingsState.castAvailable] not flipping true).
     */
    private val castRouteDiscovery: CastRouteDiscovery? = null,
    /**
     * "Cast now" action — synthesises + renders + casts the current
     * insight to the saved smart display, returning a [CastTestOutcome]
     * that carries the error message (if any) plus which highwater
     * timestamps the attempt should advance ("last sent", "last
     * played"). The Activity wires this with the real
     * [castCurrentInsight] closure over Context + InsightCache +
     * [CastInsightController]. Null in pure-VM tests; the button is
     * inert in that case.
     */
    private val castNowAction: (suspend () -> CastTestOutcome)? = null,
    /**
     * True when [com.google.android.gms.cast.framework.CastContext] is
     * available on this device. Cast-less emulators / GMS-free builds
     * pass false; the Settings UI hides the whole Cast section then.
     */
    private val castAvailable: Boolean = false,
    /**
     * True when the developer's Cloud Function TTS proxy is available on
     * this build (Firebase initialised + `GEMINI_PROXY_URL` set). Lets a
     * user without a BYOK key still preview / use Gemini — the Voice
     * screen ORs this with `apiKeyConfigured`. Process-stable; passed
     * straight through to [SettingsState.sharedTtsAvailable] on init.
     */
    private val sharedTtsAvailable: Boolean = false,
    /**
     * Reads the user's synced calendars for the Celebrations screen's
     * upcoming-birthdays / -holidays listing. Null in pure-VM tests that don't
     * need an Android ContentResolver; [loadCalendarCelebrations] then no-ops
     * and the listing stays empty.
     */
    private val calendarEventReader: CalendarEventReader? = null,
    /**
     * Read-only access to the cached current snapshot so the Format settings
     * page can preview the user's *real* ClothesCast next to the synthetic
     * example. Null in pure-VM tests that don't need the cache; the Activity
     * wires the real [InsightCache] and [SettingsState.currentInsightSummary]
     * then tracks page 1 of the Today pager, derived against the current
     * preferences flow so every setting that affects the prose updates the
     * preview in the same frame as the dropdown closes.
     */
    private val insightCache: InsightCache? = null,
    /**
     * One-shot Google Weather connectivity probe used by [probeGoogleWeather].
     * Resolves the current location + Gemini key and hits the Weather API,
     * returning a [GoogleWeatherProbe] the Forecasters page renders as a status
     * line. Null in pure-VM tests that don't need network; [probeGoogleWeather]
     * then no-ops and the status stays unset.
     */
    private val googleWeatherProbe: (suspend () -> GoogleWeatherProbe)? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsState(sharedTtsAvailable = sharedTtsAvailable),
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /**
     * Tracks the most recent device-voice enumeration so we can cancel it
     * when the locale changes mid-load — avoids a stale en-US list landing
     * after the user has already switched to en-GB.
     */
    private var deviceVoiceLoadJob: Job? = null
    /**
     * Tracks the in-flight mDNS scan so we can cancel it on stop or when the
     * VM is cleared, even if the user navigates away mid-scan.
     */
    private var discoveryJob: Job? = null
    /** In-flight upcoming-celebrations read; guards against overlapping loads. */
    private var calendarCelebrationsJob: Job? = null
    /** In-flight device-calendar enumeration; guards against overlapping loads. */
    private var availableCalendarsJob: Job? = null
    /**
     * In-flight Google Weather probe. A new probe supersedes it (cancel +
     * restart) rather than being dropped, so replacing the key mid-probe
     * re-runs against the new key and the latest verdict is the one that lands.
     */
    private var googleProbeJob: Job? = null
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
                val regionLocale = prefs.region.toJavaLocale() ?: Locale.getDefault()
                val effectiveCountries = prefs.holidayCountrySelection.resolveEnabledCountries(
                    localeCountry = regionLocale.country,
                    weatherLocationCountry = prefs.location?.countryCode,
                    allCountries = HolidayCatalog.allCountries,
                )
                _state.update {
                    it.copy(
                        scheduleTime = prefs.schedule.time,
                        scheduleDays = prefs.schedule.days,
                        dailyEnabled = prefs.dailyEnabled,
                        tonightTime = prefs.tonightSchedule.time,
                        tonightDays = prefs.tonightSchedule.days,
                        tonightEnabled = prefs.tonightEnabled,
                        tonightNotifyOnlyOnEvents = prefs.tonightNotifyOnlyOnEvents,
                        deliveryMode = prefs.deliveryMode,
                        tonightDeliveryMode = prefs.tonightDeliveryMode,
                        dailyMentionEveningEvents = prefs.dailyMentionEveningEvents,
                        clothesMentionMode = prefs.clothesMentionMode,
                        rangeFormat = prefs.rangeFormat,
                        clothesFormat = prefs.clothesFormat,
                        bottomsFormat = prefs.bottomsFormat,
                        accessoriesFormat = prefs.accessoriesFormat,
                        periodPreamble = prefs.periodPreamble,
                        wearPreamble = prefs.wearPreamble,
                        deltaThresholdC = prefs.deltaThresholdC,
                        deltaFormat = prefs.deltaFormat,
                        region = prefs.region,
                        temperatureUnit = prefs.temperatureUnit,
                        windSpeedUnit = prefs.windSpeedUnit,
                        temperatureUnitSetting = prefs.temperatureUnitSetting,
                        windSpeedUnitSetting = prefs.windSpeedUnitSetting,
                        timeFormat = prefs.timeFormat,
                        timeFormatSetting = prefs.timeFormatSetting,
                        themeMode = prefs.themeMode,
                        colorPalette = prefs.colorPalette,
                        outfitTopColors = prefs.outfitTopColors,
                        outfitBottomColors = prefs.outfitBottomColors,
                        outfitHandsColors = prefs.outfitHandsColors,
                        outfitCarriedColors = prefs.outfitCarriedColors,
                        outfitOuterColors = prefs.outfitOuterColors,
                        holidayCountrySelection = prefs.holidayCountrySelection,
                        holidayOverrides = prefs.holidayOverrides,
                        calendarOverrides = prefs.calendarOverrides,
                        effectiveEnabledHolidayCountries = effectiveCountries,
                        clothesRules = prefs.clothesRules,
                        homeSectionOrder = prefs.homeSectionOrder,
                        defaultBottom = prefs.defaultBottom,
                        defaultTop = prefs.defaultTop,
                        location = prefs.location,
                        useDeviceLocation = prefs.useDeviceLocation,
                        ttsEngine = prefs.ttsEngine,
                        geminiVoice = prefs.geminiVoice,
                        ttsStyle = prefs.ttsStyle,
                        deviceVoice = prefs.deviceVoice,
                        voiceLocale = prefs.voiceLocale,
                        calendarEnabled = prefs.calendarEnabled,
                        useCalendarEvents = prefs.useCalendarEvents,
                        themeFromCalendarHolidays = prefs.themeFromCalendarHolidays,
                        themeFromCalendarBirthdays = prefs.themeFromCalendarBirthdays,
                        telemetryEnabled = prefs.telemetryEnabled,
                        forecastModels = prefs.forecastModels,
                        mqttBridgeEnabled = prefs.mqttBridgeEnabled,
                        mqttHost = prefs.mqttHost.orEmpty(),
                        mqttPort = prefs.mqttPort,
                        mqttUseTls = prefs.mqttUseTls,
                        mqttUsername = prefs.mqttUsername.orEmpty(),
                        mqttTopic = prefs.mqttTopic,
                        mqttSkipPhoneSpeech = prefs.mqttSkipPhoneSpeech,
                        castAvailable = castAvailable,
                        castRouteName = prefs.castRouteName,
                        castEnabled = prefs.castEnabled,
                        castMorning = prefs.castMorning,
                        castTonight = prefs.castTonight,
                        castSkipPhoneSpeech = prefs.castSkipPhoneSpeech,
                    )
                }
                // Re-enumerate on first observation and whenever the effective
                // voice locale changes — from a voiceLocale flip *or* (when
                // voiceLocale is SYSTEM) a region change that shifts the
                // fallback locale.
                val effectiveLocale = prefs.voiceLocale.resolve(regionLocale)
                if (lastEnumeratedLocale != effectiveLocale) {
                    lastEnumeratedLocale = effectiveLocale
                    refreshDeviceVoices(prefs.voiceLocale)
                }
            }
        }
        insightCache?.let { cache ->
            // currentInsightSummary tracks the current-period slot for the
            // Format settings preview card ("your real ClothesCast right now,
            // with these format settings"), so it can read TODAY or TONIGHT
            // depending on which alarm last fired.
            viewModelScope.launch {
                cache.deriveFlow(
                    slot = InsightCache.Slot.THIS_PERIOD,
                    prefsFlow = settingsRepository.preferences,
                ).collect { result ->
                    _state.update { it.copy(currentInsightSummary = result?.insight?.summary) }
                }
            }
            // voicePreviewInsightSummary always prefers a daytime (TODAY)
            // summary so the Test voice audition reads as a morning briefing
            // — "Tonight will be …" reads wrong as an audition. Exactly one
            // of THIS_PERIOD / NEXT_PERIOD holds a TODAY snapshot at any time:
            //  - after morning fetch: THIS_PERIOD = TODAY, NEXT_PERIOD = TONIGHT (today)
            //  - after evening fetch: THIS_PERIOD = TONIGHT, NEXT_PERIOD = TODAY (tomorrow)
            // Pick whichever slot's summary is TODAY; null when neither has
            // been fetched yet (fresh install / cleared cache), which is the
            // canned-sample fallback in runTtsPreview.
            viewModelScope.launch {
                combine(
                    cache.deriveFlow(InsightCache.Slot.THIS_PERIOD, settingsRepository.preferences),
                    cache.deriveFlow(InsightCache.Slot.NEXT_PERIOD, settingsRepository.preferences),
                ) { thisPeriod, nextPeriod ->
                    listOf(thisPeriod, nextPeriod)
                        .firstNotNullOfOrNull { result ->
                            result?.insight?.summary?.takeIf { it.period == ForecastPeriod.TODAY }
                        }
                }.collect { summary ->
                    _state.update { it.copy(voicePreviewInsightSummary = summary) }
                }
            }
        }
        // Re-validate the API-key status whenever the stored ciphertext appears
        // or disappears. Collecting the flow (rather than a one-shot
        // refreshApiKeyStatus() at init) is what lets a key written *outside*
        // this ViewModel flip the flag — notably the phone-pairing flow now
        // reachable from Voice settings, which persists the key through a
        // separate PairingViewModel; without it, returning to the still-alive
        // SettingsViewModel via popBackStack() would keep showing the key as
        // unset until the ViewModel was recreated. We re-run refreshApiKeyStatus()
        // (which decrypts via keyStore.get()) on each emission rather than
        // trusting the flow's presence-only boolean: get() clears corrupt
        // ciphertext and returns false, so a key that exists but can no longer
        // be decrypted (e.g. after a Keystore restore/rotation) surfaces as
        // unset and prompts re-entry, instead of reading "configured" until the
        // next TTS attempt fails. Emissions are distinctUntilChanged on
        // presence, so the decrypt only runs when the stored key actually
        // changes — not a hot path.
        viewModelScope.launch {
            keyStore.geminiKeyConfiguredFlow.collect { refreshApiKeyStatus() }
        }
        viewModelScope.launch {
            keyStore.mqttPasswordConfiguredFlow.collect { set ->
                _state.update { it.copy(mqttPasswordSet = set) }
            }
        }
        viewModelScope.launch {
            settingsRepository.mqttPublishStatus.collect { status ->
                _state.update {
                    it.copy(
                        mqttLastError = status?.errorMessage,
                        mqttLastErrorAt = status?.recordedAtMs ?: 0L,
                        mqttLastPublishAt = status?.lastSuccessAtMs ?: 0L,
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.castStatus.collect { status ->
                _state.update {
                    it.copy(
                        castLastError = status?.errorMessage,
                        castLastErrorAt = status?.recordedAtMs ?: 0L,
                        castLastPublishedAt = status?.lastPublishedAtMs ?: 0L,
                        castLastFetchedAt = status?.lastFetchedAtMs ?: 0L,
                    )
                }
            }
        }
        workManager?.let { wm ->
            viewModelScope.launch {
                wm.getWorkInfosForUniqueWorkFlow(FetchAndNotifyWorker.UNIQUE_WORK_NAME_LOCATION_CACHE)
                    .collect { infos ->
                        _state.update { it.copy(locationDetecting = infos.hasActiveWork()) }
                    }
            }
            // Gate the Schedule "Play now" buttons while anything is active on
            // the daily / tonight / play queues — mirrors TodayState.anyWorkActive
            // for the top-bar Play button. The play worker runs on its own queue,
            // so WorkManager won't serialize it against a scheduled run or manual
            // refresh; without this gate a preview tap could deliver concurrently
            // (overlapping TTS, duplicate notification/MQTT/cast) and race the
            // slot's cache write.
            viewModelScope.launch {
                combine(
                    // Both scheduled periods now share one queue; Play stays
                    // on its own — see FetchAndNotifyWorker.UNIQUE_WORK_NAME.
                    wm.getWorkInfosForUniqueWorkFlow(FetchAndNotifyWorker.UNIQUE_WORK_NAME),
                    wm.getWorkInfosForUniqueWorkFlow(FetchAndNotifyWorker.UNIQUE_WORK_NAME_PLAY),
                ) { scheduled, play ->
                    scheduled.hasActiveWork() || play.hasActiveWork()
                }.collect { active ->
                    _state.update { it.copy(anyWorkActive = active) }
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
            val voices = withContext(voiceEnumerationDispatcher) {
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
                    ?: withContext(voiceEnumerationDispatcher) {
                        runCatching { voiceEnumerator.findVoice(pinnedId) }.getOrNull()
                    }
            } else {
                withContext(voiceEnumerationDispatcher) {
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
            // Re-probe Google when the key changes while Google is selected.
            // The Forecasters page's LaunchedEffect only fires on the
            // apiKeyConfigured false→true edge, so replacing an already-set key
            // (the key-recovery flow this status is meant to support) wouldn't
            // refresh it — leaving a stale "Google rejected your key" 403 next
            // to the freshly-pasted replacement until the user tapped "Check
            // again". Probing here keys the diagnostic off the new key. Harmless
            // when called from the Voice page (the result only shows on
            // Forecasters) and only probes when Google is actually selected.
            if (_state.value.forecastModels?.contains(ForecastModel.GOOGLE_WEATHER) == true) {
                probeGoogleWeather()
            }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            keyStore.clear()
            refreshApiKeyStatus()
            // The Google forecaster relies on this key. Whichever screen
            // cleared it (Voice settings or the Forecasters page both wire here),
            // drop Google from a custom selection so a "Google + one Open-Meteo"
            // pair can't silently collapse to a single contributing model and
            // lose the confidence chip. Fall back to Auto if fewer than two
            // models would remain (the confidence spread needs at least two).
            val selected = settingsRepository.preferences.first().forecastModels
            if (selected != null && ForecastModel.GOOGLE_WEATHER in selected) {
                val remaining = selected - ForecastModel.GOOGLE_WEATHER
                settingsRepository.setForecastModels(remaining.takeIf { it.size >= MIN_CONSENSUS_MODELS })
            }
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

    // Widgets repaint reactively: ClothesCastApplication observes the
    // widget-relevant slice of preferences (see WidgetInputs) and calls
    // updateAllClothesCastWidgets on any change, so these setters just write
    // and don't poke the launcher themselves.
    fun setRegion(region: Region) {
        // Apply the locale up front so the UI recreates immediately; the
        // DataStore write happens in the background. The Application's
        // onCreate reconciler re-applies on next cold start, so the order
        // here can't drift out of sync.
        applyAppLocale(region)
        viewModelScope.launch {
            settingsRepository.setRegion(region)
        }
    }

    fun setTemperatureUnitSetting(setting: TemperatureUnitSetting) {
        viewModelScope.launch {
            settingsRepository.setTemperatureUnitSetting(setting)
        }
    }

    fun setWindSpeedUnitSetting(setting: WindSpeedUnitSetting) {
        viewModelScope.launch {
            settingsRepository.setWindSpeedUnitSetting(setting)
        }
    }

    fun setTimeFormatSetting(setting: TimeFormatSetting) {
        viewModelScope.launch {
            settingsRepository.setTimeFormatSetting(setting)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun setColorPalette(palette: ColorPalette) {
        viewModelScope.launch {
            settingsRepository.setColorPalette(palette)
        }
    }

    /**
     * Sets the user's fill-colour override for the [top] icon (or clears it
     * with `argb = null`). The Today screen re-renders reactively off the
     * prefs flow; the home-screen widget repaints via the preferences observer
     * in ClothesCastApplication (see WidgetInputs).
     */
    fun setOutfitTopColor(top: OutfitSuggestion.Top, argb: Long?) {
        viewModelScope.launch {
            settingsRepository.setOutfitTopColor(top, argb)
        }
    }

    /** Sibling of [setOutfitTopColor] for the bottom-icon tier. */
    fun setOutfitBottomColor(bottom: OutfitSuggestion.Bottom, argb: Long?) {
        viewModelScope.launch {
            settingsRepository.setOutfitBottomColor(bottom, argb)
        }
    }

    /** Sibling of [setOutfitTopColor] for the optional gloves (hands) overlay. */
    fun setOutfitHandsColor(hands: OutfitSuggestion.Hands, argb: Long?) {
        viewModelScope.launch {
            settingsRepository.setOutfitHandsColor(hands, argb)
        }
    }

    /** Sibling of [setOutfitTopColor] for the optional carried umbrella overlay. */
    fun setOutfitCarriedColor(carried: OutfitSuggestion.Carried, argb: Long?) {
        viewModelScope.launch {
            settingsRepository.setOutfitCarriedColor(carried, argb)
        }
    }

    /** Sibling of [setOutfitTopColor] for the optional rain-jacket outer overlay. */
    fun setOutfitOuterColor(outer: OutfitSuggestion.Outer, argb: Long?) {
        viewModelScope.launch {
            settingsRepository.setOutfitOuterColor(outer, argb)
        }
    }

    /**
     * Flips a single holiday theme on or off in the user's preferences. The
     * Today screen reads the resulting set every time it builds state, so a
     * toggle takes effect on the next frame — no cache invalidation needed.
     */
    fun setHolidayOverride(id: HolidayId, override: HolidayOverride) {
        viewModelScope.launch { settingsRepository.setHolidayOverride(id, override) }
    }

    fun setHolidayCountryHome(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHolidayCountryHome(enabled) }
    }

    fun setHolidayCountryCurrent(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHolidayCountryCurrent(enabled) }
    }

    fun setHolidayCountryGlobal(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHolidayCountryGlobal(enabled) }
    }

    fun setHolidayCountryFunny(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHolidayCountryFunny(enabled) }
    }

    fun setHolidayCountryChristian(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHolidayCountryChristian(enabled) }
    }

    fun setHolidayCountryOrthodox(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHolidayCountryOrthodox(enabled) }
    }

    fun setHolidayCountryAll(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHolidayCountryAll(enabled) }
    }

    fun setHolidayCountryOverride(code: String, override: HolidayOverride) {
        viewModelScope.launch { settingsRepository.setHolidayCountryOverride(code, override) }
    }

    fun addClothesRule(rule: ClothesRule) {
        viewModelScope.launch {
            settingsRepository.setClothesRules(_state.value.clothesRules + rule)
        }
    }

    fun replaceClothesRule(index: Int, rule: ClothesRule) {
        viewModelScope.launch {
            val current = _state.value.clothesRules
            if (index !in current.indices) return@launch
            settingsRepository.setClothesRules(current.toMutableList().apply { this[index] = rule })
        }
    }

    fun deleteClothesRule(index: Int) {
        viewModelScope.launch {
            val current = _state.value.clothesRules
            if (index !in current.indices) return@launch
            settingsRepository.setClothesRules(current.toMutableList().apply { removeAt(index) })
        }
    }

    /**
     * Moves the home-screen section from [from] to [to], committing a settled
     * drag-reorder. No-ops on out-of-range or identical indices. Read-modify-
     * write on the current order, mirroring [replaceClothesRule] /
     * [deleteClothesRule].
     */
    fun reorderHomeSection(from: Int, to: Int) {
        viewModelScope.launch {
            val current = _state.value.homeSectionOrder
            if (from !in current.indices || to !in current.indices || from == to) return@launch
            val reordered = current.toMutableList().apply { add(to, removeAt(from)) }
            settingsRepository.setHomeSectionOrder(reordered)
        }
    }

    fun setDefaultBottom(bottom: OutfitSuggestion.Bottom) {
        viewModelScope.launch {
            settingsRepository.setDefaultBottom(bottom)
        }
    }

    fun setDefaultTop(top: OutfitSuggestion.Top) {
        viewModelScope.launch {
            settingsRepository.setDefaultTop(top)
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

    // Manual "Refresh" button on the Location settings page. Routes through
    // the same one-shot worker `setUseDeviceLocation(true)` enqueues so the
    // detecting indicator (driven by the worker's WorkInfo flow) lights up
    // for the same window. Cheap — the worker short-circuits when the cached
    // fix is still fresh, so an idle re-tap is a no-op.
    fun refreshDeviceLocation() {
        refreshLocationCache()
    }

    fun setTtsEngine(engine: TtsEngine) {
        viewModelScope.launch { settingsRepository.setTtsEngine(engine) }
    }

    fun setVoiceLocale(locale: VoiceLocale) {
        viewModelScope.launch { settingsRepository.setVoiceLocale(locale) }
    }

    /**
     * Master calendar-access switch. The repository handles the coupling
     * atomically: turning it off clears the three per-feature toggles (so a
     * later single-feature re-enable doesn't silently revive the others), and
     * enabling any sub-feature flips the master on in the same edit. The UI
     * prompts `READ_CALENDAR` before enabling.
     */
    fun setCalendarEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCalendarEnabled(enabled) }
    }

    fun setUseCalendarEvents(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setUseCalendarEvents(enabled) }
    }

    fun setThemeFromCalendarHolidays(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setThemeFromCalendarHolidays(enabled) }
    }

    fun setThemeFromCalendarBirthdays(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setThemeFromCalendarBirthdays(enabled) }
    }

    /**
     * Call after the user re-grants READ_CALENDAR via the in-app chip.
     * Bumps a tick pref so the prefs flow re-emits and `TodayViewModel`
     * re-reads calendar events without waiting for the next pref edit.
     */
    fun markCalendarPermissionRechecked() {
        viewModelScope.launch { settingsRepository.markCalendarPermissionRechecked() }
    }

    /**
     * Reads the next year of synced-calendar birthdays + public holidays into
     * [SettingsState.calendarCelebrations] for the Celebrations screen's listing.
     * Called when the screen sees READ_CALENDAR granted (and again on re-grant).
     * Only *true* duplicates collapse — same (date, title, kind) — so two
     * contacts who share a name with different birthdays, or a same-named
     * holiday recurring on different dates, both stay listed; only the same
     * event imported into two synced calendars folds together. No-ops when no
     * reader was wired (pure-VM tests) or a read is already in flight. The
     * reader degrades to an empty list on any failure, so the listing simply
     * shows "none found" rather than surfacing an error.
     */
    fun loadCalendarCelebrations() {
        val reader = calendarEventReader ?: return
        if (calendarCelebrationsJob?.isActive == true) return
        calendarCelebrationsJob = viewModelScope.launch {
            val zone = settingsRepository.preferences.first().schedule.zoneId
            val today = LocalDate.now(zone)
            val events = reader.upcomingCelebrations(today, today.plusYears(1), zone)
                .distinctBy { Triple(it.date, it.title, it.kind) }
            _state.update { it.copy(calendarCelebrations = events) }
        }
    }

    /**
     * Enumerates the device's calendars into [SettingsState.availableCalendars]
     * for the per-calendar enable/disable list. Called when the Calendar screen
     * sees READ_CALENDAR granted. No-ops when no reader is wired (pure-VM tests)
     * or a read is already in flight; degrades to an empty list on failure.
     */
    fun loadAvailableCalendars() {
        val reader = calendarEventReader ?: return
        if (availableCalendarsJob?.isActive == true) return
        availableCalendarsJob = viewModelScope.launch {
            val calendars = reader.availableCalendars()
            _state.update { it.copy(availableCalendars = calendars) }
        }
    }

    /**
     * Persists a per-calendar enable/disable choice (keyed by stable id) and
     * refreshes the celebration listings, which surface only the enabled
     * calendars — otherwise they'd keep showing a just-disabled calendar's
     * events (or omit a re-enabled one) until the screen is recreated.
     */
    fun setCalendarOverride(id: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCalendarOverride(id, enabled)
            calendarCelebrationsJob?.cancel()
            _state.update { it.copy(calendarCelebrations = null) }
            loadCalendarCelebrations()
        }
    }

    /**
     * Reads the calendar events for a single [date] so the Developer
     * "Preview a day" screen can theme an arbitrary date exactly as the Today
     * screen would. Returns an empty list when no reader is wired (pure-VM
     * tests) or the read fails (e.g. READ_CALENDAR not granted), so the
     * preview degrades to curated-catalog theming only.
     */
    suspend fun calendarEventsForDay(date: LocalDate): List<CalendarEvent> {
        val reader = calendarEventReader ?: return emptyList()
        val zone = settingsRepository.preferences.first().schedule.zoneId
        return runCatching { reader.eventsForDay(date, zone) }.getOrDefault(emptyList())
    }

    fun setTelemetryEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setTelemetryEnabled(enabled) }
    }

    fun setMqttBridgeEnabled(enabled: Boolean) {
        // Toggling the bridge off also cancels any in-flight discovery scan.
        // The picker UI is gated on the same `enabled` flag, so without this
        // a scan started before the user flipped the toggle would keep the
        // NsdManager listeners running invisibly until the screen left
        // composition (the SmartHomeContent DisposableEffect only fires on
        // route exit, not on toggle).
        if (!enabled) stopDiscovery()
        viewModelScope.launch { settingsRepository.setMqttBridgeEnabled(enabled) }
    }

    /**
     * Persists the broker connection settings. A null / blank [password]
     * leaves the stored password untouched (useful when the user edits the
     * host without re-typing the secret); pass an empty string explicitly via
     * [clearMqttPassword] to clear it. Non-blank passwords are written to
     * [SecureKeyStore] under a separate Tink AEAD slot from the Gemini key.
     */
    fun setMqttConfig(
        host: String,
        port: Int,
        useTls: Boolean,
        username: String,
        topic: String,
        password: String? = null,
    ) {
        viewModelScope.launch {
            settingsRepository.setMqttConfig(
                host = host,
                port = port,
                useTls = useTls,
                username = username,
                topic = topic,
            )
            if (password != null && password.isNotEmpty()) keyStore.setMqttPassword(password)
        }
    }

    fun clearMqttPassword() {
        viewModelScope.launch { keyStore.clearMqttPassword() }
    }

    /**
     * Publishes a test message to the configured broker immediately, using the
     * current saved configuration. Intended for the "Publish now" button on the
     * Smart Home settings page so the user can verify connectivity without
     * waiting for the next scheduled refresh. Clears or sets the last-error
     * indicator based on the outcome; [SettingsState.mqttPublishing] is true
     * for the duration.
     */
    fun publishNow() {
        val publisher = mqttPublisher ?: return
        if (_state.value.mqttPublishing) return
        viewModelScope.launch {
            _state.update { it.copy(mqttPublishing = true) }
            try {
                val action = fullPublish ?: { publisher.publishTest() }
                when (val outcome = action()) {
                    is MqttPublishOutcome.NotConfigured -> Unit
                    is MqttPublishOutcome.Success -> settingsRepository.setMqttLastError(null)
                    is MqttPublishOutcome.Failure -> settingsRepository.setMqttLastError(outcome.message)
                }
            } finally {
                _state.update { it.copy(mqttPublishing = false) }
            }
        }
    }

    /**
     * Persists the user's [ForecastModel] selection. The picker enforces a
     * minimum of two checked entries before calling here (the confidence
     * chip needs at least two models to compute a spread), so an empty
     * [models] never reaches this path from the UI; the repository still
     * defends against it for hand-edited-DataStore safety.
     */
    fun setForecastModels(models: Set<ForecastModel>?) {
        viewModelScope.launch { settingsRepository.setForecastModels(models) }
    }

    /**
     * Runs a one-shot Google Weather connectivity probe and surfaces the result
     * on the Forecasters page. The page calls this when the Google forecaster
     * is enabled (or opened with it already on) and from a "Check again" action,
     * so the user learns immediately whether their key reaches the Weather API
     * — instead of a silent missing line on the chart. No-ops when no probe
     * backend is wired (pure-VM tests). [SettingsState.googleProbeRunning] is
     * true for the duration.
     *
     * A new probe *supersedes* any in-flight one rather than being dropped: if
     * the user replaces the key while a probe is still running, that running
     * probe is hitting the API with the OLD key, so we cancel it and re-run
     * against the new key. Without this, the stale request could land its
     * verdict (e.g. a 403 for the replaced key) and the page would show a
     * rejection for a key that actually works until the user tapped "Check
     * again". The job is assigned *before* the previous one is cancelled and
     * launched LAZY so the cancelled probe's finally sees the newer job as the
     * current one and leaves its running flag / verdict alone (same identity-
     * guard pattern as [startDiscovery]).
     */
    fun probeGoogleWeather() {
        val probe = googleWeatherProbe ?: return
        val previous = googleProbeJob
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _state.update { it.copy(googleProbeRunning = true) }
            try {
                val result = probe()
                _state.update { it.copy(googleProbeResult = result) }
            } finally {
                // Only the latest probe owns the running flag — a superseded
                // probe must not switch the spinner off after a newer one took
                // over.
                if (googleProbeJob === coroutineContext[Job]) {
                    _state.update { it.copy(googleProbeRunning = false) }
                }
            }
        }
        googleProbeJob = job
        previous?.cancel()
        job.start()
    }

    /**
     * Starts an mDNS / DNS-SD scan for Home Assistant and MQTT brokers on
     * the local network. Subsequent batches arrive via the discovery flow
     * and are folded into [SettingsState.discoveredServices]. Idempotent —
     * a second call while a scan is running is a no-op. No-ops when the VM
     * was constructed without a [discovery] backend (pure-VM tests).
     */
    fun startDiscovery() {
        val source = discovery ?: return
        if (discoveryJob?.isActive == true) return
        _state.update { it.copy(discoveryRunning = true, discoveredServices = emptyList()) }
        // Launch LAZY so we can assign `discoveryJob` before the body has any
        // chance to run; the finally block's `discoveryJob === coroutineContext[Job]`
        // guard would otherwise miss under inline dispatchers (the body
        // could complete before the assignment lands).
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                source.discover().collect { hits ->
                    _state.update { it.copy(discoveredServices = hits) }
                }
            } finally {
                // Reset the running flag whenever the flow ends — natural
                // completion (NSD start failed on every service type and the
                // impl closed the channel), cancellation from stopDiscovery /
                // useDiscoveredService, or VM clear. Without this the UI
                // can sit on "Stop searching" forever after a startup
                // failure even though no listener is alive.
                //
                // Guard against a stop-then-start race: stopDiscovery cancels
                // this job, but its cancellation handler can run *after* a
                // subsequent startDiscovery has assigned a new job and
                // flipped discoveryRunning back to true. Only clear when
                // we're still the current job.
                if (discoveryJob === coroutineContext[Job]) {
                    _state.update { it.copy(discoveryRunning = false) }
                }
            }
        }
        discoveryJob = job
        job.start()
    }

    /**
     * Stops the in-flight scan. Safe to call when no scan is running.
     * Leaves the last batch of [SettingsState.discoveredServices] in place
     * so the user can pick from the results after the scan ends.
     */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _state.update { it.copy(discoveryRunning = false) }
    }

    private var castDiscoveryJob: Job? = null

    /**
     * Opens the Settings → Cast picker and starts a [MediaRouter] scan
     * for Cast routes on the LAN. The flow folds emissions into
     * [SettingsState.castDiscoveredRoutes]; the picker dialog renders
     * them. Idempotent — a second call while the picker is open does
     * nothing. No-op when the VM was constructed without a
     * [castRouteDiscovery] backend (pure-VM tests).
     */
    fun openCastPicker() {
        val source = castRouteDiscovery ?: return
        if (castDiscoveryJob?.isActive == true) return
        _state.update { it.copy(castPickerOpen = true, castDiscoveredRoutes = emptyList()) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                source.discoverRoutes().collect { routes ->
                    _state.update { it.copy(castDiscoveredRoutes = routes) }
                }
            } finally {
                if (castDiscoveryJob === coroutineContext[Job]) {
                    _state.update { it.copy(castPickerOpen = false) }
                }
            }
        }
        castDiscoveryJob = job
        job.start()
    }

    /** Closes the cast picker and cancels the in-flight discovery scan. */
    fun closeCastPicker() {
        castDiscoveryJob?.cancel()
        castDiscoveryJob = null
        _state.update { it.copy(castPickerOpen = false) }
    }

    /** Persists the user's choice and closes the picker. */
    fun pickCastRoute(route: DiscoveredCastRoute) {
        viewModelScope.launch {
            settingsRepository.setCastRoute(routeId = route.id, routeName = route.name)
            closeCastPicker()
        }
    }

    /** Clears the saved Cast route (Settings row reverts to "No display picked"). */
    fun clearCastRoute() {
        viewModelScope.launch { settingsRepository.setCastRoute(routeId = null, routeName = null) }
    }

    fun setCastEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCastEnabled(enabled) }
    }

    fun setCastMorning(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCastMorning(enabled) }
    }

    fun setCastTonight(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCastTonight(enabled) }
    }

    fun setCastSkipPhoneSpeech(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCastSkipPhoneSpeech(enabled) }
    }

    fun setMqttSkipPhoneSpeech(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMqttSkipPhoneSpeech(enabled) }
    }

    /**
     * Runs the "Cast now" test. Reuses the worker's render + synth
     * pipeline so the test cast is visually identical to what the
     * scheduled cast will (in PR B) produce. No-op when the VM was
     * constructed without a [castNowAction] (pure-VM tests).
     */
    fun castNow() {
        val action = castNowAction ?: return
        if (_state.value.castInProgress) return
        viewModelScope.launch {
            _state.update { it.copy(castInProgress = true) }
            try {
                val outcome = action()
                settingsRepository.setCastResult(
                    errorMessage = outcome.errorMessage,
                    publishedAtMs = outcome.publishedAtMs,
                    fetchedAtMs = outcome.fetchedAtMs,
                )
            } finally {
                _state.update { it.copy(castInProgress = false) }
            }
        }
    }

    /**
     * Pre-fills the MQTT broker config with a discovered service. For an
     * MQTT advert the resolved port is the broker port; for a Home
     * Assistant advert we keep the existing port (the HA web port itself
     * isn't useful and the user's Mosquitto addon usually sits on the
     * default 1883 / 8883). Stops the scan so the picker collapses.
     */
    fun useDiscoveredService(service: DiscoveredService) {
        stopDiscovery()
        viewModelScope.launch {
            val current = settingsRepository.preferences.first()
            val port = when (service.type) {
                ServiceType.MQTT -> service.port
                ServiceType.HOME_ASSISTANT -> current.mqttPort
            }
            settingsRepository.setMqttConfig(
                host = service.host,
                port = port,
                useTls = current.mqttUseTls,
                username = current.mqttUsername.orEmpty(),
                topic = current.mqttTopic,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        discoveryJob?.cancel()
        discoveryJob = null
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

    fun setClothesMentionMode(mode: ClothesMentionMode) {
        // Prose-only setting — the Today screen / Format settings preview
        // / cast / MQTT all re-derive off the cached snapshot reactively
        // through the prefs flow, so no widget poke is needed (the widget
        // only renders the outfit icon, which this mode doesn't touch).
        viewModelScope.launch { settingsRepository.setClothesMentionMode(mode) }
    }

    fun setRangeFormat(format: RangeFormat) {
        viewModelScope.launch { settingsRepository.setRangeFormat(format) }
    }

    fun setClothesFormat(format: ClothesFormat) {
        viewModelScope.launch { settingsRepository.setClothesFormat(format) }
    }

    fun setBottomsFormat(format: BottomsFormat) {
        viewModelScope.launch { settingsRepository.setBottomsFormat(format) }
    }

    fun setAccessoriesFormat(format: AccessoriesFormat) {
        viewModelScope.launch { settingsRepository.setAccessoriesFormat(format) }
    }


    fun setPeriodPreamble(visibility: PreambleVisibility) {
        viewModelScope.launch { settingsRepository.setPeriodPreamble(visibility) }
    }

    fun setWearPreamble(visibility: PreambleVisibility) {
        viewModelScope.launch { settingsRepository.setWearPreamble(visibility) }
    }

    fun setDeltaThresholdC(thresholdC: Double?) {
        // Prose-only — gates the DeltaClause for the Today screen / Format
        // settings preview / cast / MQTT, all of which re-derive from the
        // cached snapshot reactively. The widget doesn't show delta.
        viewModelScope.launch { settingsRepository.setDeltaThresholdC(thresholdC) }
    }

    fun setDeltaFormat(format: DeltaFormat) {
        // Prose-only — selects numeric vs absolute-band phrasing for the
        // change clause; re-derived reactively like the threshold above.
        viewModelScope.launch { settingsRepository.setDeltaFormat(format) }
    }

    fun setDailyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDailyEnabled(enabled)
            val prefs = settingsRepository.preferences.first()
            if (prefs.dailyEnabled) {
                rearmAlarm(prefs.schedule, ForecastPeriod.TODAY)
            } else {
                cancelAlarm(ForecastPeriod.TODAY)
            }
        }
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
        private val workManager: WorkManager? = null,
        private val mqttPublisher: MqttPublisher? = null,
        private val fullPublish: (suspend () -> MqttPublishOutcome)? = null,
        private val discovery: HomeAssistantDiscovery? = null,
        private val castRouteDiscovery: CastRouteDiscovery? = null,
        private val castNowAction: (suspend () -> CastTestOutcome)? = null,
        private val castAvailable: Boolean = false,
        private val sharedTtsAvailable: Boolean = false,
        private val calendarEventReader: CalendarEventReader? = null,
        private val insightCache: InsightCache? = null,
        private val googleWeatherProbe: (suspend () -> GoogleWeatherProbe)? = null,
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
                applyAppLocale = applyAppLocale,
                refreshLocationCache = refreshLocationCache,
                workManager = workManager,
                mqttPublisher = mqttPublisher,
                fullPublish = fullPublish,
                discovery = discovery,
                castRouteDiscovery = castRouteDiscovery,
                castNowAction = castNowAction,
                castAvailable = castAvailable,
                sharedTtsAvailable = sharedTtsAvailable,
                calendarEventReader = calendarEventReader,
                insightCache = insightCache,
                googleWeatherProbe = googleWeatherProbe,
            ) as T
        }
    }

}
