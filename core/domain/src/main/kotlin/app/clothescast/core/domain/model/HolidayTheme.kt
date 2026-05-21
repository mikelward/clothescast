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
    EPIPHANY,
    JAPAN_COMING_OF_AGE_DAY,
    MLK_DAY,
    BURNS_NIGHT,
    AUSTRALIA_DAY,
    WAITANGI_DAY,
    VALENTINES_DAY,
    US_PRESIDENTS_DAY,
    ST_DAVIDS_DAY,
    KOREAN_INDEPENDENCE_MOVEMENT_DAY,
    ST_PATRICKS_DAY,
    UK_MOTHERING_SUNDAY,
    GOOD_FRIDAY,
    EASTER_SUNDAY,
    EASTER_MONDAY,
    ST_GEORGES_DAY,
    ANZAC_DAY,
    LABOUR_DAY,
    JAPAN_GREENERY_DAY,
    UK_EARLY_MAY_BANK_HOLIDAY,
    MOTHERS_DAY,
    CROATIA_STATEHOOD_DAY,
    US_MEMORIAL_DAY,
    UK_SPRING_BANK_HOLIDAY,
    TOWEL_DAY,
    ITALY_REPUBLIC_DAY,
    KOREAN_MEMORIAL_DAY,
    UK_KINGS_BIRTHDAY,
    JUNETEENTH,
    FATHERS_DAY_JUN,
    CANADA_DAY,
    US_INDEPENDENCE_DAY,
    BASTILLE_DAY,
    JAPAN_MARINE_DAY,
    CROATIA_VICTORY_DAY,
    KOREAN_LIBERATION_DAY,
    ASSUMPTION,
    UK_SUMMER_BANK_HOLIDAY,
    FATHERS_DAY_SEP,
    BRAZIL_INDEPENDENCE_DAY,
    TALK_LIKE_A_PIRATE_DAY,
    GERMAN_UNITY_DAY,
    CROATIA_INDEPENDENCE_DAY,
    KOREAN_HANGEUL_DAY,
    CANADIAN_THANKSGIVING,
    SPAIN_HISPANIC_DAY,
    AUSTRIA_NATIONAL_DAY,
    HALLOWEEN,
    ALL_SAINTS_DAY,
    JAPAN_CULTURE_DAY,
    BONFIRE_NIGHT,
    REMEMBRANCE_DAY,
    US_THANKSGIVING,
    ST_ANDREWS_DAY,
    IMMACULATE_CONCEPTION,
    CHRISTMAS_DAY,
    BOXING_DAY,
    // TODO(holidays-v4): UK Remembrance Sunday — 2nd Sun of Nov, sits
    // alongside [REMEMBRANCE_DAY] on Nov 11 in the UK (one's the formal
    // observance, the other the day itself).
    //
    // TODO(holidays-v4): Christian / Catholic religious bucket. The four
    // Easter-cluster entries ([GOOD_FRIDAY], [EASTER_SUNDAY],
    // [EASTER_MONDAY], plus Ascension / Pentecost / Whit Monday /
    // Corpus Christi when added) currently ride the [GLOBAL_COUNTRY]
    // bucket so they auto-fire for everyone who hasn't muted Global —
    // a pragmatic v1 punt that sidesteps the "which countries are
    // nominally Christian?" classification. Long term these belong in
    // a [HolidayCatalog.CHRISTIAN] (or split into CATHOLIC / PROTESTANT)
    // sentinel, with its own checkbox alongside Home / Current / Global
    // in the picker.
    //
    // TODO(holidays-v4): lunisolar holidays (Lunar New Year, Diwali,
    // Hanukkah, Eid, Holi). Need per-year lookup tables — none of them
    // has a closed-form Gregorian computus the way Easter does.
    //
    // TODO(holidays-v4): switch the [REMEMBRANCE_DAY] banner-name lookup
    // from [Region]-derived country to location-derived country once the
    // app's reverse-geocoding plumbing exposes a stable country code.
    // Region is the right *user-controlled* signal short-term; location is
    // the more accurate one once available.
    //
    // TODO(holidays-v4): same-date collisions — first-match in catalog
    // order currently wins, which means a UK user with St David's enabled
    // will never see Korean Independence Movement Day (same Mar 1 date),
    // and an Italian user can't see Liberation Day because Anzac (same
    // Apr 25 date) gets in first. Resolver should pick by country once
    // location-derived country lands, with first-match as the fallback.

    // Synthetic ids — kept at the end of the enum so older persisted
    // override sets (which serialise as enum names) continue to deserialise
    // without producing unknown-name errors. These never resolve through
    // the catalog; they're produced at runtime by [FestiveThemes] when a
    // calendar event arrives carrying [EventKind.PUBLIC_HOLIDAY] or
    // [EventKind.BIRTHDAY] and the user has opted in.
    GENERIC_PUBLIC_HOLIDAY,
    BIRTHDAY,
}

/**
 * How a holiday's date is computed from a [LocalDate]. Most are [Fixed] —
 * the same Month+day every year. [NthWeekday] / [LastWeekday] cover the
 * fixed-weekday movables (US Thanksgiving = 4th Thu of November, US
 * Memorial Day = last Mon of May, UK bank holidays). [EasterRelative]
 * covers Western Easter and the holidays anchored to it.
 */
sealed interface HolidayDate {
    fun matches(date: LocalDate): Boolean

    /**
     * Materialises the predicate into the actual [LocalDate] the holiday
     * falls on in [year]. Used by the Settings UI to sort entries within
     * each country bucket chronologically; the caller picks a
     * representative year (typically the current one) and the sort
     * comparison reads only month + day, so the relative ordering is
     * effectively year-agnostic for the vast majority of catalog
     * entries (movable holidays that straddle a neighbouring fixed
     * date are the only edge cases, and they're still locally
     * coherent).
     */
    fun dateIn(year: Int): LocalDate

    data class Fixed(val month: Month, val day: Int) : HolidayDate {
        override fun matches(date: LocalDate): Boolean =
            date.month == month && date.dayOfMonth == day

        override fun dateIn(year: Int): LocalDate = LocalDate.of(year, month, day)
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

        override fun dateIn(year: Int): LocalDate {
            val first = LocalDate.of(year, month, 1)
            val shift = (day.value - first.dayOfWeek.value + 7) % 7
            return first.plusDays(shift.toLong() + (nth - 1) * 7L)
        }
    }

    /**
     * The final occurrence of [day] in [month]. Used by US Memorial Day
     * (last Mon of May) and similar holidays anchored to the *end* of
     * the month rather than the start. Using [NthWeekday] with `nth=5`
     * would fail in any month where the weekday only occurs four times.
     */
    data class LastWeekday(val month: Month, val day: DayOfWeek) : HolidayDate {
        override fun matches(date: LocalDate): Boolean {
            if (date.month != month || date.dayOfWeek != day) return false
            // The last [day] of [month] is the one where seven days later
            // crosses into the next month — i.e. there's no further
            // occurrence of the same weekday inside [month].
            return date.plusDays(7).month != month
        }

