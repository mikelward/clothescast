package app.clothescast.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.R
import app.clothescast.tts.toJavaLocale
import app.clothescast.ui.EdgeFadeOverlay
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
 * Shared chrome for a Settings sub-page: a top bar showing [titleRes] with a back
 * arrow wired to [onBack] (which pops the nav back stack), plus edge-to-edge
 * content insets. Every destination in the Settings nested graph renders through
 * this — there's no central route state or custom back handling; the framework's
 * back stack owns up-navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
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
            )
        },
        content = content,
    )
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_root_schedule, onBack) { padding ->
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
            ttsEngine = state.ttsEngine,
            geminiKeyConfigured = state.apiKeyConfigured,
            sharedTtsAvailable = state.sharedTtsAvailable,
            padding = padding,
            onSetSchedule = viewModel::setSchedule,
            onSetDailyEnabled = viewModel::setDailyEnabled,
            onSetTonightSchedule = viewModel::setTonightSchedule,
            onSetTonightEnabled = viewModel::setTonightEnabled,
            onSetTonightNotifyOnlyOnEvents = viewModel::setTonightNotifyOnlyOnEvents,
            onSetDailyMentionEveningEvents = viewModel::setDailyMentionEveningEvents,
            onSetDeliveryMode = viewModel::setDeliveryMode,
            onSetTonightDeliveryMode = viewModel::setTonightDeliveryMode,
            onSetTtsEngine = viewModel::setTtsEngine,
            onSetGeminiKey = viewModel::setApiKey,
            onClearGeminiKey = viewModel::clearApiKey,
            previewEnabled = !state.anyWorkActive,
        )
        }
    }
}

@Composable
internal fun ClothesPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_root_clothes, onBack) { padding ->
        ClothesContent(
            rules = state.clothesRules,
            defaultBottom = state.defaultBottom,
            defaultTop = state.defaultTop,
            temperatureUnit = state.temperatureUnit,
            outfitTopColors = state.outfitTopColors,
            outfitBottomColors = state.outfitBottomColors,
            outfitHandsColors = state.outfitHandsColors,
            outfitCarriedColors = state.outfitCarriedColors,
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
        )
    }
}

@Composable
internal fun RegionPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_root_region, onBack) { padding ->
        RegionContent(
            region = state.region,
            temperatureUnitSetting = state.temperatureUnitSetting,
            distanceUnitSetting = state.distanceUnitSetting,
            timeFormatSetting = state.timeFormatSetting,
            resolvedTemperatureUnit = state.temperatureUnit,
            resolvedDistanceUnit = state.distanceUnit,
            resolvedTimeFormat = state.timeFormat,
            padding = padding,
            onSetRegion = viewModel::setRegion,
            onSetTemperatureUnit = viewModel::setTemperatureUnitSetting,
            onSetDistanceUnit = viewModel::setDistanceUnitSetting,
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
    SettingsScaffold(R.string.settings_root_voice, onBack) { padding ->
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
            currentInsight = state.currentInsightSummary,
            padding = padding,
            onSetTtsEngine = viewModel::setTtsEngine,
            onSetGeminiVoice = viewModel::setGeminiVoice,
            onSetTtsStyle = viewModel::setTtsStyle,
            onSetDeviceVoice = viewModel::setDeviceVoice,
            onSetVoiceLocale = viewModel::setVoiceLocale,
            onSetGeminiKey = viewModel::setApiKey,
            onClearGeminiKey = viewModel::clearApiKey,
            onPairFromPhone = onPairFromPhone,
        )
    }
}

@Composable
internal fun DisplayPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_root_display, onBack) { padding ->
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
    SettingsScaffold(R.string.settings_root_location, onBack) { padding ->
        LocationContent(
            location = state.location,
            useDeviceLocation = state.useDeviceLocation,
            locationDetecting = state.locationDetecting,
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
    SettingsScaffold(R.string.settings_root_calendar, onBack) { padding ->
        CalendarContent(
            holidayCountrySelection = state.holidayCountrySelection,
            holidayOverrides = state.holidayOverrides,
            effectiveEnabledHolidayCountries = state.effectiveEnabledHolidayCountries,
            localeCountry = state.region.toJavaLocale()?.country
                ?: java.util.Locale.getDefault().country,
            weatherLocationCountry = state.location?.countryCode,
            calendarEnabled = state.calendarEnabled,
            useCalendarEvents = state.useCalendarEvents,
            themeFromCalendarHolidays = state.themeFromCalendarHolidays,
            themeFromCalendarBirthdays = state.themeFromCalendarBirthdays,
            calendarCelebrations = state.calendarCelebrations,
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
            onNavigateToRegionSettings = onNavigateToRegion,
            onNavigateToLocationSettings = onNavigateToLocation,
        )
    }
}

@Composable
internal fun ForecastersPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_root_forecasters, onBack) { padding ->
        ForecastersContent(
            forecastModels = state.forecastModels,
            location = state.location,
            padding = padding,
            onSetForecastModels = viewModel::setForecastModels,
        )
    }
}

@Composable
internal fun SmartHomePage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_root_smart_home, onBack) { padding ->
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
            onSetTtsEngine = viewModel::setTtsEngine,
            onSetGeminiKey = viewModel::setApiKey,
            onClearGeminiKey = viewModel::clearApiKey,
        )
        }
    }
}

@Composable
internal fun PrivacyPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_root_privacy, onBack) { padding ->
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
