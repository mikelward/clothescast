package app.clothescast.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.DistanceUnitSetting
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayCountrySelection
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TemperatureUnitSetting
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.discovery.DiscoveredService
import app.clothescast.discovery.ServiceType
import app.clothescast.ui.theme.ClothesCastTheme
import java.time.LocalTime

//
// Preview wrappers for the Settings screens. Same pattern as `TodayPreviews.kt`:
// each `@Preview internal fun` is rendered both in Studio's design pane and in
// the Roborazzi snapshot test in `app/src/test`.
//
// One preview per sub-page in its default state. The settings sub-pages don't
// have many distinct visual states worth capturing (most variation is text
// copy and pickers nested in dialogs), so the snapshots here are first and
// foremost a layout-regression net for the cards / rows / FlowRow chip wraps.
//

@Composable
private fun SettingsFrame(content: @Composable () -> Unit) {
    ClothesCastTheme(dynamicColor = false) {
        Surface { content() }
    }
}

@Preview(name = "Settings · root list", widthDp = 360)
@Composable
internal fun SettingsRootPreview() {
    SettingsFrame {
        SettingsRoot(
            useDeviceLocation = false,
            padding = PaddingValues(0.dp),
            onNavigate = {},
        )
    }
}

@Preview(name = "Settings · Schedule", widthDp = 360)
@Composable
internal fun SettingsSchedulePreview() {
    SettingsFrame {
        ScheduleContent(
            time = LocalTime.of(7, 0),
            days = Schedule.EVERY_DAY,
            tonightTime = LocalTime.of(19, 0),
            tonightDays = Schedule.EVERY_DAY,
            tonightEnabled = true,
            tonightNotifyOnlyOnEvents = false,
            dailyMentionEveningEvents = false,
            deliveryMode = DeliveryMode.NOTIFICATION_ONLY,
            tonightDeliveryMode = DeliveryMode.NOTIFICATION_ONLY,
            skipTtsAtHome = false,
            homeLocationConfigured = false,
            padding = PaddingValues(0.dp),
            onSetSchedule = { _, _ -> },
            onSetTonightSchedule = { _, _ -> },
            onSetTonightEnabled = {},
            onSetTonightNotifyOnlyOnEvents = {},
            onSetDailyMentionEveningEvents = {},
            onSetDeliveryMode = {},
            onSetTonightDeliveryMode = {},
            onSetSkipTtsAtHome = {},
        )
    }
}

@Preview(name = "Settings · Clothes rules", widthDp = 360)
@Composable
internal fun SettingsClothesPreview() {
    SettingsFrame {
        ClothesContent(
            rules = ClothesRule.DEFAULTS,
            defaultBottom = OutfitSuggestion.Bottom.LONG_PANTS,
            temperatureUnit = TemperatureUnit.CELSIUS,
            outfitTopColors = emptyMap(),
            outfitBottomColors = emptyMap(),
            padding = PaddingValues(0.dp),
            onAdd = {},
            onReplace = { _, _ -> },
            onDelete = {},
            onSetDefaultBottom = {},
            onSetOutfitTopColor = { _, _ -> },
            onSetOutfitBottomColor = { _, _ -> },
        )
    }
}

// Fahrenheit view of a mixed-unit list: the °C-typed defaults (sweater 18°C,
// jacket 12°C, shorts 24°C) render as "18°C (64°F)" etc. — current unit first,
// original parenthesised. The fourth rule was set in °F, so it shows just
// "75°F" without parens because rule unit == display unit. This is the
// Fahrenheit user's regression net: the Settings → Clothes editor used to lie
// to them with hardcoded °C output regardless of preference.
@Preview(name = "Settings · Clothes rules · Fahrenheit", widthDp = 360)
@Composable
internal fun SettingsClothesFahrenheitPreview() {
    SettingsFrame {
        ClothesContent(
            rules = ClothesRule.DEFAULTS + ClothesRule(
                Garment.TSHIRT.itemKey,
                ClothesRule.TemperatureAbove(75.0, TemperatureUnit.FAHRENHEIT),
            ),
            defaultBottom = OutfitSuggestion.Bottom.LONG_PANTS,
            temperatureUnit = TemperatureUnit.FAHRENHEIT,
            outfitTopColors = emptyMap(),
            outfitBottomColors = emptyMap(),
            padding = PaddingValues(0.dp),
            onAdd = {},
            onReplace = { _, _ -> },
            onDelete = {},
            onSetDefaultBottom = {},
            onSetOutfitTopColor = { _, _ -> },
            onSetOutfitBottomColor = { _, _ -> },
        )
    }
}

