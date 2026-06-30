package app.clothescast.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.R
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.tts.toJavaLocale
import app.clothescast.ui.BugReportOverflowMenu
import app.clothescast.ui.EdgeFadeOverlay
import app.clothescast.ui.LocalNavigateToAbout
import app.clothescast.ui.LocalTimeFormat

/**
 * A row on the Settings root menu: a label, subtitle, and the navigation it
 * triggers. The nav host (ClothesCastNavHost) builds the ordered list and
 * attaches each row's navigation; this type carries no navigation dependency so
 * previews can render the menu standalone.
 */
data class SettingsMenuItem(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val onClick: () -> Unit,
)

/**
 * Canonical list of Settings root rows, in display order. Centralising the
 * order + label strings here keeps the nav host (which wires each row to its
 * navigation destination) and the snapshot preview (which renders with no-op
 * clicks) in lockstep — adding a new settings page is one new entry, and the
 * compiler enforces exhaustive handling in the nav host's mapping `when`.
 */
internal enum class SettingsDest(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
) {
    SCHEDULE(R.string.settings_root_schedule, R.string.settings_root_schedule_subtitle),
    CLOTHES(R.string.settings_root_clothes, R.string.settings_root_clothes_subtitle),
    FORMAT(R.string.settings_root_format, R.string.settings_root_format_subtitle),
    LOCATION(R.string.settings_root_location, R.string.settings_root_location_subtitle),
    REGION(R.string.settings_root_region, R.string.settings_root_region_subtitle),
    VOICE(R.string.settings_root_voice, R.string.settings_root_voice_subtitle),
    DISPLAY(R.string.settings_root_display, R.string.settings_root_display_subtitle),
    CALENDAR(R.string.settings_root_calendar, R.string.settings_root_calendar_subtitle),
    FORECASTERS(R.string.settings_root_forecasters, R.string.settings_root_forecasters_subtitle),
    SMART_HOME(R.string.settings_root_smart_home, R.string.settings_root_smart_home_subtitle),
    PRIVACY(R.string.settings_root_privacy, R.string.settings_root_privacy_subtitle),
    DEVELOPER(R.string.settings_root_developer, R.string.settings_root_developer_subtitle),
}

/**
 * Optional "Done" action for a Settings sub-page, supplied by the nav host (see
 * ClothesCastNavHost) when the page was opened from outside the Settings root
 * menu — deep-linked from a Today promo/banner, or pushed from another settings
 * page's setup jump (e.g. enabling spoken delivery on Schedule / Smart Home).
 * When non-null, [SettingsScaffold] renders a bottom "Done" bar that invokes it:
 * a clear "finished — take me back" affordance for a focused task, alongside the
 * top-bar back arrow. Null (the default, and always so for menu-originated pages
 * and for previews/tests with no provider) renders no bar, so the menu-driven
 * browse flow keeps its single back affordance.
 */
internal val LocalSettingsDoneAction = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Shared chrome for a Settings sub-page: a top bar showing [titleRes] with a back
 * arrow wired to [onBack] (which pops the nav back stack), plus edge-to-edge
 * content insets. Every destination in the Settings nested graph renders through
 * this — there's no central route state or custom back handling; the framework's
 * back stack owns up-navigation. When [LocalSettingsDoneAction] is provided, a
 * bottom "Done" bar is shown that returns the user to wherever the page was
 * opened from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val onDone = LocalSettingsDoneAction.current
    Scaffold(
        // Drop the default `safeDrawing` content insets so each sub-screen's
        // scroll viewport extends edge-to-edge under the (transparent) nav bar.
        // Each sub-screen's inner Column adds
        // `windowInsetsPadding(WindowInsets.navigationBars)` as content padding
        // so the last item can scroll above the nav bar; the bottom fade in
        // `EdgeFadeOverlay` does the same so it sits just above the bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                actions = {
                    BugReportOverflowMenu(onNavigateToAbout = LocalNavigateToAbout.current)
                },
            )
        },
        bottomBar = { if (onDone != null) SettingsDoneBar(onDone) },
        content = content,
    )
}

