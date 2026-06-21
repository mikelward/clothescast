package app.clothescast.core.domain.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.LocalTime
import java.util.Locale
import org.junit.jupiter.api.Test

class UnitsTest {

    @Test
    fun `celsius is the identity for both directions`() {
        val c = 18.0
        c.toUnit(TemperatureUnit.CELSIUS) shouldBe c
        c.fromUnit(TemperatureUnit.CELSIUS) shouldBe c
    }

    @Test
    fun `fahrenheit toUnit matches the textbook conversion`() {
        0.0.toUnit(TemperatureUnit.FAHRENHEIT) shouldBe 32.0
        100.0.toUnit(TemperatureUnit.FAHRENHEIT) shouldBe 212.0
        18.0.toUnit(TemperatureUnit.FAHRENHEIT) shouldBe (64.4 plusOrMinus 1e-9)
    }

    @Test
    fun `fahrenheit fromUnit is the inverse`() {
        32.0.fromUnit(TemperatureUnit.FAHRENHEIT) shouldBe (0.0 plusOrMinus 1e-9)
        212.0.fromUnit(TemperatureUnit.FAHRENHEIT) shouldBe (100.0 plusOrMinus 1e-9)
        65.0.fromUnit(TemperatureUnit.FAHRENHEIT) shouldBe (18.333333333 plusOrMinus 1e-6)
    }

    @Test
    fun `roundtrip through fahrenheit returns the original celsius`() {
        listOf(-20.0, -5.5, 0.0, 12.3, 18.0, 37.0).forEach { c ->
            c.toUnit(TemperatureUnit.FAHRENHEIT).fromUnit(TemperatureUnit.FAHRENHEIT) shouldBe (c plusOrMinus 1e-9)
        }
    }

    @Test
    fun `kmh is the identity for wind speed`() {
        15.0.toWindSpeedUnit(WindSpeedUnit.KMH) shouldBe 15.0
        0.0.toWindSpeedUnit(WindSpeedUnit.KMH) shouldBe 0.0
    }

