package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalTime

class MorningSchedulesTest {

    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
    private val weekend = setOf(SATURDAY, SUNDAY)

    private fun primary(days: Set<DayOfWeek>, time: LocalTime = LocalTime.of(7, 0)) =
        MorningScheduleEntry(MorningSchedules.PRIMARY_ID, time, days)

    @Test
    fun `addEntry on the default all-week primary splits off the weekend`() {
        val entries = listOf(primary(Schedule.EVERY_DAY))

        val result = MorningSchedules.addEntry(entries, LocalTime.of(9, 0))

        val primary = result.first { it.id == MorningSchedules.PRIMARY_ID }
        val extra = result.first { it.id != MorningSchedules.PRIMARY_ID }
        primary.days shouldBe weekdays
        extra.days shouldBe weekend
        extra.time shouldBe LocalTime.of(9, 0)
        extra.id shouldBe 1
    }

    @Test
    fun `addEntry peels off the latest day when no weekend is left on the primary`() {
        val entries = listOf(primary(weekdays))

        val result = MorningSchedules.addEntry(entries, LocalTime.of(8, 30))

        result.first { it.id == MorningSchedules.PRIMARY_ID }.days shouldBe
            setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY)
        result.first { it.id != MorningSchedules.PRIMARY_ID }.days shouldBe setOf(FRIDAY)
    }

    @Test
    fun `addEntry is refused when the primary has only one day`() {
        val entries = listOf(primary(setOf(MONDAY)))

        MorningSchedules.canAddEntry(entries) shouldBe false
        MorningSchedules.addEntry(entries, LocalTime.of(9, 0)) shouldBe entries
    }

    @Test
    fun `claiming a day for an entry strips it from the primary`() {
        val entries = listOf(
            primary(weekdays),
            MorningScheduleEntry(1, LocalTime.of(9, 0), weekend),
        )

        // Friday currently belongs to the primary; move it to the weekend entry.
        val result = MorningSchedules.toggleDay(entries, entryId = 1, day = FRIDAY)

        result.first { it.id == MorningSchedules.PRIMARY_ID }.days shouldBe
            setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY)
        result.first { it.id == 1 }.days shouldBe setOf(FRIDAY, SATURDAY, SUNDAY)
    }

    @Test
    fun `claiming a day for the primary strips it from the entry that held it`() {
        val entries = listOf(
            primary(weekdays),
            MorningScheduleEntry(1, LocalTime.of(9, 0), weekend),
        )

        // Pull Saturday back to the primary.
        val result = MorningSchedules.toggleDay(entries, MorningSchedules.PRIMARY_ID, SATURDAY)

        result.first { it.id == MorningSchedules.PRIMARY_ID }.days shouldBe (weekdays + SATURDAY)
        result.first { it.id == 1 }.days shouldBe setOf(SUNDAY)
    }

    @Test
    fun `releasing an entry's last day drops the entry`() {
        val entries = listOf(
            primary(weekdays + SATURDAY),
            MorningScheduleEntry(1, LocalTime.of(9, 0), setOf(SUNDAY)),
        )

        val result = MorningSchedules.toggleDay(entries, entryId = 1, day = SUNDAY)

        result.none { it.id == 1 } shouldBe true
        // Sunday is now uncovered — released, not handed back.
        result.first { it.id == MorningSchedules.PRIMARY_ID }.days shouldBe (weekdays + SATURDAY)
    }

    @Test
    fun `releasing the primary's last day is refused`() {
        val entries = listOf(primary(setOf(MONDAY)))

        MorningSchedules.toggleDay(entries, MorningSchedules.PRIMARY_ID, MONDAY) shouldBe entries
    }

    @Test
    fun `claiming the primary's last day for an entry is refused`() {
        val entries = listOf(
            primary(setOf(MONDAY)),
            MorningScheduleEntry(1, LocalTime.of(9, 0), weekend),
        )

        MorningSchedules.toggleDay(entries, entryId = 1, day = MONDAY) shouldBe entries
    }

    @Test
    fun `removeEntry hands its days back to the primary`() {
        val entries = listOf(
            primary(weekdays),
            MorningScheduleEntry(1, LocalTime.of(9, 0), weekend),
        )

        val result = MorningSchedules.removeEntry(entries, entryId = 1)

        result.map { it.id } shouldBe listOf(MorningSchedules.PRIMARY_ID)
        result.first().days shouldBe Schedule.EVERY_DAY
    }

    @Test
    fun `nextId is monotonic across the highest existing id`() {
        val entries = listOf(
            primary(weekdays),
            MorningScheduleEntry(1, LocalTime.of(9, 0), setOf(SATURDAY)),
            MorningScheduleEntry(5, LocalTime.of(10, 0), setOf(SUNDAY)),
        )

        MorningSchedules.nextId(entries) shouldBe 6
    }
}
