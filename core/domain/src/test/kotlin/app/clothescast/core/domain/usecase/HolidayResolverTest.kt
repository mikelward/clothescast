package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayDate
import app.clothescast.core.domain.model.HolidayId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import org.junit.jupiter.api.Test

class HolidayResolverTest {
    private val subject = HolidayResolver()
    private val allOn: Set<HolidayId> = HolidayId.entries.toSet()
    private val allCountries: Set<String> = HolidayCatalog.allCountries

    // --- Fixed-date holidays. One representative case per holiday — we check
    // the exact date matches, then bracket day-before / day-after to confirm
    // the predicate isn't off-by-one. Christmas + a couple of leading-zero
    // and end-of-month dates cover the obvious off-by-one traps.

    @Test
    fun `Christmas matches Dec 25 with Boxing Day immediately after`() {
        subject.resolve(LocalDate.of(2026, 12, 25), allOn, allCountries)?.id shouldBe HolidayId.CHRISTMAS_DAY
        subject.resolve(LocalDate.of(2026, 12, 24), allOn, allCountries).shouldBeNull()
        // Dec 26 is Boxing Day — the catalog entry just after Christmas.
        subject.resolve(LocalDate.of(2026, 12, 26), allOn, allCountries)?.id shouldBe HolidayId.BOXING_DAY
    }

    @Test
    fun `New Year matches Jan 1`() {
        subject.resolve(LocalDate.of(2026, 1, 1), allOn, allCountries)?.id shouldBe HolidayId.NEW_YEARS_DAY
        subject.resolve(LocalDate.of(2025, 12, 31), allOn, allCountries).shouldBeNull()
        subject.resolve(LocalDate.of(2026, 1, 2), allOn, allCountries).shouldBeNull()
    }

    @Test
    fun `Burns Night matches Jan 25`() {
        subject.resolve(LocalDate.of(2026, 1, 25), allOn, allCountries)?.id shouldBe HolidayId.BURNS_NIGHT
    }

    @Test
    fun `Australia Day matches Jan 26`() {
        subject.resolve(LocalDate.of(2026, 1, 26), allOn, allCountries)?.id shouldBe HolidayId.AUSTRALIA_DAY
    }

    @Test
    fun `Waitangi Day matches Feb 6`() {
        subject.resolve(LocalDate.of(2026, 2, 6), allOn, allCountries)?.id shouldBe HolidayId.WAITANGI_DAY
    }

    @Test
    fun `Japan Greenery Day matches May 4`() {
        subject.resolve(LocalDate.of(2026, 5, 4), allOn, allCountries)?.id shouldBe HolidayId.JAPAN_GREENERY_DAY
    }

    @Test
    fun `Croatia Statehood Day matches May 30`() {
        subject.resolve(LocalDate.of(2026, 5, 30), allOn, allCountries)?.id shouldBe HolidayId.CROATIA_STATEHOOD_DAY
    }

    @Test
    fun `Korean Memorial Day matches Jun 6`() {
        subject.resolve(LocalDate.of(2026, 6, 6), allOn, allCountries)?.id shouldBe HolidayId.KOREAN_MEMORIAL_DAY
    }

    @Test
    fun `Juneteenth matches Jun 19`() {
        subject.resolve(LocalDate.of(2026, 6, 19), allOn, allCountries)?.id shouldBe HolidayId.JUNETEENTH
    }

    @Test
    fun `Korean Liberation Day matches Aug 15`() {
        subject.resolve(LocalDate.of(2026, 8, 15), allOn, allCountries)?.id shouldBe HolidayId.KOREAN_LIBERATION_DAY
    }

    @Test
    fun `Korean Hangeul Day matches Oct 9`() {
        subject.resolve(LocalDate.of(2026, 10, 9), allOn, allCountries)?.id shouldBe HolidayId.KOREAN_HANGEUL_DAY
    }

