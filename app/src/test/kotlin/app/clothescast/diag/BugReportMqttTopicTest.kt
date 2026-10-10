package app.clothescast.diag

import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WindSpeedUnit
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.ZoneId

class BugReportMqttTopicTest {

    private val basePrefs = UserPreferences(
        schedule = Schedule(time = LocalTime.of(7, 0), days = Schedule.EVERY_DAY, zoneId = ZoneId.of("UTC")),
        deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        temperatureUnit = TemperatureUnit.CELSIUS,
        windSpeedUnit = WindSpeedUnit.KMH,
        clothesRules = emptyList(),
    )

    @Test
    fun `a topic derived from the name says so`() {
        val prefs = basePrefs.copy(mqttTopic = "clothescast/alexs", customName = "Alex's")
        BugReport.mqttTopicLine(prefs) shouldBe "MQTT topic: clothescast/alexs (from name)"
    }

    @Test
    fun `a typed topic carries no source suffix`() {
        val prefs = basePrefs.copy(
            mqttTopic = "home/sam",
            mqttTopicOverride = "home/sam",
            customName = "Alex's",
        )
        BugReport.mqttTopicLine(prefs) shouldBe "MQTT topic: home/sam"
    }

    @Test
    fun `no name and no override reads as the default`() {
        BugReport.mqttTopicLine(basePrefs) shouldBe
            "MQTT topic: ${UserPreferences.DEFAULT_MQTT_TOPIC} (default)"
    }
}
