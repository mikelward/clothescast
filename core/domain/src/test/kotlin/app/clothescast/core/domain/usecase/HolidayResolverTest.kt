package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayDate
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import org.junit.jupiter.api.Test

class HolidayResolverTest {
    private val subject = HolidayResolver()
    /** All holidays AUTO — the default state. */
    private val noOverrides: Map<HolidayId, HolidayOverride> = emptyMap()
    private val allCountries: Set<String> = HolidayCatalog.allCountries

    /**
     * Build an override map that lets only [ids] fire (forced [HolidayOverride.ON])
     * with everything else forced [HolidayOverride.OFF]. Used by single-holiday
     * isolation tests so date predicates can be exercised without the country
     * filter or any AUTO siblings dragging in a different theme.
     */
    private fun onlyOn(vararg ids: HolidayId): Map<HolidayId, HolidayOverride> =
        HolidayId.entries.associateWith { id ->
            if (id in ids) HolidayOverride.ON else HolidayOverride.OFF
        }

    // --- Fixed-date holidays. One representative case per holiday — we check
    // the exact date matches, then bracket day-before / day-after to confirm
    // the predicate isn't off-by-one. Christmas + a couple of leading-zero
    // and end-of-month dates cover the obvious off-by-one traps.

    @Test
    fun `Christmas matches Dec 25 with Boxing Day immediately after`() {
        subject.resolve(LocalDate.of(2026, 12, 25), noOverrides, allCountries)?.id shouldBe HolidayId.CHRISTMAS_DAY
        subject.resolve(LocalDate.of(2026, 12, 24), noOverrides, allCountries).shouldBeNull()
        subject.resolve(LocalDate.of(2026, 12, 26), noOverrides, allCountries)?.id shouldBe HolidayId.BOXING_DAY
    }

    @Test
    fun `New Year matches Jan 1`() {
        subject.resolve(LocalDate.of(2026, 1, 1), noOverrides, allCountries)?.id shouldBe HolidayId.NEW_YEARS_DAY
        subject.resolve(LocalDate.of(2025, 12, 31), noOverrides, allCountries).shouldBeNull()
        subject.resolve(LocalDate.of(2026, 1, 2), noOverrides, allCountries).shouldBeNull()
    }

    @Test
    fun `Burns Night matches Jan 25`() {
        subject.resolve(LocalDate.of(2026, 1, 25), noOverrides, allCountries)?.id shouldBe HolidayId.BURNS_NIGHT
    }

    @Test
    fun `Australia Day matches Jan 26`() {
        subject.resolve(LocalDate.of(2026, 1, 26), noOverrides, allCountries)?.id shouldBe HolidayId.AUSTRALIA_DAY
    }

    @Test
    fun `Waitangi Day matches Feb 6`() {
        subject.resolve(LocalDate.of(2026, 2, 6), noOverrides, allCountries)?.id shouldBe HolidayId.WAITANGI_DAY
    }

    @Test
    fun `Japan Greenery Day matches May 4`() {
        subject.resolve(LocalDate.of(2026, 5, 4), noOverrides, allCountries)?.id shouldBe HolidayId.JAPAN_GREENERY_DAY
    }

    @Test
    fun `Croatia Statehood Day matches May 30`() {
        subject.resolve(LocalDate.of(2026, 5, 30), noOverrides, allCountries)?.id shouldBe HolidayId.CROATIA_STATEHOOD_DAY
    }

    @Test
    fun `Korean Memorial Day matches Jun 6`() {
        subject.resolve(LocalDate.of(2026, 6, 6), noOverrides, allCountries)?.id shouldBe HolidayId.KOREAN_MEMORIAL_DAY
    }

    @Test
    fun `Juneteenth matches Jun 19`() {
        subject.resolve(LocalDate.of(2026, 6, 19), noOverrides, allCountries)?.id shouldBe HolidayId.JUNETEENTH
    }

    @Test
    fun `Korean Liberation Day matches Aug 15`() {
        // Aug 15 is a three-way collision: KR Liberation, Catholic Assumption,
        // and IN Independence all share the date. Gate to KR so a multi-
        // country user resolving via catalog-order doesn't drag in the
        // Indian entry that now sits earlier in the catalog.
        subject.resolve(LocalDate.of(2026, 8, 15), noOverrides, setOf("KR"))?.id shouldBe HolidayId.KOREAN_LIBERATION_DAY
    }

    @Test
    fun `Korean Hangeul Day matches Oct 9`() {
        subject.resolve(LocalDate.of(2026, 10, 9), noOverrides, allCountries)?.id shouldBe HolidayId.KOREAN_HANGEUL_DAY
    }

    @Test
    fun `Japan Culture Day matches Nov 3`() {
        subject.resolve(LocalDate.of(2026, 11, 3), noOverrides, allCountries)?.id shouldBe HolidayId.JAPAN_CULTURE_DAY
    }