    @Test
    fun `mph conversion matches the textbook factor`() {
        // 1 mile = 1.609344 km exactly, so 1 km/h = 1 / 1.609344 mph.
        1.609344.toWindSpeedUnit(WindSpeedUnit.MPH) shouldBe (1.0 plusOrMinus 1e-9)
        100.0.toWindSpeedUnit(WindSpeedUnit.MPH) shouldBe (62.1371192 plusOrMinus 1e-6)
        0.0.toWindSpeedUnit(WindSpeedUnit.MPH) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `knots conversion matches the nautical-mile factor`() {
        // 1 knot = 1.852 km/h exactly (one nautical mile per hour).
        1.852.toWindSpeedUnit(WindSpeedUnit.KNOTS) shouldBe (1.0 plusOrMinus 1e-9)
        100.0.toWindSpeedUnit(WindSpeedUnit.KNOTS) shouldBe (53.9956803 plusOrMinus 1e-6)
        0.0.toWindSpeedUnit(WindSpeedUnit.KNOTS) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `metres-per-second conversion is km per hour over 3_6`() {
        3.6.toWindSpeedUnit(WindSpeedUnit.MS) shouldBe (1.0 plusOrMinus 1e-9)
        36.0.toWindSpeedUnit(WindSpeedUnit.MS) shouldBe (10.0 plusOrMinus 1e-9)
        0.0.toWindSpeedUnit(WindSpeedUnit.MS) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `wind speed unit symbols are the standard abbreviations`() {
        WindSpeedUnit.KMH.symbol() shouldBe "km/h"
        WindSpeedUnit.MPH.symbol() shouldBe "mph"
        WindSpeedUnit.KNOTS.symbol() shouldBe "kn"
        WindSpeedUnit.MS.symbol() shouldBe "m/s"
    }

    @Test
    fun `24h top-of-hour reads zero-padded`() {
        val locale = Locale.UK
        TimeFormat.TWENTY_FOUR_HOUR.formatTopOfHour(LocalTime.of(0, 0), locale) shouldBe "00:00"
        TimeFormat.TWENTY_FOUR_HOUR.formatTopOfHour(LocalTime.of(9, 0), locale) shouldBe "09:00"
        TimeFormat.TWENTY_FOUR_HOUR.formatTopOfHour(LocalTime.of(20, 0), locale) shouldBe "20:00"
    }

    @Test
    fun `12h English top-of-hour drops minutes and uses lowercase no-space`() {
        // The convention en-GB / en-AU readers expect — "8pm" not "8 PM" — and the
        // form InsightFormatter already uses in spoken prose.
        val locale = Locale.UK
        TimeFormat.TWELVE_HOUR.formatTopOfHour(LocalTime.of(0, 0), locale) shouldBe "12am"
        TimeFormat.TWELVE_HOUR.formatTopOfHour(LocalTime.of(1, 0), locale) shouldBe "1am"
        TimeFormat.TWELVE_HOUR.formatTopOfHour(LocalTime.of(12, 0), locale) shouldBe "12pm"
        TimeFormat.TWELVE_HOUR.formatTopOfHour(LocalTime.of(20, 0), locale) shouldBe "8pm"
        TimeFormat.TWELVE_HOUR.formatTopOfHour(LocalTime.of(23, 0), locale) shouldBe "11pm"
    }

    @Test
    fun `12h English hour-minute keeps minutes when nonzero, drops them at the hour`() {
        val locale = Locale.US
        TimeFormat.TWELVE_HOUR.formatHourMinute(LocalTime.of(7, 0), locale) shouldBe "7am"
        TimeFormat.TWELVE_HOUR.formatHourMinute(LocalTime.of(7, 30), locale) shouldBe "7:30am"
        TimeFormat.TWELVE_HOUR.formatHourMinute(LocalTime.of(19, 5), locale) shouldBe "7:05pm"
        TimeFormat.TWELVE_HOUR.formatHourMinute(LocalTime.of(0, 0), locale) shouldBe "12am"
    }

    @Test
    fun `24h hour-minute reads zero-padded for any locale`() {
        TimeFormat.TWENTY_FOUR_HOUR.formatHourMinute(LocalTime.of(7, 5), Locale.GERMANY) shouldBe "07:05"
        TimeFormat.TWENTY_FOUR_HOUR.formatHourMinute(LocalTime.of(20, 0), Locale.US) shouldBe "20:00"
    }

    @Test
    fun `pattern scanner ignores 12h field characters inside single-quoted literals`() {
        // fr_CA: "HH 'h' mm" — the quoted 'h' is the French separator, not an
        // hour field. Misclassifying it as 12h flips the Auto picker to AM/PM
        // in a 24h locale.
        "HH 'h' mm".containsTwelveHourPatternField() shouldBe false
        // hsb_DE: "H:mm 'hodź'." — quoted literal carrying an 'h'.
        "H:mm 'hodź'.".containsTwelveHourPatternField() shouldBe false
        // Plain 24h pattern — no h/K at all.
        "HH:mm".containsTwelveHourPatternField() shouldBe false
        // Real 12h patterns — h (1–12) and K (0–11) both count.
        "h:mm a".containsTwelveHourPatternField() shouldBe true
        "KK:mm a".containsTwelveHourPatternField() shouldBe true
        // 12h field outside a closed-then-reopened quote pair.
        "'lit' h:mm a 'end'".containsTwelveHourPatternField() shouldBe true
        // Escaped single quotes ('') stay inside the literal — the h after
        // is still quoted.
        "'it''s h' HH:mm".containsTwelveHourPatternField() shouldBe false
    }

    @Test
    fun `12h non-English preserves locale AM-PM placement when locale is 12h`() {
        // Korean places the marker before the hour ("오후 2:30" / "a h:mm"),
        // not after — a hardcoded "h:mm a" would invert the convention. The
        // exact marker glyph and separator vary by JDK CLDR version, so we
        // just assert the first character is non-digit (i.e., the marker
        // leads the string) — that's the inversion the fix prevents.
        val out = TimeFormat.TWELVE_HOUR.formatHourMinute(LocalTime.of(14, 30), Locale.forLanguageTag("ko-KR"))
        out.first().isDigit() shouldBe false
    }

    @Test
    fun `12h non-English top-of-hour keeps minute field`() {
        // The tighter "8pm" form is an English-reader convention only; non-
        // English locales render the minute field even at the top of an hour
        // so we don't have to strip ":mm" out of an arbitrary locale pattern.
        // The exact AM/PM marker depends on JDK locale-data version, so we
        // just assert the minute field is present.
        val out = TimeFormat.TWELVE_HOUR.formatTopOfHour(LocalTime.of(14, 0), Locale.GERMANY)
        out shouldContain "2"
        out shouldContain ":00"
    }
}
