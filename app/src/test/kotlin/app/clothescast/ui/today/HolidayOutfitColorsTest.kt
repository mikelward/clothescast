package app.clothescast.ui.today

import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.OutfitSuggestion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class HolidayOutfitColorsTest {
    private val christmas = HolidayCatalog.themeFor(HolidayId.CHRISTMAS_DAY)!!
    private val day = LocalDate.of(2026, 12, 25)
    private val userBlue = 0xFF0000FFL
    private val base = OutfitTierColors(
        topColors = mapOf(OutfitSuggestion.Top.SWEATER to userBlue),
        bottomColors = mapOf(OutfitSuggestion.Bottom.LONG_PANTS to userBlue),
    )

    @Test
    fun `celebration day takes the holiday palette over the user's colors`() {
        val colors = base.forDate(day, christmas, holidayDate = day)

        colors.topColors[OutfitSuggestion.Top.SWEATER] shouldBe christmas.topOverrides[OutfitSuggestion.Top.SWEATER]
        colors.bottomColors[OutfitSuggestion.Bottom.LONG_PANTS] shouldBe
            christmas.bottomOverrides[OutfitSuggestion.Bottom.LONG_PANTS]
    }

    @Test
    fun `a later day keeps the user's colors`() {
        base.forDate(day.plusDays(1), christmas, holidayDate = day) shouldBe base
    }

    @Test
    fun `no holiday keeps the user's colors`() {
        base.forDate(day, holiday = null, holidayDate = null) shouldBe base
    }
}