        override fun dateIn(year: Int): LocalDate {
            val lastOfMonth = LocalDate.of(year, month, 1)
                .with(java.time.temporal.TemporalAdjusters.lastDayOfMonth())
            val shiftBack = (lastOfMonth.dayOfWeek.value - day.value + 7) % 7
            return lastOfMonth.minusDays(shiftBack.toLong())
        }
    }

    /**
     * Date relative to Western (Gregorian) Easter Sunday, expressed as
     * a signed day offset. Easter Sunday itself is `EasterRelative(0)`;
     * Good Friday is `EasterRelative(-2)`; Easter Monday is `EasterRelative(+1)`;
     * UK Mothering Sunday (4th Sun of Lent) is `EasterRelative(-21)`.
     *
     * Movable date, so the cached materialisation is year-keyed inside
     * [dateIn] rather than memoised on the data class. Orthodox Easter
     * (Julian Computus) is *not* covered by this variant — when we
     * add Orthodox holidays they'll get their own sibling type so the
     * Western / Orthodox split stays explicit at the predicate level.
     */
    data class EasterRelative(val daysOffset: Int) : HolidayDate {
        override fun matches(date: LocalDate): Boolean = date == dateIn(date.year)

        override fun dateIn(year: Int): LocalDate =
            easterSundayGregorian(year).plusDays(daysOffset.toLong())

        companion object {
            /**
             * Anonymous Gregorian algorithm (a.k.a. Meeus / Jones / Butcher
             * algorithm) for Western Easter Sunday. Pure integer arithmetic;
             * no exception handling needed inside the catalog year range
             * since the result always lands on a real calendar date between
             * March 22 and April 25 inclusive.
             */
            fun easterSundayGregorian(year: Int): LocalDate {
                val a = year % 19
                val b = year / 100
                val c = year % 100
                val d = b / 4
                val e = b % 4
                val f = (b + 8) / 25
                val g = (b - f + 1) / 3
                val h = (19 * a + b - d - g + 15) % 30
                val i = c / 4
                val k = c % 4
                val l = (32 + 2 * e + 2 * i - h - k) % 7
                val m = (a + 11 * h + 22 * l) / 451
                val rawMonth = h + l - 7 * m + 114
                val month = rawMonth / 31
                val day = rawMonth % 31 + 1
                return LocalDate.of(year, month, day)
            }
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
    /**
     * Which countries observe this holiday, as ISO 3166-1 alpha-2 uppercase
     * codes (e.g. "US", "GB"). Universal holidays (Christmas, New Year's,
     * Valentine's, Halloween) carry [HolidayCatalog.GLOBAL_COUNTRY] instead
     * of any specific country — the Global bucket in the Settings UI is its
     * own toggleable "country." The resolver only fires a theme when at
     * least one of its [countries] is in the user's effective enabled-
     * country set, so an Australian on the default "Auto" mode never sees
     * Bastille Day even with its per-holiday toggle on.
     *
     * A holiday observed in multiple countries lists them all (e.g.
     * Remembrance Day → AU/CA/GB/IE/NZ/US/FR). One holiday-name-per-country
     * is intentional: the same calendar date with different names lives as
     * separate [HolidayId] entries (e.g. KOREAN_INDEPENDENCE_MOVEMENT_DAY
     * vs ST_DAVIDS_DAY).
     */
    val countries: Set<String> = emptySet(),
    /**
     * `true` for themes synthesised at runtime from a `CalendarEvent` rather
     * than entries in [HolidayCatalog]. The Holiday settings picker filters
     * these out (they have no fixed date / country and shouldn't render as
     * user-toggleable rows). See [FestiveThemes].
     */
    val isSynthetic: Boolean = false,
    /**
     * When non-null, the banner uses this raw string instead of looking up
     * a localised resource by [bannerTextKey]. Set by synthetic themes
     * whose "display name" is a runtime value (e.g. a calendar event's
     * title — "Diwali", "Alice's birthday") rather than a resource id.
     */
    val displayTitleOverride: String? = null,
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

    /**
     * Sentinel "country" for holidays observed everywhere — Christmas, New
     * Year's, Valentine's, Halloween. Sits in [HolidayTheme.countries]
     * alongside ISO 3166-1 alpha-2 codes (uppercase by convention) and is
     * surfaced in Settings as its own toggleable bucket. Not an ISO code,
     * so it can't collide with one.
     */
    const val GLOBAL_COUNTRY: String = "GLOBAL"

    /**
     * Sentinel "country" for the Funny bucket — playful, non-national
     * observances like Talk Like a Pirate Day that aren't tied to any one
     * place. Like [GLOBAL_COUNTRY] it sits in [HolidayTheme.countries]
     * alongside real ISO codes and is surfaced in Settings as its own
     * toggleable bucket (on by default). Not an ISO code, so it can't
     * collide with one.
     */
    const val FUNNY: String = "FUNNY"

    /**
     * [HolidayId]s constructed at runtime by [FestiveThemes] from calendar
     * events rather than living in this catalog. The Holiday-settings
     * picker filters these out (no fixed date / country, nothing to toggle
     * per-holiday) and the catalog-completeness invariant in
     * [HolidayResolverTest] skips them too.
     */
    val SYNTHETIC_IDS: Set<HolidayId> = setOf(
        HolidayId.GENERIC_PUBLIC_HOLIDAY,
        HolidayId.BIRTHDAY,
    )

    /** Lookup for the (sub)set of UI surfaces that need a theme by id. */
    fun themeFor(id: HolidayId): HolidayTheme? = byId[id]

    /**
     * Distinct country codes across the catalog, including
     * [GLOBAL_COUNTRY]. Used by the Settings UI to render the per-country
     * toggle list and by the "All" mode of the holiday country filter as
     * its effective enabled-country set. Order is insertion order — every
     * catalog entry contributes its codes in catalog (calendar) order.
     */
    val allCountries: Set<String> by lazy {
        buildSet {
            all.forEach { (_, theme) -> addAll(theme.countries) }
        }
    }

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
            countries = setOf(GLOBAL_COUNTRY),
        ),

        // Jan 6 — Epiphany / Three Kings' Day. Catholic observance in
        // Austria, Spain, Italy, Croatia (and several German Länder, but
        // catalog stays at country granularity). Royal gold top + deep
        // purple bottom evokes the Magi's gifts.
        HolidayDate.Fixed(Month.JANUARY, 6) to HolidayTheme(
            id = HolidayId.EPIPHANY,
            displayNameKey = "holiday_name_epiphany",
            bannerTextKey = "holiday_banner_epiphany",
            emoji = "⭐", // ⭐ — Star of Bethlehem
            topOverrides = topPaletteAll(EPIPHANY_GOLD),
            bottomOverrides = bottomPaletteAll(EPIPHANY_PURPLE),
            bannerArgb = EPIPHANY_GOLD,
            countries = setOf("AT", "ES", "HR", "IT"),
        ),

