package app.clothescast.ui.today

import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.core.domain.model.OutfitSuggestion
import java.time.LocalDate

/** The fill and stroke colors one outfit card paints its top and bottom with. */
internal data class OutfitTierColors(
    val topColors: Map<OutfitSuggestion.Top, Long>,
    val bottomColors: Map<OutfitSuggestion.Bottom, Long>,
    val topStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    val bottomStrokes: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
)

/**
 * The colors for a period on [date]: the base colors, with [holiday]'s palette
 * on top only when [date] is the day it celebrates ([holidayDate]). Tomorrow's
 * and later outfits keep the user's own colors, so a celebration never
 * spills onto a day it isn't.
 */
internal fun OutfitTierColors.forDate(
    date: LocalDate,
    holiday: HolidayTheme?,
    holidayDate: LocalDate?,
): OutfitTierColors {
    if (holiday == null || date != holidayDate) return this
    return OutfitTierColors(
        topColors = topColors + holiday.topOverrides,
        bottomColors = bottomColors + holiday.bottomOverrides,
        topStrokes = topStrokes + holiday.topStrokeOverrides,
        bottomStrokes = bottomStrokes + holiday.bottomStrokeOverrides,
    )
}
