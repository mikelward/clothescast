package app.clothescast.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.R

/**
 * One sub-page per concern. Order in the enum matches the order shown in the
 * root list: the most-frequently-tweaked rules at the top, set-once
 * configuration in the middle, and data sources at the bottom. BYOK keys live
 * inside Voice — the only thing they gate is cloud TTS.
 *
 * About is reachable as a deep-link target only — it's surfaced from Today's
 * overflow menu, not from the settings root list.
 *
 * Public so callers (e.g. the onboarding flow, Today's overflow) can deep-link
 * into a specific sub-page via [SettingsScreen]'s `initialRoute` param.
 */
enum class SettingsRoute(@StringRes val titleRes: Int, @StringRes val subtitleRes: Int? = null) {
    Root(R.string.settings_title),
    Schedule(R.string.settings_root_schedule, R.string.settings_root_schedule_subtitle),
    Clothes(R.string.settings_root_clothes, R.string.settings_root_clothes_subtitle),
    Region(R.string.settings_root_region, R.string.settings_root_region_subtitle),
    Voice(R.string.settings_root_voice, R.string.settings_root_voice_subtitle),
    Display(R.string.settings_root_display, R.string.settings_root_display_subtitle),
    Location(R.string.settings_root_location, R.string.settings_root_location_subtitle),
    Calendar(R.string.settings_root_calendar, R.string.settings_root_calendar_subtitle),
    Privacy(R.string.settings_root_privacy, R.string.settings_root_privacy_subtitle),
    About(R.string.settings_root_about),
}

// Saved as the enum name string with a runCatching restore so an old install
// that had the page open at process death (e.g. on the now-removed
// `DataSources` route) doesn't crash on restore — it falls back to Root.
private val SettingsRouteSaver: Saver<SettingsRoute, String> = Saver(
    save = { it.name },
    restore = { runCatching { SettingsRoute.valueOf(it) }.getOrDefault(SettingsRoute.Root) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    initialRoute: SettingsRoute? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var route by rememberSaveable(stateSaver = SettingsRouteSaver) {
        mutableStateOf(initialRoute ?: SettingsRoute.Root)
    }

    // When entered via deep link (initialRoute is non-null), back from the deep-linked
    // sub-page exits Settings entirely instead of going to Root — so onboarding's
    // "Continue → Schedule" needs only one back to reach Today, not two.
    fun goBackOrUp() {
        when {
            route == SettingsRoute.Root -> onNavigateBack()
            initialRoute != null && route == initialRoute -> onNavigateBack()
            else -> route = SettingsRoute.Root
        }
    }

    BackHandler(enabled = route != SettingsRoute.Root || initialRoute != null) {
        goBackOrUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(route.titleRes)) },
                navigationIcon = {
                    IconButton(onClick = ::goBackOrUp) {
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
                onNavigate = { route = it },
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
                padding = padding,
                onSetSchedule = viewModel::setSchedule,
                onSetTonightSchedule = viewModel::setTonightSchedule,
                onSetTonightEnabled = viewModel::setTonightEnabled,
                onSetTonightNotifyOnlyOnEvents = viewModel::setTonightNotifyOnlyOnEvents,
                onSetDailyMentionEveningEvents = viewModel::setDailyMentionEveningEvents,
                onSetDeliveryMode = viewModel::setDeliveryMode,
                onSetTonightDeliveryMode = viewModel::setTonightDeliveryMode,
                // Show a Done button only when this page is the deep-link
                // landing from onboarding's "Continue" — gives the user an
                // obvious way to finish setup and reach Today. In the regular
                // settings flow they exit via the top-bar back arrow.
                onDone = if (initialRoute == SettingsRoute.Schedule) onNavigateBack else null,
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
            SettingsRoute.Location -> LocationContent(
                location = state.location,
                useDeviceLocation = state.useDeviceLocation,
                locationDetecting = state.locationDetecting,
                padding = padding,
                onSetUseDeviceLocation = viewModel::setUseDeviceLocation,
                onSelectLocation = viewModel::selectLocation,
                onClearLocation = viewModel::clearLocation,
                onSearchLocations = viewModel::searchLocations,
            )
            SettingsRoute.Calendar -> CalendarContent(
                useCalendarEvents = state.useCalendarEvents,
                padding = padding,
                onSetUseCalendarEvents = viewModel::setUseCalendarEvents,
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
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
