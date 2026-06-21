package app.clothescast.widget

import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WindSpeedUnit
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalTime
import java.time.ZoneId
import org.junit.jupiter.api.Test

/**
 * Guards the contract that [UserPreferences.toWidgetInputs] projects exactly the
 * fields the home-screen widgets render — no more, no less. Under-projecting
 * reintroduces the stale-widget bug (a setting changes but the launcher doesn't
 * repaint); over-projecting causes needless repaints on unrelated edits.
 */
class WidgetInputsTest {
    private val base = UserPreferences(
        schedule = Schedule(time = LocalTime.of(7, 0), days = Schedule.EVERY_DAY, zoneId = ZoneId.of("UTC")),
        deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        temperatureUnit = TemperatureUnit.CELSIUS,
        windSpeedUnit = WindSpeedUnit.KMH,
        clothesRules = emptyList(),
    )

    @Test
    fun `fields the widgets render flip the projection`() {
        val baseInputs = base.toWidgetInputs()
        baseInputs shouldNotBe base.copy(region = Region.EN_US).toWidgetInputs()
        baseInputs shouldNotBe base.copy(temperatureUnit = TemperatureUnit.FAHRENHEIT).toWidgetInputs()
        baseInputs shouldNotBe base.copy(windSpeedUnit = WindSpeedUnit.KNOTS).toWidgetInputs()
        baseInputs shouldNotBe base.copy(timeFormat = TimeFormat.TWELVE_HOUR).toWidgetInputs()
        baseInputs shouldNotBe base.copy(colorPalette = ColorPalette.ACCESSIBLE).toWidgetInputs()
        baseInputs shouldNotBe base.copy(themeMode = ThemeMode.DARK).toWidgetInputs()
        baseInputs shouldNotBe base.copy(
            clothesRules = listOf(ClothesRule(Garment.HOODIE, ClothesRule.TemperatureBelow(0.0))),
        ).toWidgetInputs()
        baseInputs shouldNotBe base.copy(defaultTop = OutfitSuggestion.Top.POLO).toWidgetInputs()
        baseInputs shouldNotBe base.copy(defaultBottom = OutfitSuggestion.Bottom.SHORTS).toWidgetInputs()
        baseInputs shouldNotBe base.copy(
            outfitTopColors = mapOf(OutfitSuggestion.Top.TSHIRT to 0xFF00FF00L),
        ).toWidgetInputs()
        baseInputs shouldNotBe base.copy(
            outfitBottomColors = mapOf(OutfitSuggestion.Bottom.LONG_PANTS to 1L),
        ).toWidgetInputs()
        baseInputs shouldNotBe base.copy(
            outfitHandsColors = mapOf(OutfitSuggestion.Hands.GLOVES to 1L),
        ).toWidgetInputs()
        baseInputs shouldNotBe base.copy(
            outfitCarriedColors = mapOf(OutfitSuggestion.Carried.UMBRELLA to 1L),
        ).toWidgetInputs()
        baseInputs shouldNotBe base.copy(
            outfitOuterColors = mapOf(OutfitSuggestion.Outer.RAIN_JACKET to 1L),
        ).toWidgetInputs()
    }

    @Test
    fun `settings the widgets don't render leave the projection unchanged`() {
        val baseInputs = base.toWidgetInputs()
        // A few representative non-widget settings — these must not trigger a repaint.
        baseInputs shouldBe base.copy(geminiVoice = "Kore").toWidgetInputs()
        baseInputs shouldBe base.copy(mqttHost = "example.com").toWidgetInputs()
        baseInputs shouldBe base.copy(telemetryEnabled = false).toWidgetInputs()
        baseInputs shouldBe base.copy(clothesMentionMode = ClothesMentionMode.NEVER).toWidgetInputs()
        baseInputs shouldBe base.copy(deltaThresholdC = 8.0).toWidgetInputs()
        // Schedule is deliberately excluded — which period the widget shows is
        // clock-driven at render, and a schedule edit doesn't repaint today.
        baseInputs shouldBe base.copy(
            schedule = Schedule(time = LocalTime.of(9, 0), days = Schedule.EVERY_DAY, zoneId = ZoneId.of("UTC")),
        ).toWidgetInputs()
    }
}