    @Test
    fun `Japan Culture Day matches Nov 3`() {
        subject.resolve(LocalDate.of(2026, 11, 3), allOn, allCountries)?.id shouldBe HolidayId.JAPAN_CULTURE_DAY
    }

    @Test
    fun `Boxing Day matches Dec 26`() {
        subject.resolve(LocalDate.of(2026, 12, 26), allOn, allCountries)?.id shouldBe HolidayId.BOXING_DAY
    }

    @Test
    fun `St Davids wins over Korean Independence Movement Day on Mar 1`() {
        // Same calendar date; catalog order picks St David's first. Tracked
        // by the same-date-collision TODO at the top of HolidayId — when
        // location-aware resolution lands, a Korean user will see the
        // Korean entry instead.
        subject.resolve(LocalDate.of(2026, 3, 1), allOn, allCountries)?.id shouldBe HolidayId.ST_DAVIDS_DAY
        // With only Korean enabled, the Korean entry still resolves.
        subject.resolve(
            LocalDate.of(2026, 3, 1),
            setOf(HolidayId.KOREAN_INDEPENDENCE_MOVEMENT_DAY),
            allCountries,
        )?.id shouldBe HolidayId.KOREAN_INDEPENDENCE_MOVEMENT_DAY
    }

    @Test
    fun `Valentines Day matches Feb 14`() {
        subject.resolve(LocalDate.of(2026, 2, 14), allOn, allCountries)?.id shouldBe HolidayId.VALENTINES_DAY
    }

    @Test
    fun `St Davids Day matches Mar 1`() {
        subject.resolve(LocalDate.of(2026, 3, 1), allOn, allCountries)?.id shouldBe HolidayId.ST_DAVIDS_DAY
    }

    @Test
    fun `St Patricks Day matches Mar 17`() {
        subject.resolve(LocalDate.of(2026, 3, 17), allOn, allCountries)?.id shouldBe HolidayId.ST_PATRICKS_DAY
    }

    @Test
    fun `St Georges Day matches Apr 23`() {
        subject.resolve(LocalDate.of(2026, 4, 23), allOn, allCountries)?.id shouldBe HolidayId.ST_GEORGES_DAY
    }

    @Test
    fun `Anzac Day matches Apr 25`() {
        subject.resolve(LocalDate.of(2026, 4, 25), allOn, allCountries)?.id shouldBe HolidayId.ANZAC_DAY
    }

    @Test
    fun `Italian Republic Day matches Jun 2`() {
        subject.resolve(LocalDate.of(2026, 6, 2), allOn, allCountries)?.id shouldBe HolidayId.ITALY_REPUBLIC_DAY
    }

    @Test
    fun `Canada Day matches Jul 1`() {
        subject.resolve(LocalDate.of(2026, 7, 1), allOn, allCountries)?.id shouldBe HolidayId.CANADA_DAY
    }

    @Test
    fun `US Independence Day matches Jul 4`() {
        subject.resolve(LocalDate.of(2026, 7, 4), allOn, allCountries)?.id shouldBe HolidayId.US_INDEPENDENCE_DAY
    }

    @Test
    fun `Bastille Day matches Jul 14`() {
        subject.resolve(LocalDate.of(2026, 7, 14), allOn, allCountries)?.id shouldBe HolidayId.BASTILLE_DAY
    }

    @Test
    fun `Brazil Independence matches Sep 7`() {
        subject.resolve(LocalDate.of(2026, 9, 7), allOn, allCountries)?.id shouldBe HolidayId.BRAZIL_INDEPENDENCE_DAY
    }

    @Test
    fun `German Unity Day matches Oct 3`() {
        subject.resolve(LocalDate.of(2026, 10, 3), allOn, allCountries)?.id shouldBe HolidayId.GERMAN_UNITY_DAY
    }

