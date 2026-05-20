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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.R
import app.clothescast.tts.toJavaLocale
import app.clothescast.ui.EdgeFadeOverlay

/**
 * One sub-page per concern. Order in the enum matches the order shown in the
 * root list: the most-frequently-tweaked rules at the top, set-once
 * configuration in the middle, and data sources at the bottom. BYOK keys live
 * inside Voice — the only thing they gate is cloud TTS.
 *
 * About is reachable as a deep-link target only — it's surfaced from Today's
 * overflow menu, not from the settings root list.
 *
 * Each entry maps to a navigation destination in the Settings nested graph
 * (see ClothesCastNavHost); the framework's back stack owns up-navigation.
 */
enum class SettingsRoute(@StringRes val titleRes: Int, @StringRes val subtitleRes: Int? = null) {
    Root(R.string.settings_title),
    Schedule(R.string.settings_root_schedule, R.string.settings_root_schedule_subtitle),
    Clothes(R.string.settings_root_clothes, R.string.settings_root_clothes_subtitle),
    Region(R.string.settings_root_region, R.string.settings_root_region_subtitle),
    Voice(R.string.settings_root_voice, R.string.settings_root_voice_subtitle),
    Display(R.string.settings_root_display, R.string.settings_root_display_subtitle),
    Holidays(R.string.settings_root_holidays, R.string.settings_root_holidays_subtitle),
    Location(R.string.settings_root_location, R.string.settings_root_location_subtitle),
    Forecasters(R.string.settings_root_forecasters, R.string.settings_root_forecasters_subtitle),
    Calendar(R.string.settings_root_calendar, R.string.settings_root_calendar_subtitle),
    SmartHome(R.string.settings_root_smart_home, R.string.settings_root_smart_home_subtitle),
    Privacy(R.string.settings_root_privacy, R.string.settings_root_privacy_subtitle),
    About(R.string.settings_root_about),
}

