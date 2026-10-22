package app.clothescast.core.domain.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WeatherAlertTest {

    // A 2° × 2° square around (51, -1), with a 0.5° hole in the middle.
    private val square = listOf(
        GeoPoint(50.0, -2.0), GeoPoint(50.0, 0.0), GeoPoint(52.0, 0.0), GeoPoint(52.0, -2.0),
    )
    private val hole = listOf(
        GeoPoint(50.75, -1.25), GeoPoint(50.75, -0.75), GeoPoint(51.25, -0.75), GeoPoint(51.25, -1.25),
    )

    @Test
    fun `contains points inside and not outside`() {
        val area = AlertArea(listOf(square))
        area.contains(GeoPoint(51.5, -1.5)) shouldBe true
        area.contains(GeoPoint(53.0, -1.0)) shouldBe false
        area.contains(GeoPoint(51.0, 1.0)) shouldBe false
    }

    @Test
    fun `a hole excludes its inside and a second part adds its own`() {
        val second = listOf(GeoPoint(10.0, 10.0), GeoPoint(10.0, 11.0), GeoPoint(11.0, 11.0))
        val area = AlertArea(listOf(square, hole, second))
        area.contains(GeoPoint(51.0, -1.0)) shouldBe false
        area.contains(GeoPoint(51.5, -1.5)) shouldBe true
        area.contains(GeoPoint(10.2, 10.8)) shouldBe true
    }

    @Test
    fun `a closed ring (first point repeated) reads the same`() {
        val area = AlertArea(listOf(square + square.first()))
        area.contains(GeoPoint(51.0, -1.0)) shouldBe true
        area.contains(GeoPoint(49.9, -1.0)) shouldBe false
    }

    @Test
    fun `distance to the boundary is measured in km`() {
        val area = AlertArea(listOf(square))
        // 0.01° south of the southern edge: about 1.1 km.
        area.distanceToBoundaryKm(GeoPoint(49.99, -1.0)) shouldBe (1.112 plusOrMinus 0.01)
        // On the edge.
        area.distanceToBoundaryKm(GeoPoint(50.0, -1.0)) shouldBe (0.0 plusOrMinus 0.001)
    }

    @Test
    fun `span is the longer side of the bounding box`() {
        val area = AlertArea(listOf(square))
        // 2° of latitude ≈ 222 km; 2° of longitude at 51° ≈ 140 km.
        area.spanKm() shouldBe (222.4 plusOrMinus 0.5)
        AlertArea(emptyList()).spanKm() shouldBe 0.0
    }

    @Test
    fun `severity maps onto levels and parses Google's names`() {
        AlertSeverity.parse("EXTREME").level shouldBe AlertLevel.RED
        AlertSeverity.parse("severe").level shouldBe AlertLevel.ORANGE
        AlertSeverity.parse("MODERATE").level shouldBe AlertLevel.YELLOW
        AlertSeverity.parse("MINOR").level shouldBe AlertLevel.INFO
        AlertSeverity.parse("SEVERITY_UNKNOWN") shouldBe AlertSeverity.UNKNOWN
        AlertSeverity.parse(null) shouldBe AlertSeverity.UNKNOWN
    }

    @Test
    fun `notify levels pick which levels post`() {
        AlertNotifyLevel.ORANGE_AND_RED.notifies(AlertLevel.RED) shouldBe true
        AlertNotifyLevel.ORANGE_AND_RED.notifies(AlertLevel.ORANGE) shouldBe true
        AlertNotifyLevel.ORANGE_AND_RED.notifies(AlertLevel.YELLOW) shouldBe false
        AlertNotifyLevel.RED_ONLY.notifies(AlertLevel.ORANGE) shouldBe false
        AlertNotifyLevel.ALL.notifies(AlertLevel.YELLOW) shouldBe true
        AlertNotifyLevel.ALL.notifies(AlertLevel.INFO) shouldBe false
        AlertNotifyLevel.NONE.notifies(AlertLevel.RED) shouldBe false
        AlertNotifyLevel.parse("garbage") shouldBe AlertNotifyLevel.ORANGE_AND_RED
        AlertNotifyLevel.parse(null) shouldBe AlertNotifyLevel.DEFAULT
    }

    @Test
    fun `event types map to hazards, most specific first`() {
        AlertHazards.of("WIND") shouldBe AlertHazard.WIND
        AlertHazards.of("HIGH_WIND") shouldBe AlertHazard.WIND
        AlertHazards.of("RAIN_EVENT") shouldBe AlertHazard.RAIN
        AlertHazards.of("FREEZING_RAIN_EVENT") shouldBe AlertHazard.SNOW_AND_ICE
        AlertHazards.of("THUNDERSTORM") shouldBe AlertHazard.THUNDERSTORM
        AlertHazards.of("TROPICAL_STORM") shouldBe AlertHazard.STORM
        AlertHazards.of("TYPHOON") shouldBe AlertHazard.STORM
        AlertHazards.of("COASTAL_FLOOD") shouldBe AlertHazard.COASTAL
        AlertHazards.of("FLASH_FLOOD") shouldBe AlertHazard.FLOOD
        AlertHazards.of("WIND_CHILL") shouldBe AlertHazard.COLD
        AlertHazards.of("FIRE_WEATHER") shouldBe AlertHazard.WILDFIRE
        AlertHazards.of("DENSE_FOG") shouldBe AlertHazard.FOG
        AlertHazards.of("EXTREME_HEAT") shouldBe AlertHazard.HEAT
        AlertHazards.of("SOMETHING_NEW").shouldBeNull()
        AlertHazards.of(null).shouldBeNull()
    }

    @Test
    fun `non-weather alerts are excluded`() {
        AlertHazards.isExcluded("EARTHQUAKE") shouldBe true
        AlertHazards.isExcluded("TSUNAMI") shouldBe true
        AlertHazards.isExcluded("AIR_QUALITY") shouldBe true
        AlertHazards.isExcluded("CHILD_ABDUCTION") shouldBe true
        AlertHazards.isExcluded("WIND") shouldBe false
        AlertHazards.isExcluded("SOMETHING_NEW") shouldBe false
        AlertHazards.isExcluded(null) shouldBe false
    }

    @Test
    fun `color-graded titles lead with a color and say warning`() {
        AlertTitles.colorGraded("Yellow warning of wind") shouldBe AlertTitles.Color.YELLOW
        AlertTitles.colorGraded("Amber warning of rain") shouldBe AlertTitles.Color.AMBER
        AlertTitles.colorGraded("Orange Wind Warning") shouldBe AlertTitles.Color.ORANGE
        AlertTitles.colorGraded("RED WARNING OF SNOW") shouldBe AlertTitles.Color.RED
        AlertTitles.colorGraded("Red Flag Warning").shouldBeNull()
        AlertTitles.colorGraded("High Wind Warning").shouldBeNull()
        AlertTitles.colorGraded("Yellow wind advisory").shouldBeNull()
        AlertTitles.colorGraded("Gelbe Warnung vor Sturmböen").shouldBeNull()
        AlertTitles.colorGraded("").shouldBeNull()
    }
}
