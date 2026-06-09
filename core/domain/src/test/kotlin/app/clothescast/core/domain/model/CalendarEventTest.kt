package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CalendarEventTest {

    private val day: LocalDate = LocalDate.of(2026, 4, 25)

    private fun event(start: LocalDateTime, end: LocalDateTime, allDay: Boolean = false) =
        CalendarEvent(title = "gig", start = start, end = end, allDay = allDay)

    @Test
    fun `overlaps is a half-open interval for a same-day event`() {
        val e = event(day.atTime(20, 0), day.atTime(22, 0))
        e.overlaps(day.atTime(19, 59)) shouldBe false
        e.overlaps(day.atTime(20, 0)) shouldBe true
        e.overlaps(day.atTime(21, 30)) shouldBe true
        e.overlaps(day.atTime(22, 0)) shouldBe false
    }

    @Test
    fun `midnight-crossing event overlaps both sides of midnight with plain interval semantics`() {
        // start/end carry dates, so a 21:00 gig ending at 00:30 the next day
        // is just an interval whose end falls on the next date — no wrapped-
        // interval special case needed for the overlap to hold on both sides
        // of midnight.
        val nextDay = day.plusDays(1)
        val gig = event(day.atTime(21, 0), nextDay.atTime(0, 30))
        gig.overlaps(day.atTime(20, 59)) shouldBe false
        gig.overlaps(day.atTime(21, 0)) shouldBe true
        gig.overlaps(day.atTime(23, 0)) shouldBe true
        gig.overlaps(nextDay.atStartOfDay()) shouldBe true
        gig.overlaps(nextDay.atTime(0, 29)) shouldBe true
        gig.overlaps(nextDay.atTime(0, 30)) shouldBe false
        // Same wall-clock hour on the wrong date never matches.
        gig.overlaps(day.atTime(0, 15)) shouldBe false
        gig.overlaps(day.atTime(12, 0)) shouldBe false
        gig.overlaps(nextDay.atTime(21, 0)) shouldBe false
    }

    @Test
    fun `event ending exactly at midnight covers the evening only`() {
        // end = next-day 00:00 reads as "until midnight", half-open.
        val e = event(day.atTime(21, 0), day.plusDays(1).atStartOfDay())
        e.overlaps(day.atTime(23, 0)) shouldBe true
        e.overlaps(day.plusDays(1).atStartOfDay()) shouldBe false
        e.overlaps(day.atTime(20, 0)) shouldBe false
    }

    @Test
    fun `zero-length event matches only its own instant`() {
        val e = event(day.atTime(9, 0), day.atTime(9, 0))
        e.overlaps(day.atTime(9, 0)) shouldBe true
        e.overlaps(day.atTime(9, 1)) shouldBe false
    }

    @Test
    fun `end-before-start interval never matches`() {
        // Defensive: a reader bug yielding end < start reads as empty, not wrapped.
        val e = event(day.atTime(21, 0), day.atTime(20, 0))
        e.overlaps(day.atTime(20, 30)) shouldBe false
        e.overlaps(day.atTime(21, 0)) shouldBe false
        e.overlaps(day.atTime(22, 0)) shouldBe false
    }

    @Test
    fun `all-day events never overlap`() {
        val e = event(day.atStartOfDay(), day.atStartOfDay(), allDay = true)
        e.overlaps(day.atTime(12, 0)) shouldBe false
        e.overlaps(day.atStartOfDay()) shouldBe false
    }
}