/**
 * Bottom "Done" bar for a settings sub-page opened as a standalone task. A
 * full-width button on its own surface, lifted above the system nav bar so it
 * doesn't collide with gesture / button navigation. The host [Scaffold] insets
 * its content by this bar's height, so a page's scroll viewport ends just above
 * the button.
 */
@Composable
private fun SettingsDoneBar(onDone: () -> Unit) {
    Surface {
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.settings_done)) }
    }
}

@Composable
internal fun SettingsRootPage(
    viewModel: SettingsViewModel,
    items: List<SettingsMenuItem>,
    onBack: () -> Unit,
    onOpenLocation: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_title, onBack) { padding ->
        SettingsRoot(
            useDeviceLocation = state.useDeviceLocation,
            scheduleEnabled = state.dailyEnabled || state.tonightEnabled,
            items = items,
            padding = padding,
            onOpenLocation = onOpenLocation,
        )
    }
}

@Composable
internal fun SchedulePage(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onSetUpSpeech: () -> Unit,
    onOpenSmartHome: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_page_schedule, onBack) { padding ->
        CompositionLocalProvider(LocalTimeFormat provides state.timeFormat) {
        ScheduleContent(
            time = state.scheduleTime,
            days = state.scheduleDays,
            dailyEnabled = state.dailyEnabled,
            tonightTime = state.tonightTime,
            tonightDays = state.tonightDays,
            tonightEnabled = state.tonightEnabled,
            tonightNotifyOnlyOnEvents = state.tonightNotifyOnlyOnEvents,
            dailyMentionEveningEvents = state.dailyMentionEveningEvents,
            deliveryMode = state.deliveryMode,
            tonightDeliveryMode = state.tonightDeliveryMode,
            speechConfigured = state.speechConfigured,
            useDeviceLocation = state.useDeviceLocation,
            location = state.location,
            padding = padding,
            onSetSchedule = viewModel::setSchedule,
            onSetDailyEnabled = viewModel::setDailyEnabled,
            onSetUseDeviceLocation = viewModel::setUseDeviceLocation,
            onSetTonightSchedule = viewModel::setTonightSchedule,
            onSetTonightEnabled = viewModel::setTonightEnabled,
            onSetTonightNotifyOnlyOnEvents = viewModel::setTonightNotifyOnlyOnEvents,
            onSetDailyMentionEveningEvents = viewModel::setDailyMentionEveningEvents,
            onSetDeliveryMode = viewModel::setDeliveryMode,
            onSetTonightDeliveryMode = viewModel::setTonightDeliveryMode,
            onSetUpSpeech = onSetUpSpeech,
            mqttBridgeEnabled = state.mqttBridgeEnabled,
            mqttSkipPhoneSpeech = state.mqttSkipPhoneSpeech,
            castAvailable = state.castAvailable,
            castRouteName = state.castRouteName,
            castEnabled = state.castEnabled,
            castMorning = state.castMorning,
            castTonight = state.castTonight,
            castSkipPhoneSpeech = state.castSkipPhoneSpeech,
            onSetMqttSkipPhoneSpeech = viewModel::setMqttSkipPhoneSpeech,
            onSetCastMorning = viewModel::setCastMorning,
            onSetCastTonight = viewModel::setCastTonight,
            onSetCastSkipPhoneSpeech = viewModel::setCastSkipPhoneSpeech,
            onOpenSmartHome = onOpenSmartHome,
            previewEnabled = !state.anyWorkActive,
        )
        }
    }
}

@Composable
internal fun ClothesPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_page_clothes, onBack) { padding ->
        ClothesContent(
            rules = state.clothesRules,
            defaultBottom = state.defaultBottom,
            defaultTop = state.defaultTop,
            temperatureUnit = state.temperatureUnit,
            outfitTopColors = state.outfitTopColors,
            outfitBottomColors = state.outfitBottomColors,
            outfitHandsColors = state.outfitHandsColors,
            outfitCarriedColors = state.outfitCarriedColors,
            outfitOuterColors = state.outfitOuterColors,
            padding = padding,
            onAdd = viewModel::addClothesRule,
            onReplace = viewModel::replaceClothesRule,
            onDelete = viewModel::deleteClothesRule,
            onSetDefaultBottom = viewModel::setDefaultBottom,
            onSetDefaultTop = viewModel::setDefaultTop,
            onSetOutfitTopColor = viewModel::setOutfitTopColor,
            onSetOutfitBottomColor = viewModel::setOutfitBottomColor,
            onSetOutfitHandsColor = viewModel::setOutfitHandsColor,
            onSetOutfitCarriedColor = viewModel::setOutfitCarriedColor,
            onSetOutfitOuterColor = viewModel::setOutfitOuterColor,
        )
    }
}