    @Test
    fun `Spain Hispanic Day matches Oct 12`() {
        // Use a year where Oct 12 isn't the 2nd Monday — in years where it
        // is (e.g. 2026), Canadian Thanksgiving wins under allOn since it's
        // listed first. Single-holiday set isolates Spain to confirm its
        // predicate fires on any Oct 12.
        val spainOnly = setOf(HolidayId.SPAIN_HISPANIC_DAY)
        listOf(
            LocalDate.of(2025, 10, 12), // Sunday
            LocalDate.of(2026, 10, 12), // Monday — would clash with Canadian Thanksgiving under allOn
            LocalDate.of(2027, 10, 12), // Tuesday
        ).forEach { d ->
            withClue(d.toString()) {
                subject.resolve(d, spainOnly, allCountries)?.id shouldBe HolidayId.SPAIN_HISPANIC_DAY
            }
        }
    }

    @Test
    fun `Halloween matches Oct 31`() {
        subject.resolve(LocalDate.of(2026, 10, 31), allOn, allCountries)?.id shouldBe HolidayId.HALLOWEEN
        subject.resolve(LocalDate.of(2026, 11, 1), allOn, allCountries).shouldBeNull()
    }

    @Test
    fun `Bonfire Night matches Nov 5`() {
        subject.resolve(LocalDate.of(2026, 11, 5), allOn, allCountries)?.id shouldBe HolidayId.BONFIRE_NIGHT
    }

    @Test
    fun `Remembrance Day matches Nov 11`() {
        subject.resolve(LocalDate.of(2026, 11, 11), allOn, allCountries)?.id shouldBe HolidayId.REMEMBRANCE_DAY
        subject.resolve(LocalDate.of(2026, 11, 10), allOn, allCountries).shouldBeNull()
        subject.resolve(LocalDate.of(2026, 11, 12), allOn, allCountries).shouldBeNull()
    }

    @Test
    fun `St Andrews Day matches Nov 30`() {
        subject.resolve(LocalDate.of(2026, 11, 30), allOn, allCountries)?.id shouldBe HolidayId.ST_ANDREWS_DAY
        // End-of-month boundary — make sure December 1 is not a match.
        subject.resolve(LocalDate.of(2026, 12, 1), allOn, allCountries).shouldBeNull()
    }

    // --- Nth-weekday holidays. Several years per entry so the date maths
    // is checked across a span where the leading day-of-month shifts.

