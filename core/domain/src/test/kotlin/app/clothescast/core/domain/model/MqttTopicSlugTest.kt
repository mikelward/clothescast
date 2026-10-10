package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MqttTopicSlugTest {
    @Test
    fun `apostrophe is removed, not replaced`() {
        mqttTopicSlug("Alex's") shouldBe "alexs"
    }

    @Test
    fun `whitespace runs become a single underscore`() {
        mqttTopicSlug("Mary Jo") shouldBe "mary_jo"
        mqttTopicSlug("Mary \t  Jo") shouldBe "mary_jo"
    }

    @Test
    fun `punctuation is stripped and the ends trimmed`() {
        mqttTopicSlug("  Sam's (kitchen)! ") shouldBe "sams_kitchen"
        mqttTopicSlug("Mary-Jo") shouldBe "maryjo"
        mqttTopicSlug("_Sam_") shouldBe "sam"
    }

    @Test
    fun `digits survive`() {
        mqttTopicSlug("Flat 2B") shouldBe "flat_2b"
    }

    @Test
    fun `non-ASCII-only name slugs to empty`() {
        mqttTopicSlug("Ñ") shouldBe ""
        mqttTopicSlug("東京") shouldBe ""
    }

    @Test
    fun `null and blank slug to empty`() {
        mqttTopicSlug(null) shouldBe ""
        mqttTopicSlug("") shouldBe ""
        mqttTopicSlug("   ") shouldBe ""
    }

    @Test
    fun `topic for a name falls back to the default when the slug is empty`() {
        UserPreferences.mqttTopicForName("Alex's") shouldBe "clothescast/alexs"
        UserPreferences.mqttTopicForName(null) shouldBe UserPreferences.DEFAULT_MQTT_TOPIC
        UserPreferences.mqttTopicForName("東京") shouldBe UserPreferences.DEFAULT_MQTT_TOPIC
    }
}