@Composable
internal fun RegionPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_page_region, onBack) { padding ->
        RegionContent(
            region = state.region,
            temperatureUnitSetting = state.temperatureUnitSetting,
            windSpeedUnitSetting = state.windSpeedUnitSetting,
            timeFormatSetting = state.timeFormatSetting,
            resolvedTemperatureUnit = state.temperatureUnit,
            resolvedWindSpeedUnit = state.windSpeedUnit,
            resolvedTimeFormat = state.timeFormat,
            padding = padding,
            onSetRegion = viewModel::setRegion,
            onSetTemperatureUnit = viewModel::setTemperatureUnitSetting,
            onSetWindSpeedUnit = viewModel::setWindSpeedUnitSetting,
            onSetTimeFormat = viewModel::setTimeFormatSetting,
        )
    }
}

@Composable
internal fun VoicePage(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onPairFromPhone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_page_voice, onBack) { padding ->
        VoiceContent(
            selected = state.ttsEngine,
            geminiVoice = state.geminiVoice,
            ttsStyle = state.ttsStyle,
            deviceVoice = state.deviceVoice,
            deviceVoices = state.deviceVoices,
            effectiveDeviceVoice = state.effectiveDeviceVoice,
            geminiKeyConfigured = state.apiKeyConfigured,
            sharedTtsAvailable = state.sharedTtsAvailable,
            voiceLocale = state.voiceLocale,
            region = state.region,
            temperatureUnit = state.temperatureUnit,
            rangeFormat = state.rangeFormat,
            clothesFormat = state.clothesFormat,
            bottomsFormat = state.bottomsFormat,
            accessoriesFormat = state.accessoriesFormat,
            periodPreamble = state.periodPreamble,
            wearPreamble = state.wearPreamble,
            currentInsight = state.voicePreviewInsightSummary,
            padding = padding,
            onSetTtsEngine = viewModel::setTtsEngine,
            onSetGeminiVoice = viewModel::setGeminiVoice,
            onSetTtsStyle = viewModel::setTtsStyle,
            onSetDeviceVoice = viewModel::setDeviceVoice,
            onSetVoiceLocale = viewModel::setVoiceLocale,
            onSetGeminiKey = viewModel::setApiKey,
            onClearGeminiKey = viewModel::clearApiKey,
            onPairFromPhone = onPairFromPhone,
            // Production entry point — show the debug-token row. Previews
            // (`SettingsVoice*Preview` in `SettingsPreviews.kt`) leave this
            // off by default so a developer's real token never gets baked
            // into the committed `app/snapshots/settings_voice_*.png` files.
            showAppCheckDebugTokenRow = true,
        )
    }
}

@Composable
internal fun DisplayPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_page_display, onBack) { padding ->
        DisplayContent(
            themeMode = state.themeMode,
            colorPalette = state.colorPalette,
            homeSectionOrder = state.homeSectionOrder,
            padding = padding,
            onSetThemeMode = viewModel::setThemeMode,
            onSetColorPalette = viewModel::setColorPalette,
            onReorderHomeSection = viewModel::reorderHomeSection,
        )
    }
}

@Composable
internal fun LocationPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_page_location, onBack) { padding ->
        LocationContent(
            location = state.location,
            useDeviceLocation = state.useDeviceLocation,
            locationDetecting = state.locationDetecting,
            scheduleEnabled = state.dailyEnabled || state.tonightEnabled,
            padding = padding,
            onSetUseDeviceLocation = viewModel::setUseDeviceLocation,
            onSelectLocation = viewModel::selectLocation,
            onClearLocation = viewModel::clearLocation,
            onSearchLocations = viewModel::searchLocations,
            onRefresh = viewModel::refreshDeviceLocation,
        )
    }
}