    @Test
    fun `MLK Day matches 3rd Monday of January`() {
        // 2025-01-20, 2026-01-19, 2027-01-18, 2028-01-17, 2029-01-15.
        listOf(
            LocalDate.of(2025, 1, 20),
            LocalDate.of(2026, 1, 19),
            LocalDate.of(2027, 1, 18),
            LocalDate.of(2028, 1, 17),
            LocalDate.of(2029, 1, 15),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, allOn, allCountries)?.id shouldBe HolidayId.MLK_DAY
            }
        }
    }

    @Test
    fun `Japan Coming of Age Day matches 2nd Monday of January`() {
        // 2025-01-13, 2026-01-12, 2027-01-11, 2028-01-10, 2029-01-08.
        listOf(
            LocalDate.of(2025, 1, 13),
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2027, 1, 11),
            LocalDate.of(2028, 1, 10),
            LocalDate.of(2029, 1, 8),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, allOn, allCountries)?.id shouldBe HolidayId.JAPAN_COMING_OF_AGE_DAY
            }
        }
    }

    @Test
    fun `US Memorial Day matches the LAST Monday of May`() {
        // Last Mon May: 2025-05-26, 2026-05-25, 2027-05-31, 2028-05-29, 2029-05-28.
        // Note 2027 has 5 Mondays — Memorial Day is the 5th, not the 4th.
        listOf(
            LocalDate.of(2025, 5, 26),
            LocalDate.of(2026, 5, 25),
            LocalDate.of(2027, 5, 31),
            LocalDate.of(2028, 5, 29),
            LocalDate.of(2029, 5, 28),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, allOn, allCountries)?.id shouldBe HolidayId.US_MEMORIAL_DAY
            }
        }
        // Non-last Mondays in May should NOT match Memorial Day.
        val thanksgivingOnly = setOf(HolidayId.US_MEMORIAL_DAY)
        // 2027 — first four Mondays (3, 10, 17, 24) should not match the
        // Last-Mon predicate; the 5th (31) does.
        listOf(3, 10, 17, 24).forEach { dom ->
            val d = LocalDate.of(2027, 5, dom)
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, thanksgivingOnly, allCountries).shouldBeNull()
            }
        }
    }

    @Test
    fun `Japan Marine Day matches 3rd Monday of July`() {
        // 2025-07-21, 2026-07-20, 2027-07-19, 2028-07-17, 2029-07-16.
        listOf(
            LocalDate.of(2025, 7, 21),
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2027, 7, 19),
            LocalDate.of(2028, 7, 17),
            LocalDate.of(2029, 7, 16),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.MONDAY
                subject.resolve(d, allOn, allCountries)?.id shouldBe HolidayId.JAPAN_MARINE_DAY
            }
        }
    }

    @Test
    fun `Canadian Thanksgiving matches 2nd Monday of October`() {
        // 2025-10-13, 2026-10-12, 2027-10-11, 2028-10-09, 2029-10-08.
        // In 2028 the 2nd Mon is Oct 9, which also matches Hangeul Day
        // (Oct 9 fixed); in 2026 it's Oct 12 which also matches Hispanic
        // Day. Single-holiday set isolates Canadian Thanksgiving's predicate
        // from those catalog-order collisions.
        val canadianOnly = setOf(HolidayId.CANADIAN_THANKSGIVING)
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
        // Reference dates (Wikipedia): 2025-05-11, 2026-05-10, 2027-05-09,
        // 2028-05-14, 2029-05-13.
        listOf(
            LocalDate.of(2025, 5, 11),
            LocalDate.of(2026, 5, 10),
            LocalDate.of(2027, 5, 9),
            LocalDate.of(2028, 5, 14),
            LocalDate.of(2029, 5, 13),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.SUNDAY
                subject.resolve(d, allOn, allCountries)?.id shouldBe HolidayId.MOTHERS_DAY
            }
        }
        // First Sunday of May 2026 (May 3) shouldn't match.
        subject.resolve(LocalDate.of(2026, 5, 3), allOn, allCountries).shouldBeNull()
    }

    @Test
    fun `Fathers Day Jun matches 3rd Sunday of June`() {
        // 2025-06-15, 2026-06-21, 2027-06-20, 2028-06-18, 2029-06-17.
        listOf(
            LocalDate.of(2025, 6, 15),
            LocalDate.of(2026, 6, 21),
            LocalDate.of(2027, 6, 20),
            LocalDate.of(2028, 6, 18),
            LocalDate.of(2029, 6, 17),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.SUNDAY
                subject.resolve(d, allOn, allCountries)?.id shouldBe HolidayId.FATHERS_DAY_JUN
            }
        }
    }

    @Test
    fun `Fathers Day Sep matches 1st Sunday of September`() {
        // 2025-09-07, 2026-09-06, 2027-09-05, 2028-09-03, 2029-09-02.
        listOf(
            LocalDate.of(2025, 9, 7),
            LocalDate.of(2026, 9, 6),
            LocalDate.of(2027, 9, 5),
            LocalDate.of(2028, 9, 3),
            LocalDate.of(2029, 9, 2),
        ).forEach { d ->
            withClue(d.toString()) {
                d.dayOfWeek shouldBe DayOfWeek.SUNDAY
                subject.resolve(d, allOn, allCountries)?.id shouldBe HolidayId.FATHERS_DAY_SEP
            }
        }
    }

    // --- Thanksgiving = 4th Thursday of November. Five real years so the
    // nth-weekday maths is checked across a span where the leading day
    // shifts.

    @Test
    fun `Thanksgiving matches 4th Thursday of November`() {
        // Known reference dates: Nov 26 2026, Nov 25 2027, Nov 23 2028,
        // Nov 22 2029, Nov 28 2030. Pulled from Wikipedia; cross-checked
        // against java.time below in the negative cases.
        listOf(
            LocalDate.of(2026, 11, 26),
            LocalDate.of(2027, 11, 25),
            LocalDate.of(2028, 11, 23),
            LocalDate.of(2029, 11, 22),
            LocalDate.of(2030, 11, 28),
        ).forEach { d ->
            withClue(d.toString()) {
                subject.resolve(d, allOn, allCountries)?.id shouldBe HolidayId.US_THANKSGIVING
            }
        }
    }

    @Test
    fun `Thanksgiving does not match other Thursdays in November`() {
        // 2026 has Thursdays on Nov 5, 12, 19, 26. Only the 26th is Thanksgiving.
        // Note: Nov 5 is Bonfire Night, so we can't assert "no match" on the
        // full catalogue — instead, check the resolver doesn't pick Thanksgiving
        // when only Thanksgiving is enabled and the date is a non-4th Thursday.
        val thanksgivingOnly = setOf(HolidayId.US_THANKSGIVING)
        listOf(5, 12, 19).forEach { dom ->
            val d = LocalDate.of(2026, 11, dom)
            withClue("$d (dayOfWeek=${d.dayOfWeek})") {
                d.dayOfWeek shouldBe DayOfWeek.THURSDAY
                subject.resolve(d, thanksgivingOnly, allCountries).shouldBeNull()
            }
        }
    }

    // --- Resolver semantics.

    @Test
    fun `empty enabled set returns null on every holiday`() {
        HolidayCatalog.all.forEach { (predicate, theme) ->
            val date = (predicate as? HolidayDate.Fixed)?.let { LocalDate.of(2026, it.month, it.day) }
                ?: LocalDate.of(2026, 11, 26) // Thanksgiving 2026 (covers the NthWeekday entry)
            withClue("${theme.id} on $date") {
                subject.resolve(date, emptySet(), allCountries).shouldBeNull()
            }
        }
    }

    @Test
    fun `single-holiday enabled set returns only that holiday`() {
        val christmasOnly = setOf(HolidayId.CHRISTMAS_DAY)
        // On St Patrick's Day with only Christmas enabled, no match.
        subject.resolve(LocalDate.of(2026, 3, 17), christmasOnly, allCountries).shouldBeNull()
        // On Christmas itself, Christmas wins.
        subject.resolve(LocalDate.of(2026, 12, 25), christmasOnly, allCountries)?.id shouldBe HolidayId.CHRISTMAS_DAY
    }

    @Test
    fun `non-holiday date returns null even with all enabled`() {
        // A deliberately unremarkable date well clear of every entry.
        subject.resolve(LocalDate.of(2026, 8, 11), allOn, allCountries).shouldBeNull()
    }

    @Test
    fun `every catalog entry has a unique HolidayId`() {
        val ids = HolidayCatalog.all.map { it.second.id }
        ids.toSet().size shouldBe ids.size
    }

    @Test
    fun `every HolidayId appears in the catalog`() {
        val ids = HolidayCatalog.all.map { it.second.id }.toSet()
        HolidayId.entries.forEach { id ->
            withClue(id.name) { (id in ids) shouldBe true }
        }
    }

    @Test
    fun `themeFor returns the catalog entry`() {
        HolidayId.entries.forEach { id ->
            withClue(id.name) {
                val theme = HolidayCatalog.themeFor(id)
                theme.shouldNotBeNull()
                theme.id shouldBe id
            }
        }
    }

    // --- NthWeekday predicate exercised directly: covers the 1st/2nd/3rd
    // bucket maths Thanksgiving doesn't exercise (it's only ever 4th).

    @Test
    fun `NthWeekday matches the nth occurrence and rejects others`() {
        val secondMondayMay = HolidayDate.NthWeekday(Month.MAY, 2, DayOfWeek.MONDAY)
        // May 2026: Mondays are 4, 11, 18, 25. Second is May 11.
        secondMondayMay.matches(LocalDate.of(2026, 5, 11)) shouldBe true
        secondMondayMay.matches(LocalDate.of(2026, 5, 4)) shouldBe false
        secondMondayMay.matches(LocalDate.of(2026, 5, 18)) shouldBe false
        // Different month, same day-of-week: no match.
        secondMondayMay.matches(LocalDate.of(2026, 6, 8)) shouldBe false
    }

    // --- Country filter. Independent of the per-holiday toggle: a
    // holiday must be both in `enabled` AND have at least one of its
    // [HolidayTheme.countries] in `enabledCountries` to resolve.

    @Test
    fun `empty enabledCountries returns null on every holiday`() {
        // Mirror the empty-enabled test but on the new arg.
        HolidayCatalog.all.forEach { (predicate, theme) ->
            val date = (predicate as? HolidayDate.Fixed)?.let { LocalDate.of(2026, it.month, it.day) }
                ?: LocalDate.of(2026, 11, 26) // Thanksgiving 2026
            withClue("${theme.id} on $date") {
                subject.resolve(date, allOn, emptySet()).shouldBeNull()
            }
        }
    }

    @Test
    fun `country filter excludes otherwise-matching holiday`() {
        // Bastille Day is FR-only; an AU-and-Global user with everything
        // enabled shouldn't see it.
        val auAndGlobal = setOf("AU", HolidayCatalog.GLOBAL_COUNTRY)
        subject.resolve(LocalDate.of(2026, 7, 14), allOn, auAndGlobal).shouldBeNull()
        // Same date with FR enabled: Bastille resolves.
        subject.resolve(LocalDate.of(2026, 7, 14), allOn, setOf("FR"))?.id shouldBe HolidayId.BASTILLE_DAY
    }

    @Test
    fun `GLOBAL bucket resolves Christmas without any ISO country enabled`() {
        val globalOnly = setOf(HolidayCatalog.GLOBAL_COUNTRY)
        subject.resolve(LocalDate.of(2026, 12, 25), allOn, globalOnly)?.id shouldBe HolidayId.CHRISTMAS_DAY
        // Boxing Day (Commonwealth countries) doesn't fire under GLOBAL-only.
        subject.resolve(LocalDate.of(2026, 12, 26), allOn, globalOnly).shouldBeNull()
    }

    @Test
    fun `same-date collision picks the country-enabled holiday`() {
        // Mar 1 — St David's (GB) wins under all-countries catalog order.
        // With KR-only, Korean Independence Movement Day resolves; with
        // GB-only, St David's resolves.
        subject.resolve(LocalDate.of(2026, 3, 1), allOn, setOf("KR"))?.id shouldBe
            HolidayId.KOREAN_INDEPENDENCE_MOVEMENT_DAY
        subject.resolve(LocalDate.of(2026, 3, 1), allOn, setOf("GB"))?.id shouldBe
            HolidayId.ST_DAVIDS_DAY
    }

    @Test
    fun `Remembrance Day fires for every tagged country`() {
        val date = LocalDate.of(2026, 11, 11)
        listOf("AU", "CA", "GB", "IE", "NZ", "US", "FR").forEach { country ->
            withClue(country) {
                subject.resolve(date, allOn, setOf(country))?.id shouldBe HolidayId.REMEMBRANCE_DAY
            }
        }
        // Not in the tagged list — Japan doesn't observe Nov 11 in this app.
        subject.resolve(date, allOn, setOf("JP")).shouldBeNull()
    }

    @Test
    fun `every catalog entry carries at least one country tag`() {
        HolidayCatalog.all.forEach { (_, theme) ->
            withClue(theme.id.name) {
                theme.countries.isNotEmpty() shouldBe true
            }
        }
    }

    private fun withClue(clue: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("[$clue] ${e.message}", e)
        }
    }
}