        // 2nd Monday of January — Japan's Coming of Age Day (成人の日).
        // Celebratory; sakura-pink top + black bottom evokes a kimono.
        HolidayDate.NthWeekday(Month.JANUARY, 2, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.JAPAN_COMING_OF_AGE_DAY,
            displayNameKey = "holiday_name_japan_coming_of_age_day",
            bannerTextKey = "holiday_banner_japan_coming_of_age_day",
            emoji = "🌸", // 🌸
            topOverrides = topPaletteAll(JAPAN_SAKURA_PINK),
            bottomOverrides = bottomPaletteAll(JAPAN_BLACK),
            bannerArgb = JAPAN_SAKURA_PINK,
            countries = setOf("JP"),
        ),

        // 3rd Monday of January — US Martin Luther King Jr Day. Sober,
        // dignified palette; this is a civil-rights remembrance, not a
        // celebration. Charcoal monochrome with no contrasting trim,
        // matching the Anzac / Remembrance shape.
        HolidayDate.NthWeekday(Month.JANUARY, 3, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.MLK_DAY,
            displayNameKey = "holiday_name_mlk_day",
            bannerTextKey = "holiday_banner_mlk_day",
            emoji = "🕊", // 🕊 — dove of peace
            topOverrides = topPaletteAll(MLK_CHARCOAL),
            bottomOverrides = bottomPaletteAll(MLK_CHARCOAL),
            bannerArgb = MLK_CHARCOAL,
            countries = setOf("US"),
        ),

        // Jan 25 — Burns Night (Scotland). Tartan-evoking dark green top +
        // tartan red bottom — Black Watch / hunting-Stewart vibe.
        HolidayDate.Fixed(Month.JANUARY, 25) to HolidayTheme(
            id = HolidayId.BURNS_NIGHT,
            displayNameKey = "holiday_name_burns_night",
            bannerTextKey = "holiday_banner_burns_night",
            emoji = "🥃", // 🥃 — whisky tumbler
            topOverrides = topPaletteAll(BURNS_TARTAN_GREEN),
            bottomOverrides = bottomPaletteAll(BURNS_TARTAN_RED),
            bannerArgb = BURNS_TARTAN_GREEN,
            countries = setOf("GB"),
        ),

        // Jan 26 — Australia Day. Sporting green tops + gold bottoms.
        // Distinct from the actual flag colours (blue/red/white).
        HolidayDate.Fixed(Month.JANUARY, 26) to HolidayTheme(
            id = HolidayId.AUSTRALIA_DAY,
            displayNameKey = "holiday_name_australia_day",
            bannerTextKey = "holiday_banner_australia_day",
            emoji = "🇦🇺", // 🇦🇺
            topOverrides = topPaletteAll(AUS_GREEN),
            bottomOverrides = bottomPaletteAll(AUS_GOLD),
            bannerArgb = AUS_GREEN,
            countries = setOf("AU"),
        ),

        // Feb 6 — Waitangi Day (NZ). All Blacks-evoking palette: black tops
        // + silver-fern silver bottoms. Distinct from the actual NZ flag
        // (blue / red / white, indistinguishable from Australia / UK at a
        // glance) — sport colours are far more recognisably NZ.
        HolidayDate.Fixed(Month.FEBRUARY, 6) to HolidayTheme(
            id = HolidayId.WAITANGI_DAY,
            displayNameKey = "holiday_name_waitangi_day",
            bannerTextKey = "holiday_banner_waitangi_day",
            emoji = "🇳🇿", // 🇳🇿
            topOverrides = topPaletteAll(NZ_BLACK),
            bottomOverrides = bottomPaletteAll(NZ_SILVER),
            bannerArgb = NZ_BLACK,
            countries = setOf("NZ"),
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
            countries = setOf(GLOBAL_COUNTRY),
        ),

        // 3rd Monday of February — US Presidents' Day (officially Washington's
        // Birthday). Civic palette anchored on the flag's canton: navy-blue
        // tops + white bottoms, no contrasting stroke — auto-derived darker
        // shade reads as understated trim. Distinct from the full red-white-
        // blue tricolour reserved for [US_INDEPENDENCE_DAY], and from the
        // solemn monochromes of [MLK_DAY] / [US_MEMORIAL_DAY].
        HolidayDate.NthWeekday(Month.FEBRUARY, 3, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.US_PRESIDENTS_DAY,
            displayNameKey = "holiday_name_us_presidents_day",
            bannerTextKey = "holiday_banner_us_presidents_day",
            emoji = "🎩", // 🎩 — top hat, presidential
            topOverrides = topPaletteAll(USA_BLUE),
            bottomOverrides = bottomPaletteAll(USA_WHITE),
            bannerArgb = USA_BLUE,
            countries = setOf("US"),
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
            countries = setOf("GB"),
        ),

        // Mar 1 — Korean Independence Movement Day (삼일절). Red top + blue
        // bottom matches the Taegeuk halves on the South Korean flag.
        // Same date as St David's; the resolver's first-match rule
        // currently picks St David's for a user with both enabled — see
        // the same-date-collision TODO in [HolidayId].
        HolidayDate.Fixed(Month.MARCH, 1) to HolidayTheme(
            id = HolidayId.KOREAN_INDEPENDENCE_MOVEMENT_DAY,
            displayNameKey = "holiday_name_korean_independence_movement_day",
            bannerTextKey = "holiday_banner_korean_independence_movement_day",
            emoji = "🇰🇷", // 🇰🇷
            topOverrides = topPaletteAll(KOREA_RED),
            bottomOverrides = bottomPaletteAll(KOREA_BLUE),
            bannerArgb = KOREA_RED,
            countries = setOf("KR"),
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
            countries = setOf("IE", "GB"),
        ),

        // 4th Sunday of Lent (Easter − 21) — UK / Irish Mothering Sunday.
        // Tagged GB / IE only rather than GLOBAL_COUNTRY because the
        // existing [MOTHERS_DAY] entry (2nd Sun of May) covers
        // US / AU / CA / NZ — putting Mothering Sunday in the global
        // bucket would surface it to those users too and double up.
        // Soft-rose top + cream bottom keeps it visibly distinct from
        // Mother's Day's deeper pink / green bouquet palette.
        HolidayDate.EasterRelative(-21) to HolidayTheme(
            id = HolidayId.UK_MOTHERING_SUNDAY,
            displayNameKey = "holiday_name_uk_mothering_sunday",
            bannerTextKey = "holiday_banner_uk_mothering_sunday",
            emoji = "💐", // 💐 — shared with Mother's Day on purpose
            topOverrides = topPaletteAll(MOTHERING_ROSE),
            bottomOverrides = bottomPaletteAll(MOTHERING_CREAM),
            bannerArgb = MOTHERING_ROSE,
            countries = setOf("GB", "IE"),
        ),

