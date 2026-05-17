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
    MOTHERS_DAY,
    ITALY_REPUBLIC_DAY,
    FATHERS_DAY_JUN,
    CANADA_DAY,
    US_INDEPENDENCE_DAY,
    BASTILLE_DAY,
    FATHERS_DAY_SEP,
    BRAZIL_INDEPENDENCE_DAY,
    GERMAN_UNITY_DAY,
    SPAIN_HISPANIC_DAY,
    HALLOWEEN,
    BONFIRE_NIGHT,
    REMEMBRANCE_DAY,
    US_THANKSGIVING,
    ST_ANDREWS_DAY,
    CHRISTMAS_DAY,
    // TODO(holidays-v3): country mapping + sensitive-holiday opt-out.
    //
    // The settings flow today shows all holidays to everyone — a Brazilian
    // user sees St Andrew's Day, an Australian sees Bastille Day. The Today
    // screen already resolves *naming* per country via
    // [HolidayTheme.bannerTextKeyByCountry] (Veterans Day vs. Remembrance
    // Day) — the next step is gating *visibility* the same way. Plan:
    //
    //   1. Tag every holiday with one or more ISO 3166-1 alpha-2 country
    //      codes (e.g. Canada Day → {CA}; St Patrick's → {IE}; Christmas →
    //      many; Anzac Day → {AU, NZ}; Remembrance Day → {GB, CA, AU, NZ,
    //      US}). Add a `countries: Set<String>` field on [HolidayTheme].
    //      The bannerTextKeyByCountry keys are a natural overlap.
    //   2. Add a "Countries" picker under Region (default: derive from the
    //      user's Region setting via [Region.toJavaLocale]'s country, then
    //      let them tick / untick). Only holidays whose `countries`
    //      intersect the user's chosen set surface in the holiday-toggle
    //      list, defaulting to on.
    //   3. Keep the existing per-holiday switch as a second layer — a
    //      *tactful* opt-out for individual days. Mother's Day, Father's
    //      Day, and the military-remembrance days can be painful for users
    //      who've lost someone. Frame the section copy something like:
    //      "Some of these days can be hard. Switch any of them off."
    //
    // TODO(holidays-v3): UK Mothering Sunday — 4th Sun of Lent, i.e. movable
    // and tied to Easter (Computus). The current [MOTHERS_DAY] entry uses
    // 2nd Sun of May which is correct for US/AU/CA/NZ but not UK/IE. Adding
    // a Computus implementation unlocks both Easter Sunday and Mothering
    // Sunday at the same time.
    //
    // TODO(holidays-v3): US Memorial Day (last Mon of May) — same monochrome
    // shape as [REMEMBRANCE_DAY], distinct date.
    //
    // TODO(holidays-v3): UK Remembrance Sunday — 2nd Sun of Nov, sits
    // alongside [REMEMBRANCE_DAY] on Nov 11 in the UK (one's the formal
    // observance, the other the day itself).
    //
    // TODO(holidays-v3): movable / lunisolar holidays (Easter, Lunar New Year,
    // Diwali, Hanukkah, Eid, Holi). Need either a Computus implementation for
    // Easter or per-year lookup tables for the others.
    //
    // TODO(holidays-v3): switch the [REMEMBRANCE_DAY] banner-name lookup
    // from [Region]-derived country to location-derived country once the
    // app's reverse-geocoding plumbing exposes a stable country code.
    // Region is the right *user-controlled* signal short-term; location is
    // the more accurate one once available. The country-filtering plan
    // above should follow the same signal source so naming and visibility
    // stay in lockstep.
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
 * existing auto-derive-from-fill logic kicks in, producing an unobtrusive
 * darker shade of the fill (the default for two-colour holidays where the
 * second colour lives on the opposite garment, not as a contrasting trim).
 * A non-empty stroke map paints an explicit contrasting accent — reserved
 * for the true tricolour holidays (US July 4, Bastille Day, Italy,
 * Germany, New Year's) where a third flag colour needs to appear on
 * every garment alongside the fill.
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
    /**
     * Optional per-country overrides for [bannerTextKey]. Keyed by ISO 3166-1
     * alpha-2 uppercase country code (e.g. "US", "GB", "AU"). When the user's
     * effective country matches a key, that override resolves instead of the
     * default. Used today for the [HolidayId.REMEMBRANCE_DAY] / Veterans Day
     * naming split on Nov 11 — most countries call it Remembrance Day, the US
     * calls it Veterans Day. Country resolution lives at the UI seam (see
     * [bannerTextKeyFor]) so this data carrier stays Region-agnostic.
     */
    val bannerTextKeyByCountry: Map<String, String> = emptyMap(),
)