// Pre-selects the first preset (red) so the snapshot also covers the
// selection-ring styling, not just the swatch grid layout. The label and
// `currentArgb` are the only two visible inputs to the dialog — picking a
// recognisable colour and a short garment name keeps the snapshot small
// and the regression net obvious.
@Preview(name = "Settings · Garment colour picker", widthDp = 360)
@Composable
internal fun SettingsGarmentColorPickerPreview() {
    SettingsFrame {
        GarmentColorPickerDialog(
            garmentLabel = "T-shirt",
            currentArgb = Color(0xFFE53935).toArgb().toLong() and 0xFFFFFFFFL,
            onPick = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Settings · Region & Units", widthDp = 360)
@Composable
internal fun SettingsRegionPreview() {
    SettingsFrame {
        RegionContent(
            region = Region.SYSTEM,
            temperatureUnitSetting = TemperatureUnitSetting.AUTO,
            distanceUnitSetting = DistanceUnitSetting.AUTO,
            resolvedTemperatureUnit = TemperatureUnit.CELSIUS,
            resolvedDistanceUnit = DistanceUnit.KILOMETERS,
            padding = PaddingValues(0.dp),
            onSetRegion = {},
            onSetTemperatureUnit = {},
            onSetDistanceUnit = {},
        )
    }
}

@Preview(name = "Settings · Display", widthDp = 360)
@Composable
internal fun SettingsDisplayPreview() {
    SettingsFrame {
        DisplayContent(
            themeMode = ThemeMode.SYSTEM,
            colorPalette = ColorPalette.RAINBOW,
            padding = PaddingValues(0.dp),
            onSetThemeMode = {},
            onSetColorPalette = {},
        )
    }
}

@Preview(name = "Settings · Voice (device engine)", widthDp = 360)
@Composable
internal fun SettingsVoiceDevicePreview() {
    SettingsFrame {
        VoiceContent(
            selected = TtsEngine.DEVICE,
            geminiVoice = UserPreferences.DEFAULT_GEMINI_VOICE,
            ttsStyle = TtsStyle.WEATHER_FORECASTER,
            deviceVoice = null,
            deviceVoices = emptyList(),
            effectiveDeviceVoice = null,
            geminiKeyConfigured = false,
            voiceLocale = VoiceLocale.SYSTEM,
            region = Region.SYSTEM,
            padding = PaddingValues(0.dp),
            onSetTtsEngine = {},
            onSetGeminiVoice = {},
            onSetTtsStyle = {},
            onSetDeviceVoice = {},
            onSetVoiceLocale = {},
            onSetGeminiKey = {},
            onClearGeminiKey = {},
        )
    }
}

@Preview(name = "Settings · Voice (Gemini engine)", widthDp = 360)
@Composable
internal fun SettingsVoiceGeminiPreview() {
    SettingsFrame {
        VoiceContent(
            selected = TtsEngine.GEMINI,
            geminiVoice = UserPreferences.DEFAULT_GEMINI_VOICE,
            ttsStyle = TtsStyle.WEATHER_FORECASTER,
            deviceVoice = null,
            deviceVoices = emptyList(),
            effectiveDeviceVoice = null,
            geminiKeyConfigured = true,
            voiceLocale = VoiceLocale.SYSTEM,
            region = Region.SYSTEM,
            padding = PaddingValues(0.dp),
            onSetTtsEngine = {},
            onSetGeminiVoice = {},
            onSetTtsStyle = {},
            onSetDeviceVoice = {},
            onSetVoiceLocale = {},
            onSetGeminiKey = {},
            onClearGeminiKey = {},
        )
    }
}

// Render with device-location ON and a stored fallback city: this exercises
// both the "currently using" path (the card displays the cached city) and —
// because the Robolectric host has no ACCESS_BACKGROUND_LOCATION grant —
// surfaces the warning banner that's the new primary CTA. Captures the
// regression net for the redesigned page in one snapshot.
@Preview(name = "Settings · Location", widthDp = 360)
@Composable
internal fun SettingsLocationPreview() {
    SettingsFrame {
        LocationContent(
            location = Location(
                latitude = 51.5074,
                longitude = -0.1278,
                displayName = "London",
            ),
            useDeviceLocation = true,
            homeLocation = null,
            padding = PaddingValues(0.dp),
            onSetUseDeviceLocation = {},
            onSelectLocation = {},
            onClearLocation = {},
            onSelectHomeLocation = {},
            onClearHomeLocation = {},
            onUseCurrentLocationForHome = {},
            onSearchLocations = { emptyList() },
        )
    }
}

// London anchors the location-aware Auto default so the preview shows a
// stable, recognisable region (UKMO in the resolved trio) rather than
// shifting with hardware locale. Custom mode also exercises the disabled-
// row paths (BOM and below-cap unchecked rows).
@Preview(name = "Settings · Forecasters", widthDp = 360)
@Composable
internal fun SettingsForecastersPreview() {
    SettingsFrame {
        ForecastersContent(
            forecastModels = setOf(
                ForecastModel.ECMWF_IFS04,
                ForecastModel.GFS_SEAMLESS,
                ForecastModel.ICON_SEAMLESS,
                ForecastModel.UKMO_SEAMLESS,
            ),
            location = Location(
                latitude = 51.5074,
                longitude = -0.1278,
            ),
            padding = PaddingValues(0.dp),
            onSetForecastModels = {},
        )
    }
}

@Preview(name = "Settings · Calendar", widthDp = 360)
@Composable
internal fun SettingsCalendarPreview() {
    SettingsFrame {
        CalendarContent(
            useCalendarEvents = false,
            padding = PaddingValues(0.dp),
            onSetUseCalendarEvents = {},
        )
    }
}

// Holidays page with a representative mix of enabled / disabled rows so the
// snapshot covers both per-country and per-holiday dropdown states.
// France carries an explicit ON country override (the user pinned it from
// abroad); Japan an explicit OFF (muted even if it matched location);
// Australia is the locale + weather country and shows Auto (on); everything
// else shows Auto (off). Bastille Day is force-on at the holiday tier;
// MLK Day is force-off.
@Preview(name = "Settings · Holidays", widthDp = 360)
@Composable
internal fun SettingsHolidaysPreview() {
    SettingsFrame {
        HolidaysContent(
            holidayCountrySelection = HolidayCountrySelection(
                countryOverrides = mapOf(
                    "FR" to HolidayOverride.ON,
                    "JP" to HolidayOverride.OFF,
                ),
            ),
            holidayOverrides = mapOf(
                HolidayId.BASTILLE_DAY to HolidayOverride.ON,
                HolidayId.MLK_DAY to HolidayOverride.OFF,
            ),
            effectiveEnabledHolidayCountries = setOf("AU", "FR", HolidayCatalog.GLOBAL_COUNTRY),
            localeCountry = "AU",
            weatherLocationCountry = "AU",
            themeFromCalendarHolidays = false,
            themeFromCalendarBirthdays = false,
            padding = PaddingValues(0.dp),
            onSetCountryHome = {},
            onSetCountryCurrent = {},
            onSetCountryGlobal = {},
            onSetCountryAll = {},
            onSetCountryOverride = { _, _ -> },
            onSetHolidayOverride = { _, _ -> },
            onSetThemeFromCalendarHolidays = {},
            onSetThemeFromCalendarBirthdays = {},
            onCalendarPermissionRechecked = {},
        )
    }
}

@Preview(name = "Settings · Privacy", widthDp = 360)
@Composable
internal fun SettingsPrivacyPreview() {
    SettingsFrame {
        PrivacyContent(
            telemetryEnabled = true,
            padding = PaddingValues(0.dp),
            onSetTelemetryEnabled = {},
        )
    }
}

// MQTT bridge enabled with a live scan that's already surfaced two hits
// (a Home Assistant advert and a Mosquitto MQTT advert on the same host).
// This covers the discovery picker + the existing host / port / TLS /
// password / topic form rows in one frame so the regression net catches
// layout changes to either band.
@Preview(name = "Settings · Smart Home", widthDp = 360)
@Composable
internal fun SettingsSmartHomePreview() {
    SettingsFrame {
        SmartHomeContent(
            bridgeEnabled = true,
            host = "homeassistant.local",
            port = 1883,
            useTls = false,
            username = "clothescast",
            topic = UserPreferences.DEFAULT_MQTT_TOPIC,
            passwordSet = true,
            lastError = null,
            lastErrorAt = 0L,
            lastPublishAt = 0L,
            publishing = false,
            discoveryRunning = true,
            discoveredServices = listOf(
                DiscoveredService(
                    type = ServiceType.HOME_ASSISTANT,
                    name = "Home Assistant",
                    host = "192.168.1.20",
                    port = 8123,
                ),
                DiscoveredService(
                    type = ServiceType.MQTT,
                    name = "mosquitto",
                    host = "192.168.1.20",
                    port = 1883,
                ),
            ),
            padding = PaddingValues(0.dp),
            onSetBridgeEnabled = {},
            onSaveConfig = { _, _, _, _, _, _ -> },
            onClearPassword = {},
            onPublishNow = {},
            onStartDiscovery = {},
            onStopDiscovery = {},
            onUseDiscoveredService = {},
        )
    }
}

// AboutContent intentionally not previewed: it reads BuildConfig.VERSION_NAME
// and VERSION_CODE, both derived from `git rev-list --count` + short SHA, so a
// snapshot would re-record on every commit and drown PR diffs in unrelated
// version-string churn. The screen is mostly static text + link buttons —
// low layout-regression risk to begin with.