@Composable
internal fun CalendarPage(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigateToRegion: () -> Unit,
    onNavigateToLocation: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Observe the device locale so the fallback country tracks a locale change.
    val deviceLocale = LocalConfiguration.current.locales[0]
    SettingsScaffold(R.string.settings_page_calendar, onBack) { padding ->
        CalendarContent(
            holidayCountrySelection = state.holidayCountrySelection,
            holidayOverrides = state.holidayOverrides,
            effectiveEnabledHolidayCountries = state.effectiveEnabledHolidayCountries,
            localeCountry = state.region.toJavaLocale()?.country
                ?: deviceLocale.country,
            weatherLocationCountry = state.location?.countryCode,
            calendarEnabled = state.calendarEnabled,
            useCalendarEvents = state.useCalendarEvents,
            themeFromCalendarHolidays = state.themeFromCalendarHolidays,
            themeFromCalendarBirthdays = state.themeFromCalendarBirthdays,
            calendarCelebrations = state.calendarCelebrations,
            availableCalendars = state.availableCalendars,
            calendarOverrides = state.calendarOverrides,
            padding = padding,
            onSetCalendarEnabled = viewModel::setCalendarEnabled,
            onSetUseCalendarEvents = viewModel::setUseCalendarEvents,
            onSetCountryHome = viewModel::setHolidayCountryHome,
            onSetCountryCurrent = viewModel::setHolidayCountryCurrent,
            onSetCountryGlobal = viewModel::setHolidayCountryGlobal,
            onSetCountryFunny = viewModel::setHolidayCountryFunny,
            onSetCountryChristian = viewModel::setHolidayCountryChristian,
            onSetCountryOrthodox = viewModel::setHolidayCountryOrthodox,
            onSetCountryAll = viewModel::setHolidayCountryAll,
            onSetCountryOverride = viewModel::setHolidayCountryOverride,
            onSetHolidayOverride = viewModel::setHolidayOverride,
            onSetThemeFromCalendarHolidays = viewModel::setThemeFromCalendarHolidays,
            onSetThemeFromCalendarBirthdays = viewModel::setThemeFromCalendarBirthdays,
            onCalendarPermissionRechecked = viewModel::markCalendarPermissionRechecked,
            onLoadCalendarCelebrations = viewModel::loadCalendarCelebrations,
            onLoadAvailableCalendars = viewModel::loadAvailableCalendars,
            onSetCalendarOverride = viewModel::setCalendarOverride,
            onNavigateToRegionSettings = onNavigateToRegion,
            onNavigateToLocationSettings = onNavigateToLocation,
        )
    }
}

@Composable
internal fun ForecastersPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Probe Google whenever it becomes the selected-and-keyed state — covers
    // both opening the page with Google already on and toggling it on here.
    // Keyed on the two booleans (not the whole model set) so changing an
    // unrelated model doesn't trigger a redundant probe.
    val googleSelected = state.forecastModels?.contains(ForecastModel.GOOGLE_WEATHER) == true
    LaunchedEffect(googleSelected, state.googleApiKeyConfigured) {
        if (googleSelected && state.googleApiKeyConfigured) viewModel.probeGoogleWeather()
    }
    SettingsScaffold(R.string.settings_page_forecasters, onBack) { padding ->
        ForecastersContent(
            forecastModels = state.forecastModels,
            location = state.location,
            padding = padding,
            onSetForecastModels = viewModel::setForecastModels,
            googleApiKeyConfigured = state.googleApiKeyConfigured,
            onSetGoogleApiKey = viewModel::setGoogleApiKey,
            onClearGoogleApiKey = viewModel::clearGoogleApiKey,
            googleProbeRunning = state.googleProbeRunning,
            googleProbeResult = state.googleProbeResult,
            onCheckGoogleWeather = viewModel::probeGoogleWeather,
        )
    }
}

