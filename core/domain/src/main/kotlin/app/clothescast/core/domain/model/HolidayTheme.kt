package app.clothescast.core.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/**
 * Identifies a holiday whose presence on today's date can swap the outfit
 * preview palette and surface a themed banner on the Today screen. Stored as
 * the enum name string in DataStore so adding new holidays doesn't migrate
 * existing installs' choices — unknown names on read drop silently
 * (forward-compat).
 *
 * Catalog order in [HolidayCatalog.all] is calendar order, which is also
 * resolver-precedence order — first match wins if two holidays ever land on
 * the same calendar day. None do in the v1 list.
 */
enum class HolidayId {
    NEW_YEARS_DAY,
    AUSTRALIA_DAY,
    VALENTINES_DAY,
    ST_DAVIDS_DAY,
    ST_PATRICKS_DAY,
    ST_GEORGES_DAY,
    ANZAC_DAY,
    ITALY_REPUBLIC_DAY,
    CANADA_DAY,
    US_INDEPENDENCE_DAY,
    BASTILLE_DAY,
    BRAZIL_INDEPENDENCE_DAY,
    GERMAN_UNITY_DAY,
    SPAIN_HISPANIC_DAY,
    HALLOWEEN,
    BONFIRE_NIGHT,
    US_THANKSGIVING,
    ST_ANDREWS_DAY,
    CHRISTMAS_DAY,
    // TODO(holidays-v2): Mother's Day and Father's Day are nth-weekday *and*
    // country-variant — Mother's Day = 2nd Sun May for most but Mothering
    // Sunday in UK/IE (movable); Father's Day = 3rd Sun Jun in US/UK/CA but
    // 1st Sun Sep in AU/NZ. Add when we have a region-gating story, or split
    // into MOTHERS_DAY_MAY / FATHERS_DAY_JUN / FATHERS_DAY_SEP entries.
    //
    // TODO(holidays-v2): movable / lunisolar holidays (Easter, Lunar New Year,
    // Diwali, Hanukkah, Eid, Holi). Need either a Computus implementation for
    // Easter or per-year lookup tables for the others.
}

/**
 * How a holiday's date is computed from a [LocalDate]. Most are [Fixed] —
 * the same Month+day every year. [NthWeekday] covers the only v1 movable
 * date (Thanksgiving = 4th Thu of November).
 */
sealed interface HolidayDate {
    fun matches(date: LocalDate): Boolean

    data class Fixed(val month: Month, val day: Int) : HolidayDate {
        override fun matches(date: LocalDate): Boolean =
            date.month == month && date.dayOfMonth == day
    }

    /**
     * Nth occurrence of [day] within [month]. Example for Thanksgiving:
     * `NthWeekday(NOVEMBER, 4, THURSDAY)` — the 4th Thursday of November.
     */
    data class NthWeekday(val month: Month, val nth: Int, val day: DayOfWeek) : HolidayDate {
        override fun matches(date: LocalDate): Boolean {
            if (date.month != month || date.dayOfWeek != day) return false
            // 1st of the month's day-of-month is 1..7; 2nd is 8..14; etc.
            // Equivalent to: ((dayOfMonth - 1) / 7) + 1 == nth.
            return (date.dayOfMonth - 1) / 7 + 1 == nth
        }
    }
}

/**
 * A holiday's display data + per-garment palette. The palette is keyed by
 * the rendered icon tier ([OutfitSuggestion.Top] / [OutfitSuggestion.Bottom])
 * so the merge into [UserPreferences.outfitTopColors] is a trivial map union.
 *
 * Colours are packed ARGB ([Int.toLong]) matching the type of
 * [UserPreferences.outfitTopColors].
 *
 * Fill (`*Overrides`) is the primary colour of the garment body. Stroke
 * (`*StrokeOverrides`) is the colour of the outline / collar / cuff /
 * trim — the SVG's secondary detail colour. Empty stroke map ⇒ the
 * existing auto-derive-from-fill logic kicks in (monochrome holidays
 * like St Patrick's get an unobtrusive darker-green outline). A
 * non-empty stroke map paints a contrasting accent: yellow Australia-
 * Day shirt with green collar / sleeves, red Christmas top with white
 * trim, etc.
 *
 * [emoji] is a single glyph rendered alongside the banner text — keep it to
 * one visible code point so RTL / fontScale doesn't reflow the line.
 */
