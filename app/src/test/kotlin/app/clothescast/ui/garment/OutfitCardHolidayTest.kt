package app.clothescast.ui.garment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.FestiveThemes
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.usecase.ThemeForToday
import app.clothescast.ui.today.holidayBannerContentArgb
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode.NATIVE
import java.time.LocalDate
import java.time.Month

/**
 * The outfit card names the day's celebration the way the Today banner does —
 * a birthday by its calendar title, a holiday by its banner copy — in a chip
 * under the header.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(NATIVE)
@Config(sdk = [33])
class OutfitCardHolidayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `an ordinary day has no holiday chip`() {
        outfitCardHoliday(context, theme = null, region = Region.EN_US).shouldBeNull()
    }

    @Test
    fun `a birthday is named by its calendar title`() {
        val birthday = FestiveThemes.birthday("Alice's birthday")

        val holiday = outfitCardHoliday(context, birthday, Region.EN_US).shouldNotBeNull()

        holiday.label shouldBe "🎂  Alice's birthday"
        holiday.backgroundArgb shouldBe birthday.bannerArgb.toInt()
        holiday.contentArgb shouldBe holidayBannerContentArgb(birthday.bannerArgb)
    }

    @Test
    fun `an unrecognized calendar holiday is named on a neutral chip with no emoji`() {
        val plain = FestiveThemes.publicHoliday("Daylight Saving Time ends")

        val holiday = outfitCardHoliday(context, plain, Region.EN_US).shouldNotBeNull()

        holiday.label shouldBe "Daylight Saving Time ends"
        holiday.backgroundArgb shouldBe 0xFFE0E0E0.toInt()
    }

    @Test
    fun `a catalog holiday reads its banner copy`() {
        outfitCardHoliday(context, christmas, Region.EN_US)?.label shouldBe "${christmas.emoji}  Merry Christmas"
    }

    @Test
    fun `a birthday on a holiday names both, as the Today banner does`() {
        val composed = ThemeForToday().resolve(
            date = LocalDate.of(2026, Month.DECEMBER, 25),
            overrides = emptyMap(),
            enabledCountries = HolidayCatalog.allCountries,
            events = listOf(
                CalendarEvent(
                    title = "Alice's birthday",
                    start = LocalDate.of(2026, Month.DECEMBER, 25).atStartOfDay(),
                    end = LocalDate.of(2026, Month.DECEMBER, 25).atStartOfDay(),
                    allDay = true,
                    kind = EventKind.BIRTHDAY,
                ),
            ),
            themeFromCalendarHolidays = false,
            themeFromCalendarBirthdays = true,
        ).shouldNotBeNull()

        outfitCardHoliday(context, composed, Region.EN_US)?.label shouldBe
            "${composed.emoji}  Merry Christmas and Alice's birthday"
    }

    @Test
    fun `the holiday's name follows the region`() {
        outfitCardHoliday(context, remembranceDay, Region.EN_US)?.label shouldBe
            "${remembranceDay.emoji}  Honoring our Veterans"
        outfitCardHoliday(context, remembranceDay, Region.EN_GB)?.label shouldBe
            "${remembranceDay.emoji}  Lest we forget"
    }

    @Test
    fun `text on a bright banner is dark and on a deep one light`() {
        holidayBannerContentArgb(0xFFFFD700L) shouldBe android.graphics.Color.BLACK
        holidayBannerContentArgb(0xFF8B0000L) shouldBe android.graphics.Color.WHITE
    }

    @Test
    fun `the chip is drawn under the header in the banner color`() {
        val withChip = render(OutfitCardHoliday("🎂  Alice's birthday", CHIP_ARGB, android.graphics.Color.WHITE))
        val withoutChip = render(holiday = null)

        withChip.getPixel(CHIP_PROBE_X, CHIP_PROBE_Y) shouldBe CHIP_ARGB
        withoutChip.getPixel(CHIP_PROBE_X, CHIP_PROBE_Y) shouldNotBe CHIP_ARGB
    }

    @Test
    fun `a long name is cut to the text column, never past it`() {
        val card = render(
            OutfitCardHoliday("🎂  " + "Alice's birthday ".repeat(20), CHIP_ARGB, android.graphics.Color.WHITE),
        )

        // Fills the column out to its right edge...
        card.getPixel(COLUMN_RIGHT - 4, CHIP_PROBE_Y) shouldBe CHIP_ARGB
        // ...and stops at the card's padding.
        card.getPixel(COLUMN_RIGHT + 4, CHIP_PROBE_Y) shouldBe android.graphics.Color.WHITE
    }

    private fun render(holiday: OutfitCardHoliday?): Bitmap {
        val png = renderOutfitCard(
            context = context,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
            header = "7am ClothesCast",
            prose = "Today, it will be cool. Wear a sweater and long pants.",
            info = OutfitCardInfoLines(tempLine = "11–18°C", tempFillFraction = 0.5f, rainFillFraction = null),
            topColors = emptyMap(),
            bottomColors = emptyMap(),
            holiday = holiday,
        )
        return BitmapFactory.decodeByteArray(png, 0, png.size)
    }

    private companion object {
        val christmas = HolidayCatalog.all.first { it.second.id == HolidayId.CHRISTMAS_DAY }.second
        val remembranceDay = HolidayCatalog.all.first { it.second.id == HolidayId.REMEMBRANCE_DAY }.second

        // Pure green: no garment, glyph or palette color on the card uses it.
        const val CHIP_ARGB = 0xFF00FF00.toInt()

        // The text column runs from just right of the 160px icon column
        // (36 + 160 + 24) to the card's right padding (800 - 36). Probe inside
        // the chip's left padding, clear of its rounded corners and its text,
        // a little below the header.
        const val COLUMN_LEFT = 220
        const val COLUMN_RIGHT = 764
        const val CHIP_PROBE_X = COLUMN_LEFT + 6
        const val CHIP_PROBE_Y = 118
    }
}