@Composable
internal fun SmartHomePage(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onSetUpSpeech: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_page_smart_home, onBack) { padding ->
        CompositionLocalProvider(LocalTimeFormat provides state.timeFormat) {
        SmartHomeContent(
            bridgeEnabled = state.mqttBridgeEnabled,
            host = state.mqttHost,
            port = state.mqttPort,
            useTls = state.mqttUseTls,
            username = state.mqttUsername,
            topic = state.mqttTopic,
            passwordSet = state.mqttPasswordSet,
            lastError = state.mqttLastError,
            lastErrorAt = state.mqttLastErrorAt,
            lastPublishAt = state.mqttLastPublishAt,
            publishing = state.mqttPublishing,
            mqttSkipPhoneSpeech = state.mqttSkipPhoneSpeech,
            discoveryRunning = state.discoveryRunning,
            discoveredServices = state.discoveredServices,
            castAvailable = state.castAvailable,
            castRouteName = state.castRouteName,
            castPickerOpen = state.castPickerOpen,
            castDiscoveredRoutes = state.castDiscoveredRoutes,
            castInProgress = state.castInProgress,
            castLastError = state.castLastError,
            castLastErrorAt = state.castLastErrorAt,
            castLastPublishedAt = state.castLastPublishedAt,
            castLastFetchedAt = state.castLastFetchedAt,
            castEnabled = state.castEnabled,
            castMorning = state.castMorning,
            castTonight = state.castTonight,
            castSkipPhoneSpeech = state.castSkipPhoneSpeech,
            ttsEngine = state.ttsEngine,
            geminiKeyConfigured = state.apiKeyConfigured,
            sharedTtsAvailable = state.sharedTtsAvailable,
            padding = padding,
            onSetBridgeEnabled = viewModel::setMqttBridgeEnabled,
            onSaveConfig = viewModel::setMqttConfig,
            onClearPassword = viewModel::clearMqttPassword,
            onPublishNow = viewModel::publishNow,
            onStartDiscovery = viewModel::startDiscovery,
            onStopDiscovery = viewModel::stopDiscovery,
            onUseDiscoveredService = viewModel::useDiscoveredService,
            onOpenCastPicker = viewModel::openCastPicker,
            onCloseCastPicker = viewModel::closeCastPicker,
            onPickCastRoute = viewModel::pickCastRoute,
            onClearCastRoute = viewModel::clearCastRoute,
            onCastNow = viewModel::castNow,
            onSetCastEnabled = viewModel::setCastEnabled,
            onSetCastMorning = viewModel::setCastMorning,
            onSetCastTonight = viewModel::setCastTonight,
            onSetCastSkipPhoneSpeech = viewModel::setCastSkipPhoneSpeech,
            onSetMqttSkipPhoneSpeech = viewModel::setMqttSkipPhoneSpeech,
            onSetUpSpeech = onSetUpSpeech,
        )
        }
    }
}

@Composable
internal fun PrivacyPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_page_privacy, onBack) { padding ->
        PrivacyContent(
            telemetryEnabled = state.telemetryEnabled,
            padding = padding,
            onSetTelemetryEnabled = viewModel::setTelemetryEnabled,
        )
    }
}

@Composable
internal fun AboutPage(onBack: () -> Unit) {
    SettingsScaffold(R.string.settings_root_about, onBack) { padding ->
        AboutContent(padding = padding)
    }
}

@Composable
internal fun SettingsRoot(
    useDeviceLocation: Boolean,
    scheduleEnabled: Boolean,
    items: List<SettingsMenuItem>,
    padding: PaddingValues,
    onOpenLocation: () -> Unit,
) {
    val scrollState = rememberScrollState()
    EdgeFadeOverlay(
        scrollState = scrollState,
        modifier = Modifier.padding(padding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Surface a missing always-on grant from the settings root too so the
            // user sees the broken state without having to drill into Location.
            // Tapping the card deep-links into Location where the launcher and
            // rationale dialogs live.
            BackgroundLocationWarningCard(
                useDeviceLocation = useDeviceLocation,
                scheduleEnabled = scheduleEnabled,
                onClick = onOpenLocation,
            )
            items.forEach { item ->
                SettingsNavRow(
                    title = stringResource(item.titleRes),
                    subtitle = stringResource(item.subtitleRes),
                    onClick = item.onClick,
                )
            }
        }
    }
}