data class HolidayTheme(
    val id: HolidayId,
    val displayNameKey: String,
    val bannerTextKey: String,
    val emoji: String,
    val topOverrides: Map<OutfitSuggestion.Top, Long>,
    val bottomOverrides: Map<OutfitSuggestion.Bottom, Long>,
    val bannerArgb: Long,
    val topStrokeOverrides: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    val bottomStrokeOverrides: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
)

/**
 * The full v1 list of holidays with their date predicate, palette, and
 * banner copy. Catalog order is calendar order — also the resolver's
 * first-match precedence order if two ever clash (none do in v1).
 *
 * Palette philosophy: anchor on the holiday's flag / iconic colours, and
 * spread them across the outfit tiers so a Christmas-day THICK_COAT user
 * gets a different fill than a Christmas-day TSHIRT user but both feel
 * Christmasy. Where a holiday has only two flag colours we double up — same
 * colour on adjacent tiers — rather than inventing a third that's not in
 * the holiday's visual vocabulary.
 */
object HolidayCatalog {

    /** Lookup for the (sub)set of UI surfaces that need a theme by id. */
    fun themeFor(id: HolidayId): HolidayTheme? = byId[id]

    val all: List<Pair<HolidayDate, HolidayTheme>> = listOf(
        // Jan 1 — black + gold. Top fill gold + black outline; bottom fill
        // black + gold outline so a single outfit shows both colours.
        HolidayDate.Fixed(Month.JANUARY, 1) to HolidayTheme(
            id = HolidayId.NEW_YEARS_DAY,
            displayNameKey = "holiday_name_new_years_day",
            bannerTextKey = "holiday_banner_new_years_day",
            emoji = "🎉", // 🎉
            topOverrides = topPaletteAll(NY_GOLD),
            bottomOverrides = bottomPaletteAll(NY_BLACK),
            topStrokeOverrides = topStrokeAll(NY_BLACK),
            bottomStrokeOverrides = bottomStrokeAll(NY_GOLD),
            bannerArgb = NY_GOLD,
        ),

        // Jan 26 — Australia Day. Sporting green + gold. Yellow shirt with
        // green collar / sleeves; green shorts with yellow trim. Distinct
        // from the actual flag colours (blue/red/white).
        HolidayDate.Fixed(Month.JANUARY, 26) to HolidayTheme(
            id = HolidayId.AUSTRALIA_DAY,
            displayNameKey = "holiday_name_australia_day",
            bannerTextKey = "holiday_banner_australia_day",
            emoji = "🦎", // 🦎 — a nod that doesn't lean on the flag
            topOverrides = topPaletteAll(AUS_GOLD),
            bottomOverrides = bottomPaletteAll(AUS_GREEN),
            topStrokeOverrides = topStrokeAll(AUS_GREEN),
            bottomStrokeOverrides = bottomStrokeAll(AUS_GOLD),
            bannerArgb = AUS_GREEN,
        ),

        // Feb 14 — Valentine's. Pink shirts with red trim; red bottoms with
        // pink trim. The deep-red third hue from v1 was dropped — pink and
        // red alone read unambiguously as Valentine's.
        HolidayDate.Fixed(Month.FEBRUARY, 14) to HolidayTheme(
            id = HolidayId.VALENTINES_DAY,
            displayNameKey = "holiday_name_valentines_day",
            bannerTextKey = "holiday_banner_valentines_day",
            emoji = "❤️", // ❤️
            topOverrides = topPaletteAll(VAL_PINK),
            bottomOverrides = bottomPaletteAll(VAL_RED),
            topStrokeOverrides = topStrokeAll(VAL_RED),
            bottomStrokeOverrides = bottomStrokeAll(VAL_PINK),
            bannerArgb = VAL_RED,
        ),

        // Mar 1 — St David's Day. Daffodil yellow tops with leek-green
        // trim; green bottoms with yellow trim.
        HolidayDate.Fixed(Month.MARCH, 1) to HolidayTheme(
            id = HolidayId.ST_DAVIDS_DAY,
            displayNameKey = "holiday_name_st_davids_day",
            bannerTextKey = "holiday_banner_st_davids_day",
            emoji = "🌼", // 🌼
            topOverrides = topPaletteAll(WALES_YELLOW),
            bottomOverrides = bottomPaletteAll(WALES_GREEN),
            topStrokeOverrides = topStrokeAll(WALES_GREEN),
            bottomStrokeOverrides = bottomStrokeAll(WALES_YELLOW),
            bannerArgb = WALES_GREEN,
        ),

        // Mar 17 — St Patrick's Day. Green, green, more green. Monochrome,
        // so stroke overrides are intentionally empty — the existing auto-
        // derive logic paints a slightly darker green outline that reads
        // as natural shading rather than a flag accent.
        HolidayDate.Fixed(Month.MARCH, 17) to HolidayTheme(
            id = HolidayId.ST_PATRICKS_DAY,
            displayNameKey = "holiday_name_st_patricks_day",
            bannerTextKey = "holiday_banner_st_patricks_day",
            emoji = "☘️", // ☘️
            topOverrides = topPaletteAll(IRELAND_GREEN),
            bottomOverrides = bottomPaletteAll(IRELAND_GREEN),
            bannerArgb = IRELAND_DEEP,
        ),

        // Apr 23 — St George's Day. White shirts with red trim (a hint of
        // the flag's red cross); red bottoms with white trim.
        HolidayDate.Fixed(Month.APRIL, 23) to HolidayTheme(
            id = HolidayId.ST_GEORGES_DAY,
            displayNameKey = "holiday_name_st_georges_day",
            bannerTextKey = "holiday_banner_st_georges_day",
            emoji = "🏴‍☠️", // 🏴‍☠️ — closest single glyph
            topOverrides = topPaletteAll(ENGLAND_WHITE),
            bottomOverrides = bottomPaletteAll(ENGLAND_RED),
            topStrokeOverrides = topStrokeAll(ENGLAND_RED),
            bottomStrokeOverrides = bottomStrokeAll(ENGLAND_WHITE),
            bannerArgb = ENGLAND_RED,
        ),

        // Apr 25 — Anzac Day. Solemn day — khaki uniform-evoking palette
        // with a single red-poppy outline thread through every garment.
        HolidayDate.Fixed(Month.APRIL, 25) to HolidayTheme(
            id = HolidayId.ANZAC_DAY,
            displayNameKey = "holiday_name_anzac_day",
            bannerTextKey = "holiday_banner_anzac_day",
            emoji = "🔺", // 🔺 — abstract; avoids lighter / flower vibe
            topOverrides = topPaletteAll(ANZAC_KHAKI),
            bottomOverrides = bottomPaletteAll(ANZAC_KHAKI),
            topStrokeOverrides = topStrokeAll(ANZAC_POPPY),
            bottomStrokeOverrides = bottomStrokeAll(ANZAC_POPPY),
            bannerArgb = ANZAC_POPPY,
        ),

        // Jun 2 — Italian Republic Day. Green tops + red bottoms with the
        // flag's white field threaded through as accent trim on both.
        HolidayDate.Fixed(Month.JUNE, 2) to HolidayTheme(
            id = HolidayId.ITALY_REPUBLIC_DAY,
            displayNameKey = "holiday_name_italy_republic_day",
            bannerTextKey = "holiday_banner_italy_republic_day",
            emoji = "🇮🇹", // 🇮🇹
            topOverrides = topPaletteAll(ITALY_GREEN),
            bottomOverrides = bottomPaletteAll(ITALY_RED),
            topStrokeOverrides = topStrokeAll(ITALY_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(ITALY_WHITE),
            bannerArgb = ITALY_GREEN,
        ),

        // Jul 1 — Canada Day. White tops with red trim; red bottoms with
        // white trim — same flag-cross pattern as St George's.
        HolidayDate.Fixed(Month.JULY, 1) to HolidayTheme(
            id = HolidayId.CANADA_DAY,
            displayNameKey = "holiday_name_canada_day",
            bannerTextKey = "holiday_banner_canada_day",
            emoji = "🇨🇦", // 🇨🇦
            topOverrides = topPaletteAll(CANADA_WHITE),
            bottomOverrides = bottomPaletteAll(CANADA_RED),
            topStrokeOverrides = topStrokeAll(CANADA_RED),
            bottomStrokeOverrides = bottomStrokeAll(CANADA_WHITE),
            bannerArgb = CANADA_RED,
        ),

        // Jul 4 — US Independence Day. Red tops + blue bottoms with white
        // as the unifying accent trim across both — every outfit pair
        // shows all three flag colours simultaneously.
        HolidayDate.Fixed(Month.JULY, 4) to HolidayTheme(
            id = HolidayId.US_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_us_independence_day",
            bannerTextKey = "holiday_banner_us_independence_day",
            emoji = "🎆", // 🎆
            topOverrides = topPaletteAll(USA_RED),
            bottomOverrides = bottomPaletteAll(USA_BLUE),
            topStrokeOverrides = topStrokeAll(USA_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(USA_WHITE),
            bannerArgb = USA_BLUE,
        ),

        // Jul 14 — Bastille Day. French tricolour blue/white/red. Lead with
        // blue tops + red bottoms (mirror image of July 4) so the two
        // summer tricolour holidays read as visibly distinct.
        HolidayDate.Fixed(Month.JULY, 14) to HolidayTheme(
            id = HolidayId.BASTILLE_DAY,
            displayNameKey = "holiday_name_bastille_day",
            bannerTextKey = "holiday_banner_bastille_day",
            emoji = "🇫🇷", // 🇫🇷
            topOverrides = topPaletteAll(FRANCE_BLUE),
            bottomOverrides = bottomPaletteAll(FRANCE_RED),
            topStrokeOverrides = topStrokeAll(FRANCE_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(FRANCE_WHITE),
            bannerArgb = FRANCE_BLUE,
        ),

        // Sep 7 — Brazil Independence Day. Green tops with yellow trim;
        // yellow bottoms with green trim.
        HolidayDate.Fixed(Month.SEPTEMBER, 7) to HolidayTheme(
            id = HolidayId.BRAZIL_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_brazil_independence_day",
            bannerTextKey = "holiday_banner_brazil_independence_day",
            emoji = "🇧🇷", // 🇧🇷
            topOverrides = topPaletteAll(BRAZIL_GREEN),
            bottomOverrides = bottomPaletteAll(BRAZIL_YELLOW),
            topStrokeOverrides = topStrokeAll(BRAZIL_YELLOW),
            bottomStrokeOverrides = bottomStrokeAll(BRAZIL_GREEN),
            bannerArgb = BRAZIL_GREEN,
        ),

        // Oct 3 — German Unity Day. Black tops + red bottoms with gold as
        // the unifying flag-accent trim across both.
        HolidayDate.Fixed(Month.OCTOBER, 3) to HolidayTheme(
            id = HolidayId.GERMAN_UNITY_DAY,
            displayNameKey = "holiday_name_german_unity_day",
            bannerTextKey = "holiday_banner_german_unity_day",
            emoji = "🇩🇪", // 🇩🇪
            topOverrides = topPaletteAll(GERMANY_BLACK),
            bottomOverrides = bottomPaletteAll(GERMANY_RED),
            topStrokeOverrides = topStrokeAll(GERMANY_GOLD),
            bottomStrokeOverrides = bottomStrokeAll(GERMANY_GOLD),
            bannerArgb = GERMANY_RED,
        ),

        // Oct 12 — Hispanic Day (Spain national day). Red tops with yellow
        // trim; yellow bottoms with red trim.
        HolidayDate.Fixed(Month.OCTOBER, 12) to HolidayTheme(
            id = HolidayId.SPAIN_HISPANIC_DAY,
            displayNameKey = "holiday_name_spain_hispanic_day",
            bannerTextKey = "holiday_banner_spain_hispanic_day",
            emoji = "🇪🇸", // 🇪🇸
            topOverrides = topPaletteAll(SPAIN_RED),
            bottomOverrides = bottomPaletteAll(SPAIN_YELLOW),
            topStrokeOverrides = topStrokeAll(SPAIN_YELLOW),
            bottomStrokeOverrides = bottomStrokeAll(SPAIN_RED),
            bannerArgb = SPAIN_RED,
        ),

        // Oct 31 — Halloween. Pumpkin-orange tops with black trim; black
        // bottoms with orange trim. Purple was originally a third hue but
        // dropped to keep the palette focused on the two colours that read
        // unambiguously as Halloween.
        HolidayDate.Fixed(Month.OCTOBER, 31) to HolidayTheme(
            id = HolidayId.HALLOWEEN,
            displayNameKey = "holiday_name_halloween",
            bannerTextKey = "holiday_banner_halloween",
            emoji = "🎃", // 🎃
            topOverrides = topPaletteAll(HALLOWEEN_ORANGE),
            bottomOverrides = bottomPaletteAll(HALLOWEEN_BLACK),
            topStrokeOverrides = topStrokeAll(HALLOWEEN_BLACK),
            bottomStrokeOverrides = bottomStrokeAll(HALLOWEEN_ORANGE),
            bannerArgb = HALLOWEEN_ORANGE,
        ),

        // Nov 5 — Bonfire Night. Fire orange + smoke red. Orange tops with
        // red trim; red bottoms with orange trim.
        HolidayDate.Fixed(Month.NOVEMBER, 5) to HolidayTheme(
            id = HolidayId.BONFIRE_NIGHT,
            displayNameKey = "holiday_name_bonfire_night",
            bannerTextKey = "holiday_banner_bonfire_night",
            emoji = "🎆", // 🎆
            topOverrides = topPaletteAll(BONFIRE_ORANGE),
            bottomOverrides = bottomPaletteAll(BONFIRE_RED),
            topStrokeOverrides = topStrokeAll(BONFIRE_RED),
            bottomStrokeOverrides = bottomStrokeAll(BONFIRE_ORANGE),
            bannerArgb = BONFIRE_RED,
        ),

        // 4th Thursday of November — US Thanksgiving. Pumpkin tops + rust
        // bottoms with deep-autumn brown as the unifying accent trim.
        HolidayDate.NthWeekday(Month.NOVEMBER, 4, DayOfWeek.THURSDAY) to HolidayTheme(
            id = HolidayId.US_THANKSGIVING,
            displayNameKey = "holiday_name_us_thanksgiving",
            bannerTextKey = "holiday_banner_us_thanksgiving",
            emoji = "🦃", // 🦃
            topOverrides = topPaletteAll(THANKS_PUMPKIN),
            bottomOverrides = bottomPaletteAll(THANKS_RUST),
            topStrokeOverrides = topStrokeAll(THANKS_BROWN),
            bottomStrokeOverrides = bottomStrokeAll(THANKS_BROWN),
            bannerArgb = THANKS_RUST,
        ),

        // Nov 30 — St Andrew's Day. Saltire blue tops with white trim;
        // white bottoms with blue trim.
        HolidayDate.Fixed(Month.NOVEMBER, 30) to HolidayTheme(
            id = HolidayId.ST_ANDREWS_DAY,
            displayNameKey = "holiday_name_st_andrews_day",
            bannerTextKey = "holiday_banner_st_andrews_day",
            emoji = "🏴󠁧󠁢󠁳󠁣󠁴󠁿", // 🏴󠁧󠁢󠁳󠁣󠁴󠁿
            topOverrides = topPaletteAll(SCOTLAND_BLUE),
            bottomOverrides = bottomPaletteAll(SCOTLAND_WHITE),
            topStrokeOverrides = topStrokeAll(SCOTLAND_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(SCOTLAND_BLUE),
            bannerArgb = SCOTLAND_BLUE,
        ),

        // Dec 25 — Christmas Day. Pillarbox-red tops + holly-green bottoms
        // with snow-white as the unifying accent trim — three classic
        // Christmas hues simultaneously visible on every outfit.
        HolidayDate.Fixed(Month.DECEMBER, 25) to HolidayTheme(
            id = HolidayId.CHRISTMAS_DAY,
            displayNameKey = "holiday_name_christmas_day",
            bannerTextKey = "holiday_banner_christmas_day",
            emoji = "🎄", // 🎄
            topOverrides = topPaletteAll(XMAS_RED),
            bottomOverrides = bottomPaletteAll(XMAS_GREEN),
            topStrokeOverrides = topStrokeAll(XMAS_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(XMAS_WHITE),
            bannerArgb = XMAS_RED,
        ),
    )

    private val byId: Map<HolidayId, HolidayTheme> =
        all.associate { (_, theme) -> theme.id to theme }
}

/** Every top tier gets the same fill colour. */
private fun topPaletteAll(argb: Long): Map<OutfitSuggestion.Top, Long> =
    OutfitSuggestion.Top.entries.associateWith { argb }

/** Every bottom tier gets the same fill colour. */
private fun bottomPaletteAll(argb: Long): Map<OutfitSuggestion.Bottom, Long> =
    OutfitSuggestion.Bottom.entries.associateWith { argb }

/** Every top tier gets the same stroke / accent colour. */
private fun topStrokeAll(argb: Long): Map<OutfitSuggestion.Top, Long> =
    OutfitSuggestion.Top.entries.associateWith { argb }

/** Every bottom tier gets the same stroke / accent colour. */
private fun bottomStrokeAll(argb: Long): Map<OutfitSuggestion.Bottom, Long> =
    OutfitSuggestion.Bottom.entries.associateWith { argb }

// Packed ARGB constants (0xAARRGGBB as Long). Kept here so each holiday's
// palette assignment reads as "which tier gets which colour" rather than
// burying the hex inside the theme table. All alpha = FF (fully opaque).
// L suffix needed: any 0xFFnnnnnn value with the high byte set exceeds
// Int.MAX_VALUE, so the literal must be Long to avoid a compile error.

private const val NY_GOLD = 0xFFD4AF37L
private const val NY_BLACK = 0xFF1A1A1AL

private const val AUS_GREEN = 0xFF00843DL
private const val AUS_GOLD = 0xFFFFCD00L

private const val VAL_PINK = 0xFFFF6B9DL
private const val VAL_RED = 0xFFD81B60L

private const val WALES_GREEN = 0xFF2E7D32L
private const val WALES_YELLOW = 0xFFFFC107L

private const val IRELAND_GREEN = 0xFF1B8F47L
private const val IRELAND_DEEP = 0xFF0E5C2BL

private const val ENGLAND_RED = 0xFFCE1124L
private const val ENGLAND_WHITE = 0xFFF5F5F5L

private const val ANZAC_POPPY = 0xFFB71C1CL
private const val ANZAC_KHAKI = 0xFF6D6748L

private const val ITALY_GREEN = 0xFF008C45L
private const val ITALY_WHITE = 0xFFF4F5F0L
private const val ITALY_RED = 0xFFCD212AL

private const val CANADA_RED = 0xFFD52B1EL
private const val CANADA_WHITE = 0xFFF5F5F5L

private const val USA_RED = 0xFFB22234L
private const val USA_WHITE = 0xFFF5F5F5L
private const val USA_BLUE = 0xFF3C3B6EL

private const val FRANCE_BLUE = 0xFF0055A4L
private const val FRANCE_WHITE = 0xFFF5F5F5L
private const val FRANCE_RED = 0xFFEF4135L

private const val BRAZIL_GREEN = 0xFF009C3BL
private const val BRAZIL_YELLOW = 0xFFFFDF00L

private const val GERMANY_BLACK = 0xFF1A1A1AL
private const val GERMANY_RED = 0xFFDD0000L
private const val GERMANY_GOLD = 0xFFFFCE00L

private const val SPAIN_RED = 0xFFAA151BL
private const val SPAIN_YELLOW = 0xFFF1BF00L

private const val HALLOWEEN_ORANGE = 0xFFE65100L
private const val HALLOWEEN_BLACK = 0xFF212121L

private const val BONFIRE_ORANGE = 0xFFEF6C00L
private const val BONFIRE_RED = 0xFFC62828L

private const val THANKS_PUMPKIN = 0xFFEF6C00L
private const val THANKS_RUST = 0xFFBF360CL
private const val THANKS_BROWN = 0xFF5D4037L

private const val SCOTLAND_BLUE = 0xFF005EB8L
private const val SCOTLAND_WHITE = 0xFFF5F5F5L

private const val XMAS_RED = 0xFFC62828L
private const val XMAS_GREEN = 0xFF2E7D32L
private const val XMAS_WHITE = 0xFFF5F5F5L
