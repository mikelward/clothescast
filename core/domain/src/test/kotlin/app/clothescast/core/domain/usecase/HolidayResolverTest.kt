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

    // --- Fixed-date holidays. One representative case per holiday — we check
    // the exact date matches, then bracket day-before / day-after to confirm
    // the predicate isn't off-by-one. Christmas + a couple of leading-zero
    // and end-of-month dates cover the obvious off-by-one traps.

    @Test
    fun `Christmas matches Dec 25`() {
        subject.resolve(LocalDate.of(2026, 12, 25), allOn)?.id shouldBe HolidayId.CHRISTMAS_DAY
        subject.resolve(LocalDate.of(2026, 12, 24), allOn).shouldBeNull()
        subject.resolve(LocalDate.of(2026, 12, 26), allOn).shouldBeNull()
    }

    @Test
    fun `New Year matches Jan 1`() {
        subject.resolve(LocalDate.of(2026, 1, 1), allOn)?.id shouldBe HolidayId.NEW_YEARS_DAY
        subject.resolve(LocalDate.of(2025, 12, 31), allOn).shouldBeNull()
        subject.resolve(LocalDate.of(2026, 1, 2), allOn).shouldBeNull()
    }

    @Test
    fun `Australia Day matches Jan 26`() {
        subject.resolve(LocalDate.of(2026, 1, 26), allOn)?.id shouldBe HolidayId.AUSTRALIA_DAY
    }

    @Test
    fun `Valentines Day matches Feb 14`() {
        subject.resolve(LocalDate.of(2026, 2, 14), allOn)?.id shouldBe HolidayId.VALENTINES_DAY
    }

    @Test
    fun `St Davids Day matches Mar 1`() {
        subject.resolve(LocalDate.of(2026, 3, 1), allOn)?.id shouldBe HolidayId.ST_DAVIDS_DAY
    }

    @Test
    fun `St Patricks Day matches Mar 17`() {
        subject.resolve(LocalDate.of(2026, 3, 17), allOn)?.id shouldBe HolidayId.ST_PATRICKS_DAY
    }

    @Test
    fun `St Georges Day matches Apr 23`() {
        subject.resolve(LocalDate.of(2026, 4, 23), allOn)?.id shouldBe HolidayId.ST_GEORGES_DAY
    }

    @Test
    fun `Anzac Day matches Apr 25`() {
        subject.resolve(LocalDate.of(2026, 4, 25), allOn)?.id shouldBe HolidayId.ANZAC_DAY
    }

    @Test
    fun `Italian Republic Day matches Jun 2`() {
        subject.resolve(LocalDate.of(2026, 6, 2), allOn)?.id shouldBe HolidayId.ITALY_REPUBLIC_DAY
    }

    @Test
    fun `Canada Day matches Jul 1`() {
        subject.resolve(LocalDate.of(2026, 7, 1), allOn)?.id shouldBe HolidayId.CANADA_DAY
    }

    @Test
    fun `US Independence Day matches Jul 4`() {
        subject.resolve(LocalDate.of(2026, 7, 4), allOn)?.id shouldBe HolidayId.US_INDEPENDENCE_DAY
    }

    @Test
    fun `Bastille Day matches Jul 14`() {
        subject.resolve(LocalDate.of(2026, 7, 14), allOn)?.id shouldBe HolidayId.BASTILLE_DAY
    }

    @Test
    fun `Brazil Independence matches Sep 7`() {
        subject.resolve(LocalDate.of(2026, 9, 7), allOn)?.id shouldBe HolidayId.BRAZIL_INDEPENDENCE_DAY
    }

    @Test
    fun `German Unity Day matches Oct 3`() {
        subject.resolve(LocalDate.of(2026, 10, 3), allOn)?.id shouldBe HolidayId.GERMAN_UNITY_DAY
    }

    @Test
    fun `Spain Hispanic Day matches Oct 12`() {
        subject.resolve(LocalDate.of(2026, 10, 12), allOn)?.id shouldBe HolidayId.SPAIN_HISPANIC_DAY
    }

    @Test
    fun `Halloween matches Oct 31`() {
        subject.resolve(LocalDate.of(2026, 10, 31), allOn)?.id shouldBe HolidayId.HALLOWEEN
        subject.resolve(LocalDate.of(2026, 11, 1), allOn).shouldBeNull()
    }

    @Test
    fun `Bonfire Night matches Nov 5`() {
        subject.resolve(LocalDate.of(2026, 11, 5), allOn)?.id shouldBe HolidayId.BONFIRE_NIGHT
    }

    @Test
    fun `Remembrance Day matches Nov 11`() {
        subject.resolve(LocalDate.of(2026, 11, 11), allOn)?.id shouldBe HolidayId.REMEMBRANCE_DAY
        subject.resolve(LocalDate.of(2026, 11, 10), allOn).shouldBeNull()
        subject.resolve(LocalDate.of(2026, 11, 12), allOn).shouldBeNull()
    }

    @Test
    fun `St Andrews Day matches Nov 30`() {
        subject.resolve(LocalDate.of(2026, 11, 30), allOn)?.id shouldBe HolidayId.ST_ANDREWS_DAY
        // End-of-month boundary — make sure December 1 is not a match.
        subject.resolve(LocalDate.of(2026, 12, 1), allOn).shouldBeNull()
    }

    // --- Nth-weekday holidays. Several years per entry so the date maths
    // is checked across a span where the leading day-of-month shifts.

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
                subject.resolve(d, allOn)?.id shouldBe HolidayId.MOTHERS_DAY
            }
        }
        // First Sunday of May 2026 (May 3) shouldn't match.
        subject.resolve(LocalDate.of(2026, 5, 3), allOn).shouldBeNull()
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
                subject.resolve(d, allOn)?.id shouldBe HolidayId.FATHERS_DAY_JUN
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
                subject.resolve(d, allOn)?.id shouldBe HolidayId.FATHERS_DAY_SEP
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
                subject.resolve(d, allOn)?.id shouldBe HolidayId.US_THANKSGIVING
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
                subject.resolve(d, thanksgivingOnly).shouldBeNull()
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
                subject.resolve(date, emptySet()).shouldBeNull()
            }
        }
    }

    @Test
    fun `single-holiday enabled set returns only that holiday`() {
        val christmasOnly = setOf(HolidayId.CHRISTMAS_DAY)
        // On St Patrick's Day with only Christmas enabled, no match.
        subject.resolve(LocalDate.of(2026, 3, 17), christmasOnly).shouldBeNull()
        // On Christmas itself, Christmas wins.
        subject.resolve(LocalDate.of(2026, 12, 25), christmasOnly)?.id shouldBe HolidayId.CHRISTMAS_DAY
    }

    @Test
    fun `non-holiday date returns null even with all enabled`() {
        // A deliberately unremarkable date well clear of every entry.
        subject.resolve(LocalDate.of(2026, 8, 11), allOn).shouldBeNull()
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

    private fun withClue(clue: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("[$clue] ${e.message}", e)
        }
    }
}