        // Easter − 2 — Western Good Friday. Solemn, monochrome aubergine
        // (Passion-week liturgical purple). Same single-colour shape as
        // Anzac / MLK / US Memorial — a celebratory two-colour palette
        // would read wrong here. Tagged [GLOBAL_COUNTRY] as a v1 punt;
        // see the Christian-bucket TODO at the top of [HolidayId].
        HolidayDate.EasterRelative(-2) to HolidayTheme(
            id = HolidayId.GOOD_FRIDAY,
            displayNameKey = "holiday_name_good_friday",
            bannerTextKey = "holiday_banner_good_friday",
            emoji = "✝", // ✝
            topOverrides = topPaletteAll(GOOD_FRIDAY_AUBERGINE),
            bottomOverrides = bottomPaletteAll(GOOD_FRIDAY_AUBERGINE),
            bannerArgb = GOOD_FRIDAY_AUBERGINE,
            countries = setOf(GLOBAL_COUNTRY),
        ),

        // Easter Sunday. Pastel-lemon top + pastel-mint bottom — egg-
        // decorating spring-renewal palette, no flag association.
        // Tagged [GLOBAL_COUNTRY] for the same v1 reason as Good Friday.
        HolidayDate.EasterRelative(0) to HolidayTheme(
            id = HolidayId.EASTER_SUNDAY,
            displayNameKey = "holiday_name_easter_sunday",
            bannerTextKey = "holiday_banner_easter_sunday",
            emoji = "🥚", // 🥚 — Easter egg
            topOverrides = topPaletteAll(EASTER_LEMON),
            bottomOverrides = bottomPaletteAll(EASTER_MINT),
            bannerArgb = EASTER_LEMON,
            countries = setOf(GLOBAL_COUNTRY),
        ),

        // Easter Monday. Same pastel palette as Easter Sunday so the
        // Sun→Mon weekend reads as a continuous theme rather than two
        // different days. Tagged [GLOBAL_COUNTRY] like the others above.
        HolidayDate.EasterRelative(1) to HolidayTheme(
            id = HolidayId.EASTER_MONDAY,
            displayNameKey = "holiday_name_easter_monday",
            bannerTextKey = "holiday_banner_easter_monday",
            emoji = "🐰", // 🐰
            topOverrides = topPaletteAll(EASTER_LEMON),
            bottomOverrides = bottomPaletteAll(EASTER_MINT),
            bannerArgb = EASTER_LEMON,
            countries = setOf(GLOBAL_COUNTRY),
        ),

        // Apr 23 — St George's Day. White tops + red bottoms — the flag's
        // two halves.
        HolidayDate.Fixed(Month.APRIL, 23) to HolidayTheme(
            id = HolidayId.ST_GEORGES_DAY,
            displayNameKey = "holiday_name_st_georges_day",
            bannerTextKey = "holiday_banner_st_georges_day",
            emoji = "🏴󠁧󠁢󠁥󠁮󠁧󠁿", // 🏴󠁧󠁢󠁥󠁮󠁧󠁿 — England subdivision flag
            topOverrides = topPaletteAll(ENGLAND_WHITE),
            bottomOverrides = bottomPaletteAll(ENGLAND_RED),
            bannerArgb = ENGLAND_RED,
            countries = setOf("GB"),
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
            countries = setOf("AU", "NZ"),
        ),

        // May 1 — Labour Day / International Workers' Day. Public holiday
        // across most of continental Europe. Solid labour-movement red
        // top + workwear-black bottom; the carnation emoji nods to the
        // traditional May Day buttonhole.
        HolidayDate.Fixed(Month.MAY, 1) to HolidayTheme(
            id = HolidayId.LABOUR_DAY,
            displayNameKey = "holiday_name_labour_day",
            bannerTextKey = "holiday_banner_labour_day",
            emoji = "🌹", // 🌹 — red carnation, the May Day symbol
            topOverrides = topPaletteAll(LABOUR_RED),
            bottomOverrides = bottomPaletteAll(LABOUR_BLACK),
            bannerArgb = LABOUR_RED,
            countries = setOf("AT", "DE", "ES", "FR", "HR", "IT"),
        ),

        // May 4 — Japan's Greenery Day (みどりの日). Literally green-themed
        // — green tops + earth-brown bottoms (nature / wood). Listed
        // before [UK_EARLY_MAY_BANK_HOLIDAY] so a multi-country user
        // resolves Greenery on May 4 in years where May 4 is also the
        // 1st Monday of May (e.g. 2026); single-country JP / GB users
        // are unaffected since the country tags don't overlap.
        HolidayDate.Fixed(Month.MAY, 4) to HolidayTheme(
            id = HolidayId.JAPAN_GREENERY_DAY,
            displayNameKey = "holiday_name_japan_greenery_day",
            bannerTextKey = "holiday_banner_japan_greenery_day",
            emoji = "🌿", // 🌿
            topOverrides = topPaletteAll(GREENERY_GREEN),
            bottomOverrides = bottomPaletteAll(GREENERY_BROWN),
            bannerArgb = GREENERY_GREEN,
            countries = setOf("JP"),
        ),

