package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalTime

class CalendarEventTest {

    private fun event(start: LocalTime, end: LocalTime, allDay: Boolean = false) =
        CalendarEvent(title = "gig", start = start, end = end, allDay = allDay)

    @Test
    fun `overlaps is a half-open interval for a same-day event`() {
        val e = event(LocalTime.of(20, 0), LocalTime.of(22, 0))
        e.overlaps(LocalTime.of(19, 59)) shouldBe false
        e.overlaps(LocalTime.of(20, 0)) shouldBe true
        e.overlaps(LocalTime.of(21, 30)) shouldBe true
        e.overlaps(LocalTime.of(22, 0)) shouldBe false
    }

    @Test
    fun `midnight-crossing event overlaps both sides of midnight`() {
        // start/end are date-less wall-clock times, so a 21:00-00:30 gig
        // projects with end < start. It must read as a wrapped interval —
        // the late-evening events the tonight tie-in exists for would
        // otherwise never overlap any peak hour.
        val gig = event(LocalTime.of(21, 0), LocalTime.of(0, 30))
        gig.overlaps(LocalTime.of(20, 59)) shouldBe false
        gig.overlaps(LocalTime.of(21, 0)) shouldBe true
        gig.overlaps(LocalTime.of(23, 0)) shouldBe true
        gig.overlaps(LocalTime.MIDNIGHT) shouldBe true
        gig.overlaps(LocalTime.of(0, 29)) shouldBe true
        gig.overlaps(LocalTime.of(0, 30)) shouldBe false
        gig.overlaps(LocalTime.of(12, 0)) shouldBe false
    }

    @Test
    fun `event ending exactly at midnight covers the evening only`() {
        // end = 00:00 reads as "until midnight", not an empty interval.
        val e = event(LocalTime.of(21, 0), LocalTime.MIDNIGHT)
        e.overlaps(LocalTime.of(23, 0)) shouldBe true
        e.overlaps(LocalTime.MIDNIGHT) shouldBe false
        e.overlaps(LocalTime.of(20, 0)) shouldBe false
    }

    @Test
    fun `all-day events never overlap`() {
        val e = event(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, allDay = true)
        e.overlaps(LocalTime.of(12, 0)) shouldBe false
        e.overlaps(LocalTime.MIDNIGHT) shouldBe false
    }
}
