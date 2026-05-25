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
    ORTHODOX_CHRISTMAS,
    JAPAN_COMING_OF_AGE_DAY,
    MLK_DAY,
    UKRAINE_UNITY_DAY,
    BURNS_NIGHT,
    AUSTRALIA_DAY,
    INDIA_REPUBLIC_DAY,
    MEXICO_CONSTITUTION_DAY,
    WAITANGI_DAY,
    VALENTINES_DAY,
    US_PRESIDENTS_DAY,
    RUSSIA_DEFENDER_OF_FATHERLAND_DAY,
    ST_DAVIDS_DAY,
    KOREAN_INDEPENDENCE_MOVEMENT_DAY,
    MARDI_GRAS,
    ASH_WEDNESDAY,
    ST_PATRICKS_DAY,
    MEXICO_BENITO_JUAREZ_BIRTHDAY,
    PAKISTAN_DAY,
    BANGLADESH_INDEPENDENCE_DAY,
    UK_MOTHERING_SUNDAY,
    PALM_SUNDAY,
    MAUNDY_THURSDAY,
    GOOD_FRIDAY,
    EASTER_SUNDAY,
    EASTER_MONDAY,
    THAILAND_CHAKRI_DAY,
    THAILAND_SONGKRAN,
    BRAZIL_TIRADENTES_DAY,
    ST_GEORGES_DAY,
    TURKEY_CHILDRENS_DAY,
    ANZAC_DAY,
    EGYPT_SINAI_LIBERATION_DAY,
    SOUTH_AFRICA_FREEDOM_DAY,
    VIETNAM_REUNIFICATION_DAY,
    LABOUR_DAY,
    JAPAN_GREENERY_DAY,
    CINCO_DE_MAYO,
    UK_EARLY_MAY_BANK_HOLIDAY,
    UKRAINE_VICTORY_DAY,
    RUSSIA_VICTORY_DAY,
    MOTHERS_DAY,
    TURKEY_YOUTH_DAY,
    ARGENTINA_MAY_REVOLUTION,
    ASCENSION_DAY,
    PENTECOST,
    WHIT_MONDAY,
    CROATIA_STATEHOOD_DAY,
    US_MEMORIAL_DAY,
    UK_SPRING_BANK_HOLIDAY,
    TOWEL_DAY,
    INDONESIA_PANCASILA_DAY,
    ITALY_REPUBLIC_DAY,
    KOREAN_MEMORIAL_DAY,
    CORPUS_CHRISTI,
    UK_KINGS_BIRTHDAY,
    PHILIPPINES_INDEPENDENCE_DAY,
    NIGERIA_DEMOCRACY_DAY,
    RUSSIA_DAY,
    SOUTH_AFRICA_YOUTH_DAY,
    JUNETEENTH,
    FATHERS_DAY_JUN,
    UKRAINE_CONSTITUTION_DAY,
    CANADA_DAY,
    US_INDEPENDENCE_DAY,
    ARGENTINA_INDEPENDENCE_DAY,
    BASTILLE_DAY,
    UKRAINE_STATEHOOD_DAY,
    JAPAN_MARINE_DAY,
    EGYPT_REVOLUTION_DAY,
    CROATIA_VICTORY_DAY,
    SINGAPORE_NATIONAL_DAY,
    PAKISTAN_INDEPENDENCE_DAY,
    INDIA_INDEPENDENCE_DAY,
    KOREAN_LIBERATION_DAY,
    ASSUMPTION,
    INDONESIA_INDEPENDENCE_DAY,
    UKRAINE_INDEPENDENCE_DAY,
    UK_SUMMER_BANK_HOLIDAY,
    TURKEY_VICTORY_DAY,
    MALAYSIA_INDEPENDENCE_DAY,
    FATHERS_DAY_SEP,
    VIETNAM_NATIONAL_DAY,
    BRAZIL_INDEPENDENCE_DAY,
    US_LABOR_DAY,
    MALAYSIA_DAY,
    MEXICO_INDEPENDENCE_DAY,
    TALK_LIKE_A_PIRATE_DAY,
    SOUTH_AFRICA_HERITAGE_DAY,
    UKRAINE_DEFENDER_DAY,
    NIGERIA_INDEPENDENCE_DAY,
    INDIA_GANDHI_JAYANTI,
    GERMAN_UNITY_DAY,
    CROATIA_INDEPENDENCE_DAY,
    KOREAN_HANGEUL_DAY,
    CANADIAN_THANKSGIVING,
    BRAZIL_OUR_LADY_APARECIDA,
    SPAIN_HISPANIC_DAY,
    AUSTRIA_NATIONAL_DAY,
    NZ_LABOUR_DAY,
    TURKEY_REPUBLIC_DAY,
    HALLOWEEN,
    ALL_SAINTS_DAY,
    MEXICO_DAY_OF_THE_DEAD,
    JAPAN_CULTURE_DAY,
    RUSSIA_UNITY_DAY,
    MELBOURNE_CUP_DAY,
    US_ELECTION_DAY,
    BONFIRE_NIGHT,
    UK_REMEMBRANCE_SUNDAY,
    REMEMBRANCE_DAY,
    INDIA_CHILDRENS_DAY,
    BRAZIL_REPUBLIC_PROCLAMATION,
    MEXICO_REVOLUTION_DAY,
    BRAZIL_BLACK_AWARENESS,
    US_THANKSGIVING,
    PHILIPPINES_BONIFACIO_DAY,
    ST_ANDREWS_DAY,
    IMMACULATE_CONCEPTION,
    BANGLADESH_VICTORY_DAY,
    SOUTH_AFRICA_DAY_OF_RECONCILIATION,
    CHRISTMAS_DAY,
    BOXING_DAY,
    PHILIPPINES_RIZAL_DAY,
    // TODO(holidays-v5): Lookup predicate + lunisolar / Hijri / Hebrew /
    // Julian-calendar holidays. Needs a new HolidayDate.Lookup(
    //   Map<Year, MonthDay>) predicate that returns null when the year
    // is outside the table (so the resolver silently falls through),
    // plus per-year tables covering ~2024-2040 (refreshed annually).
    // When that lands, add these holidays and the matching
    // religious-bucket sentinels + HolidayCountrySelection toggles in
    // the same PR (so a bucket and its first entry land together,
    // rather than leaving an empty checkbox in Settings):
    //
    //   ISLAMIC bucket (new): Eid al-Fitr, Eid al-Adha, Islamic New
    //     Year (Muharram), Mawlid, Ramadan start. Countries: ID, PK,
    //     BD, TR, EG, MY, NG, SA, AE, MA, DZ, IQ, IR ...
    //   JEWISH bucket (new): Hanukkah, Passover, Rosh Hashanah, Yom
    //     Kippur, Sukkot, Purim. Countries: IL plus diaspora-honest
    //     GLOBAL-style.
    //   HINDU bucket (new): Diwali, Holi, Raksha Bandhan, Ganesh
    //     Chaturthi, Navratri. Countries: IN, NP, MU, FJ, SG (per
    //     Singapore public holiday list).
    //   Lunar (no religious bucket — cultural): Lunar New Year,
    //     Mid-Autumn Festival, Dragon Boat Festival, Qingming.
    //     Countries: CN, TW, HK, SG, MY, VN (Tet = Lunar NY).
    //   Orthodox Easter (and its Good Friday / Pentecost / Whit
    //     Monday cluster): Julian-calendar computus. Either move
    //     alongside the existing EasterRelative entries once Lookup
    //     lands, or add a sibling OrthodoxEasterRelative predicate so
    //     the Western / Orthodox split stays explicit at the
    //     predicate level.
    //   Buddhist (TH, LK, MM): Vesak, Asalha Bucha, Khao Phansa.
    //
    // TODO(holidays-v5): switch the [REMEMBRANCE_DAY] banner-name
    // lookup from [Region]-derived country to location-derived
    // country once the app's reverse-geocoding plumbing exposes a
    // stable country code. Region is the right *user-controlled*
    // signal short-term; location is the more accurate one once
    // available.
    //
    // TODO(holidays-v5): same-date collisions — first-match in
    // catalog order currently wins, which means a UK user with St
    // David's enabled will never see Korean Independence Movement Day
    // (same Mar 1 date), and an Italian user can't see Liberation Day
    // because Anzac (same Apr 25 date) gets in first. After the v4
    // expansion the list of same-date collisions now includes Jan 26
    // (AU Day / IN Republic Day), Aug 15 (KR Liberation / Assumption
    // / IN Independence), Apr 25 (Anzac / EG Sinai Liberation), and
    // Oct 1 (NG Independence / UA Defender). Resolver should pick by
    // location-derived country once that lands, with first-match as
    // the fallback.

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
     * The first [day] falling on or after [dayOfMonth] within [month] — the
     * only occurrence of that weekday inside the seven-day window
     * `[dayOfMonth, dayOfMonth + 6]`. Covers "Tuesday after the first Monday"
     * style anchors: US Election Day is the Tuesday on or after Nov 2
     * (`FirstWeekdayOnOrAfter(NOVEMBER, TUESDAY, 2)`), which is exactly the
     * Tuesday immediately following the first Monday.
     */
    data class FirstWeekdayOnOrAfter(
        val month: Month,
        val day: DayOfWeek,
        val dayOfMonth: Int,
    ) : HolidayDate {
        override fun matches(date: LocalDate): Boolean {
            if (date.month != month || date.dayOfWeek != day) return false
            return date.dayOfMonth in dayOfMonth..(dayOfMonth + 6)
        }

        override fun dateIn(year: Int): LocalDate {
            val floor = LocalDate.of(year, month, dayOfMonth)
            val shift = (day.value - floor.dayOfWeek.value + 7) % 7
            return floor.plusDays(shift.toLong())
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
    /**
     * Funny-bucket themes carry two banner forms. [bannerTextKey] is the
     * punchy standalone copy shown when the theme is the *only* celebration
     * that day ("Don't panic!"); [bannerJoinKey] is the lower-case clause
     * folded in when the day also has other celebrations ("…and don't
     * forget your towel"). Null on non-Funny themes, which always use
     * [bannerTextKey] in both positions.
     */
    val bannerJoinKey: String? = null,
    /**
     * `true` for solemn remembrance days (Anzac, Memorial Day, Remembrance
     * Day, Korean Memorial Day). When any solemn theme fires, the day's
     * composed banner drops every Funny clause — "Honoring our fallen"
     * never reads "…and don't forget your towel".
     */
    val solemn: Boolean = false,
    /**
     * Set only on an *ephemeral composed* theme produced by [ThemeForToday]
     * when more than one celebration fires today. Lists the banner pieces
     * to join with "and", in display order. Null on every catalog and
     * synthetic theme — those render the single [bannerTextKey] /
     * [displayTitleOverride] path. The palette / [bannerArgb] / [emoji] on
     * a composed theme are copied from whichever member supplies the
     * colours (the Funny theme when present, else the first primary).
     */
    val bannerSegments: List<BannerSegment>? = null,
)

/**
 * One piece of a composed (multi-celebration) banner. Resolves to
 * [literalText] when set (a synthetic calendar title), otherwise to the
 * per-country override in [textKeyByCountry] for the user's country, else
 * the default [textKey] resource. Mirrors the resolution
 * [HolidayTheme.bannerTextKeyFor] does for a single theme so a joined
 * banner still honours the Remembrance Day → Veterans Day naming split.
 */
data class BannerSegment(
    val textKey: String? = null,
    val textKeyByCountry: Map<String, String> = emptyMap(),
    val literalText: String? = null,
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
 * `true` for Funny-bucket themes — playful overlays (Talk Like a Pirate
 * Day, Towel Day) that decorate a day's primary celebration rather than
 * standing as a national/global holiday. Drives the colour-source pick and
 * the standalone-vs-join banner form in [ThemeForToday].
 */
val HolidayTheme.isFunny: Boolean
    get() = HolidayCatalog.FUNNY in countries

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
     * Sentinel "country" for the Christian-religious bucket — Easter-relative
     * observances and other Christian feasts whose dates / observance cross
     * national boundaries: Ash Wednesday, Mardi Gras / Shrove Tuesday, Palm
     * Sunday, Maundy Thursday, Good Friday, Easter Sunday, Easter Monday,
     * Ascension Day, Pentecost, Whit Monday, Corpus Christi. Sits in
     * [HolidayTheme.countries] alongside real ISO codes (each holiday is
     * also tagged with the countries where it's a public holiday, so a
     * French user picks up Ascension via FR and a UK user picks up Good
     * Friday via GB even without enabling the Christian bucket). Surfaced
     * in Settings as its own toggleable bucket, off by default — these are
     * religious-tradition observances rather than universal civic days, so
     * we don't push them onto every user.
     */
    const val CHRISTIAN: String = "CHRISTIAN"

    /**
     * Sentinel "country" for the Orthodox-Christian bucket. Currently holds
     * Orthodox Christmas (Jan 7); Orthodox Easter and its cluster need the
     * Julian-calendar computus (see the [HolidayId.ORTHODOX_CHRISTMAS]-area
     * v5 TODO). Like [CHRISTIAN] each holiday is also tagged with its
     * observing countries (RU, RS, GE, ET, MK, BG, BY) so a Russian user
     * sees it via their `home` country without flipping the Orthodox
     * toggle. Off by default — most users aren't Orthodox, and the
     * per-country path covers the observing ones.
     */
    const val ORTHODOX: String = "ORTHODOX"

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

        // Jan 7 — Orthodox Christmas (Julian-calendar Dec 25). Observed by
        // the Russian, Serbian, Georgian, Macedonian, Belarusian and
        // Ethiopian Orthodox Churches. Notably **not** tagged UA: the
        // Orthodox Church of Ukraine officially moved Christmas to Dec 25
        // in 2023 as part of the de-Russification calendar shift that also
        // moved Defender Day from Oct 14 to Oct 1 and Victory Day from
        // May 9 to May 8 — Ukrainian users still get Dec 25 via the
        // existing GLOBAL Christmas entry, and adding UA here would
        // misrepresent the official observance. Liturgical green top +
        // iconostasis-gold bottom, kept clear of [CHRISTMAS_DAY]'s red
        // + green so the two Christmases read as visually distinct.
        HolidayDate.Fixed(Month.JANUARY, 7) to HolidayTheme(
            id = HolidayId.ORTHODOX_CHRISTMAS,
            displayNameKey = "holiday_name_orthodox_christmas",
            bannerTextKey = "holiday_banner_orthodox_christmas",
            emoji = "☦", // ☦ — Orthodox cross
            topOverrides = topPaletteAll(ORTHODOX_GREEN),
            bottomOverrides = bottomPaletteAll(ORTHODOX_GOLD),
            bannerArgb = ORTHODOX_GREEN,
            countries = setOf(ORTHODOX, "RU", "RS", "GE", "ET", "MK", "BY"),
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

        // Jan 22 — Ukrainian Unity Day (День Соборності), commemorating the
        // 1919 unification of the Ukrainian People's Republic and the West
        // Ukrainian People's Republic. Flag blue top + wheat-field yellow
        // bottom. Same Ukrainian palette shared by every UA-tagged entry
        // so the country reads as one continuous visual cluster.
        HolidayDate.Fixed(Month.JANUARY, 22) to HolidayTheme(
            id = HolidayId.UKRAINE_UNITY_DAY,
            displayNameKey = "holiday_name_ukraine_unity_day",
            bannerTextKey = "holiday_banner_ukraine_unity_day",
            emoji = "🇺🇦", // 🇺🇦
            topOverrides = topPaletteAll(UKRAINE_BLUE),
            bottomOverrides = bottomPaletteAll(UKRAINE_YELLOW),
            bannerArgb = UKRAINE_BLUE,
            countries = setOf("UA"),
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

        // Jan 26 — Indian Republic Day (गणतंत्र दिवस), marking the 1950
        // adoption of the Constitution. Saffron top + green bottom with
        // navy Ashoka Chakra trim on both, matching the flag's tricolour.
        // Same calendar date as Australia Day above — catalog order means
        // an AU user resolves Australia Day, an IN user resolves Republic
        // Day, and a multi-country (AU+IN) user falls to Australia Day
        // first. See the same-date-collision TODO at the top of
        // [HolidayId] for the eventual country-resolved tiebreak.
        HolidayDate.Fixed(Month.JANUARY, 26) to HolidayTheme(
            id = HolidayId.INDIA_REPUBLIC_DAY,
            displayNameKey = "holiday_name_india_republic_day",
            bannerTextKey = "holiday_banner_india_republic_day",
            emoji = "🇮🇳", // 🇮🇳
            topOverrides = topPaletteAll(INDIA_SAFFRON),
            bottomOverrides = bottomPaletteAll(INDIA_GREEN),
            topStrokeOverrides = topStrokeAll(INDIA_NAVY),
            bottomStrokeOverrides = bottomStrokeAll(INDIA_NAVY),
            bannerArgb = INDIA_SAFFRON,
            countries = setOf("IN"),
        ),

        // 1st Monday of February — Mexican Day of the Constitution
        // (Día de la Constitución), commemorating the 1917 constitution.
        // Mexican-flag green top + red bottom with white papel-picado
        // accent stroke, distinguishing it from [CINCO_DE_MAYO] (same
        // palette, but tagged MX/US) and the autumn Independence Day.
        // Date can land Feb 1-7; on Feb 6 it collides with Waitangi but
        // country tags don't overlap so each user resolves their own.
        HolidayDate.NthWeekday(Month.FEBRUARY, 1, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.MEXICO_CONSTITUTION_DAY,
            displayNameKey = "holiday_name_mexico_constitution_day",
            bannerTextKey = "holiday_banner_mexico_constitution_day",
            emoji = "🇲🇽", // 🇲🇽
            topOverrides = topPaletteAll(MEXICO_GREEN),
            bottomOverrides = bottomPaletteAll(MEXICO_RED),
            topStrokeOverrides = topStrokeAll(MEXICO_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(MEXICO_WHITE),
            bannerArgb = MEXICO_GREEN,
            countries = setOf("MX"),
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

        // Feb 23 — Defender of the Fatherland Day (День защитника
        // Отечества), Russia's celebration of military service.
        // Russian-flag tricolour: white top + red bottom with the navy
        // flag stripe as the unifying accent stroke, matching the
        // horizontal white / blue / red flag layout.
        HolidayDate.Fixed(Month.FEBRUARY, 23) to HolidayTheme(
            id = HolidayId.RUSSIA_DEFENDER_OF_FATHERLAND_DAY,
            displayNameKey = "holiday_name_russia_defender_of_fatherland_day",
            bannerTextKey = "holiday_banner_russia_defender_of_fatherland_day",
            emoji = "🇷🇺", // 🇷🇺
            topOverrides = topPaletteAll(RUSSIA_WHITE),
            bottomOverrides = bottomPaletteAll(RUSSIA_RED),
            topStrokeOverrides = topStrokeAll(RUSSIA_BLUE),
            bottomStrokeOverrides = bottomStrokeAll(RUSSIA_BLUE),
            bannerArgb = RUSSIA_RED,
            countries = setOf("RU"),
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

        // Easter − 47 — Mardi Gras / Shrove Tuesday / Carnival Tuesday.
        // The day before Ash Wednesday; widely observed as the climax of
        // Carnival (Rio, New Orleans, Venice, Cologne). Tagged CHRISTIAN
        // (so users in the Christian bucket get the theme regardless of
        // country) plus BR for Carnival and US for New Orleans's Mardi
        // Gras, where it's a legal holiday in Louisiana. New Orleans
        // purple / gold tricolour with green stroke trim — the city's
        // canonical Carnival colours, distinct from the more sombre
        // [ASH_WEDNESDAY] palette one day later.
        HolidayDate.EasterRelative(-47) to HolidayTheme(
            id = HolidayId.MARDI_GRAS,
            displayNameKey = "holiday_name_mardi_gras",
            bannerTextKey = "holiday_banner_mardi_gras",
            emoji = "🎭", // 🎭 — Carnival masks
            topOverrides = topPaletteAll(MARDI_GRAS_PURPLE),
            bottomOverrides = bottomPaletteAll(MARDI_GRAS_GOLD),
            topStrokeOverrides = topStrokeAll(MARDI_GRAS_GREEN),
            bottomStrokeOverrides = bottomStrokeAll(MARDI_GRAS_GREEN),
            bannerArgb = MARDI_GRAS_PURPLE,
            countries = setOf(CHRISTIAN, "BR", "US"),
        ),

        // Easter − 46 — Ash Wednesday, the first day of Lent. Penitential
        // ash-grey monochrome across both tiers, mirroring the imposed
        // cross. Tagged CHRISTIAN; no specific countries since it's not
        // a public holiday anywhere but is broadly observed liturgically.
        HolidayDate.EasterRelative(-46) to HolidayTheme(
            id = HolidayId.ASH_WEDNESDAY,
            displayNameKey = "holiday_name_ash_wednesday",
            bannerTextKey = "holiday_banner_ash_wednesday",
            emoji = "✝", // ✝
            topOverrides = topPaletteAll(ASH_GREY),
            bottomOverrides = bottomPaletteAll(ASH_GREY),
            bannerArgb = ASH_GREY,
            countries = setOf(CHRISTIAN),
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

        // 3rd Monday of March — Benito Juárez's Birthday, Mexican civic
        // holiday commemorating the reform-era president (his actual
        // birthday is Mar 21). Mexican-flag green top + red bottom with
        // white papel-picado stroke, same palette as the other Mexican
        // entries so the country reads as one visual cluster.
        HolidayDate.NthWeekday(Month.MARCH, 3, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.MEXICO_BENITO_JUAREZ_BIRTHDAY,
            displayNameKey = "holiday_name_mexico_benito_juarez_birthday",
            bannerTextKey = "holiday_banner_mexico_benito_juarez_birthday",
            emoji = "🇲🇽", // 🇲🇽
            topOverrides = topPaletteAll(MEXICO_GREEN),
            bottomOverrides = bottomPaletteAll(MEXICO_RED),
            topStrokeOverrides = topStrokeAll(MEXICO_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(MEXICO_WHITE),
            bannerArgb = MEXICO_GREEN,
            countries = setOf("MX"),
        ),

        // Mar 23 — Pakistan Day (یوم پاکستان), commemorating the 1940
        // Lahore Resolution. Pakistan-flag green top + white bottom
        // evokes the flag's green field and white crescent-and-star band.
        HolidayDate.Fixed(Month.MARCH, 23) to HolidayTheme(
            id = HolidayId.PAKISTAN_DAY,
            displayNameKey = "holiday_name_pakistan_day",
            bannerTextKey = "holiday_banner_pakistan_day",
            emoji = "🇵🇰", // 🇵🇰
            topOverrides = topPaletteAll(PAKISTAN_GREEN),
            bottomOverrides = bottomPaletteAll(PAKISTAN_WHITE),
            bannerArgb = PAKISTAN_GREEN,
            countries = setOf("PK"),
        ),

        // Mar 26 — Bangladesh Independence Day (স্বাধীনতা দিবস),
        // commemorating the 1971 declaration of independence. Bottle
        // green top + red sun-disc bottom mirrors the flag.
        HolidayDate.Fixed(Month.MARCH, 26) to HolidayTheme(
            id = HolidayId.BANGLADESH_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_bangladesh_independence_day",
            bannerTextKey = "holiday_banner_bangladesh_independence_day",
            emoji = "🇧🇩", // 🇧🇩
            topOverrides = topPaletteAll(BANGLADESH_GREEN),
            bottomOverrides = bottomPaletteAll(BANGLADESH_RED),
            bannerArgb = BANGLADESH_GREEN,
            countries = setOf("BD"),
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

        // Easter − 7 — Palm Sunday, marking Christ's entry into Jerusalem.
        // Olive / palm green top + Passion-week deep purple bottom — the
        // palm fronds and the impending Passion. Tagged CHRISTIAN; not a
        // public holiday in any catalog country so no per-country tags.
        HolidayDate.EasterRelative(-7) to HolidayTheme(
            id = HolidayId.PALM_SUNDAY,
            displayNameKey = "holiday_name_palm_sunday",
            bannerTextKey = "holiday_banner_palm_sunday",
            emoji = "🌿", // 🌿 — palm frond
            topOverrides = topPaletteAll(PALM_GREEN),
            bottomOverrides = bottomPaletteAll(PALM_PURPLE),
            bannerArgb = PALM_GREEN,
            countries = setOf(CHRISTIAN),
        ),

        // Easter − 3 — Maundy Thursday, commemorating the Last Supper.
        // Liturgical red top (Western vestment colour for the Mass of the
        // Lord's Supper) + white-linen bottom (the foot-washing cloth).
        // Tagged CHRISTIAN.
        HolidayDate.EasterRelative(-3) to HolidayTheme(
            id = HolidayId.MAUNDY_THURSDAY,
            displayNameKey = "holiday_name_maundy_thursday",
            bannerTextKey = "holiday_banner_maundy_thursday",
            emoji = "🍞", // 🍞 — bread of the Eucharist
            topOverrides = topPaletteAll(MAUNDY_RED),
            bottomOverrides = bottomPaletteAll(MAUNDY_WHITE),
            bannerArgb = MAUNDY_RED,
            countries = setOf(CHRISTIAN),
        ),

        // Easter − 2 — Western Good Friday. Solemn, monochrome aubergine
        // (Passion-week liturgical purple). Same single-colour shape as
        // Anzac / MLK / US Memorial — a celebratory two-colour palette
        // would read wrong here. Tagged CHRISTIAN plus the catalog
        // countries where it's a public holiday so users in those
        // countries pick it up via their home / current country without
        // having to enable the Christian bucket.
        HolidayDate.EasterRelative(-2) to HolidayTheme(
            id = HolidayId.GOOD_FRIDAY,
            displayNameKey = "holiday_name_good_friday",
            bannerTextKey = "holiday_banner_good_friday",
            emoji = "✝", // ✝
            topOverrides = topPaletteAll(GOOD_FRIDAY_AUBERGINE),
            bottomOverrides = bottomPaletteAll(GOOD_FRIDAY_AUBERGINE),
            bannerArgb = GOOD_FRIDAY_AUBERGINE,
            countries = setOf(
                CHRISTIAN,
                "AR", "AU", "BR", "CA", "DE", "ES", "GB", "ID", "IE", "IN",
                "MX", "NG", "NZ", "PH", "SG", "ZA",
            ),
        ),

        // Easter Sunday. Pastel-lemon top + pastel-mint bottom — egg-
        // decorating spring-renewal palette, no flag association. Tagged
        // CHRISTIAN plus the catalog countries where the Easter weekend is
        // observed as a public holiday (the union of the Good Friday and
        // Easter Monday sets — most countries don't formally gazette
        // Easter Sunday because it's always a Sunday, but it's the centre
        // of the weekend wherever either of the other two are public
        // holidays).
        HolidayDate.EasterRelative(0) to HolidayTheme(
            id = HolidayId.EASTER_SUNDAY,
            displayNameKey = "holiday_name_easter_sunday",
            bannerTextKey = "holiday_banner_easter_sunday",
            emoji = "🥚", // 🥚 — Easter egg
            topOverrides = topPaletteAll(EASTER_LEMON),
            bottomOverrides = bottomPaletteAll(EASTER_MINT),
            bannerArgb = EASTER_LEMON,
            countries = setOf(
                CHRISTIAN,
                "AR", "AT", "AU", "BR", "CA", "DE", "ES", "FR", "GB", "HR",
                "ID", "IE", "IN", "IT", "MX", "NG", "NZ", "PH", "SG", "ZA",
            ),
        ),

        // Easter Monday. Same pastel palette as Easter Sunday so the
        // Sun→Mon weekend reads as a continuous theme rather than two
        // different days. Tagged CHRISTIAN plus the catalog countries
        // where it's a public holiday.
        HolidayDate.EasterRelative(1) to HolidayTheme(
            id = HolidayId.EASTER_MONDAY,
            displayNameKey = "holiday_name_easter_monday",
            bannerTextKey = "holiday_banner_easter_monday",
            emoji = "🐰", // 🐰
            topOverrides = topPaletteAll(EASTER_LEMON),
            bottomOverrides = bottomPaletteAll(EASTER_MINT),
            bannerArgb = EASTER_LEMON,
            countries = setOf(
                CHRISTIAN,
                "AT", "AU", "CA", "DE", "FR", "GB", "HR", "IE", "IT", "NG",
                "NZ", "ZA",
            ),
        ),

        // Apr 6 — Chakri Day (Thailand), commemorating the founding of the
        // House of Chakri in 1782. Thai-flag red top + blue bottom with
        // white accent stroke evokes the five-band Thong Trairong.
        HolidayDate.Fixed(Month.APRIL, 6) to HolidayTheme(
            id = HolidayId.THAILAND_CHAKRI_DAY,
            displayNameKey = "holiday_name_thailand_chakri_day",
            bannerTextKey = "holiday_banner_thailand_chakri_day",
            emoji = "🇹🇭", // 🇹🇭
            topOverrides = topPaletteAll(THAILAND_RED),
            bottomOverrides = bottomPaletteAll(THAILAND_BLUE),
            topStrokeOverrides = topStrokeAll(THAILAND_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(THAILAND_WHITE),
            bannerArgb = THAILAND_RED,
            countries = setOf("TH"),
        ),

        // Apr 13 — Songkran (สงกรานต์), the Thai New Year water festival.
        // Official public holiday Apr 13-15; we anchor on the first day
        // (the most widely-celebrated). Water-blue top + temple-gold
        // bottom evokes the pouring-water tradition and Wat Pho's
        // gilded chedis — visually distinct from Chakri Day's flag
        // palette so the two Thai entries don't read as one.
        HolidayDate.Fixed(Month.APRIL, 13) to HolidayTheme(
            id = HolidayId.THAILAND_SONGKRAN,
            displayNameKey = "holiday_name_thailand_songkran",
            bannerTextKey = "holiday_banner_thailand_songkran",
            emoji = "💦", // 💦 — splashing water, Songkran's iconic image
            topOverrides = topPaletteAll(SONGKRAN_WATER_BLUE),
            bottomOverrides = bottomPaletteAll(SONGKRAN_GOLD),
            bannerArgb = SONGKRAN_WATER_BLUE,
            countries = setOf("TH"),
        ),

        // Apr 21 — Tiradentes Day (Brazil), commemorating the 1792
        // execution of independence-movement martyr Joaquim José da Silva
        // Xavier. Brazilian-flag green top + yellow bottom, same as
        // [BRAZIL_INDEPENDENCE_DAY] so the two Brazilian national days
        // share a visual cluster.
        HolidayDate.Fixed(Month.APRIL, 21) to HolidayTheme(
            id = HolidayId.BRAZIL_TIRADENTES_DAY,
            displayNameKey = "holiday_name_brazil_tiradentes_day",
            bannerTextKey = "holiday_banner_brazil_tiradentes_day",
            emoji = "🇧🇷", // 🇧🇷
            topOverrides = topPaletteAll(BRAZIL_GREEN),
            bottomOverrides = bottomPaletteAll(BRAZIL_YELLOW),
            bannerArgb = BRAZIL_GREEN,
            countries = setOf("BR"),
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

        // Apr 23 — National Sovereignty and Children's Day (23 Nisan
        // Ulusal Egemenlik ve Çocuk Bayramı), Turkey. Marks the 1920
        // founding of the Grand National Assembly and was the world's
        // first holiday formally dedicated to children. Turkish-flag
        // crimson red top + white bottom mirrors the Ay Yıldız.
        // Same calendar date as St George's Day above — country tags
        // don't overlap so each user resolves their own.
        HolidayDate.Fixed(Month.APRIL, 23) to HolidayTheme(
            id = HolidayId.TURKEY_CHILDRENS_DAY,
            displayNameKey = "holiday_name_turkey_childrens_day",
            bannerTextKey = "holiday_banner_turkey_childrens_day",
            emoji = "🇹🇷", // 🇹🇷
            topOverrides = topPaletteAll(TURKEY_RED),
            bottomOverrides = bottomPaletteAll(TURKEY_WHITE),
            bannerArgb = TURKEY_RED,
            countries = setOf("TR"),
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
            solemn = true,
        ),

        // Apr 25 — Sinai Liberation Day (Egypt), commemorating the 1982
        // return of the Sinai Peninsula. Egyptian-flag red top + black
        // bottom with gold Eagle-of-Saladin stroke. Same calendar date
        // as Anzac Day above; country tags don't overlap so each user
        // resolves their own (first-match-in-catalog applies for any
        // multi-country user — see the same-date-collision TODO).
        HolidayDate.Fixed(Month.APRIL, 25) to HolidayTheme(
            id = HolidayId.EGYPT_SINAI_LIBERATION_DAY,
            displayNameKey = "holiday_name_egypt_sinai_liberation_day",
            bannerTextKey = "holiday_banner_egypt_sinai_liberation_day",
            emoji = "🇪🇬", // 🇪🇬
            topOverrides = topPaletteAll(EGYPT_RED),
            bottomOverrides = bottomPaletteAll(EGYPT_BLACK),
            topStrokeOverrides = topStrokeAll(EGYPT_GOLD),
            bottomStrokeOverrides = bottomStrokeAll(EGYPT_GOLD),
            bannerArgb = EGYPT_RED,
            countries = setOf("EG"),
        ),

        // Apr 27 — Freedom Day (South Africa), commemorating the 1994
        // first post-apartheid democratic elections. We anchor on the
        // springbok-green Y of the flag with gold trim; the full six-
        // colour flag doesn't translate well to a two-tier garment
        // palette so we let the green / gold pair stand for the day's
        // celebratory tone, distinct from the more sombre [REMEMBRANCE_DAY]
        // and [US_MEMORIAL_DAY] khakis.
        HolidayDate.Fixed(Month.APRIL, 27) to HolidayTheme(
            id = HolidayId.SOUTH_AFRICA_FREEDOM_DAY,
            displayNameKey = "holiday_name_south_africa_freedom_day",
            bannerTextKey = "holiday_banner_south_africa_freedom_day",
            emoji = "🇿🇦", // 🇿🇦
            topOverrides = topPaletteAll(SA_GREEN),
            bottomOverrides = bottomPaletteAll(SA_GOLD),
            bannerArgb = SA_GREEN,
            countries = setOf("ZA"),
        ),

        // Apr 30 — Reunification Day (Vietnam), commemorating the 1975
        // fall of Saigon and reunification of North and South. Vietnamese-
        // flag red top + yellow-star bottom evokes the Cờ đỏ sao vàng.
        HolidayDate.Fixed(Month.APRIL, 30) to HolidayTheme(
            id = HolidayId.VIETNAM_REUNIFICATION_DAY,
            displayNameKey = "holiday_name_vietnam_reunification_day",
            bannerTextKey = "holiday_banner_vietnam_reunification_day",
            emoji = "🇻🇳", // 🇻🇳
            topOverrides = topPaletteAll(VIETNAM_RED),
            bottomOverrides = bottomPaletteAll(VIETNAM_YELLOW),
            bannerArgb = VIETNAM_RED,
            countries = setOf("VN"),
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

        // May 5 — Cinco de Mayo. Mexican flag tricolour: green top + red
        // bottom with the flag's white field threaded through as accent
        // trim on both, same option-3 stroke pattern as the other
        // tricolours. Tagged MX (and US, where it's widely celebrated).
        HolidayDate.Fixed(Month.MAY, 5) to HolidayTheme(
            id = HolidayId.CINCO_DE_MAYO,
            displayNameKey = "holiday_name_cinco_de_mayo",
            bannerTextKey = "holiday_banner_cinco_de_mayo",
            emoji = "🇲🇽", // 🇲🇽
            topOverrides = topPaletteAll(MEXICO_GREEN),
            bottomOverrides = bottomPaletteAll(MEXICO_RED),
            topStrokeOverrides = topStrokeAll(MEXICO_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(MEXICO_WHITE),
            bannerArgb = MEXICO_GREEN,
            countries = setOf("MX", "US"),
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

        // May 8 — Victory over Nazism in WWII Day (Ukraine), Україна,
        // День перемоги над нацизмом у Другій світовій війні. Ukraine
        // officially moved this observance from May 9 to May 8 in 2023 to
        // align with the Western Allies and decisively distance from
        // Russia's May 9 Victory Day. Solemn khaki monochrome — same
        // shape as Anzac / Memorial Day rather than the celebratory
        // Ukrainian blue/yellow used elsewhere — befits a remembrance
        // day. Listed BEFORE Russia's May 9 entry so the two read in
        // calendar order.
        HolidayDate.Fixed(Month.MAY, 8) to HolidayTheme(
            id = HolidayId.UKRAINE_VICTORY_DAY,
            displayNameKey = "holiday_name_ukraine_victory_day",
            bannerTextKey = "holiday_banner_ukraine_victory_day",
            emoji = "🔺", // 🔺 — match Anzac / Remembrance for visual continuity
            topOverrides = topPaletteAll(ANZAC_KHAKI),
            bottomOverrides = bottomPaletteAll(ANZAC_KHAKI),
            bannerArgb = ANZAC_KHAKI,
            countries = setOf("UA"),
            solemn = true,
        ),

        // May 9 — Victory Day (День Победы), Russia's commemoration of
        // the WWII victory over Nazi Germany. Russian-flag red top + white
        // bottom with the Ribbon of Saint George's iconic orange/black
        // diagonal stroke; the ribbon's the day's universal symbol and
        // makes it visually distinct from the simpler [RUSSIA_DEFENDER_OF_FATHERLAND_DAY]
        // tricolour. Note Ukraine moved its equivalent commemoration to
        // May 8 in 2023 (entry directly above) — country tags don't
        // overlap so each user resolves their own date.
        HolidayDate.Fixed(Month.MAY, 9) to HolidayTheme(
            id = HolidayId.RUSSIA_VICTORY_DAY,
            displayNameKey = "holiday_name_russia_victory_day",
            bannerTextKey = "holiday_banner_russia_victory_day",
            emoji = "🎖", // 🎖 — military medal
            topOverrides = topPaletteAll(RUSSIA_RED),
            bottomOverrides = bottomPaletteAll(RUSSIA_WHITE),
            topStrokeOverrides = topStrokeAll(RUSSIA_RIBBON_ORANGE),
            bottomStrokeOverrides = bottomStrokeAll(RUSSIA_RIBBON_BLACK),
            bannerArgb = RUSSIA_RED,
            countries = setOf("RU"),
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

        // May 19 — Commemoration of Atatürk, Youth and Sports Day (Turkey),
        // 19 Mayıs Atatürk'ü Anma, Gençlik ve Spor Bayramı. Marks
        // Atatürk's 1919 landing at Samsun, the start of the Turkish War
        // of Independence. Crimson-red top + white bottom mirrors the
        // flag; shared palette across Turkey's catalog entries.
        HolidayDate.Fixed(Month.MAY, 19) to HolidayTheme(
            id = HolidayId.TURKEY_YOUTH_DAY,
            displayNameKey = "holiday_name_turkey_youth_day",
            bannerTextKey = "holiday_banner_turkey_youth_day",
            emoji = "🇹🇷", // 🇹🇷
            topOverrides = topPaletteAll(TURKEY_RED),
            bottomOverrides = bottomPaletteAll(TURKEY_WHITE),
            bannerArgb = TURKEY_RED,
            countries = setOf("TR"),
        ),

        // May 25 — May Revolution / Día de la Revolución de Mayo
        // (Argentina), commemorating the 1810 events that led to
        // independence. Argentine-flag celeste blue top + white bottom
        // with the golden Sol de Mayo as the unifying stroke — the same
        // sun-and-stripe motif as the actual flag, simplified for the
        // two-tier palette. Same calendar date as [TOWEL_DAY] below;
        // country tags don't overlap (AR vs FUNNY) so each user
        // resolves their own.
        HolidayDate.Fixed(Month.MAY, 25) to HolidayTheme(
            id = HolidayId.ARGENTINA_MAY_REVOLUTION,
            displayNameKey = "holiday_name_argentina_may_revolution",
            bannerTextKey = "holiday_banner_argentina_may_revolution",
            emoji = "🇦🇷", // 🇦🇷
            topOverrides = topPaletteAll(ARGENTINA_BLUE),
            bottomOverrides = bottomPaletteAll(ARGENTINA_WHITE),
            topStrokeOverrides = topStrokeAll(ARGENTINA_GOLD),
            bottomStrokeOverrides = bottomStrokeAll(ARGENTINA_GOLD),
            bannerArgb = ARGENTINA_BLUE,
            countries = setOf("AR"),
        ),

        // Easter + 39 — Ascension Day. Western Christian feast marking
        // Christ's ascension into heaven, the Thursday 40 days after
        // Easter. Public holiday in Germany, France, Netherlands,
        // Austria, Switzerland, Sweden, Norway and Denmark. Sky-blue top
        // + white bottom evokes the heavenward motion. Tagged CHRISTIAN
        // and per-country.
        HolidayDate.EasterRelative(39) to HolidayTheme(
            id = HolidayId.ASCENSION_DAY,
            displayNameKey = "holiday_name_ascension_day",
            bannerTextKey = "holiday_banner_ascension_day",
            emoji = "☁", // ☁ — cloud, evoking the ascension
            topOverrides = topPaletteAll(ASCENSION_SKY),
            bottomOverrides = bottomPaletteAll(ASCENSION_WHITE),
            bannerArgb = ASCENSION_SKY,
            countries = setOf(CHRISTIAN, "DE", "FR", "AT"),
        ),

        // Easter + 49 — Pentecost / Whit Sunday, marking the descent of
        // the Holy Spirit fifty days after Easter. White top + Holy-
        // Spirit liturgical red bottom (the tongues of flame). Tagged
        // CHRISTIAN; public holiday in several catalog countries via
        // their per-country tags.
        HolidayDate.EasterRelative(49) to HolidayTheme(
            id = HolidayId.PENTECOST,
            displayNameKey = "holiday_name_pentecost",
            bannerTextKey = "holiday_banner_pentecost",
            emoji = "🔥", // 🔥 — tongues of fire
            topOverrides = topPaletteAll(ASCENSION_WHITE),
            bottomOverrides = bottomPaletteAll(PENTECOST_RED),
            bannerArgb = PENTECOST_RED,
            countries = setOf(CHRISTIAN, "DE", "FR", "AT"),
        ),

        // Easter + 50 — Whit Monday, the day after Pentecost. Public
        // holiday in Germany, France, Austria, the Netherlands and
        // several other Western European countries. Same Pentecost
        // palette (white + liturgical red) so the Pentecost weekend
        // reads as one continuous theme.
        HolidayDate.EasterRelative(50) to HolidayTheme(
            id = HolidayId.WHIT_MONDAY,
            displayNameKey = "holiday_name_whit_monday",
            bannerTextKey = "holiday_banner_whit_monday",
            emoji = "🔥", // 🔥
            topOverrides = topPaletteAll(ASCENSION_WHITE),
            bottomOverrides = bottomPaletteAll(PENTECOST_RED),
            bannerArgb = PENTECOST_RED,
            countries = setOf(CHRISTIAN, "DE", "FR", "AT"),
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
            solemn = true,
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
            bannerJoinKey = "holiday_banner_join_towel_day",
            emoji = "🪐", // 🪐
            topOverrides = topPaletteAll(TOWEL_TEAL),
            bottomOverrides = bottomPaletteAll(TOWEL_SAND),
            bannerArgb = TOWEL_TEAL,
            countries = setOf(FUNNY),
        ),

        // Jun 1 — Pancasila Day (Hari Lahir Pancasila), Indonesia,
        // commemorating the 1945 articulation of the Pancasila founding
        // philosophy. Indonesian-flag red top + white bottom mirrors the
        // Sang Saka Merah Putih.
        HolidayDate.Fixed(Month.JUNE, 1) to HolidayTheme(
            id = HolidayId.INDONESIA_PANCASILA_DAY,
            displayNameKey = "holiday_name_indonesia_pancasila_day",
            bannerTextKey = "holiday_banner_indonesia_pancasila_day",
            emoji = "🇮🇩", // 🇮🇩
            topOverrides = topPaletteAll(INDONESIA_RED),
            bottomOverrides = bottomPaletteAll(INDONESIA_WHITE),
            bannerArgb = INDONESIA_RED,
            countries = setOf("ID"),
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
            solemn = true,
        ),

        // Easter + 60 — Corpus Christi. Western Catholic feast of the
        // Body of Christ, the Thursday after Trinity Sunday (60 days
        // after Easter). Public holiday in AT, ES, IT, BR, PT, PL and
        // several Catholic German states. White-host top + gold-ciborium
        // bottom evokes the Eucharistic monstrance. Tagged CHRISTIAN +
        // observing countries; falls in early-to-late June across years.
        HolidayDate.EasterRelative(60) to HolidayTheme(
            id = HolidayId.CORPUS_CHRISTI,
            displayNameKey = "holiday_name_corpus_christi",
            bannerTextKey = "holiday_banner_corpus_christi",
            emoji = "⛪", // ⛪
            topOverrides = topPaletteAll(CORPUS_WHITE),
            bottomOverrides = bottomPaletteAll(CORPUS_GOLD),
            bannerArgb = CORPUS_GOLD,
            countries = setOf(CHRISTIAN, "AT", "ES", "IT", "BR"),
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

        // Jun 12 — Independence Day (Araw ng Kalayaan), Philippines,
        // commemorating the 1898 declaration. Philippine-flag blue top +
        // red bottom with white triangle + gold sun stroke evokes the
        // full flag layout. Same calendar date as Nigeria's Democracy
        // Day and Russia Day below (three-way collision); country tags
        // don't overlap so each user resolves their own — multi-country
        // users fall to first-match-in-catalog.
        HolidayDate.Fixed(Month.JUNE, 12) to HolidayTheme(
            id = HolidayId.PHILIPPINES_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_philippines_independence_day",
            bannerTextKey = "holiday_banner_philippines_independence_day",
            emoji = "🇵🇭", // 🇵🇭
            topOverrides = topPaletteAll(PHILIPPINES_BLUE),
            bottomOverrides = bottomPaletteAll(PHILIPPINES_RED),
            topStrokeOverrides = topStrokeAll(PHILIPPINES_GOLD),
            bottomStrokeOverrides = bottomStrokeAll(PHILIPPINES_WHITE),
            bannerArgb = PHILIPPINES_BLUE,
            countries = setOf("PH"),
        ),

        // Jun 12 — Democracy Day (Nigeria), commemorating the 1993
        // election widely considered the fairest in Nigerian history.
        // Officially moved to Jun 12 in 2018 from May 29. Nigerian-flag
        // green top + green bottom with white centre-band stroke.
        HolidayDate.Fixed(Month.JUNE, 12) to HolidayTheme(
            id = HolidayId.NIGERIA_DEMOCRACY_DAY,
            displayNameKey = "holiday_name_nigeria_democracy_day",
            bannerTextKey = "holiday_banner_nigeria_democracy_day",
            emoji = "🇳🇬", // 🇳🇬
            topOverrides = topPaletteAll(NIGERIA_GREEN),
            bottomOverrides = bottomPaletteAll(NIGERIA_GREEN),
            topStrokeOverrides = topStrokeAll(NIGERIA_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(NIGERIA_WHITE),
            bannerArgb = NIGERIA_GREEN,
            countries = setOf("NG"),
        ),

        // Jun 12 — Russia Day (День России), commemorating the 1990
        // declaration of state sovereignty by the Russian SFSR. The full
        // white / blue / red Russian flag tricolour with the option-3
        // stroke pattern (white top + red bottom + blue stroke on both)
        // so the day reads as a national-celebratory tricolour rather
        // than the simpler [RUSSIA_DEFENDER_OF_FATHERLAND_DAY] palette.
        HolidayDate.Fixed(Month.JUNE, 12) to HolidayTheme(
            id = HolidayId.RUSSIA_DAY,
            displayNameKey = "holiday_name_russia_day",
            bannerTextKey = "holiday_banner_russia_day",
            emoji = "🇷🇺", // 🇷🇺
            topOverrides = topPaletteAll(RUSSIA_WHITE),
            bottomOverrides = bottomPaletteAll(RUSSIA_RED),
            topStrokeOverrides = topStrokeAll(RUSSIA_BLUE),
            bottomStrokeOverrides = bottomStrokeAll(RUSSIA_BLUE),
            bannerArgb = RUSSIA_BLUE,
            countries = setOf("RU"),
        ),

        // Jun 16 — Youth Day (South Africa), commemorating the 1976
        // Soweto uprising. Solemn but not strictly remembrance — we use
        // SA's blue / red / yellow flag colours via the deep blue +
        // gold pairing, distinct from [SOUTH_AFRICA_FREEDOM_DAY]'s
        // green / gold.
        HolidayDate.Fixed(Month.JUNE, 16) to HolidayTheme(
            id = HolidayId.SOUTH_AFRICA_YOUTH_DAY,
            displayNameKey = "holiday_name_south_africa_youth_day",
            bannerTextKey = "holiday_banner_south_africa_youth_day",
            emoji = "🇿🇦", // 🇿🇦
            topOverrides = topPaletteAll(SA_BLUE),
            bottomOverrides = bottomPaletteAll(SA_GOLD),
            bannerArgb = SA_BLUE,
            countries = setOf("ZA"),
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

        // Jun 28 — Constitution Day (Ukraine), commemorating the 1996
        // adoption of the post-Soviet Ukrainian Constitution. Blue +
        // yellow flag pairing, same palette as the other UA entries.
        HolidayDate.Fixed(Month.JUNE, 28) to HolidayTheme(
            id = HolidayId.UKRAINE_CONSTITUTION_DAY,
            displayNameKey = "holiday_name_ukraine_constitution_day",
            bannerTextKey = "holiday_banner_ukraine_constitution_day",
            emoji = "🇺🇦", // 🇺🇦
            topOverrides = topPaletteAll(UKRAINE_BLUE),
            bottomOverrides = bottomPaletteAll(UKRAINE_YELLOW),
            bannerArgb = UKRAINE_BLUE,
            countries = setOf("UA"),
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

        // Jul 9 — Independence Day (Argentina, Día de la Independencia),
        // commemorating the 1816 declaration. Celeste-blue top + white
        // bottom with the golden Sol de Mayo as the unifying accent —
        // same palette as [ARGENTINA_MAY_REVOLUTION] so the two
        // Argentine national days read as one visual cluster.
        HolidayDate.Fixed(Month.JULY, 9) to HolidayTheme(
            id = HolidayId.ARGENTINA_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_argentina_independence_day",
            bannerTextKey = "holiday_banner_argentina_independence_day",
            emoji = "🇦🇷", // 🇦🇷
            topOverrides = topPaletteAll(ARGENTINA_BLUE),
            bottomOverrides = bottomPaletteAll(ARGENTINA_WHITE),
            topStrokeOverrides = topStrokeAll(ARGENTINA_GOLD),
            bottomStrokeOverrides = bottomStrokeAll(ARGENTINA_GOLD),
            bannerArgb = ARGENTINA_BLUE,
            countries = setOf("AR"),
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

        // Jul 15 — Statehood Day of Ukraine (День Української
        // Державності), a new holiday instituted by presidential decree
        // in 2022. Commemorates the baptism of Kyivan Rus' under
        // Volodymyr the Great. Same Ukrainian blue + yellow palette.
        HolidayDate.Fixed(Month.JULY, 15) to HolidayTheme(
            id = HolidayId.UKRAINE_STATEHOOD_DAY,
            displayNameKey = "holiday_name_ukraine_statehood_day",
            bannerTextKey = "holiday_banner_ukraine_statehood_day",
            emoji = "🇺🇦", // 🇺🇦
            topOverrides = topPaletteAll(UKRAINE_BLUE),
            bottomOverrides = bottomPaletteAll(UKRAINE_YELLOW),
            bannerArgb = UKRAINE_BLUE,
            countries = setOf("UA"),
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

        // Jul 23 — Revolution Day (Egypt), commemorating the 1952 Free
        // Officers' coup. Egyptian-flag red top + black bottom with
        // gold Eagle-of-Saladin stroke — same palette as Sinai
        // Liberation so the two Egyptian entries share a visual cluster.
        HolidayDate.Fixed(Month.JULY, 23) to HolidayTheme(
            id = HolidayId.EGYPT_REVOLUTION_DAY,
            displayNameKey = "holiday_name_egypt_revolution_day",
            bannerTextKey = "holiday_banner_egypt_revolution_day",
            emoji = "🇪🇬", // 🇪🇬
            topOverrides = topPaletteAll(EGYPT_RED),
            bottomOverrides = bottomPaletteAll(EGYPT_BLACK),
            topStrokeOverrides = topStrokeAll(EGYPT_GOLD),
            bottomStrokeOverrides = bottomStrokeAll(EGYPT_GOLD),
            bannerArgb = EGYPT_RED,
            countries = setOf("EG"),
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

        // Aug 9 — National Day of Singapore. Red top + white bottom
        // mirrors the flag (Singapore's flag has a crescent + stars on
        // the red band; we anchor on the two bands for the two-tier
        // palette). Same red / white motif as Indonesia but flipped:
        // Singapore puts red on top in both flag and theme.
        HolidayDate.Fixed(Month.AUGUST, 9) to HolidayTheme(
            id = HolidayId.SINGAPORE_NATIONAL_DAY,
            displayNameKey = "holiday_name_singapore_national_day",
            bannerTextKey = "holiday_banner_singapore_national_day",
            emoji = "🇸🇬", // 🇸🇬
            topOverrides = topPaletteAll(SINGAPORE_RED),
            bottomOverrides = bottomPaletteAll(SINGAPORE_WHITE),
            bannerArgb = SINGAPORE_RED,
            countries = setOf("SG"),
        ),

        // Aug 14 — Independence Day (Pakistan, یوم آزادی), commemorating
        // the 1947 partition. Same Pakistani green + white palette as
        // Pakistan Day so the two share a visual cluster.
        HolidayDate.Fixed(Month.AUGUST, 14) to HolidayTheme(
            id = HolidayId.PAKISTAN_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_pakistan_independence_day",
            bannerTextKey = "holiday_banner_pakistan_independence_day",
            emoji = "🇵🇰", // 🇵🇰
            topOverrides = topPaletteAll(PAKISTAN_GREEN),
            bottomOverrides = bottomPaletteAll(PAKISTAN_WHITE),
            bannerArgb = PAKISTAN_GREEN,
            countries = setOf("PK"),
        ),

        // Aug 15 — Independence Day (India, स्वतंत्रता दिवस), commemorating
        // the 1947 end of British rule. Same Indian tricolour palette as
        // Republic Day so the two national days share a visual cluster.
        // Listed BEFORE Korean Liberation and Assumption so an IN user
        // resolves Independence Day first (catalog-order precedence);
        // KR users see Liberation, AT/ES/FR/HR/IT users see Assumption
        // — no country overlap. Same-date-collision TODO applies for
        // any multi-country user.
        HolidayDate.Fixed(Month.AUGUST, 15) to HolidayTheme(
            id = HolidayId.INDIA_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_india_independence_day",
            bannerTextKey = "holiday_banner_india_independence_day",
            emoji = "🇮🇳", // 🇮🇳
            topOverrides = topPaletteAll(INDIA_SAFFRON),
            bottomOverrides = bottomPaletteAll(INDIA_GREEN),
            topStrokeOverrides = topStrokeAll(INDIA_NAVY),
            bottomStrokeOverrides = bottomStrokeAll(INDIA_NAVY),
            bannerArgb = INDIA_GREEN,
            countries = setOf("IN"),
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

        // Aug 17 — Proclamation of Independence Day (Indonesia, Hari
        // Kemerdekaan), commemorating the 1945 declaration. Same red /
        // white Sang Saka Merah Putih palette as Pancasila Day.
        HolidayDate.Fixed(Month.AUGUST, 17) to HolidayTheme(
            id = HolidayId.INDONESIA_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_indonesia_independence_day",
            bannerTextKey = "holiday_banner_indonesia_independence_day",
            emoji = "🇮🇩", // 🇮🇩
            topOverrides = topPaletteAll(INDONESIA_RED),
            bottomOverrides = bottomPaletteAll(INDONESIA_WHITE),
            bannerArgb = INDONESIA_RED,
            countries = setOf("ID"),
        ),

        // Aug 24 — Independence Day of Ukraine (День Незалежності),
        // commemorating the 1991 declaration of independence from the
        // USSR. The headline Ukrainian national holiday — blue +
        // yellow flag pairing matches every other UA entry so the
        // country's catalog reads as one cohesive cluster.
        HolidayDate.Fixed(Month.AUGUST, 24) to HolidayTheme(
            id = HolidayId.UKRAINE_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_ukraine_independence_day",
            bannerTextKey = "holiday_banner_ukraine_independence_day",
            emoji = "🇺🇦", // 🇺🇦
            topOverrides = topPaletteAll(UKRAINE_BLUE),
            bottomOverrides = bottomPaletteAll(UKRAINE_YELLOW),
            bannerArgb = UKRAINE_BLUE,
            countries = setOf("UA"),
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

        // Aug 30 — Victory Day (Turkey, Zafer Bayramı), commemorating
        // the 1922 victory at the Battle of Dumlupınar that ended the
        // Turkish War of Independence. Crimson-red top + white bottom
        // mirrors the flag.
        HolidayDate.Fixed(Month.AUGUST, 30) to HolidayTheme(
            id = HolidayId.TURKEY_VICTORY_DAY,
            displayNameKey = "holiday_name_turkey_victory_day",
            bannerTextKey = "holiday_banner_turkey_victory_day",
            emoji = "🇹🇷", // 🇹🇷
            topOverrides = topPaletteAll(TURKEY_RED),
            bottomOverrides = bottomPaletteAll(TURKEY_WHITE),
            bannerArgb = TURKEY_RED,
            countries = setOf("TR"),
        ),

        // Aug 31 — Independence Day / Merdeka Day (Malaysia, Hari
        // Merdeka), commemorating the 1957 independence from Britain.
        // Malaysian flag's deep blue canton + yellow crescent and 14-
        // point star palette. The full red-and-white-stripe Jalur
        // Gemilang doesn't reduce cleanly to two tiers; the canton
        // colours read as unambiguously Malaysian.
        HolidayDate.Fixed(Month.AUGUST, 31) to HolidayTheme(
            id = HolidayId.MALAYSIA_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_malaysia_independence_day",
            bannerTextKey = "holiday_banner_malaysia_independence_day",
            emoji = "🇲🇾", // 🇲🇾
            topOverrides = topPaletteAll(MALAYSIA_BLUE),
            bottomOverrides = bottomPaletteAll(MALAYSIA_YELLOW),
            bannerArgb = MALAYSIA_BLUE,
            countries = setOf("MY"),
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

        // Sep 2 — National Day of Vietnam (Quốc Khánh), commemorating
        // the 1945 declaration of independence. Same red + yellow Cờ
        // đỏ sao vàng palette as Reunification Day.
        HolidayDate.Fixed(Month.SEPTEMBER, 2) to HolidayTheme(
            id = HolidayId.VIETNAM_NATIONAL_DAY,
            displayNameKey = "holiday_name_vietnam_national_day",
            bannerTextKey = "holiday_banner_vietnam_national_day",
            emoji = "🇻🇳", // 🇻🇳
            topOverrides = topPaletteAll(VIETNAM_RED),
            bottomOverrides = bottomPaletteAll(VIETNAM_YELLOW),
            bannerArgb = VIETNAM_RED,
            countries = setOf("VN"),
        ),

        // Sep 7 — Brazil Independence Day. Green tops + yellow bottoms.
        // Listed before [US_LABOR_DAY] so that in years where the 1st Monday
        // of September lands on the 7th (e.g. 2026) a multi-country user
        // resolves Brazil's fixed national day rather than the movable Labor
        // Day; single-country US / CA users are unaffected (no country
        // overlap with BR).
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

        // 1st Monday of September — North American Labor Day (US / CA).
        // Distinct from the May 1 [LABOUR_DAY] (continental Europe) in both
        // date and palette: end-of-summer workwear here — hard-hat safety
        // yellow top + denim-blue bottom — rather than the May Day
        // labour-movement red. Canada spells it "Labour Day" (banner
        // override below); the shared settings label carries both spellings.
        // See the Brazil entry above for the Sep 7 collision tiebreak.
        HolidayDate.NthWeekday(Month.SEPTEMBER, 1, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.US_LABOR_DAY,
            displayNameKey = "holiday_name_us_labor_day",
            bannerTextKey = "holiday_banner_us_labor_day",
            bannerTextKeyByCountry = mapOf(
                "CA" to "holiday_banner_ca_labour_day",
            ),
            emoji = "🛠", // 🛠 — hammer and wrench, the worker's tools
            topOverrides = topPaletteAll(HARDHAT_YELLOW),
            bottomOverrides = bottomPaletteAll(DENIM_BLUE),
            bannerArgb = DENIM_BLUE,
            countries = setOf("US", "CA"),
        ),

        // Sep 16 — Malaysia Day (Hari Malaysia), commemorating the 1963
        // formation of the federation. Same Malaysian blue + yellow
        // palette as Merdeka Day. Same calendar date as Mexican
        // Independence Day below; country tags don't overlap.
        HolidayDate.Fixed(Month.SEPTEMBER, 16) to HolidayTheme(
            id = HolidayId.MALAYSIA_DAY,
            displayNameKey = "holiday_name_malaysia_day",
            bannerTextKey = "holiday_banner_malaysia_day",
            emoji = "🇲🇾", // 🇲🇾
            topOverrides = topPaletteAll(MALAYSIA_BLUE),
            bottomOverrides = bottomPaletteAll(MALAYSIA_YELLOW),
            bannerArgb = MALAYSIA_BLUE,
            countries = setOf("MY"),
        ),

        // Sep 16 — Mexican Independence Day (Día de la Independencia),
        // commemorating Father Hidalgo's 1810 Grito de Dolores. Full
        // Mexican-flag tricolour palette (green / white / red), same as
        // Cinco de Mayo and Constitution Day so the Mexican entries
        // read as a cohesive cluster.
        HolidayDate.Fixed(Month.SEPTEMBER, 16) to HolidayTheme(
            id = HolidayId.MEXICO_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_mexico_independence_day",
            bannerTextKey = "holiday_banner_mexico_independence_day",
            emoji = "🇲🇽", // 🇲🇽
            topOverrides = topPaletteAll(MEXICO_GREEN),
            bottomOverrides = bottomPaletteAll(MEXICO_RED),
            topStrokeOverrides = topStrokeAll(MEXICO_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(MEXICO_WHITE),
            bannerArgb = MEXICO_GREEN,
            countries = setOf("MX"),
        ),

        // Sep 19 — Talk Like a Pirate Day. A playful, non-national
        // observance, so it rides the [FUNNY] bucket rather than any ISO
        // country. White tops + black bottoms (Jolly Roger).
        HolidayDate.Fixed(Month.SEPTEMBER, 19) to HolidayTheme(
            id = HolidayId.TALK_LIKE_A_PIRATE_DAY,
            displayNameKey = "holiday_name_talk_like_a_pirate_day",
            bannerTextKey = "holiday_banner_talk_like_a_pirate_day",
            bannerJoinKey = "holiday_banner_join_talk_like_a_pirate_day",
            emoji = "🦜", // 🦜
            topOverrides = topPaletteAll(PIRATE_WHITE),
            bottomOverrides = bottomPaletteAll(PIRATE_BLACK),
            bannerArgb = PIRATE_BLACK,
            countries = setOf(FUNNY),
        ),

        // Sep 24 — Heritage Day (South Africa). Springbok-green Y of the
        // flag with gold trim, same as [SOUTH_AFRICA_FREEDOM_DAY] — the
        // two celebratory South African entries share a visual cluster,
        // distinct from [SOUTH_AFRICA_YOUTH_DAY]'s blue / gold solemnity.
        HolidayDate.Fixed(Month.SEPTEMBER, 24) to HolidayTheme(
            id = HolidayId.SOUTH_AFRICA_HERITAGE_DAY,
            displayNameKey = "holiday_name_south_africa_heritage_day",
            bannerTextKey = "holiday_banner_south_africa_heritage_day",
            emoji = "🇿🇦", // 🇿🇦
            topOverrides = topPaletteAll(SA_GREEN),
            bottomOverrides = bottomPaletteAll(SA_GOLD),
            bannerArgb = SA_GREEN,
            countries = setOf("ZA"),
        ),

        // Oct 1 — Defender of Ukraine Day (День захисників і захисниць
        // України). Officially moved from Oct 14 in 2023 alongside the
        // de-Russification calendar shifts; commemorates the country's
        // military defenders. Solemn khaki shape (same as the other
        // remembrance days), not the celebratory blue + yellow used
        // elsewhere in the UA cluster — befits a day honouring service
        // and sacrifice. Listed before Nigeria's Independence Day below
        // (same Oct 1 date); country tags don't overlap.
        HolidayDate.Fixed(Month.OCTOBER, 1) to HolidayTheme(
            id = HolidayId.UKRAINE_DEFENDER_DAY,
            displayNameKey = "holiday_name_ukraine_defender_day",
            bannerTextKey = "holiday_banner_ukraine_defender_day",
            emoji = "🔺", // 🔺 — solemn, matches the other remembrance days
            topOverrides = topPaletteAll(ANZAC_KHAKI),
            bottomOverrides = bottomPaletteAll(ANZAC_KHAKI),
            bannerArgb = ANZAC_KHAKI,
            countries = setOf("UA"),
            solemn = true,
        ),

        // Oct 1 — Independence Day (Nigeria), commemorating the 1960
        // independence from Britain. Nigerian-flag green / white / green
        // vertical tricolour translates here as green tops + green
        // bottoms with white centre-band stroke. Same calendar date as
        // [UKRAINE_DEFENDER_DAY] above; country tags don't overlap so
        // each user resolves their own.
        HolidayDate.Fixed(Month.OCTOBER, 1) to HolidayTheme(
            id = HolidayId.NIGERIA_INDEPENDENCE_DAY,
            displayNameKey = "holiday_name_nigeria_independence_day",
            bannerTextKey = "holiday_banner_nigeria_independence_day",
            emoji = "🇳🇬", // 🇳🇬
            topOverrides = topPaletteAll(NIGERIA_GREEN),
            bottomOverrides = bottomPaletteAll(NIGERIA_GREEN),
            topStrokeOverrides = topStrokeAll(NIGERIA_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(NIGERIA_WHITE),
            bannerArgb = NIGERIA_GREEN,
            countries = setOf("NG"),
        ),

        // Oct 2 — Gandhi Jayanti (गांधी जयंती), commemorating Mohandas
        // Gandhi's birthday and the UN International Day of Non-Violence.
        // Khadi-white top + Indian-saffron bottom evokes Gandhi's homespun
        // dhoti and the Indian flag's first band. Quieter than the
        // Republic-Day tricolour; suits the day's reflective character.
        HolidayDate.Fixed(Month.OCTOBER, 2) to HolidayTheme(
            id = HolidayId.INDIA_GANDHI_JAYANTI,
            displayNameKey = "holiday_name_india_gandhi_jayanti",
            bannerTextKey = "holiday_banner_india_gandhi_jayanti",
            emoji = "🕊", // 🕊 — dove of peace / non-violence
            topOverrides = topPaletteAll(INDIA_WHITE),
            bottomOverrides = bottomPaletteAll(INDIA_SAFFRON),
            bannerArgb = INDIA_SAFFRON,
            countries = setOf("IN"),
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

        // Oct 12 — Our Lady of Aparecida / Nossa Senhora Aparecida
        // (Brazil), the Catholic feast of Brazil's patron saint. Marian
        // white top + Marian-blue bottom matches the Assumption /
        // Immaculate Conception cluster. Same calendar date as Spain's
        // Hispanic Day below; country tags don't overlap so each user
        // resolves their own.
        HolidayDate.Fixed(Month.OCTOBER, 12) to HolidayTheme(
            id = HolidayId.BRAZIL_OUR_LADY_APARECIDA,
            displayNameKey = "holiday_name_brazil_our_lady_aparecida",
            bannerTextKey = "holiday_banner_brazil_our_lady_aparecida",
            emoji = "⛪", // ⛪
            topOverrides = topPaletteAll(MARIAN_WHITE),
            bottomOverrides = bottomPaletteAll(MARIAN_BLUE),
            bannerArgb = MARIAN_BLUE,
            countries = setOf("BR"),
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

        // 4th Monday of October — New Zealand Labour Day, marking the
        // eight-hour-workday campaign. Shares the May Day labour-movement
        // palette (red top + workwear black) and rose emoji — same
        // movement, different hemisphere and date, so no visual clash with
        // the continental [LABOUR_DAY].
        HolidayDate.NthWeekday(Month.OCTOBER, 4, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.NZ_LABOUR_DAY,
            displayNameKey = "holiday_name_nz_labour_day",
            bannerTextKey = "holiday_banner_labour_day",
            emoji = "🌹", // 🌹 — red carnation / rose, the labour-movement symbol
            topOverrides = topPaletteAll(LABOUR_RED),
            bottomOverrides = bottomPaletteAll(LABOUR_BLACK),
            bannerArgb = LABOUR_RED,
            countries = setOf("NZ"),
        ),

        // Oct 29 — Republic Day (Turkey, Cumhuriyet Bayramı),
        // commemorating the 1923 proclamation of the Turkish Republic.
        // Crimson-red top + white bottom mirrors the flag — same palette
        // as the other Turkey entries so the country reads as a
        // cohesive cluster.
        HolidayDate.Fixed(Month.OCTOBER, 29) to HolidayTheme(
            id = HolidayId.TURKEY_REPUBLIC_DAY,
            displayNameKey = "holiday_name_turkey_republic_day",
            bannerTextKey = "holiday_banner_turkey_republic_day",
            emoji = "🇹🇷", // 🇹🇷
            topOverrides = topPaletteAll(TURKEY_RED),
            bottomOverrides = bottomPaletteAll(TURKEY_WHITE),
            bannerArgb = TURKEY_RED,
            countries = setOf("TR"),
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

        // Nov 2 — Día de los Muertos / Day of the Dead (Mexico),
        // commemorating departed loved ones with ofrendas (offerings),
        // marigold cempasúchil flowers, and calavera-decorated sugar
        // skulls. Marigold orange top + papel-picado purple bottom with
        // white calavera stroke — the day's iconic palette, distinct
        // from every other Mexican entry. NOT marked solemn: although
        // it honours the dead, the tone is festive (mariachi-led
        // graveyard vigils, sugar-skull face paint, parades) rather
        // than the sombre tone of [REMEMBRANCE_DAY] / [ANZAC_DAY].
        // Sits adjacent to [ALL_SAINTS_DAY] (Nov 1, the Catholic feast
        // that anchors the two-day Mexican observance) but tagged MX
        // only — All Saints already covers AT/DE/ES/FR/HR/IT.
        HolidayDate.Fixed(Month.NOVEMBER, 2) to HolidayTheme(
            id = HolidayId.MEXICO_DAY_OF_THE_DEAD,
            displayNameKey = "holiday_name_mexico_day_of_the_dead",
            bannerTextKey = "holiday_banner_mexico_day_of_the_dead",
            emoji = "💀", // 💀 — calavera, the day's icon
            topOverrides = topPaletteAll(MEXICO_MARIGOLD),
            bottomOverrides = bottomPaletteAll(MEXICO_DEAD_PURPLE),
            topStrokeOverrides = topStrokeAll(MEXICO_DEAD_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(MEXICO_DEAD_WHITE),
            bannerArgb = MEXICO_MARIGOLD,
            countries = setOf("MX"),
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

        // Nov 4 — Unity Day (День народного единства), Russia,
        // commemorating the 1612 expulsion of Polish-Lithuanian
        // occupiers from Moscow. The principal post-Soviet national
        // holiday, replacing the Soviet-era November 7. Full Russian-
        // flag tricolour: white top + red bottom with blue stroke,
        // same pattern as [RUSSIA_DAY].
        HolidayDate.Fixed(Month.NOVEMBER, 4) to HolidayTheme(
            id = HolidayId.RUSSIA_UNITY_DAY,
            displayNameKey = "holiday_name_russia_unity_day",
            bannerTextKey = "holiday_banner_russia_unity_day",
            emoji = "🇷🇺", // 🇷🇺
            topOverrides = topPaletteAll(RUSSIA_WHITE),
            bottomOverrides = bottomPaletteAll(RUSSIA_RED),
            topStrokeOverrides = topStrokeAll(RUSSIA_BLUE),
            bottomStrokeOverrides = bottomStrokeAll(RUSSIA_BLUE),
            bannerArgb = RUSSIA_BLUE,
            countries = setOf("RU"),
        ),

        // 1st Tuesday of November — Melbourne Cup Day ("the race that stops
        // a nation"). A Victorian public holiday but watched Australia-wide,
        // so it's tagged AU rather than gated to a state the catalog can't
        // express. Racing-silks palette: Flemington-rose top + trophy-gold
        // bottom, kept clear of Australia Day's green / gold.
        HolidayDate.NthWeekday(Month.NOVEMBER, 1, DayOfWeek.TUESDAY) to HolidayTheme(
            id = HolidayId.MELBOURNE_CUP_DAY,
            displayNameKey = "holiday_name_melbourne_cup_day",
            bannerTextKey = "holiday_banner_melbourne_cup_day",
            emoji = "🏇", // 🏇 — horse racing
            topOverrides = topPaletteAll(CUP_ROSE),
            bottomOverrides = bottomPaletteAll(CUP_GOLD),
            bannerArgb = CUP_ROSE,
            countries = setOf("AU"),
        ),

        // Tuesday on or after Nov 2 — US Election Day (the Tuesday after the
        // first Monday in November). Civic palette: navy top + red bottom,
        // no white stroke, so it reads as patriotic without duplicating the
        // [US_INDEPENDENCE_DAY] tricolour or [US_PRESIDENTS_DAY] navy / white.
        HolidayDate.FirstWeekdayOnOrAfter(Month.NOVEMBER, DayOfWeek.TUESDAY, 2) to HolidayTheme(
            id = HolidayId.US_ELECTION_DAY,
            displayNameKey = "holiday_name_us_election_day",
            bannerTextKey = "holiday_banner_us_election_day",
            emoji = "🗳", // 🗳 — ballot box
            topOverrides = topPaletteAll(USA_BLUE),
            bottomOverrides = bottomPaletteAll(USA_RED),
            bannerArgb = USA_BLUE,
            countries = setOf("US"),
        ),

        // Nov 5 — Bonfire Night / Guy Fawkes Night. Orange-flame tops +
        // smoke-red bottoms. Observed in the UK and New Zealand (where it's
        // commonly called Guy Fawkes Night); the banner copy stays the
        // universal "Remember, remember" rhyme for both.
        HolidayDate.Fixed(Month.NOVEMBER, 5) to HolidayTheme(
            id = HolidayId.BONFIRE_NIGHT,
            displayNameKey = "holiday_name_bonfire_night",
            bannerTextKey = "holiday_banner_bonfire_night",
            emoji = "🎆", // 🎆
            topOverrides = topPaletteAll(BONFIRE_ORANGE),
            bottomOverrides = bottomPaletteAll(BONFIRE_RED),
            bannerArgb = BONFIRE_RED,
            countries = setOf("GB", "NZ"),
        ),

        // 2nd Sunday of November — UK Remembrance Sunday. The formal UK
        // observance (poppy-laying, Cenotaph service) sits on the Sunday
        // closest to Nov 11; [REMEMBRANCE_DAY] on Nov 11 itself is the
        // armistice anniversary. Same khaki palette and solemn flag as
        // Remembrance Day — same observance, distinct calendar day for
        // most years. Banner uses the Royal British Legion's "We will
        // remember them" so a same-day collision (Nov 11 falling on a
        // Sunday, ~once every 7 years — next 2029) joins as "Lest we
        // forget and We will remember them" rather than echoing itself.
        HolidayDate.NthWeekday(Month.NOVEMBER, 2, DayOfWeek.SUNDAY) to HolidayTheme(
            id = HolidayId.UK_REMEMBRANCE_SUNDAY,
            displayNameKey = "holiday_name_uk_remembrance_sunday",
            bannerTextKey = "holiday_banner_uk_remembrance_sunday",
            emoji = "🔺", // 🔺 — match Anzac / Remembrance Day for visual continuity
            topOverrides = topPaletteAll(ANZAC_KHAKI),
            bottomOverrides = bottomPaletteAll(ANZAC_KHAKI),
            bannerArgb = ANZAC_KHAKI,
            countries = setOf("GB"),
            solemn = true,
        ),

        // Nov 11 — Remembrance Day (US calls it Veterans Day, FR calls it
        // Armistice Day / Jour de l'Armistice). Solemn monochrome khaki,
        // same shape as Anzac. Banner text varies by country via the
        // country-keyed override map. The UK's formal observance falls on
        // [UK_REMEMBRANCE_SUNDAY] (2nd Sun of Nov) — Nov 11 is the
        // armistice anniversary itself.
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
            solemn = true,
        ),

        // Nov 14 — Children's Day (बाल दिवस), India, commemorating
        // Jawaharlal Nehru's birthday — Nehru reportedly enjoyed
        // children's company and the day's been observed since his
        // death. Same Indian saffron / green tricolour palette as the
        // other Indian entries.
        HolidayDate.Fixed(Month.NOVEMBER, 14) to HolidayTheme(
            id = HolidayId.INDIA_CHILDRENS_DAY,
            displayNameKey = "holiday_name_india_childrens_day",
            bannerTextKey = "holiday_banner_india_childrens_day",
            emoji = "🇮🇳", // 🇮🇳
            topOverrides = topPaletteAll(INDIA_SAFFRON),
            bottomOverrides = bottomPaletteAll(INDIA_GREEN),
            topStrokeOverrides = topStrokeAll(INDIA_NAVY),
            bottomStrokeOverrides = bottomStrokeAll(INDIA_NAVY),
            bannerArgb = INDIA_NAVY,
            countries = setOf("IN"),
        ),

        // Nov 15 — Proclamation of the Republic / Proclamação da
        // República (Brazil), commemorating the 1889 transition from
        // empire to republic. Same Brazilian green + yellow flag
        // palette as the other BR entries.
        HolidayDate.Fixed(Month.NOVEMBER, 15) to HolidayTheme(
            id = HolidayId.BRAZIL_REPUBLIC_PROCLAMATION,
            displayNameKey = "holiday_name_brazil_republic_proclamation",
            bannerTextKey = "holiday_banner_brazil_republic_proclamation",
            emoji = "🇧🇷", // 🇧🇷
            topOverrides = topPaletteAll(BRAZIL_GREEN),
            bottomOverrides = bottomPaletteAll(BRAZIL_YELLOW),
            bannerArgb = BRAZIL_GREEN,
            countries = setOf("BR"),
        ),

        // 3rd Monday of November — Day of the Revolution / Día de la
        // Revolución (Mexico), commemorating the start of the 1910
        // Mexican Revolution (whose actual anniversary is Nov 20).
        // Full Mexican tricolour, same palette as the other MX entries.
        // Listed BEFORE Brazil's Black Awareness Day (Nov 20) because
        // the 3rd Monday usually falls Nov 15-21, often earlier than
        // Nov 20.
        HolidayDate.NthWeekday(Month.NOVEMBER, 3, DayOfWeek.MONDAY) to HolidayTheme(
            id = HolidayId.MEXICO_REVOLUTION_DAY,
            displayNameKey = "holiday_name_mexico_revolution_day",
            bannerTextKey = "holiday_banner_mexico_revolution_day",
            emoji = "🇲🇽", // 🇲🇽
            topOverrides = topPaletteAll(MEXICO_GREEN),
            bottomOverrides = bottomPaletteAll(MEXICO_RED),
            topStrokeOverrides = topStrokeAll(MEXICO_WHITE),
            bottomStrokeOverrides = bottomStrokeAll(MEXICO_WHITE),
            bannerArgb = MEXICO_RED,
            countries = setOf("MX"),
        ),

        // Nov 20 — Black Awareness Day / Dia da Consciência Negra
        // (Brazil), commemorating the death of quilombo leader Zumbi
        // dos Palmares. Federal holiday since 2024. Pan-African red
        // top + green bottom with black trim — same Pan-African
        // palette as Juneteenth, marking the Black-liberation lineage
        // shared across the two days.
        HolidayDate.Fixed(Month.NOVEMBER, 20) to HolidayTheme(
            id = HolidayId.BRAZIL_BLACK_AWARENESS,
            displayNameKey = "holiday_name_brazil_black_awareness",
            bannerTextKey = "holiday_banner_brazil_black_awareness",
            emoji = "✊🏿", // ✊🏿 — raised fist
            topOverrides = topPaletteAll(PAN_AFRICAN_RED),
            bottomOverrides = bottomPaletteAll(PAN_AFRICAN_GREEN),
            topStrokeOverrides = topStrokeAll(PAN_AFRICAN_BLACK),
            bottomStrokeOverrides = bottomStrokeAll(PAN_AFRICAN_BLACK),
            bannerArgb = PAN_AFRICAN_BLACK,
            countries = setOf("BR"),
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

        // Nov 30 — Bonifacio Day (Philippines), commemorating Andrés
        // Bonifacio's birthday (founder of the Katipunan independence
        // movement). Same Philippine palette as Independence Day —
        // blue + red with gold + white accent strokes — so the two
        // PH entries cluster visually. Same calendar date as St
        // Andrew's Day below; country tags don't overlap.
        HolidayDate.Fixed(Month.NOVEMBER, 30) to HolidayTheme(
            id = HolidayId.PHILIPPINES_BONIFACIO_DAY,
            displayNameKey = "holiday_name_philippines_bonifacio_day",
            bannerTextKey = "holiday_banner_philippines_bonifacio_day",
            emoji = "🇵🇭", // 🇵🇭
            topOverrides = topPaletteAll(PHILIPPINES_BLUE),
            bottomOverrides = bottomPaletteAll(PHILIPPINES_RED),
            topStrokeOverrides = topStrokeAll(PHILIPPINES_GOLD),
            bottomStrokeOverrides = bottomStrokeAll(PHILIPPINES_WHITE),
            bannerArgb = PHILIPPINES_RED,
            countries = setOf("PH"),
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

        // Dec 16 — Victory Day (Bangladesh, বিজয় দিবস), commemorating
        // the 1971 victory in the Bangladesh Liberation War. Same
        // Bangladeshi bottle-green + red sun-disc palette as
        // Independence Day so the two BD national days share a visual
        // cluster. Same calendar date as South Africa's Day of
        // Reconciliation below; country tags don't overlap.
        HolidayDate.Fixed(Month.DECEMBER, 16) to HolidayTheme(
            id = HolidayId.BANGLADESH_VICTORY_DAY,
            displayNameKey = "holiday_name_bangladesh_victory_day",
            bannerTextKey = "holiday_banner_bangladesh_victory_day",
            emoji = "🇧🇩", // 🇧🇩
            topOverrides = topPaletteAll(BANGLADESH_GREEN),
            bottomOverrides = bottomPaletteAll(BANGLADESH_RED),
            bannerArgb = BANGLADESH_GREEN,
            countries = setOf("BD"),
        ),

        // Dec 16 — Day of Reconciliation (South Africa), commemorating
        // both the 1838 Battle of Blood River and the 1961 founding of
        // uMkhonto we Sizwe — the date was deliberately repurposed
        // post-apartheid for reconciliation between the two histories.
        // Deep flag-blue top + flag-red bottom evokes both groups'
        // remembrance traditions in one palette, distinct from the
        // celebratory greens of [SOUTH_AFRICA_FREEDOM_DAY] /
        // [SOUTH_AFRICA_HERITAGE_DAY].
        HolidayDate.Fixed(Month.DECEMBER, 16) to HolidayTheme(
            id = HolidayId.SOUTH_AFRICA_DAY_OF_RECONCILIATION,
            displayNameKey = "holiday_name_south_africa_day_of_reconciliation",
            bannerTextKey = "holiday_banner_south_africa_day_of_reconciliation",
            emoji = "🇿🇦", // 🇿🇦
            topOverrides = topPaletteAll(SA_BLUE),
            bottomOverrides = bottomPaletteAll(SA_RED),
            bannerArgb = SA_BLUE,
            countries = setOf("ZA"),
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

        // Dec 30 — Rizal Day (Philippines), commemorating José Rizal's
        // 1896 execution. The third PH catalog entry — same Philippine
        // palette as the other two (blue + red + gold + white).
        HolidayDate.Fixed(Month.DECEMBER, 30) to HolidayTheme(
            id = HolidayId.PHILIPPINES_RIZAL_DAY,
            displayNameKey = "holiday_name_philippines_rizal_day",
            bannerTextKey = "holiday_banner_philippines_rizal_day",
            emoji = "🇵🇭", // 🇵🇭
            topOverrides = topPaletteAll(PHILIPPINES_BLUE),
            bottomOverrides = bottomPaletteAll(PHILIPPINES_RED),
            topStrokeOverrides = topStrokeAll(PHILIPPINES_GOLD),
            bottomStrokeOverrides = bottomStrokeAll(PHILIPPINES_WHITE),
            bannerArgb = PHILIPPINES_BLUE,
            countries = setOf("PH"),
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
// red, with a workwear black for the bottom tier. Shared by the May 1
// continental holiday and NZ's October Labour Day.
private const val LABOUR_RED = 0xFFD32F2FL
private const val LABOUR_BLACK = 0xFF212121L

// North American Labor Day (US / CA) — end-of-summer workwear, kept
// distinct from the May Day red: hard-hat safety yellow + denim blue.
private const val HARDHAT_YELLOW = 0xFFF9A825L
private const val DENIM_BLUE = 0xFF34568BL

// Cinco de Mayo — Mexican flag tricolour (green / white / red).
private const val MEXICO_GREEN = 0xFF006847L
private const val MEXICO_WHITE = 0xFFF5F5F5L
private const val MEXICO_RED = 0xFFCE1126L

// Melbourne Cup Day — racing silks: Flemington-rose + trophy gold.
// Deliberately clear of Australia Day's green / gold.
private const val CUP_ROSE = 0xFFC2185BL
private const val CUP_GOLD = 0xFFFBC02DL

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

// --- v4 holiday-catalogue expansion. Colours for the new fixed-date and
// Easter-relative entries added in the "Indian / Muslim-market / Christian /
// Orthodox / additional-big-Android-market countries" pass. Grouped per
// holiday or palette so the swap-in colour shows next to the catalog entry
// reference. Each section reuses an existing const wherever a holiday
// shares a national palette (e.g. Tiradentes Day reuses the Brazil green +
// yellow); only genuinely new hues live below.

// Orthodox Christmas — Russian Orthodox liturgical green (Romanov / Empire)
// + golden iconostasis trim, distinct from the Christian Easter-cluster
// liturgical aubergine.
private const val ORTHODOX_GREEN = 0xFF1B5E20L
private const val ORTHODOX_GOLD = 0xFFFFD54FL

// Christian Easter-cluster additions — pastels and liturgical purples that
// extend the existing GOOD_FRIDAY_AUBERGINE / EASTER_LEMON family.
// Ash Wednesday: penitential ash grey, mirroring the imposed cross.
private const val ASH_GREY = 0xFF616161L
// Mardi Gras: New Orleans purple / gold / green tricolour.
private const val MARDI_GRAS_PURPLE = 0xFF6A1B9AL
private const val MARDI_GRAS_GOLD = 0xFFFFC107L
private const val MARDI_GRAS_GREEN = 0xFF388E3CL
// Palm Sunday: olive / palm green over a Passion-week deep purple.
private const val PALM_GREEN = 0xFF558B2FL
private const val PALM_PURPLE = 0xFF4A148CL
// Maundy Thursday: deep liturgical red (the colour of the Last Supper
// vestments in Western tradition) over white linen / table cloth.
private const val MAUNDY_RED = 0xFF8E0000L
private const val MAUNDY_WHITE = 0xFFF5F5F5L
// Ascension / Pentecost / Whit Monday — shared sky-blue + white cluster
// (Ascension's heavenward motion) with Pentecost / Whit Monday adding the
// liturgical-red of the descending Holy Spirit / tongues of flame.
private const val ASCENSION_SKY = 0xFF42A5F5L
private const val ASCENSION_WHITE = 0xFFF5F5F5L
private const val PENTECOST_RED = 0xFFD32F2FL
// Corpus Christi: white Eucharistic host on a deep liturgical gold ciborium.
private const val CORPUS_WHITE = 0xFFF5F5F5L
private const val CORPUS_GOLD = 0xFFC9A227L

// India — saffron + green flag halves with navy Ashoka Chakra accent
// (true tricolour, gets the option-3 stroke pattern).
private const val INDIA_SAFFRON = 0xFFFF9933L
private const val INDIA_WHITE = 0xFFF5F5F5L
private const val INDIA_GREEN = 0xFF138808L
private const val INDIA_NAVY = 0xFF000080L

// Indonesia — Sang Saka Merah Putih, red over white. Same palette as
// Singapore (shares the red-and-white motif with the crescent omitted).
private const val INDONESIA_RED = 0xFFCE1126L
private const val INDONESIA_WHITE = 0xFFF5F5F5L

// Pakistan — flag green with white crescent-and-star band.
private const val PAKISTAN_GREEN = 0xFF01411CL
private const val PAKISTAN_WHITE = 0xFFF5F5F5L

// Bangladesh — bottle green with a deep red sun disc, evoking the
// blood-of-martyrs symbolism of the 1971 War of Independence.
private const val BANGLADESH_GREEN = 0xFF006A4EL
private const val BANGLADESH_RED = 0xFFF42A41L

// Russia — flag white / blue / red horizontal tricolour. Victory Day adds
// the Saint George ribbon's black-and-orange diagonal stripes via stroke.
private const val RUSSIA_WHITE = 0xFFF5F5F5L
private const val RUSSIA_BLUE = 0xFF0039A6L
private const val RUSSIA_RED = 0xFFD52B1EL
private const val RUSSIA_RIBBON_ORANGE = 0xFFE08B00L
private const val RUSSIA_RIBBON_BLACK = 0xFF1A1A1AL

// Ukraine — flag azure-over-yellow (sky over wheat). Defender of Ukraine
// Day adds a sunflower / wheat-stalk accent.
private const val UKRAINE_BLUE = 0xFF0057B7L
private const val UKRAINE_YELLOW = 0xFFFFD500L

// Philippines — flag deep blue / red with white triangle and gold sun.
private const val PHILIPPINES_BLUE = 0xFF0038A8L
private const val PHILIPPINES_RED = 0xFFCE1126L
private const val PHILIPPINES_WHITE = 0xFFF5F5F5L
private const val PHILIPPINES_GOLD = 0xFFFCD116L

// Vietnam — Cờ đỏ sao vàng, red over a five-pointed yellow star.
private const val VIETNAM_RED = 0xFFDA251DL
private const val VIETNAM_YELLOW = 0xFFFFFF00L

// Turkey — Ay Yıldız, crimson red with a white crescent and star.
private const val TURKEY_RED = 0xFFE30A17L
private const val TURKEY_WHITE = 0xFFF5F5F5L

// Egypt — flag red / white / black horizontal tricolour with the
// Eagle of Saladin in gold on the white band.
private const val EGYPT_RED = 0xFFCE1126L
private const val EGYPT_WHITE = 0xFFF5F5F5L
private const val EGYPT_BLACK = 0xFF1A1A1AL
private const val EGYPT_GOLD = 0xFFC09300L

// Nigeria — flag green / white / green vertical tricolour.
private const val NIGERIA_GREEN = 0xFF008751L
private const val NIGERIA_WHITE = 0xFFF5F5F5L

// Thailand — Thong Trairong tricolour (red / white / blue / white / red).
// Songkran (Thai New Year, mid-April) adds water-pouring blue.
private const val THAILAND_RED = 0xFFA51931L
private const val THAILAND_WHITE = 0xFFF5F5F5L
private const val THAILAND_BLUE = 0xFF2D2A4AL
private const val SONGKRAN_WATER_BLUE = 0xFF29B6F6L
private const val SONGKRAN_GOLD = 0xFFFFB300L

// Malaysia — Jalur Gemilang red / white stripes with blue canton + gold
// crescent and 14-point star.
private const val MALAYSIA_BLUE = 0xFF010066L
private const val MALAYSIA_RED = 0xFFCC0001L
private const val MALAYSIA_YELLOW = 0xFFFFCC00L

// Singapore — red over white with white crescent and stars (shares the
// red / white motif with Indonesia, but flipped: red on top in both).
private const val SINGAPORE_RED = 0xFFEF3340L
private const val SINGAPORE_WHITE = 0xFFF5F5F5L

// South Africa — six-colour flag; we anchor on the springbok-green Y-shape
// + gold trim for Heritage / Freedom Days, with the deep blue serving as
// the Reconciliation-Day base.
private const val SA_GREEN = 0xFF007749L
private const val SA_GOLD = 0xFFFFB81CL
private const val SA_BLUE = 0xFF002395L
private const val SA_RED = 0xFFDE3831L

// Argentina — Celeste y Blanco horizontal stripes with golden Sol de Mayo.
private const val ARGENTINA_BLUE = 0xFF74ACDFL
private const val ARGENTINA_WHITE = 0xFFF5F5F5L
private const val ARGENTINA_GOLD = 0xFFFFD700L

// Mexico — additions beyond the Cinco de Mayo green / white / red already
// defined above. Día de los Muertos: marigold (cempasúchil) orange + deep
// purple papel picado with white calavera stroke; Independence Day uses
// the full flag tricolour (green / white / red, same as Cinco).
private const val MEXICO_MARIGOLD = 0xFFFF8F00L
private const val MEXICO_DEAD_PURPLE = 0xFF5E35B1L
private const val MEXICO_DEAD_WHITE = 0xFFF5F5F5L

// Synthetic themes used by [FestiveThemes] when the user has opted into
// calendar-sourced theming and a row arrives carrying [EventKind.PUBLIC_HOLIDAY]
// or [EventKind.BIRTHDAY]. Generic festive gold/purple for a holiday name we
// don't recognise (catalog gaps); religion-specific palettes when the owner
// account identifies one of Google's religion-keyed holiday calendars
// (Eid from "Muslim Holidays", Yom Kippur from "Jewish Holidays", Diwali
// from "Hindu Holidays"); and a confetti yellow/magenta for a detected
// birthday — gender-neutral, maximum party pop, and visually distinct from
// every catalog holiday and the generic-holiday fallback above so a "Bob's
// birthday" in the banner never gets mistaken for a recognised holiday.
private const val FESTIVE_GOLD = 0xFFD4AF37L
private const val FESTIVE_PURPLE = 0xFF6A1B9AL
private const val ISLAMIC_GREEN = 0xFF1B8A4BL
private const val ISLAMIC_GOLD = 0xFFD4AF37L
private const val JUDAISM_BLUE = 0xFF0038B8L
private const val JUDAISM_WHITE = 0xFFFAFAFAL
private const val HINDU_SAFFRON = 0xFFFF9933L
private const val HINDU_MAGENTA = 0xFFC2185BL
private const val BIRTHDAY_YELLOW = 0xFFFFD54FL
private const val BIRTHDAY_MAGENTA = 0xFFD81B60L

/**
 * Religion of a calendar-sourced public holiday, inferred from the source
 * calendar's owner account. Detection is locale-stable: Google localises the
 * locale prefix (`en.`, `de.`, `fr.`, …) and the event titles, but the
 * religion key (`judaism`, `islamic`, `hinduism`) stays fixed, so a French
 * phone seeing "Aïd el-Fitr" still themes green/gold. Region-keyed calendars
 * (`en.usa`, `en.indian`, `en.australian`) and any non-Google source fall
 * through to `null` and pick up the generic gold/purple fallback.
 *
 * The set is intentionally small. Adding more religions is cheap (one regex
 * arm + one palette) but each one is a colour-design call, so leave it to a
 * follow-up when there's a concrete user asking for it.
 */
private enum class HolidayReligion { ISLAMIC, JUDAISM, HINDUISM }

private val RELIGION_OWNER_REGEX = Regex(
    "\\.(judaism|islamic|hinduism)#holiday@group\\.v\\.calendar\\.google\\.com$",
    RegexOption.IGNORE_CASE,
)

private fun religionFromOwnerAccount(ownerAccount: String?): HolidayReligion? {
    if (ownerAccount == null) return null
    val match = RELIGION_OWNER_REGEX.find(ownerAccount) ?: return null
    return when (match.groupValues[1].lowercase()) {
        "islamic" -> HolidayReligion.ISLAMIC
        "judaism" -> HolidayReligion.JUDAISM
        "hinduism" -> HolidayReligion.HINDUISM
        else -> null
    }
}

/**
 * Cleans up a calendar-sourced public-holiday title for display in the
 * Today banner. Google's holiday calendars routinely append a region in
 * parentheses ("King's Birthday (Western Australia)", "Labour Day (most
 * regions)") — strip a single trailing parenthetical so the banner reads
 * as the holiday's plain name. Also folds a curly apostrophe to a straight
 * one and trims trailing punctuation / whitespace, so the various forms a
 * title can arrive in ("Presidents' Day", "Presidents’ Day.",
 * "Presidents' Day (Observed)") all collapse to the same clean string.
 *
 * Display-only: the raw title never crosses the device boundary (see the
 * privacy note on [FestiveThemes] / [CalendarEvent.title]); this only
 * tidies what the on-screen banner shows.
 */
fun normalizeCalendarHolidayTitle(raw: String): String =
    raw.replace('’', '\'')
        .trim()
        // Drop trailing punctuation first so a region suffix is still flush
        // with the end of the string ("King's Birthday (WA)!" → strip "!" →
        // strip "(WA)").
        .trimEnd('!', '?', '.', ',', ';', ':', ' ')
        .replace(Regex("\\s*\\([^()]*\\)$"), "")
        .trim()
        .trimEnd('!', '?', '.', ',', ';', ':', ' ')
        .trim()
        .ifBlank { raw.trim() }

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
    fun publicHoliday(title: String, ownerAccount: String? = null): HolidayTheme {
        val display = normalizeCalendarHolidayTitle(title)
        val religion = religionFromOwnerAccount(ownerAccount)
        val emoji = when (religion) {
            HolidayReligion.ISLAMIC -> "🌙"
            HolidayReligion.JUDAISM -> "✡️"
            HolidayReligion.HINDUISM -> "🪔"
            null -> "🎊"
        }
        val (top, bottom) = when (religion) {
            HolidayReligion.ISLAMIC -> ISLAMIC_GREEN to ISLAMIC_GOLD
            HolidayReligion.JUDAISM -> JUDAISM_BLUE to JUDAISM_WHITE
            HolidayReligion.HINDUISM -> HINDU_SAFFRON to HINDU_MAGENTA
            null -> FESTIVE_GOLD to FESTIVE_PURPLE
        }
        return HolidayTheme(
            id = HolidayId.GENERIC_PUBLIC_HOLIDAY,
            displayNameKey = display,
            bannerTextKey = display,
            emoji = emoji,
            topOverrides = topPaletteAll(top),
            bottomOverrides = bottomPaletteAll(bottom),
            bannerArgb = top,
            countries = emptySet(),
            isSynthetic = true,
            displayTitleOverride = display,
        )
    }

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