/**
 * Renders one Settings sub-page. Each [SettingsRoute] is its own navigation
 * destination, so there's no internal route state and no custom back handling
 * here — [onBack] just pops the nav back stack and the framework restores the
 * previous destination. Cross-page links (and the root list) call [onNavigate].
 *
 * [onboardingLanding] marks the Schedule page when it's the onboarding
 * "Continue" target, surfacing a Done button that calls [onFinishOnboarding].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubPage(
    route: SettingsRoute,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigate: (SettingsRoute) -> Unit,
    onboardingLanding: Boolean = false,
    onFinishOnboarding: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        // Drop the default `safeDrawing` content insets so each sub-screen's
        // scroll viewport extends edge-to-edge under the (transparent) nav
        // bar. Each sub-screen's inner Column adds
        // `windowInsetsPadding(WindowInsets.navigationBars)` as content
        // padding so the last item can scroll above the nav bar; the bottom
        // fade in `EdgeFadeOverlay` does the same so it sits just above
        // the bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(route.titleRes)) },
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
    ) { padding ->
        when (route) {
            SettingsRoute.Root -> SettingsRoot(
                useDeviceLocation = state.useDeviceLocation,
                padding = padding,
                onNavigate = onNavigate,
            )
            SettingsRoute.Schedule -> ScheduleContent(
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
                // Show a Done button only when this page is the deep-link
                // landing from onboarding's "Continue" — gives the user an
                // obvious way to finish setup and reach Today. In the regular
                // settings flow they exit via the top-bar back arrow.
                onDone = if (onboardingLanding) onFinishOnboarding else null,
            )
            SettingsRoute.Clothes -> ClothesContent(
                rules = state.clothesRules,
                defaultBottom = state.defaultBottom,
                temperatureUnit = state.temperatureUnit,
                outfitTopColors = state.outfitTopColors,
                outfitBottomColors = state.outfitBottomColors,
                padding = padding,
                onAdd = viewModel::addClothesRule,
                onReplace = viewModel::replaceClothesRule,
                onDelete = viewModel::deleteClothesRule,
                onSetDefaultBottom = viewModel::setDefaultBottom,
                onSetOutfitTopColor = viewModel::setOutfitTopColor,
                onSetOutfitBottomColor = viewModel::setOutfitBottomColor,
            )
            SettingsRoute.Region -> RegionContent(
                region = state.region,
                temperatureUnitSetting = state.temperatureUnitSetting,
                distanceUnitSetting = state.distanceUnitSetting,
                resolvedTemperatureUnit = state.temperatureUnit,
                resolvedDistanceUnit = state.distanceUnit,
                padding = padding,
                onSetRegion = viewModel::setRegion,
                onSetTemperatureUnit = viewModel::setTemperatureUnitSetting,
                onSetDistanceUnit = viewModel::setDistanceUnitSetting,
            )
            SettingsRoute.Voice -> VoiceContent(
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
            SettingsRoute.Display -> DisplayContent(
                themeMode = state.themeMode,
                colorPalette = state.colorPalette,
                padding = padding,
                onSetThemeMode = viewModel::setThemeMode,
                onSetColorPalette = viewModel::setColorPalette,
            )
            SettingsRoute.Holidays -> HolidaysContent(
                holidayCountrySelection = state.holidayCountrySelection,
                holidayOverrides = state.holidayOverrides,
                effectiveEnabledHolidayCountries = state.effectiveEnabledHolidayCountries,
                localeCountry = state.region.toJavaLocale()?.country
                    ?: java.util.Locale.getDefault().country,
                weatherLocationCountry = state.location?.countryCode,
                themeFromCalendarHolidays = state.themeFromCalendarHolidays,
                themeFromCalendarBirthdays = state.themeFromCalendarBirthdays,
                padding = padding,
                onSetCountryHome = viewModel::setHolidayCountryHome,
                onSetCountryCurrent = viewModel::setHolidayCountryCurrent,
                onSetCountryGlobal = viewModel::setHolidayCountryGlobal,
                onSetCountryAll = viewModel::setHolidayCountryAll,
                onSetCountryOverride = viewModel::setHolidayCountryOverride,
                onSetHolidayOverride = viewModel::setHolidayOverride,
                onSetThemeFromCalendarHolidays = viewModel::setThemeFromCalendarHolidays,
                onSetThemeFromCalendarBirthdays = viewModel::setThemeFromCalendarBirthdays,
                onCalendarPermissionRechecked = viewModel::markCalendarPermissionRechecked,
                onNavigateToRegionSettings = { onNavigate(SettingsRoute.Region) },
                onNavigateToLocationSettings = { onNavigate(SettingsRoute.Location) },
                onNavigateToCalendarSettings = { onNavigate(SettingsRoute.Calendar) },
            )
            SettingsRoute.Location -> LocationContent(
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
            SettingsRoute.Calendar -> CalendarContent(
                useCalendarEvents = state.useCalendarEvents,
                padding = padding,
                onSetUseCalendarEvents = viewModel::setUseCalendarEvents,
            )
            SettingsRoute.Forecasters -> ForecastersContent(
                forecastModels = state.forecastModels,
                location = state.location,
                padding = padding,
                onSetForecastModels = viewModel::setForecastModels,
            )
            SettingsRoute.SmartHome -> SmartHomeContent(
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
            SettingsRoute.Privacy -> PrivacyContent(
                telemetryEnabled = state.telemetryEnabled,
                padding = padding,
                onSetTelemetryEnabled = viewModel::setTelemetryEnabled,
            )
            SettingsRoute.About -> AboutContent(padding = padding)
        }
    }
}

@Composable
internal fun SettingsRoot(
    useDeviceLocation: Boolean,
    padding: PaddingValues,
    onNavigate: (SettingsRoute) -> Unit,
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
                onClick = { onNavigate(SettingsRoute.Location) },
            )
            SettingsRoute.entries
                .filter { it != SettingsRoute.Root && it != SettingsRoute.About }
                .forEach { destination ->
                    SettingsNavRow(
                        title = stringResource(destination.titleRes),
                        subtitle = destination.subtitleRes?.let { stringResource(it) },
                        onClick = { onNavigate(destination) },
                    )
                }
        }
    }
}