        // 1st Monday of May — UK Early May Bank Holiday. Union Jack
        // tricolour: red top + blue bottom with white as the unifying
        // accent stroke. Same option-3 stroke pattern as the existing
        // flag tricolours (US July 4, Bastille, Germany, Italy).
        HolidayDate.NthWeekday(Month.MAY, 1, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.UK_EARLY_MAY_BANK_HOLIDAY,
            displayNameKey = "holiday_name_uk_early_may_bank_holiday",
            bannerTextKey = "holiday_banner_uk_bank_holiday",
            emoji = "🇬🇧", // 🇬🇧
            topOverrides = topPaletteAll(UK_RED),
            bottomOverrides = bottomPaletteAll(UK_BLUE),
            topStrokeOverrides = topStrokeAll(UK_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(UK_WHITE),
            bannerArgb = UK_RED,
            countries = setOf("GB"),
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
            countries = setOf("US", "AU", "CA", "NZ"),
        ),

        // May 30 — Croatia Statehood Day. True flag tricolour
        // (red/white/blue, top-middle-bottom). Same option-3 stroke pattern
        // as US July 4 / Bastille: red tops + blue bottoms with white as
        // the unifying accent across both.
        HolidayDate.Fixed(Month.MAY, 30) to HolidayTheme(
            id = HolidayId.CROATIA_STATEHOOD_DAY,
            displayNameKey = "holiday_name_croatia_statehood_day",
            bannerTextKey = "holiday_banner_croatia_statehood_day",
            emoji = "🇭🇷", // 🇭🇷
            topOverrides = topPaletteAll(CROATIA_RED),
            bottomOverrides = bottomPaletteAll(CROATIA_BLUE),
            topStrokeOverrides = topStrokeAll(CROATIA_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(CROATIA_WHITE),
            bannerArgb = CROATIA_RED,
            countries = setOf("HR"),
        ),

        // Last Monday of May — US Memorial Day. Solemn monochrome khaki,
        // same shape as Anzac. Different from US Veterans Day (Nov 11),
        // which is the Remembrance Day banner with a US country override.
        // Same-date collision with [UK_SPRING_BANK_HOLIDAY] below; the
        // catalog-order tiebreak means a US user resolves Memorial Day
        // (US tag), a GB user resolves Spring BH (GB tag), and a
        // multi-country user (US+GB) falls to US Memorial first.
        HolidayDate.LastWeekday(Month.MAY, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.US_MEMORIAL_DAY,
            displayNameKey = "holiday_name_us_memorial_day",
            bannerTextKey = "holiday_banner_us_memorial_day",
            emoji = "🔺", // 🔺 — match Anzac / Remembrance for visual continuity
            topOverrides = topPaletteAll(ANZAC_KHAKI),
            bottomOverrides = bottomPaletteAll(ANZAC_KHAKI),
            bannerArgb = ANZAC_KHAKI,
            countries = setOf("US"),
        ),

        // Last Monday of May — UK Spring Bank Holiday. Union Jack
        // tricolour, same as the other UK bank holidays.
        HolidayDate.LastWeekday(Month.MAY, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.UK_SPRING_BANK_HOLIDAY,
            displayNameKey = "holiday_name_uk_spring_bank_holiday",
            bannerTextKey = "holiday_banner_uk_bank_holiday",
            emoji = "🇬🇧", // 🇬🇧
            topOverrides = topPaletteAll(UK_RED),
            bottomOverrides = bottomPaletteAll(UK_BLUE),
            topStrokeOverrides = topStrokeAll(UK_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(UK_WHITE),
            bannerArgb = UK_RED,
            countries = setOf("GB"),
        ),

        // May 25 — Towel Day. A playful, non-national observance, so it
        // rides the [FUNNY] bucket. Deliberately placed *after* the two
        // last-Monday-of-May
        // national holidays above: in years where the last Monday lands on
        // May 25, catalog-order precedence lets a US user still resolve
        // Memorial Day and a GB user Spring Bank Holiday, while everyone
        // else (and Funny-only) gets Towel Day. Beach-towel teal top +
        // sandy bottom.
        HolidayDate.Fixed(Month.MAY, 25) to HolidayTheme(
            id = HolidayId.TOWEL_DAY,
            displayNameKey = "holiday_name_towel_day",
            bannerTextKey = "holiday_banner_towel_day",
            emoji = "🪐", // 🪐
            topOverrides = topPaletteAll(TOWEL_TEAL),
            bottomOverrides = bottomPaletteAll(TOWEL_SAND),
            bannerArgb = TOWEL_TEAL,
            countries = setOf(FUNNY),
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
            countries = setOf("IT"),
        ),

        // Jun 6 — South Korean Memorial Day (현충일). Same solemn shape as
        // Anzac / US Memorial Day — monochrome khaki across all garments.
        HolidayDate.Fixed(Month.JUNE, 6) to HolidayTheme(
            id = HolidayId.KOREAN_MEMORIAL_DAY,
            displayNameKey = "holiday_name_korean_memorial_day",
            bannerTextKey = "holiday_banner_korean_memorial_day",
            emoji = "🔺", // 🔺 — match Anzac / US Memorial for visual continuity
            topOverrides = topPaletteAll(ANZAC_KHAKI),
            bottomOverrides = bottomPaletteAll(ANZAC_KHAKI),
            bannerArgb = ANZAC_KHAKI,
            countries = setOf("KR"),
        ),

        // 2nd Saturday of June — UK King's Official Birthday (Trooping
        // the Colour). Not a UK bank holiday per se, but the date is
        // observed annually. Same Union Jack palette as the bank holidays.
        HolidayDate.NthWeekday(Month.JUNE, 2, DayOfWeek.SATURDAY) to HolidayTheme(
            id = HolidayId.UK_KINGS_BIRTHDAY,
            displayNameKey = "holiday_name_uk_kings_birthday",
            bannerTextKey = "holiday_banner_uk_kings_birthday",
            emoji = "🇬🇧", // 🇬🇧
            topOverrides = topPaletteAll(UK_RED),
            bottomOverrides = bottomPaletteAll(UK_BLUE),
            topStrokeOverrides = topStrokeAll(UK_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(UK_WHITE),
            bannerArgb = UK_RED,
            countries = setOf("GB"),
        ),

        // Jun 19 — Juneteenth (US). Pan-African flag colours: red top +
        // green bottom with black as the unifying accent stroke. True
        // tricolour, so the option-3 stroke pattern applies.
        HolidayDate.Fixed(Month.JUNE, 19) to HolidayTheme(
            id = HolidayId.JUNETEENTH,
            displayNameKey = "holiday_name_juneteenth",
            bannerTextKey = "holiday_banner_juneteenth",
            emoji = "✊", // ✊ — raised fist
            topOverrides = topPaletteAll(PAN_AFRICAN_RED),
            bottomOverrides = bottomPaletteAll(PAN_AFRICAN_GREEN),
            topStrokeOverrides = topStrokeAll(PAN_AFRICAN_BLACK),
            bottomStrokeOverrides = bottomStrokeAll(PAN_AFRICAN_BLACK),
            bannerArgb = PAN_AFRICAN_RED,
            countries = setOf("US"),
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
            countries = setOf("US", "CA", "IE", "GB"),
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
            countries = setOf("CA"),
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
            countries = setOf("US"),
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
            countries = setOf("FR"),
        ),

        // 3rd Monday of July — Japan's Marine Day (海の日). Ocean blue tops
        // + sand-beige bottoms.
        HolidayDate.NthWeekday(Month.JULY, 3, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.JAPAN_MARINE_DAY,
            displayNameKey = "holiday_name_japan_marine_day",
            bannerTextKey = "holiday_banner_japan_marine_day",
            emoji = "🌊", // 🌊
            topOverrides = topPaletteAll(MARINE_BLUE),
            bottomOverrides = bottomPaletteAll(MARINE_SAND),
            bannerArgb = MARINE_BLUE,
            countries = setOf("JP"),
        ),

        // Aug 5 — Croatia's Victory and Homeland Thanksgiving Day
        // ("Dan pobjede i domovinske zahvalnosti"). Same flag tricolour
        // pattern as Croatia Statehood — red top + blue bottom with white
        // unifying stroke.
        HolidayDate.Fixed(Month.AUGUST, 5) to HolidayTheme(
            id = HolidayId.CROATIA_VICTORY_DAY,
            displayNameKey = "holiday_name_croatia_victory_day",
            bannerTextKey = "holiday_banner_croatia_victory_day",
            emoji = "🇭🇷", // 🇭🇷
            topOverrides = topPaletteAll(CROATIA_RED),
            bottomOverrides = bottomPaletteAll(CROATIA_BLUE),
            topStrokeOverrides = topStrokeAll(CROATIA_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(CROATIA_WHITE),
            bannerArgb = CROATIA_RED,
            countries = setOf("HR"),
        ),

        // Aug 15 — South Korean Liberation Day (광복절). Mirror image of
        // Korean Independence Movement Day above — blue top + red bottom —
        // so the two Korean celebratory holidays read as visibly distinct.
        // Same-date collision with Catholic [ASSUMPTION] below; first-match
        // catalog order means a KR user with default settings still
        // gets Liberation Day, while non-KR Catholic-country users see
        // Assumption since their country tags miss this entry.
        HolidayDate.Fixed(Month.AUGUST, 15) to HolidayTheme(
            id = HolidayId.KOREAN_LIBERATION_DAY,
            displayNameKey = "holiday_name_korean_liberation_day",
            bannerTextKey = "holiday_banner_korean_liberation_day",
            emoji = "🇰🇷", // 🇰🇷
            topOverrides = topPaletteAll(KOREA_BLUE),
            bottomOverrides = bottomPaletteAll(KOREA_RED),
            bannerArgb = KOREA_BLUE,
            countries = setOf("KR"),
        ),

        // Aug 15 — Assumption of Mary. Catholic high feast observed as a
        // public holiday in AT, ES, FR, HR, IT. Marian white top + sky
        // blue bottom. Same calendar date as Korean Liberation Day above;
        // catalog order means KR users see Liberation, non-KR Catholic-
        // country users see Assumption (no country overlap).
        HolidayDate.Fixed(Month.AUGUST, 15) to HolidayTheme(
            id = HolidayId.ASSUMPTION,
            displayNameKey = "holiday_name_assumption",
            bannerTextKey = "holiday_banner_assumption",
            emoji = "⛪", // ⛪ — church
            topOverrides = topPaletteAll(MARIAN_WHITE),
            bottomOverrides = bottomPaletteAll(MARIAN_BLUE),
            bannerArgb = MARIAN_BLUE,
            countries = setOf("AT", "ES", "FR", "HR", "IT"),
        ),

        // Last Monday of August — UK Summer Bank Holiday. Union Jack
        // tricolour, same as the other UK bank holidays.
        HolidayDate.LastWeekday(Month.AUGUST, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.UK_SUMMER_BANK_HOLIDAY,
            displayNameKey = "holiday_name_uk_summer_bank_holiday",
            bannerTextKey = "holiday_banner_uk_bank_holiday",
            emoji = "🇬🇧", // 🇬🇧
            topOverrides = topPaletteAll(UK_RED),
            bottomOverrides = bottomPaletteAll(UK_BLUE),
            topStrokeOverrides = topStrokeAll(UK_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(UK_WHITE),
            bannerArgb = UK_RED,
            countries = setOf("GB"),
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
            countries = setOf("AU", "NZ"),
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
            countries = setOf("BR"),
        ),

        // Sep 19 — Talk Like a Pirate Day. A playful, non-national
        // observance, so it rides the [FUNNY] bucket rather than any ISO
        // country. White tops + black bottoms (Jolly Roger).
        HolidayDate.Fixed(Month.SEPTEMBER, 19) to HolidayTheme(
            id = HolidayId.TALK_LIKE_A_PIRATE_DAY,
            displayNameKey = "holiday_name_talk_like_a_pirate_day",
            bannerTextKey = "holiday_banner_talk_like_a_pirate_day",
            emoji = "🦜", // 🦜
            topOverrides = topPaletteAll(PIRATE_WHITE),
            bottomOverrides = bottomPaletteAll(PIRATE_BLACK),
            bannerArgb = PIRATE_BLACK,
            countries = setOf(FUNNY),
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
            countries = setOf("DE"),
        ),

        // Oct 8 — Croatia's Independence Day ("Dan neovisnosti"). Same
        // flag tricolour pattern as the other Croatia entries.
        HolidayDate.Fixed(Month.OCTOBER, 8) to HolidayTheme(
            id = HolidayId.CROATIA_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_croatia_independence_day",
            bannerTextKey = "holiday_banner_croatia_independence_day",
            emoji = "🇭🇷", // 🇭🇷
            topOverrides = topPaletteAll(CROATIA_RED),
            bottomOverrides = bottomPaletteAll(CROATIA_BLUE),
            topStrokeOverrides = topStrokeAll(CROATIA_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(CROATIA_WHITE),
            bannerArgb = CROATIA_RED,
            countries = setOf("HR"),
        ),

        // Oct 9 — South Korean Hangeul Day (한글날), celebrating the Korean
        // alphabet. Blue top + white bottom evokes ink-on-page.
        HolidayDate.Fixed(Month.OCTOBER, 9) to HolidayTheme(
            id = HolidayId.KOREAN_HANGEUL_DAY,
            displayNameKey = "holiday_name_korean_hangeul_day",
            bannerTextKey = "holiday_banner_korean_hangeul_day",
            emoji = "📜", // 📜 — scroll, evoking writing
            topOverrides = topPaletteAll(KOREA_BLUE),
            bottomOverrides = bottomPaletteAll(KOREA_WHITE),
            bannerArgb = KOREA_BLUE,
            countries = setOf("KR"),
        ),

        // 2nd Monday of October — Canadian Thanksgiving. Same autumn
        // palette as US Thanksgiving (pumpkin top + brown bottom) but a
        // distinct date. Can land on Oct 12 in some years and collide
        // with Hispanic Day — first-match in catalog wins, see TODO.
        HolidayDate.NthWeekday(Month.OCTOBER, 2, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.CANADIAN_THANKSGIVING,
            displayNameKey = "holiday_name_canadian_thanksgiving",
            bannerTextKey = "holiday_banner_canadian_thanksgiving",
            emoji = "🦃", // 🦃 — match US Thanksgiving
            topOverrides = topPaletteAll(THANKS_PUMPKIN),
            bottomOverrides = bottomPaletteAll(THANKS_BROWN),
            bannerArgb = THANKS_BROWN,
            countries = setOf("CA"),
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
            countries = setOf("ES"),
        ),

        // Oct 26 — Austrian National Day (Nationalfeiertag), marking the
        // 1955 declaration of permanent neutrality. Flag-tricolour pattern:
        // red top + red bottom with white as the unifying stroke across
        // both, matching the red-white-red horizontal flag.
        HolidayDate.Fixed(Month.OCTOBER, 26) to HolidayTheme(
            id = HolidayId.AUSTRIA_NATIONAL_DAY,
            displayNameKey = "holiday_name_austria_national_day",
            bannerTextKey = "holiday_banner_austria_national_day",
            emoji = "🇦🇹", // 🇦🇹
            topOverrides = topPaletteAll(AUSTRIA_RED),
            bottomOverrides = bottomPaletteAll(AUSTRIA_RED),
            topStrokeOverrides = topStrokeAll(AUSTRIA_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(AUSTRIA_WHITE),
            bannerArgb = AUSTRIA_RED,
            countries = setOf("AT"),
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
            countries = setOf(GLOBAL_COUNTRY),
        ),

        // Nov 1 — All Saints' Day. Catholic public holiday in AT, DE
        // (some Länder), ES, FR, HR, IT. White top + charcoal bottom keeps
        // the reverent-but-not-celebratory feel; same shape as the
        // remembrance entries below without their stark monochrome.
        HolidayDate.Fixed(Month.NOVEMBER, 1) to HolidayTheme(
            id = HolidayId.ALL_SAINTS_DAY,
            displayNameKey = "holiday_name_all_saints_day",
            bannerTextKey = "holiday_banner_all_saints_day",
            emoji = "🕯", // 🕯 — candle
            topOverrides = topPaletteAll(SAINTS_WHITE),
            bottomOverrides = bottomPaletteAll(SAINTS_CHARCOAL),
            bannerArgb = SAINTS_CHARCOAL,
            countries = setOf("AT", "DE", "ES", "FR", "HR", "IT"),
        ),

        // Nov 3 — Japan's Culture Day (文化の日). Japan flag colours:
        // white top + red sun-disc bottom.
        HolidayDate.Fixed(Month.NOVEMBER, 3) to HolidayTheme(
            id = HolidayId.JAPAN_CULTURE_DAY,
            displayNameKey = "holiday_name_japan_culture_day",
            bannerTextKey = "holiday_banner_japan_culture_day",
            emoji = "⛩", // ⛩ — torii gate
            topOverrides = topPaletteAll(JAPAN_WHITE),
            bottomOverrides = bottomPaletteAll(JAPAN_RED),
            bannerArgb = JAPAN_RED,
            countries = setOf("JP"),
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
            countries = setOf("GB"),
        ),

        // Nov 11 — Remembrance Day (US calls it Veterans Day, FR calls it
        // Armistice Day / Jour de l'Armistice). Solemn monochrome khaki,
        // same shape as Anzac. Banner text varies by country via the
        // country-keyed override map. UK Remembrance Sunday (2nd Sun of Nov)
        // is a TODO at the top of [HolidayId] — separate observance.
        HolidayDate.Fixed(Month.NOVEMBER, 11) to HolidayTheme(
            id = HolidayId.REMEMBRANCE_DAY,
            displayNameKey = "holiday_name_remembrance_day",
            bannerTextKey = "holiday_banner_remembrance_day",
            bannerTextKeyByCountry = mapOf(
                "US" to "holiday_banner_us_veterans_day",
                "FR" to "holiday_banner_fr_armistice_day",
            ),
            emoji = "🔺", // 🔺 — match Anzac's abstract glyph for visual continuity
            topOverrides = topPaletteAll(ANZAC_KHAKI),
            bottomOverrides = bottomPaletteAll(ANZAC_KHAKI),
            bannerArgb = ANZAC_KHAKI,
            countries = setOf("AU", "CA", "GB", "IE", "NZ", "US", "FR"),
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
            countries = setOf("US"),
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
            countries = setOf("GB"),
        ),

        // Dec 8 — Immaculate Conception. Catholic high feast (AT, ES, IT).
        // Marian palette shared with [ASSUMPTION] — white top + Marian
        // blue bottom.
        HolidayDate.Fixed(Month.DECEMBER, 8) to HolidayTheme(
            id = HolidayId.IMMACULATE_CONCEPTION,
            displayNameKey = "holiday_name_immaculate_conception",
            bannerTextKey = "holiday_banner_immaculate_conception",
            emoji = "🕊", // 🕊 — dove
            topOverrides = topPaletteAll(MARIAN_WHITE),
            bottomOverrides = bottomPaletteAll(MARIAN_BLUE),
            bannerArgb = MARIAN_BLUE,
            countries = setOf("AT", "ES", "IT"),
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
            countries = setOf(GLOBAL_COUNTRY),
        ),

        // Dec 26 — Boxing Day (UK / AU / NZ / CA / IE and most of the
        // Commonwealth). Extends the Christmas palette one extra day —
        // same red + green so a user opening the app the day after
        // Christmas still sees a festive theme.
        HolidayDate.Fixed(Month.DECEMBER, 26) to HolidayTheme(
            id = HolidayId.BOXING_DAY,
            displayNameKey = "holiday_name_boxing_day",
            bannerTextKey = "holiday_banner_boxing_day",
            emoji = "🎁", // 🎁
            topOverrides = topPaletteAll(XMAS_GREEN),
            bottomOverrides = bottomPaletteAll(XMAS_RED),
            bannerArgb = XMAS_GREEN,
            countries = setOf("GB", "AU", "NZ", "CA", "IE"),
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

// Japan's Coming of Age Day — sakura pink + black kimono.
private const val JAPAN_SAKURA_PINK = 0xFFF8BBD0L
private const val JAPAN_BLACK = 0xFF1A1A1AL

// US Martin Luther King Jr Day — solemn charcoal, monochrome by design.
private const val MLK_CHARCOAL = 0xFF424242L

// Burns Night (Scotland) — Black Watch / hunting-Stewart tartan vibe.
private const val BURNS_TARTAN_GREEN = 0xFF1B5E20L
private const val BURNS_TARTAN_RED = 0xFFB71C1CL

// Waitangi Day (NZ) — All Blacks black + silver-fern silver.
private const val NZ_BLACK = 0xFF1A1A1AL
private const val NZ_SILVER = 0xFFBDBDBDL

// Korean flag — Taegeuk red + blue + white (the flag's field).
private const val KOREA_RED = 0xFFCD2E3AL
private const val KOREA_BLUE = 0xFF0047A0L
private const val KOREA_WHITE = 0xFFF5F5F5L

// Japan's Greenery Day — leafy fresh green + earth brown.
private const val GREENERY_GREEN = 0xFF388E3CL
private const val GREENERY_BROWN = 0xFF6D4C41L

// Croatia flag tricolour.
private const val CROATIA_RED = 0xFFE71D36L
private const val CROATIA_WHITE = 0xFFF5F5F5L
private const val CROATIA_BLUE = 0xFF171796L

// Pan-African flag (red / black / green) — Juneteenth.
private const val PAN_AFRICAN_RED = 0xFFCD0000L
private const val PAN_AFRICAN_BLACK = 0xFF1A1A1AL
private const val PAN_AFRICAN_GREEN = 0xFF006B3FL

// Japan Marine Day — deep ocean blue + sandy beige beach.
private const val MARINE_BLUE = 0xFF01579BL
private const val MARINE_SAND = 0xFFD7CCC8L

// Japan Culture Day — Hinomaru white + red sun-disc.
private const val JAPAN_WHITE = 0xFFF5F5F5L
private const val JAPAN_RED = 0xFFBC002DL

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

private const val PIRATE_WHITE = 0xFFF5F5F5L
private const val PIRATE_BLACK = 0xFF1A1A1AL

private const val TOWEL_TEAL = 0xFF00B3A4L
private const val TOWEL_SAND = 0xFFE6C58CL

private const val BONFIRE_ORANGE = 0xFFEF6C00L
private const val BONFIRE_RED = 0xFFC62828L

private const val THANKS_PUMPKIN = 0xFFEF6C00L
private const val THANKS_BROWN = 0xFF5D4037L

private const val SCOTLAND_BLUE = 0xFF005EB8L
private const val SCOTLAND_WHITE = 0xFFF5F5F5L

private const val XMAS_RED = 0xFFC62828L
private const val XMAS_GREEN = 0xFF2E7D32L

// Austrian flag — red-white-red horizontal tricolour. Red is the
// official #ED2939 shade rather than reusing Croatia's slightly bluer
// red so AT and HR themes remain visibly distinct.
private const val AUSTRIA_RED = 0xFFED2939L
private const val AUSTRIA_WHITE = 0xFFF5F5F5L

// Union Jack tricolour — shared by the UK bank holidays and the
// King's Birthday. Pantone-186 red + Pantone-280 blue (the flag's
// official shades) with an off-white that matches the catalog's
// other near-whites.
private const val UK_RED = 0xFFC8102EL
private const val UK_BLUE = 0xFF012169L
private const val UK_WHITE = 0xFFF5F5F5L

// Labour Day / International Workers' Day — universal labour-movement
// red, with a workwear black for the bottom tier.
private const val LABOUR_RED = 0xFFD32F2FL
private const val LABOUR_BLACK = 0xFF212121L

// Epiphany — Magi gold + royal-robe purple.
private const val EPIPHANY_GOLD = 0xFFD4AF37L
private const val EPIPHANY_PURPLE = 0xFF4A148CL

// Marian palette — shared by Assumption and Immaculate Conception so
// the Catholic-Marian feasts read as a continuous visual cluster.
// White Marian tunic + Marian-blue mantle.
private const val MARIAN_WHITE = 0xFFF5F5F5L
private const val MARIAN_BLUE = 0xFF1976D2L

// All Saints' Day — reverent without the stark monochrome of Anzac /
// Memorial Day. White vestment top + charcoal bottom.
private const val SAINTS_WHITE = 0xFFF5F5F5L
private const val SAINTS_CHARCOAL = 0xFF424242L

// Easter Sunday + Easter Monday — pastel egg-decorating palette, no
// flag association. Shared by both days so the Sun→Mon weekend reads
// as one theme.
private const val EASTER_LEMON = 0xFFFFF59DL
private const val EASTER_MINT = 0xFFB5E6C9L

// Good Friday — solemn Passion-week liturgical aubergine. Monochrome
// across both tiers, same shape as Anzac / Memorial Day.
private const val GOOD_FRIDAY_AUBERGINE = 0xFF4A148CL

// UK / IE Mothering Sunday — soft rose top + cream bottom. Lighter
// than the [MOTHER_PINK]/[MOTHER_GREEN] palette so the same emoji
// (💐) reads as a different occasion on a different date.
private const val MOTHERING_ROSE = 0xFFF8BBD0L
private const val MOTHERING_CREAM = 0xFFFFF8E1L

// Synthetic themes used by [FestiveThemes] when the user has opted into
// calendar-sourced theming and a row arrives carrying [EventKind.PUBLIC_HOLIDAY]
// or [EventKind.BIRTHDAY]. Generic festive gold/purple for a holiday name we
// don't recognise (Diwali, Eid, Lunar New Year — catalog gaps), and a confetti
// yellow/magenta for a detected birthday — gender-neutral, maximum party pop,
// and visually distinct from every catalog holiday and the generic-holiday
// fallback above so a "Bob's birthday" in the banner never gets mistaken
// for a recognised holiday.
private const val FESTIVE_GOLD = 0xFFD4AF37L
private const val FESTIVE_PURPLE = 0xFF6A1B9AL
private const val BIRTHDAY_YELLOW = 0xFFFFD54FL
private const val BIRTHDAY_MAGENTA = 0xFFD81B60L

/**
 * Runtime-constructed [HolidayTheme]s for calendar-sourced events. The
 * `title` becomes the banner's display string verbatim (it's the calendar
 * event's own title — "Diwali", "Alice's birthday"); these themes are
 * marked [HolidayTheme.isSynthetic] so the per-holiday picker filters
 * them out, and they carry an empty [HolidayTheme.countries] set so the
 * country-resolver never re-fires them.
 *
 * Privacy note: the `title` originates from a calendar event the user
 * has opted into reading. It surfaces on-device in the Today banner and
 * **must never** flow into insight prose, TTS, or Firebase — same rule
 * as [CalendarEvent.title].
 */
object FestiveThemes {
    fun publicHoliday(title: String): HolidayTheme = HolidayTheme(
        id = HolidayId.GENERIC_PUBLIC_HOLIDAY,
        displayNameKey = title,
        bannerTextKey = title,
        emoji = "🎊",
        topOverrides = topPaletteAll(FESTIVE_GOLD),
        bottomOverrides = bottomPaletteAll(FESTIVE_PURPLE),
        bannerArgb = FESTIVE_GOLD,
        countries = emptySet(),
        isSynthetic = true,
        displayTitleOverride = title,
    )

    fun birthday(title: String): HolidayTheme = HolidayTheme(
        id = HolidayId.BIRTHDAY,
        displayNameKey = title,
        bannerTextKey = title,
        emoji = "🎂",
        topOverrides = topPaletteAll(BIRTHDAY_YELLOW),
        bottomOverrides = bottomPaletteAll(BIRTHDAY_MAGENTA),
        bannerArgb = BIRTHDAY_MAGENTA,
        countries = emptySet(),
        isSynthetic = true,
        displayTitleOverride = title,
    )
}