/**
 * Resolves [HolidayTheme.bannerTextKey] honouring [HolidayTheme.bannerTextKeyByCountry].
 * [countryCode] is the user's effective country (typically from
 * `Region.toJavaLocale()?.country` falling back to `Locale.getDefault().country`).
 * Empty or null country falls through to the default key. Case-insensitive
 * matching — callers don't have to upper-case before calling.
 */
fun HolidayTheme.bannerTextKeyFor(countryCode: String?): String {
    if (countryCode.isNullOrBlank()) return bannerTextKey
    return bannerTextKeyByCountry[countryCode.uppercase()] ?: bannerTextKey
}

/**
 * The full v1 list of holidays with their date predicate, palette, and
 * banner copy. Catalog order is calendar order — also the resolver's
 * first-match precedence order if two ever clash (none do in v1).
 *
 * Palette philosophy: anchor on the holiday's flag / iconic colours, with
 * one solid colour across every top tier and the second solid colour
 * across every bottom tier — so any outfit pair on the day reads as two
 * colours (red top + green bottom for Christmas, white top + red bottom
 * for St George's). Solemn remembrance days (Anzac) use a single
 * monochrome colour across both tiers. The five true tricolour holidays
 * (US July 4, Bastille Day, Italy, Germany, New Year's) layer a third
 * accent colour on top via [HolidayTheme.topStrokeOverrides] / [bottomStrokeOverrides].
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

        // Jan 26 — Australia Day. Sporting green tops + gold bottoms.
        // Distinct from the actual flag colours (blue/red/white).
        HolidayDate.Fixed(Month.JANUARY, 26) to HolidayTheme(
            id = HolidayId.AUSTRALIA_DAY,
            displayNameKey = "holiday_name_australia_day",
            bannerTextKey = "holiday_banner_australia_day",
            emoji = "🦎", // 🦎 — a nod that doesn't lean on the flag
            topOverrides = topPaletteAll(AUS_GREEN),
            bottomOverrides = bottomPaletteAll(AUS_GOLD),
            bannerArgb = AUS_GREEN,
        ),

        // Feb 14 — Valentine's. Pink tops + red bottoms. The deep-red third
        // hue from v1 was dropped — pink and red alone read unambiguously
        // as Valentine's.
        HolidayDate.Fixed(Month.FEBRUARY, 14) to HolidayTheme(
            id = HolidayId.VALENTINES_DAY,
            displayNameKey = "holiday_name_valentines_day",
            bannerTextKey = "holiday_banner_valentines_day",
            emoji = "❤️", // ❤️
            topOverrides = topPaletteAll(VAL_PINK),
            bottomOverrides = bottomPaletteAll(VAL_RED),
            bannerArgb = VAL_RED,
        ),

        // Mar 1 — St David's Day. Daffodil yellow tops + leek-green bottoms.
        HolidayDate.Fixed(Month.MARCH, 1) to HolidayTheme(
            id = HolidayId.ST_DAVIDS_DAY,
            displayNameKey = "holiday_name_st_davids_day",
            bannerTextKey = "holiday_banner_st_davids_day",
            emoji = "🌼", // 🌼
            topOverrides = topPaletteAll(WALES_YELLOW),
            bottomOverrides = bottomPaletteAll(WALES_GREEN),
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

        // Apr 23 — St George's Day. White tops + red bottoms — the flag's
        // two halves.
        HolidayDate.Fixed(Month.APRIL, 23) to HolidayTheme(
            id = HolidayId.ST_GEORGES_DAY,
            displayNameKey = "holiday_name_st_georges_day",
            bannerTextKey = "holiday_banner_st_georges_day",
            emoji = "🏴‍☠️", // 🏴‍☠️ — closest single glyph
            topOverrides = topPaletteAll(ENGLAND_WHITE),
            bottomOverrides = bottomPaletteAll(ENGLAND_RED),
            bannerArgb = ENGLAND_RED,
        ),

        // Apr 25 — Anzac Day. Solemn day — uniform-evoking khaki across
        // every garment. Monochrome on purpose: a celebratory two-colour
        // palette would read wrong for a remembrance day. Same shape as
        // [REMEMBRANCE_DAY] below.
        HolidayDate.Fixed(Month.APRIL, 25) to HolidayTheme(
            id = HolidayId.ANZAC_DAY,
            displayNameKey = "holiday_name_anzac_day",
            bannerTextKey = "holiday_banner_anzac_day",
            emoji = "🔺", // 🔺 — abstract; avoids lighter / flower vibe
            topOverrides = topPaletteAll(ANZAC_KHAKI),
            bottomOverrides = bottomPaletteAll(ANZAC_KHAKI),
            bannerArgb = ANZAC_KHAKI,
        ),

        // 2nd Sunday of May — Mother's Day. Same date in the US, AU, CA,
        // NZ and most non-UK countries. UK / IE use Mothering Sunday
        // (4th Sun of Lent, movable) — TODO at the top of [HolidayId].
        // Rose-pink top + leaf-green bottom evokes a bouquet.
        HolidayDate.NthWeekday(Month.MAY, 2, DayOfWeek.SUNDAY) to HolidayTheme(
            id = HolidayId.MOTHERS_DAY,
            displayNameKey = "holiday_name_mothers_day",
            bannerTextKey = "holiday_banner_mothers_day",
            emoji = "💐", // 💐
            topOverrides = topPaletteAll(MOTHER_PINK),
            bottomOverrides = bottomPaletteAll(MOTHER_GREEN),
            bannerArgb = MOTHER_PINK,
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

        // 3rd Sunday of June — Father's Day (US / UK / CA / IE and most of
        // Europe). AU / NZ get the September entry below instead. Both are
        // on by default; users can disable whichever doesn't apply.
        // Navy-blue top + brown bottom is the stereotypical "dad" palette
        // — same brown as Thanksgiving.
        HolidayDate.NthWeekday(Month.JUNE, 3, DayOfWeek.SUNDAY) to HolidayTheme(
            id = HolidayId.FATHERS_DAY_JUN,
            displayNameKey = "holiday_name_fathers_day_jun",
            bannerTextKey = "holiday_banner_fathers_day_jun",
            emoji = "👔", // 👔
            topOverrides = topPaletteAll(FATHER_NAVY),
            bottomOverrides = bottomPaletteAll(FATHER_BROWN),
            bannerArgb = FATHER_NAVY,
        ),

        // Jul 1 — Canada Day. White tops + red bottoms — same flag-halves
        // pattern as St George's.
        HolidayDate.Fixed(Month.JULY, 1) to HolidayTheme(
            id = HolidayId.CANADA_DAY,
            displayNameKey = "holiday_name_canada_day",
            bannerTextKey = "holiday_banner_canada_day",
            emoji = "🇨🇦", // 🇨🇦
            topOverrides = topPaletteAll(CANADA_WHITE),
            bottomOverrides = bottomPaletteAll(CANADA_RED),
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

        // 1st Sunday of September — Father's Day (AU / NZ). See the June
        // entry above for the international one; both ship on-by-default
        // and users disable the one that doesn't apply.
        HolidayDate.NthWeekday(Month.SEPTEMBER, 1, DayOfWeek.SUNDAY) to HolidayTheme(
            id = HolidayId.FATHERS_DAY_SEP,
            displayNameKey = "holiday_name_fathers_day_sep",
            bannerTextKey = "holiday_banner_fathers_day_sep",
            emoji = "👔", // 👔
            topOverrides = topPaletteAll(FATHER_NAVY),
            bottomOverrides = bottomPaletteAll(FATHER_BROWN),
            bannerArgb = FATHER_NAVY,
        ),

        // Sep 7 — Brazil Independence Day. Green tops + yellow bottoms.
        HolidayDate.Fixed(Month.SEPTEMBER, 7) to HolidayTheme(
            id = HolidayId.BRAZIL_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_brazil_independence_day",
            bannerTextKey = "holiday_banner_brazil_independence_day",
            emoji = "🇧🇷", // 🇧🇷
            topOverrides = topPaletteAll(BRAZIL_GREEN),
            bottomOverrides = bottomPaletteAll(BRAZIL_YELLOW),
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

        // Oct 12 — Hispanic Day (Spain national day). Red tops + yellow
        // bottoms.
        HolidayDate.Fixed(Month.OCTOBER, 12) to HolidayTheme(
            id = HolidayId.SPAIN_HISPANIC_DAY,
            displayNameKey = "holiday_name_spain_hispanic_day",
            bannerTextKey = "holiday_banner_spain_hispanic_day",
            emoji = "🇪🇸", // 🇪🇸
            topOverrides = topPaletteAll(SPAIN_RED),
            bottomOverrides = bottomPaletteAll(SPAIN_YELLOW),
            bannerArgb = SPAIN_RED,
        ),

        // Oct 31 — Halloween. Pumpkin-orange tops + black bottoms. Purple
        // was originally a third hue but dropped to keep the palette focused
        // on the two colours that read unambiguously as Halloween.
        HolidayDate.Fixed(Month.OCTOBER, 31) to HolidayTheme(
            id = HolidayId.HALLOWEEN,
            displayNameKey = "holiday_name_halloween",
            bannerTextKey = "holiday_banner_halloween",
            emoji = "🎃", // 🎃
            topOverrides = topPaletteAll(HALLOWEEN_ORANGE),
            bottomOverrides = bottomPaletteAll(HALLOWEEN_BLACK),
            bannerArgb = HALLOWEEN_ORANGE,
        ),

        // Nov 5 — Bonfire Night. Orange-flame tops + smoke-red bottoms.
        HolidayDate.Fixed(Month.NOVEMBER, 5) to HolidayTheme(
            id = HolidayId.BONFIRE_NIGHT,
            displayNameKey = "holiday_name_bonfire_night",
            bannerTextKey = "holiday_banner_bonfire_night",
            emoji = "🎆", // 🎆
            topOverrides = topPaletteAll(BONFIRE_ORANGE),
            bottomOverrides = bottomPaletteAll(BONFIRE_RED),
            bannerArgb = BONFIRE_RED,
        ),

        // Nov 11 — Remembrance Day (US calls it Veterans Day). Solemn
        // monochrome khaki, same shape as Anzac. Banner text varies by
        // country: "Veterans Day" for US users, "Remembrance Day" everywhere
        // else. The country-aware lookup happens at the UI seam via
        // [bannerTextKeyFor]. UK Remembrance Sunday (2nd Sun of Nov) is a
        // TODO at the top of [HolidayId] — separate observance.
        HolidayDate.Fixed(Month.NOVEMBER, 11) to HolidayTheme(
            id = HolidayId.REMEMBRANCE_DAY,
            displayNameKey = "holiday_name_remembrance_day",
            bannerTextKey = "holiday_banner_remembrance_day",
            bannerTextKeyByCountry = mapOf("US" to "holiday_banner_us_veterans_day"),
            emoji = "🔺", // 🔺 — match Anzac's abstract glyph for visual continuity
            topOverrides = topPaletteAll(ANZAC_KHAKI),
            bottomOverrides = bottomPaletteAll(ANZAC_KHAKI),
            bannerArgb = ANZAC_KHAKI,
        ),

        // 4th Thursday of November — US Thanksgiving. Pumpkin-orange tops +
        // deep-autumn brown bottoms. Rust was a third hue in the v1
        // tricolour palette but dropped — pumpkin and brown alone read as
        // autumnal without the extra layer.
        HolidayDate.NthWeekday(Month.NOVEMBER, 4, DayOfWeek.THURSDAY) to HolidayTheme(
            id = HolidayId.US_THANKSGIVING,
            displayNameKey = "holiday_name_us_thanksgiving",
            bannerTextKey = "holiday_banner_us_thanksgiving",
            emoji = "🦃", // 🦃
            topOverrides = topPaletteAll(THANKS_PUMPKIN),
            bottomOverrides = bottomPaletteAll(THANKS_BROWN),
            bannerArgb = THANKS_BROWN,
        ),

        // Nov 30 — St Andrew's Day. Saltire blue tops + white bottoms.
        HolidayDate.Fixed(Month.NOVEMBER, 30) to HolidayTheme(
            id = HolidayId.ST_ANDREWS_DAY,
            displayNameKey = "holiday_name_st_andrews_day",
            bannerTextKey = "holiday_banner_st_andrews_day",
            emoji = "🏴󠁧󠁢󠁳󠁣󠁴󠁿", // 🏴󠁧󠁢󠁳󠁣󠁴󠁿
            topOverrides = topPaletteAll(SCOTLAND_BLUE),
            bottomOverrides = bottomPaletteAll(SCOTLAND_WHITE),
            bannerArgb = SCOTLAND_BLUE,
        ),

        // Dec 25 — Christmas Day. Pillarbox-red tops + holly-green bottoms.
        // The white-trim third hue was tried but read as too busy for the
        // holiday's iconic "just red and green" mental model.
        HolidayDate.Fixed(Month.DECEMBER, 25) to HolidayTheme(
            id = HolidayId.CHRISTMAS_DAY,
            displayNameKey = "holiday_name_christmas_day",
            bannerTextKey = "holiday_banner_christmas_day",
            emoji = "🎄", // 🎄
            topOverrides = topPaletteAll(XMAS_RED),
            bottomOverrides = bottomPaletteAll(XMAS_GREEN),
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

private const val ANZAC_KHAKI = 0xFF6D6748L

private const val MOTHER_PINK = 0xFFEC407AL
private const val MOTHER_GREEN = 0xFF66BB6AL

private const val FATHER_NAVY = 0xFF1A237EL
private const val FATHER_BROWN = 0xFF5D4037L

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
private const val THANKS_BROWN = 0xFF5D4037L

private const val SCOTLAND_BLUE = 0xFF005EB8L
private const val SCOTLAND_WHITE = 0xFFF5F5F5L

private const val XMAS_RED = 0xFFC62828L
private const val XMAS_GREEN = 0xFF2E7D32L