    @Test
    fun `Boxing Day matches Dec 26`() {
        subject.resolve(LocalDate.of(2026, 12, 26), noOverrides, allCountries)?.id shouldBe HolidayId.BOXING_DAY
    }

    @Test
    fun `St Davids wins over Korean Independence Movement Day on Mar 1`() {
        // Same calendar date; catalog order picks St David's first under
        // allCountries. Restricting the country filter to KR (no GB) flips
        // the resolution to the Korean entry — see the same-date-collision
        // test in the country-filter section below.
        subject.resolve(LocalDate.of(2026, 3, 1), noOverrides, allCountries)?.id shouldBe HolidayId.ST_DAVIDS_DAY
    }

    @Test
    fun `Valentines Day matches Feb 14`() {
        subject.resolve(LocalDate.of(2026, 2, 14), noOverrides, allCountries)?.id shouldBe HolidayId.VALENTINES_DAY
    }

    @Test
    fun `St Davids Day matches Mar 1`() {
        subject.resolve(LocalDate.of(2026, 3, 1), noOverrides, allCountries)?.id shouldBe HolidayId.ST_DAVIDS_DAY
    }

    @Test
    fun `St Patricks Day matches Mar 17`() {
        subject.resolve(LocalDate.of(2026, 3, 17), noOverrides, allCountries)?.id shouldBe HolidayId.ST_PATRICKS_DAY
    }

    @Test
    fun `St Georges Day matches Apr 23`() {
        subject.resolve(LocalDate.of(2026, 4, 23), noOverrides, allCountries)?.id shouldBe HolidayId.ST_GEORGES_DAY
    }

    @Test
    fun `Anzac Day matches Apr 25`() {
        subject.resolve(LocalDate.of(2026, 4, 25), noOverrides, allCountries)?.id shouldBe HolidayId.ANZAC_DAY
    }

    @Test
    fun `Italian Republic Day matches Jun 2`() {
        subject.resolve(LocalDate.of(2026, 6, 2), noOverrides, allCountries)?.id shouldBe HolidayId.ITALY_REPUBLIC_DAY
    }

    @Test
    fun `Canada Day matches Jul 1`() {
        subject.resolve(LocalDate.of(2026, 7, 1), noOverrides, allCountries)?.id shouldBe HolidayId.CANADA_DAY
    }

    @Test
    fun `US Independence Day matches Jul 4`() {
        subject.resolve(LocalDate.of(2026, 7, 4), noOverrides, allCountries)?.id shouldBe HolidayId.US_INDEPENDENCE_DAY
    }

    @Test
    fun `Bastille Day matches Jul 14`() {
        subject.resolve(LocalDate.of(2026, 7, 14), noOverrides, allCountries)?.id shouldBe HolidayId.BASTILLE_DAY
    }

    @Test
    fun `Brazil Independence matches Sep 7`() {
        subject.resolve(LocalDate.of(2026, 9, 7), noOverrides, allCountries)?.id shouldBe HolidayId.BRAZIL_INDEPENDENCE_DAY
    }

    @Test
    fun `German Unity Day matches Oct 3`() {
        subject.resolve(LocalDate.of(2026, 10, 3), noOverrides, allCountries)?.id shouldBe HolidayId.GERMAN_UNITY_DAY
    }

    @Test
    fun `Spain Hispanic Day matches Oct 12`() {
        // Use a year where Oct 12 isn't the 2nd Monday — in years where it
        // is (e.g. 2026), Canadian Thanksgiving wins under no overrides
        // since it's listed first. Single-holiday override isolates Spain.
        val spainOnly = onlyOn(HolidayId.SPAIN_HISPANIC_DAY)
        listOf(
            LocalDate.of(2025, 10, 12), // Sunday
            LocalDate.of(2026, 10, 12), // Monday — would clash with Canadian Thanksgiving under noOverrides
            LocalDate.of(2027, 10, 12), // Tuesday
        ).forEach { d ->
            withClue(d.toString()) {
                subject.resolve(d, spainOnly, allCountries)?.id shouldBe HolidayId.SPAIN_HISPANIC_DAY
            }
        }
    }

    @Test
    fun `Halloween matches Oct 31`() {
        subject.resolve(LocalDate.of(2026, 10, 31), noOverrides, allCountries)?.id shouldBe HolidayId.HALLOWEEN
        // Nov 1 fires All Saints' Day under all-countries enabled — the
        // catalog entry covers AT, DE, ES, FR, HR, IT. Asserting the
        // resolved id keeps this test's scope on the Halloween entry's
        // dateboundary without sliding into a wider "nothing else fires".
        subject.resolve(LocalDate.of(2026, 11, 1), noOverrides, allCountries)?.id shouldBe HolidayId.ALL_SAINTS_DAY
    }

