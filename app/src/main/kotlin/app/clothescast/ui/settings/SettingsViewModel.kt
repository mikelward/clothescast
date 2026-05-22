package app.clothescast.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.clothescast.core.data.location.OpenMeteoGeocodingClient
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnitSetting
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnitSetting
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.data.InsightCache
import app.clothescast.data.SecureKeyStore
import app.clothescast.data.SettingsRepository
import app.clothescast.cast.CastRouteDiscovery
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
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
     * Resolves the device's current coarse fix and reverse-geocodes its
     * displayName for the "Use my current location" button on the home-pin
     * card. Returns null on any failure (permission missing at call time,
     * provider unavailable, timeout) — the caller surfaces that as a
     * no-op. Foreground action, so this only needs coarse permission;
     * the deeper background grant is checked separately at the at-home
     * gate. Defaulted to no-op so pure-VM tests don't need a real
     * [LocationResolver]; the Activity wires the real one.
     */
    private val resolveDeviceLocationWithCity: suspend () -> Location? = { null },
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
     * insight to the saved smart display, returning null on success or
     * a user-facing error string. The Activity wires this with the
     * real [castCurrentInsight] closure over Context + InsightCache +
     * [CastInsightController]. Null in pure-VM tests; the button is
     * inert in that case.
     */
    private val castNowAction: (suspend () -> String?)? = null,
    /**
     * True when [com.google.android.gms.cast.framework.CastContext] is
     * available on this device. Cast-less emulators / GMS-free builds
     * pass false; the Settings UI hides the whole Cast section then.
     */
    private val castAvailable: Boolean = false,
    /**
     * Reads the user's synced calendars for the Celebrations screen's
     * upcoming-birthdays / -holidays listing. Null in pure-VM tests that don't
     * need an Android ContentResolver; [loadCalendarCelebrations] then no-ops
     * and the listing stays empty.
     */
    private val calendarEventReader: CalendarEventReader? = null,
    /**
     * Read-only access to the cached current insight so the Format settings
     * page can preview the user's *real* ClothesCast next to the synthetic
     * example. Null in pure-VM tests that don't need the cache; the Activity
     * wires the real [InsightCache] and [SettingsState.currentInsightSummary]
     * then tracks page 1 of the Today pager.
     */
    private val insightCache: InsightCache? = null,
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
     * Tracks the in-flight mDNS scan so we can cancel it on stop or when the
     * VM is cleared, even if the user navigates away mid-scan.
     */
    private var discoveryJob: Job? = null
    /** In-flight upcoming-celebrations read; guards against overlapping loads. */
    private var calendarCelebrationsJob: Job? = null
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
                        tonightTime = prefs.tonightSchedule.time,
                        tonightDays = prefs.tonightSchedule.days,
                        tonightEnabled = prefs.tonightEnabled,
                        tonightNotifyOnlyOnEvents = prefs.tonightNotifyOnlyOnEvents,
                        deliveryMode = prefs.deliveryMode,
                        tonightDeliveryMode = prefs.tonightDeliveryMode,
                        dailyMentionEveningEvents = prefs.dailyMentionEveningEvents,
                        clothesMentionMode = prefs.clothesMentionMode,
                        rangeFormat = prefs.rangeFormat,
                        deltaThresholdC = prefs.deltaThresholdC,
                        region = prefs.region,
                        temperatureUnit = prefs.temperatureUnit,
                        distanceUnit = prefs.distanceUnit,
                        temperatureUnitSetting = prefs.temperatureUnitSetting,
                        distanceUnitSetting = prefs.distanceUnitSetting,
                        themeMode = prefs.themeMode,
                        colorPalette = prefs.colorPalette,
                        outfitTopColors = prefs.outfitTopColors,
                        outfitBottomColors = prefs.outfitBottomColors,
                        holidayCountrySelection = prefs.holidayCountrySelection,
                        holidayOverrides = prefs.holidayOverrides,
                        effectiveEnabledHolidayCountries = effectiveCountries,
                        clothesRules = prefs.clothesRules,
                        defaultBottom = prefs.defaultBottom,
                        location = prefs.location,
                        useDeviceLocation = prefs.useDeviceLocation,
                        homeLocation = prefs.homeLocation,
                        skipTtsAtHome = prefs.skipTtsAtHome,
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
                        castAvailable = castAvailable,
                        castRouteName = prefs.castRouteName,
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
            viewModelScope.launch {
                cache.thisPeriod.collect { insight ->
                    _state.update { it.copy(currentInsightSummary = insight?.summary) }
                }
            }
        }
        viewModelScope.launch { refreshApiKeyStatus() }
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
                        castLastSuccessAt = status?.lastSuccessAtMs ?: 0L,
                    )
                }
            }
        }
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

    fun setTemperatureUnitSetting(setting: TemperatureUnitSetting) {
        viewModelScope.launch { settingsRepository.setTemperatureUnitSetting(setting) }
    }

    fun setDistanceUnitSetting(setting: DistanceUnitSetting) {
        viewModelScope.launch { settingsRepository.setDistanceUnitSetting(setting) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setColorPalette(palette: ColorPalette) {
        viewModelScope.launch { settingsRepository.setColorPalette(palette) }
    }

    /**
     * Sets the user's fill-colour override for the [top] icon (or clears it
     * with `argb = null`). Re-renders the cached outfit + widget so the
     * Today screen and any home-screen widget pick up the new colour in
     * the same frame as the picker dismisses.
     */
    fun setOutfitTopColor(top: OutfitSuggestion.Top, argb: Long?) {
        viewModelScope.launch {
            settingsRepository.setOutfitTopColor(top, argb)
            refreshCachedOutfits()
        }
    }

    /** Sibling of [setOutfitTopColor] for the bottom-icon tier. */
    fun setOutfitBottomColor(bottom: OutfitSuggestion.Bottom, argb: Long?) {
        viewModelScope.launch {
            settingsRepository.setOutfitBottomColor(bottom, argb)
            refreshCachedOutfits()
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

    fun setHolidayCountryAll(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHolidayCountryAll(enabled) }
    }

    fun setHolidayCountryOverride(code: String, override: HolidayOverride) {
        viewModelScope.launch { settingsRepository.setHolidayCountryOverride(code, override) }
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

    fun selectHomeLocation(location: Location) {
        viewModelScope.launch { settingsRepository.setHomeLocation(location) }
    }

    fun clearHomeLocation() {
        viewModelScope.launch { settingsRepository.setHomeLocation(null) }
    }

    /**
     * Snapshots the device's current coarse fix and stores it as the home
     * pin. Called by the "Use my current location" button on the home-pin
     * card. Necessary because the search-by-name picker returns geocoder
     * place candidates (city / admin centroids); for the 1 km at-home
     * radius to actually fire, the home pin needs to be at the user's
     * actual house, not the centroid of their city — and a coarse device
     * fix is precise enough (~hundreds of metres). Caller is expected to
     * have requested ACCESS_COARSE_LOCATION before calling.
     *
     * No-ops silently when the resolver returns null (provider off,
     * timeout, permission revoked between request and call). The
     * preferences flow re-emits when the write lands; the UI updates
     * from there.
     */
    fun useCurrentLocationForHome() {
        if (_state.value.homeLocationResolving) return
        _state.update { it.copy(homeLocationResolving = true) }
        viewModelScope.launch {
            try {
                val fix = resolveDeviceLocationWithCity()
                if (fix != null) settingsRepository.setHomeLocation(fix)
            } finally {
                _state.update { it.copy(homeLocationResolving = false) }
            }
        }
    }

    fun setSkipTtsAtHome(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSkipTtsAtHome(enabled) }
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

    fun setCastMorning(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCastMorning(enabled) }
    }

    fun setCastTonight(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCastTonight(enabled) }
    }

    fun setCastSkipPhoneSpeech(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCastSkipPhoneSpeech(enabled) }
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
                val error = action()
                settingsRepository.setCastLastError(error)
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
        viewModelScope.launch { settingsRepository.setClothesMentionMode(mode) }
    }

    fun setRangeFormat(format: RangeFormat) {
        viewModelScope.launch { settingsRepository.setRangeFormat(format) }
    }

    fun setDeltaThresholdC(thresholdC: Double?) {
        viewModelScope.launch { settingsRepository.setDeltaThresholdC(thresholdC) }
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
        private val resolveDeviceLocationWithCity: suspend () -> Location? = { null },
        private val workManager: WorkManager? = null,
        private val mqttPublisher: MqttPublisher? = null,
        private val fullPublish: (suspend () -> MqttPublishOutcome)? = null,
        private val discovery: HomeAssistantDiscovery? = null,
        private val castRouteDiscovery: CastRouteDiscovery? = null,
        private val castNowAction: (suspend () -> String?)? = null,
        private val castAvailable: Boolean = false,
        private val calendarEventReader: CalendarEventReader? = null,
        private val insightCache: InsightCache? = null,
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
                resolveDeviceLocationWithCity = resolveDeviceLocationWithCity,
                workManager = workManager,
                mqttPublisher = mqttPublisher,
                fullPublish = fullPublish,
                discovery = discovery,
                castRouteDiscovery = castRouteDiscovery,
                castNowAction = castNowAction,
                castAvailable = castAvailable,
                calendarEventReader = calendarEventReader,
                insightCache = insightCache,
            ) as T
        }
    }

}
