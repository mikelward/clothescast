package app.clothescast.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.VoiceLocale
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
            padding = PaddingValues(0.dp),
            onSetSchedule = { _, _ -> },
            onSetTonightSchedule = { _, _ -> },
            onSetTonightEnabled = {},
            onSetTonightNotifyOnlyOnEvents = {},
            onSetDailyMentionEveningEvents = {},
            onSetDeliveryMode = {},
            onSetTonightDeliveryMode = {},
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
            padding = PaddingValues(0.dp),
            onAdd = {},
            onReplace = { _, _ -> },
            onDelete = {},
            onSetDefaultBottom = {},
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
            padding = PaddingValues(0.dp),
            onAdd = {},
            onReplace = { _, _ -> },
            onDelete = {},
            onSetDefaultBottom = {},
        )
    }
}

@Preview(name = "Settings · Region & Units", widthDp = 360)
@Composable
internal fun SettingsRegionPreview() {
    SettingsFrame {
        RegionContent(
            region = Region.SYSTEM,
            temperatureUnit = TemperatureUnit.CELSIUS,
            distanceUnit = DistanceUnit.KILOMETERS,
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
            padding = PaddingValues(0.dp),
            onSetUseDeviceLocation = {},
            onSelectLocation = {},
            onClearLocation = {},
            onSearchLocations = { emptyList() },
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

// AboutContent intentionally not previewed: it reads BuildConfig.VERSION_NAME
// and VERSION_CODE, both derived from `git rev-list --count` + short SHA, so a
// snapshot would re-record on every commit and drown PR diffs in unrelated
// version-string churn. The screen is mostly static text + link buttons —
// low layout-regression risk to begin with.