    @Test
    fun `Bonfire Night matches Nov 5`() {
        subject.resolve(LocalDate.of(2026, 11, 5), noOverrides, allCountries)?.id shouldBe HolidayId.BONFIRE_NIGHT
    }

    @Test
    fun `Remembrance Day matches Nov 11`() {
        subject.resolve(LocalDate.of(2026, 11, 11), noOverrides, allCountries)?.id shouldBe HolidayId.REMEMBRANCE_DAY
        subject.resolve(LocalDate.of(2026, 11, 10), noOverrides, allCountries).shouldBeNull()
        subject.resolve(LocalDate.of(2026, 11, 12), noOverrides, allCountries).shouldBeNull()
    }

    @Test
    fun `UK Remembrance Sunday matches the 2nd Sunday of November for GB`() {
        listOf(
            LocalDate.of(2025, 11, 9),
            LocalDate.of(2026, 11, 8),
            LocalDate.of(2027, 11, 14),
            LocalDate.of(2028, 11, 12),
            LocalDate.of(2030, 11, 10),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.SUNDAY
                subject.resolve(d, noOverrides, setOf("GB"))?.id shouldBe HolidayId.UK_REMEMBRANCE_SUNDAY
            }
        }
        // Non-GB users on the same date see nothing (the date isn't tagged
        // for any other country, and Nov 8 / 14 aren't Nov 11).
        subject.resolve(LocalDate.of(2026, 11, 8), noOverrides, setOf("US")).shouldBeNull()
    }

    @Test
    fun `St Andrews Day matches Nov 30`() {
        // Nov 30 collides with PH Bonifacio Day; gate to GB so the catalog-
        // order tiebreak doesn't drag in the Philippine entry.
        subject.resolve(LocalDate.of(2026, 11, 30), noOverrides, setOf("GB"))?.id shouldBe HolidayId.ST_ANDREWS_DAY
        subject.resolve(LocalDate.of(2026, 12, 1), noOverrides, setOf("GB")).shouldBeNull()
    }

    // --- Nth-weekday holidays.

    @Test
    fun `MLK Day matches 3rd Monday of January`() {
        listOf(
            LocalDate.of(2025, 1, 20),
            LocalDate.of(2026, 1, 19),
            LocalDate.of(2027, 1, 18),
            LocalDate.of(2028, 1, 17),
            LocalDate.of(2029, 1, 15),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, noOverrides, allCountries)?.id shouldBe HolidayId.MLK_DAY
            }
        }
    }

    @Test
    fun `US Presidents Day matches 3rd Monday of February`() {
        listOf(
            LocalDate.of(2025, 2, 17),
            LocalDate.of(2026, 2, 16),
            LocalDate.of(2027, 2, 15),
            LocalDate.of(2028, 2, 21),
            LocalDate.of(2029, 2, 19),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, noOverrides, allCountries)?.id shouldBe HolidayId.US_PRESIDENTS_DAY
            }
        }
        // Non-3rd Mondays in February should NOT match Presidents' Day.
        val presidentsOnly = onlyOn(HolidayId.US_PRESIDENTS_DAY)
        listOf(2, 9, 23).forEach { dom ->
            val d = LocalDate.of(2026, 2, dom)
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, presidentsOnly, allCountries).shouldBeNull()
            }
        }
    }

    @Test
    fun `Japan Coming of Age Day matches 2nd Monday of January`() {
        listOf(
            LocalDate.of(2025, 1, 13),
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2027, 1, 11),
            LocalDate.of(2028, 1, 10),
            LocalDate.of(2029, 1, 8),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, noOverrides, allCountries)?.id shouldBe HolidayId.JAPAN_COMING_OF_AGE_DAY
            }
        }
    }

    @Test
    fun `US Memorial Day matches the LAST Monday of May`() {
        listOf(
            LocalDate.of(2025, 5, 26),
            LocalDate.of(2026, 5, 25),
            LocalDate.of(2027, 5, 31),
            LocalDate.of(2028, 5, 29),
            LocalDate.of(2029, 5, 28),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                // Last Mon May can collide with Argentina's May Revolution
                // (May 25, 2026) and UK Spring Bank Holiday — gate to US so
                // catalog-order picks Memorial Day cleanly for each test year.
                subject.resolve(d, noOverrides, setOf("US"))?.id shouldBe HolidayId.US_MEMORIAL_DAY
            }
        }
        // Non-last Mondays in May should NOT match Memorial Day.
        val memorialOnly = onlyOn(HolidayId.US_MEMORIAL_DAY)
        listOf(3, 10, 17, 24).forEach { dom ->
            val d = LocalDate.of(2027, 5, dom)
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, memorialOnly, allCountries).shouldBeNull()
            }
        }
    }

    @Test
    fun `Japan Marine Day matches 3rd Monday of July`() {
        listOf(
            LocalDate.of(2025, 7, 21),
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2027, 7, 19),
            LocalDate.of(2028, 7, 17),
            LocalDate.of(2029, 7, 16),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, noOverrides, allCountries)?.id shouldBe HolidayId.JAPAN_MARINE_DAY
            }
        }
    }

    @Test
    fun `Canadian Thanksgiving matches 2nd Monday of October`() {
        // Some years (2026 → Oct 12, 2028 → Oct 9) collide with other fixed
        // holidays on the same date; single-holiday isolation keeps the
        // predicate test focused on Canadian Thanksgiving.
        val canadianOnly = onlyOn(HolidayId.CANADIAN_THANKSGIVING)
        listOf(
            LocalDate.of(2025, 10, 13),
            LocalDate.of(2026, 10, 12),
            LocalDate.of(2027, 10, 11),
            LocalDate.of(2028, 10, 9),
            LocalDate.of(2029, 10, 8),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, canadianOnly, allCountries)?.id shouldBe HolidayId.CANADIAN_THANKSGIVING
            }
        }
    }

    @Test
    fun `Mothers Day matches 2nd Sunday of May`() {
        listOf(
            LocalDate.of(2025, 5, 11),
            LocalDate.of(2026, 5, 10),
            LocalDate.of(2027, 5, 9),
            LocalDate.of(2028, 5, 14),
            LocalDate.of(2029, 5, 13),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.SUNDAY
                // 2nd Sun May (2027 = May 9) collides with Russia Victory Day
                // (May 9 fixed); gate to US so catalog-order picks Mother's Day.
                subject.resolve(d, noOverrides, setOf("US"))?.id shouldBe HolidayId.MOTHERS_DAY
            }
        }
        subject.resolve(LocalDate.of(2026, 5, 3), noOverrides, setOf("US")).shouldBeNull()
    }

    @Test
    fun `Fathers Day Jun matches 3rd Sunday of June`() {
        listOf(
            LocalDate.of(2025, 6, 15),
            LocalDate.of(2026, 6, 21),
            LocalDate.of(2027, 6, 20),
            LocalDate.of(2028, 6, 18),
            LocalDate.of(2029, 6, 17),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.SUNDAY
                subject.resolve(d, noOverrides, allCountries)?.id shouldBe HolidayId.FATHERS_DAY_JUN
            }
        }
    }

    @Test
    fun `Fathers Day Sep matches 1st Sunday of September`() {
        listOf(
            LocalDate.of(2025, 9, 7),
            LocalDate.of(2026, 9, 6),
            LocalDate.of(2027, 9, 5),
            LocalDate.of(2028, 9, 3),
            LocalDate.of(2029, 9, 2),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.SUNDAY
                subject.resolve(d, noOverrides, allCountries)?.id shouldBe HolidayId.FATHERS_DAY_SEP
            }
        }
    }

    @Test
    fun `Thanksgiving matches 4th Thursday of November`() {
        listOf(
            LocalDate.of(2026, 11, 26),
            LocalDate.of(2027, 11, 25),
            LocalDate.of(2028, 11, 23),
            LocalDate.of(2029, 11, 22),
            LocalDate.of(2030, 11, 28),
        ).forEach { d ->
            withClue(d.toString()) {
                subject.resolve(d, noOverrides, allCountries)?.id shouldBe HolidayId.US_THANKSGIVING
            }
        }
    }

    @Test
    fun `Thanksgiving does not match other Thursdays in November`() {
        val thanksgivingOnly = onlyOn(HolidayId.US_THANKSGIVING)
        listOf(5, 12, 19).forEach { dom ->
            val d = LocalDate.of(2026, 11, dom)
            withClue("$d (dayOfWeek=${d.dayOfWeek})") {
                d.dayOfWeek shouldBe DayOfWeek.THURSDAY
                subject.resolve(d, thanksgivingOnly, allCountries).shouldBeNull()
            }
        }
    }

    // --- Override semantics. AUTO follows the country filter; ON and OFF
    // bypass it in either direction.

    @Test
    fun `OFF override hides a holiday that AUTO would fire`() {
        // Christmas would fire on Dec 25 by default (GLOBAL ∈ allCountries).
        // OFF forces it to skip.
        val christmasOff = mapOf(HolidayId.CHRISTMAS_DAY to HolidayOverride.OFF)
        subject.resolve(LocalDate.of(2026, 12, 25), christmasOff, allCountries).shouldBeNull()
    }

    @Test
    fun `ON override fires a holiday even when its country is not enabled`() {
        // Bastille Day's countries = {FR}. With country filter empty,
        // AUTO would skip; ON forces it on anyway.
        val bastilleOn = mapOf(HolidayId.BASTILLE_DAY to HolidayOverride.ON)
        subject.resolve(LocalDate.of(2026, 7, 14), bastilleOn, emptySet())
            ?.id shouldBe HolidayId.BASTILLE_DAY
    }

    @Test
    fun `ON override still respects the date predicate`() {
        // Forcing Christmas ON doesn't make it fire on a random date.
        val christmasOn = mapOf(HolidayId.CHRISTMAS_DAY to HolidayOverride.ON)
        subject.resolve(LocalDate.of(2026, 7, 4), christmasOn, allCountries)
            ?.id shouldBe HolidayId.US_INDEPENDENCE_DAY  // July 4 — US Indep wins under allCountries
    }

    @Test
    fun `OFF override on a non-matching date is a no-op`() {
        // Christmas OFF on March 17: St Patrick's still fires (AUTO).
        val christmasOff = mapOf(HolidayId.CHRISTMAS_DAY to HolidayOverride.OFF)
        subject.resolve(LocalDate.of(2026, 3, 17), christmasOff, allCountries)
            ?.id shouldBe HolidayId.ST_PATRICKS_DAY
    }

    // --- Country filter.

    @Test
    fun `empty enabledCountries returns null when no overrides force ON`() {
        // Every holiday is AUTO with no countries enabled → nothing fires.
        HolidayCatalog.all.forEach { (predicate, theme) ->
            val date = (predicate as? HolidayDate.Fixed)?.let { LocalDate.of(2026, it.month, it.day) }
                ?: LocalDate.of(2026, 11, 26)
            withClue("${theme.id} on $date") {
                subject.resolve(date, noOverrides, emptySet()).shouldBeNull()
            }
        }
    }

    @Test
    fun `country filter excludes otherwise-matching holiday`() {
        val auAndGlobal = setOf("AU", HolidayCatalog.GLOBAL_COUNTRY)
        subject.resolve(LocalDate.of(2026, 7, 14), noOverrides, auAndGlobal).shouldBeNull()
        subject.resolve(LocalDate.of(2026, 7, 14), noOverrides, setOf("FR"))
            ?.id shouldBe HolidayId.BASTILLE_DAY
    }

    @Test
    fun `GLOBAL bucket resolves Christmas without any ISO country enabled`() {
        val globalOnly = setOf(HolidayCatalog.GLOBAL_COUNTRY)
        subject.resolve(LocalDate.of(2026, 12, 25), noOverrides, globalOnly)?.id shouldBe HolidayId.CHRISTMAS_DAY
        // Boxing Day isn't tagged GLOBAL → doesn't fire under GLOBAL-only.
        subject.resolve(LocalDate.of(2026, 12, 26), noOverrides, globalOnly).shouldBeNull()
    }

    @Test
    fun `FUNNY bucket resolves Talk Like a Pirate Day without any ISO country enabled`() {
        val funnyOnly = setOf(HolidayCatalog.FUNNY)
        subject.resolve(LocalDate.of(2026, 9, 19), noOverrides, funnyOnly)?.id shouldBe
            HolidayId.TALK_LIKE_A_PIRATE_DAY
        // Pirate Day isn't tagged GLOBAL → doesn't fire under GLOBAL-only.
        subject.resolve(LocalDate.of(2026, 9, 19), noOverrides, setOf(HolidayCatalog.GLOBAL_COUNTRY))
            .shouldBeNull()
    }

    @Test
    fun `FUNNY bucket resolves Towel Day on May 25`() {
        val funnyOnly = setOf(HolidayCatalog.FUNNY)
        subject.resolve(LocalDate.of(2027, 5, 25), noOverrides, funnyOnly)?.id shouldBe
            HolidayId.TOWEL_DAY
        // Not tagged GLOBAL → doesn't fire under GLOBAL-only.
        subject.resolve(LocalDate.of(2027, 5, 25), noOverrides, setOf(HolidayCatalog.GLOBAL_COUNTRY))
            .shouldBeNull()
    }

    @Test
    fun `last-Monday national holiday wins over Towel Day on a May 25 collision`() {
        // May 25 2026 is the last Monday of May, so US Memorial Day collides
        // with Towel Day. Catalog order places Memorial Day first, so a US
        // user (Funny on by default) still sees Memorial Day; a GB user sees
        // Spring Bank Holiday; a Funny-only user sees Towel Day.
        val date = LocalDate.of(2026, 5, 25)
        subject.resolve(date, noOverrides, setOf("US", HolidayCatalog.FUNNY))?.id shouldBe
            HolidayId.US_MEMORIAL_DAY
        subject.resolve(date, noOverrides, setOf("GB", HolidayCatalog.FUNNY))?.id shouldBe
            HolidayId.UK_SPRING_BANK_HOLIDAY
        subject.resolve(date, noOverrides, setOf(HolidayCatalog.FUNNY))?.id shouldBe
            HolidayId.TOWEL_DAY
    }

    @Test
    fun `same-date collision picks the country-enabled holiday`() {
        subject.resolve(LocalDate.of(2026, 3, 1), noOverrides, setOf("KR"))?.id shouldBe
            HolidayId.KOREAN_INDEPENDENCE_MOVEMENT_DAY
        subject.resolve(LocalDate.of(2026, 3, 1), noOverrides, setOf("GB"))?.id shouldBe
            HolidayId.ST_DAVIDS_DAY
    }

    @Test
    fun `Remembrance Day fires for every tagged country`() {
        val date = LocalDate.of(2026, 11, 11)
        listOf("AU", "CA", "GB", "IE", "NZ", "US", "FR").forEach { country ->
            withClue(country) {
                subject.resolve(date, noOverrides, setOf(country))?.id shouldBe HolidayId.REMEMBRANCE_DAY
            }
        }
        // Not tagged — Japan doesn't observe Nov 11 in this app.
        subject.resolve(date, noOverrides, setOf("JP")).shouldBeNull()
    }

    @Test
    fun `every catalog entry carries at least one country tag`() {
        HolidayCatalog.all.forEach { (_, theme) ->
            withClue(theme.id.name) {
                theme.countries.isNotEmpty() shouldBe true
            }
        }
    }

    // --- Holidays added in the colours-and-themes expansion. Each is gated
    // by country and several share a date with a neighbour, so the cases pin
    // both the date predicate and the country tiebreak.

    @Test
    fun `Cinco de Mayo matches May 5 for MX and US`() {
        subject.resolve(LocalDate.of(2026, 5, 5), noOverrides, setOf("MX"))?.id shouldBe
            HolidayId.CINCO_DE_MAYO
        subject.resolve(LocalDate.of(2026, 5, 5), noOverrides, setOf("US"))?.id shouldBe
            HolidayId.CINCO_DE_MAYO
        subject.resolve(LocalDate.of(2026, 5, 4), noOverrides, setOf("MX")).shouldBeNull()
    }

    @Test
    fun `US Labor Day matches the first Monday of September`() {
        // 2025: Sep 1 is itself the first Monday — no collision with Brazil (Sep 7).
        subject.resolve(LocalDate.of(2025, 9, 1), noOverrides, setOf("US"))?.id shouldBe
            HolidayId.US_LABOR_DAY
        subject.resolve(LocalDate.of(2025, 9, 1), noOverrides, setOf("CA"))?.id shouldBe
            HolidayId.US_LABOR_DAY
    }

    @Test
    fun `NZ Labour Day matches the fourth Monday of October for NZ`() {
        // 2026: 4th Monday of October is the 26th (shared with Austria's fixed
        // national day, but country gating keeps an NZ user on Labour Day).
        subject.resolve(LocalDate.of(2026, 10, 26), noOverrides, setOf("NZ"))?.id shouldBe
            HolidayId.NZ_LABOUR_DAY
        subject.resolve(LocalDate.of(2026, 10, 26), noOverrides, setOf("AT"))?.id shouldBe
            HolidayId.AUSTRIA_NATIONAL_DAY
    }

    @Test
    fun `Melbourne Cup and US Election share Nov 3 2026 but split by country`() {
        // Nov 3 2026 is the first Tuesday (Melbourne Cup), the Tuesday after
        // the first Monday (US Election), and Japan's Culture Day — country
        // gating sends each user to their own theme.
        subject.resolve(LocalDate.of(2026, 11, 3), noOverrides, setOf("AU"))?.id shouldBe
            HolidayId.MELBOURNE_CUP_DAY
        subject.resolve(LocalDate.of(2026, 11, 3), noOverrides, setOf("US"))?.id shouldBe
            HolidayId.US_ELECTION_DAY
        subject.resolve(LocalDate.of(2026, 11, 3), noOverrides, setOf("JP"))?.id shouldBe
            HolidayId.JAPAN_CULTURE_DAY
    }

    @Test
    fun `Bonfire Night fires for both GB and NZ`() {
        subject.resolve(LocalDate.of(2026, 11, 5), noOverrides, setOf("GB"))?.id shouldBe
            HolidayId.BONFIRE_NIGHT
        subject.resolve(LocalDate.of(2026, 11, 5), noOverrides, setOf("NZ"))?.id shouldBe
            HolidayId.BONFIRE_NIGHT
    }

    // --- v4 expansion: new countries + religious-bucket additions. One
    // representative case per country and one per Easter-relative
    // addition; the catalog-integrity invariants below verify every new
    // id has both an enum entry and a catalog row.

    @Test
    fun `Indian national days fire for IN`() {
        // Republic Day (collides with AU Day); Independence Day (collides
        // with KR Liberation and Assumption); Gandhi Jayanti.
        subject.resolve(LocalDate.of(2026, 1, 26), noOverrides, setOf("IN"))?.id shouldBe
            HolidayId.INDIA_REPUBLIC_DAY
        subject.resolve(LocalDate.of(2026, 8, 15), noOverrides, setOf("IN"))?.id shouldBe
            HolidayId.INDIA_INDEPENDENCE_DAY
        subject.resolve(LocalDate.of(2026, 10, 2), noOverrides, setOf("IN"))?.id shouldBe
            HolidayId.INDIA_GANDHI_JAYANTI
        // AU user still resolves Australia Day on Jan 26 — country gate
        // sends each user to their own theme.
        subject.resolve(LocalDate.of(2026, 1, 26), noOverrides, setOf("AU"))?.id shouldBe
            HolidayId.AUSTRALIA_DAY
    }

    @Test
    fun `Ukrainian holidays fire for UA on their post-2023 calendar dates`() {
        // May 8 (Victory over Nazism — distinct from Russia's May 9),
        // Aug 24 (Independence Day), Oct 1 (Defender Day, moved from Oct 14).
        subject.resolve(LocalDate.of(2026, 5, 8), noOverrides, setOf("UA"))?.id shouldBe
            HolidayId.UKRAINE_VICTORY_DAY
        subject.resolve(LocalDate.of(2026, 8, 24), noOverrides, setOf("UA"))?.id shouldBe
            HolidayId.UKRAINE_INDEPENDENCE_DAY
        subject.resolve(LocalDate.of(2026, 10, 1), noOverrides, setOf("UA"))?.id shouldBe
            HolidayId.UKRAINE_DEFENDER_DAY
        // Russia's Victory Day (May 9) is intentionally distinct from
        // Ukraine's May 8 — RU user gets May 9, UA user gets May 8.
        subject.resolve(LocalDate.of(2026, 5, 9), noOverrides, setOf("RU"))?.id shouldBe
            HolidayId.RUSSIA_VICTORY_DAY
        subject.resolve(LocalDate.of(2026, 5, 9), noOverrides, setOf("UA")).shouldBeNull()
    }

    @Test
    fun `Orthodox Christmas fires Jan 7 for ORTHODOX bucket and observing countries but NOT for UA`() {
        // Russia, Serbia, Georgia, Ethiopia, Macedonia, Belarus all observe
        // Jan 7. Ukraine moved Orthodox Christmas to Dec 25 in 2023 and is
        // deliberately not tagged here.
        listOf("RU", "RS", "GE", "ET", "MK", "BY").forEach { country ->
            withClue(country) {
                subject.resolve(LocalDate.of(2026, 1, 7), noOverrides, setOf(country))?.id shouldBe
                    HolidayId.ORTHODOX_CHRISTMAS
            }
        }
        // ORTHODOX sentinel resolves for users who've enabled the bucket
        // without picking a country.
        subject.resolve(LocalDate.of(2026, 1, 7), noOverrides, setOf(HolidayCatalog.ORTHODOX))?.id shouldBe
            HolidayId.ORTHODOX_CHRISTMAS
        // Ukrainian user does NOT see Orthodox Christmas — they get
        // Dec 25 via the global Christmas entry instead.
        subject.resolve(LocalDate.of(2026, 1, 7), noOverrides, setOf("UA")).shouldBeNull()
    }

    @Test
    fun `Day of the Dead fires Nov 2 for MX`() {
        subject.resolve(LocalDate.of(2026, 11, 2), noOverrides, setOf("MX"))?.id shouldBe
            HolidayId.MEXICO_DAY_OF_THE_DEAD
        // Not All Saints (Nov 1) — that's still its own entry for
        // AT/DE/ES/FR/HR/IT.
        subject.resolve(LocalDate.of(2026, 11, 1), noOverrides, setOf("MX")).shouldBeNull()
    }

    @Test
    fun `Songkran fires Apr 13 for TH`() {
        subject.resolve(LocalDate.of(2026, 4, 13), noOverrides, setOf("TH"))?.id shouldBe
            HolidayId.THAILAND_SONGKRAN
    }

    @Test
    fun `Christian-bucket holidays fire via Easter-relative predicates`() {
        // 2026: Easter is Apr 5. Ash Wed = Feb 18, Mardi Gras = Feb 17,
        // Palm Sun = Mar 29, Maundy Thu = Apr 2, Ascension = May 14,
        // Pentecost = May 24, Whit Mon = May 25, Corpus Christi = Jun 4.
        val christianOnly = setOf(HolidayCatalog.CHRISTIAN)
        subject.resolve(LocalDate.of(2026, 2, 17), noOverrides, christianOnly)?.id shouldBe
            HolidayId.MARDI_GRAS
        subject.resolve(LocalDate.of(2026, 2, 18), noOverrides, christianOnly)?.id shouldBe
            HolidayId.ASH_WEDNESDAY
        subject.resolve(LocalDate.of(2026, 3, 29), noOverrides, christianOnly)?.id shouldBe
            HolidayId.PALM_SUNDAY
        subject.resolve(LocalDate.of(2026, 4, 2), noOverrides, christianOnly)?.id shouldBe
            HolidayId.MAUNDY_THURSDAY
        subject.resolve(LocalDate.of(2026, 5, 14), noOverrides, christianOnly)?.id shouldBe
            HolidayId.ASCENSION_DAY
        subject.resolve(LocalDate.of(2026, 5, 24), noOverrides, christianOnly)?.id shouldBe
            HolidayId.PENTECOST
        subject.resolve(LocalDate.of(2026, 5, 25), noOverrides, christianOnly)?.id shouldBe
            HolidayId.WHIT_MONDAY
        subject.resolve(LocalDate.of(2026, 6, 4), noOverrides, christianOnly)?.id shouldBe
            HolidayId.CORPUS_CHRISTI
    }

    @Test
    fun `Ascension Day also fires per-country for FR DE AT without CHRISTIAN bucket`() {
        // Easter-relative Christian holidays are tagged BOTH with the
        // CHRISTIAN sentinel AND with the countries where they're public
        // holidays — so a French user resolves Ascension on May 14 2026
        // even if they've turned off the Christian bucket.
        listOf("FR", "DE", "AT").forEach { country ->
            withClue(country) {
                subject.resolve(LocalDate.of(2026, 5, 14), noOverrides, setOf(country))?.id shouldBe
                    HolidayId.ASCENSION_DAY
            }
        }
    }

    @Test
    fun `same-date Jun 12 collision splits cleanly by country`() {
        // Three holidays share Jun 12: Philippine Independence Day,
        // Nigerian Democracy Day, and Russia Day. Each user resolves
        // their own. Multi-country falls to first-match.
        subject.resolve(LocalDate.of(2026, 6, 12), noOverrides, setOf("PH"))?.id shouldBe
            HolidayId.PHILIPPINES_INDEPENDENCE_DAY
        subject.resolve(LocalDate.of(2026, 6, 12), noOverrides, setOf("NG"))?.id shouldBe
            HolidayId.NIGERIA_DEMOCRACY_DAY
        subject.resolve(LocalDate.of(2026, 6, 12), noOverrides, setOf("RU"))?.id shouldBe
            HolidayId.RUSSIA_DAY
    }

    // --- Auto-resolution helper used by Settings UI for dropdown labels.

    @Test
    fun `autoResolution returns true when any of the theme's countries is enabled`() {
        subject.autoResolution(HolidayId.BASTILLE_DAY, setOf("FR")) shouldBe true
        subject.autoResolution(HolidayId.BASTILLE_DAY, setOf("US")) shouldBe false
        subject.autoResolution(HolidayId.CHRISTMAS_DAY, setOf(HolidayCatalog.GLOBAL_COUNTRY)) shouldBe true
        subject.autoResolution(HolidayId.REMEMBRANCE_DAY, setOf("US")) shouldBe true
        subject.autoResolution(HolidayId.REMEMBRANCE_DAY, setOf("JP")) shouldBe false
    }

    // --- Resolver catalog integrity.

    @Test
    fun `non-holiday date returns null even with no overrides and all countries`() {
        subject.resolve(LocalDate.of(2026, 8, 11), noOverrides, allCountries).shouldBeNull()
    }

    @Test
    fun `every catalog entry has a unique HolidayId`() {
        val ids = HolidayCatalog.all.map { it.second.id }
        ids.toSet().size shouldBe ids.size
    }

    @Test
    fun `every non-synthetic HolidayId appears in the catalog`() {
        val ids = HolidayCatalog.all.map { it.second.id }.toSet()
        HolidayId.entries
            .filterNot { it in HolidayCatalog.SYNTHETIC_IDS }
            .forEach { id ->
                withClue(id.name) { (id in ids) shouldBe true }
            }
    }

    @Test
    fun `themeFor returns the catalog entry for every non-synthetic id`() {
        HolidayId.entries
            .filterNot { it in HolidayCatalog.SYNTHETIC_IDS }
            .forEach { id ->
                withClue(id.name) {
                    val theme = HolidayCatalog.themeFor(id)
                    theme.shouldNotBeNull()
                    theme.id shouldBe id
                }
            }
    }

    @Test
    fun `NthWeekday matches the nth occurrence and rejects others`() {
        val secondMondayMay = HolidayDate.NthWeekday(Month.MAY, 2, DayOfWeek.MONDAY)
        secondMondayMay.matches(LocalDate.of(2026, 5, 11)) shouldBe true
        secondMondayMay.matches(LocalDate.of(2026, 5, 4)) shouldBe false
        secondMondayMay.matches(LocalDate.of(2026, 5, 18)) shouldBe false
        secondMondayMay.matches(LocalDate.of(2026, 6, 8)) shouldBe false
    }

    private fun withClue(clue: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("[$clue] ${e.message}", e)
        }
    }
}
