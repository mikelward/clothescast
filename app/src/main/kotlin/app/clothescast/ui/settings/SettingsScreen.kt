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
    onboardingLanding: Boolean,
    onFinishOnboarding: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(R.string.settings_root_schedule, onBack) { padding ->
        CompositionLocalProvider(LocalTimeFormat provides state.timeFormat) {
        ScheduleContent(
            time = state.scheduleTime,
            days = state.scheduleDays,
            tonightTime = state.tonightTime,
            tonightDays = state.tonightDays,
            tonightEnabled = state.tonightEnabled,
            tonightNotifyOnlyOnEvents = state.tonightNotifyOnlyOnEvents,
            dailyMentionEveningEvents = state.dailyMentionEveningEvents,
            deliveryMode = state.deliveryMode,
            tonightDeliveryMode = state.tonightDeliveryMode,
            skipTtsAtHome = state.skipTtsAtHome,
            homeLocationConfigured = state.homeLocation != null,
            padding = padding,
            onSetSchedule = viewModel::setSchedule,
            onSetTonightSchedule = viewModel::setTonightSchedule,
            onSetTonightEnabled = viewModel::setTonightEnabled,
            onSetTonightNotifyOnlyOnEvents = viewModel::setTonightNotifyOnlyOnEvents,
            onSetDailyMentionEveningEvents = viewModel::setDailyMentionEveningEvents,
            onSetDeliveryMode = viewModel::setDeliveryMode,
            onSetTonightDeliveryMode = viewModel::setTonightDeliveryMode,
            onSetSkipTtsAtHome = viewModel::setSkipTtsAtHome,
            // Show a Done button only when this page is the deep-link landing from
            // onboarding's "Continue" — gives the user an obvious way to finish
            // setup and reach Today. In the regular settings flow they exit via
            // the top-bar back arrow.
            onDone = if (onboardingLanding) onFinishOnboarding else null,
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
            padding = padding,
            onAdd = viewModel::addClothesRule,
            onReplace = viewModel::replaceClothesRule,
            onDelete = viewModel::deleteClothesRule,
            onSetDefaultBottom = viewModel::setDefaultBottom,
            onSetDefaultTop = viewModel::setDefaultTop,
            onSetOutfitTopColor = viewModel::setOutfitTopColor,
            onSetOutfitBottomColor = viewModel::setOutfitBottomColor,
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
internal fun VoicePage(viewModel: SettingsViewModel, onBack: () -> Unit) {
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
            voiceLocale = state.voiceLocale,
            region = state.region,
            padding = padding,
            onSetTtsEngine = viewModel::setTtsEngine,
            onSetGeminiVoice = viewModel::setGeminiVoice,
            onSetTtsStyle = viewModel::setTtsStyle,
            onSetDeviceVoice = viewModel::setDeviceVoice,
            onSetVoiceLocale = viewModel::setVoiceLocale,
            onSetGeminiKey = viewModel::setApiKey,
            onClearGeminiKey = viewModel::clearApiKey,
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
            padding = padding,
            onSetThemeMode = viewModel::setThemeMode,
            onSetColorPalette = viewModel::setColorPalette,
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
            homeLocation = state.homeLocation,
            homeLocationResolving = state.homeLocationResolving,
            locationDetecting = state.locationDetecting,
            padding = padding,
            onSetUseDeviceLocation = viewModel::setUseDeviceLocation,
            onSelectLocation = viewModel::selectLocation,
            onClearLocation = viewModel::clearLocation,
            onSelectHomeLocation = viewModel::selectHomeLocation,
            onClearHomeLocation = viewModel::clearHomeLocation,
            onUseCurrentLocationForHome = viewModel::useCurrentLocationForHome,
            onSearchLocations = viewModel::searchLocations,
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
            discoveryRunning = state.discoveryRunning,
            discoveredServices = state.discoveredServices,
            castAvailable = state.castAvailable,
            castRouteName = state.castRouteName,
            castPickerOpen = state.castPickerOpen,
            castDiscoveredRoutes = state.castDiscoveredRoutes,
            castInProgress = state.castInProgress,
            castLastError = state.castLastError,
            castLastErrorAt = state.castLastErrorAt,
            castLastSuccessAt = state.castLastSuccessAt,
            castMorning = state.castMorning,
            castTonight = state.castTonight,
            castSkipPhoneSpeech = state.castSkipPhoneSpeech,
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
            onSetCastMorning = viewModel::setCastMorning,
            onSetCastTonight = viewModel::setCastTonight,
            onSetCastSkipPhoneSpeech = viewModel::setCastSkipPhoneSpeech,
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
            NotificationPermissionBanner()
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
