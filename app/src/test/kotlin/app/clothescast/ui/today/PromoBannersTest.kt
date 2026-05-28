package app.clothescast.ui.today

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PromoBannersTest {

    @Test
    fun `customization promos are hidden until a forecast has been delivered`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = true,
            schedulePromoEligible = true,
            celebrationEligible = true,
            hasForecast = false,
        ) shouldBe emptySet()
    }

    @Test
    fun `customization promos show once a forecast exists`() {
        // Clothes + schedule fill the two slots (priority clothes > schedule >
        // celebration), so celebration waits its turn under the cap.
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = true,
            schedulePromoEligible = true,
            celebrationEligible = true,
            hasForecast = true,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.CLOTHES, PromoBanner.SCHEDULE)
    }

    @Test
    fun `location and privacy show to a brand-new user with no forecast`() {
        // Location + telemetry aren't forecast-gated, so they're the only
        // promos a first-run (empty-cache) user can see.
        promoBannersToShow(
            locationActionRequired = true,
            telemetryNoticeVisible = true,
            clothesPromoEligible = true,
            schedulePromoEligible = true,
            celebrationEligible = true,
            hasForecast = false,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.LOCATION, PromoBanner.TELEMETRY)
    }

    @Test
    fun `at most two promos show, taken in priority order`() {
        promoBannersToShow(
            locationActionRequired = true,
            telemetryNoticeVisible = true,
            clothesPromoEligible = true,
            schedulePromoEligible = true,
            celebrationEligible = true,
            hasForecast = true,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.LOCATION, PromoBanner.TELEMETRY)
    }

    @Test
    fun `dropping a higher promo promotes the next one`() {
        // Location resolved, telemetry acked, schedule already set up: clothes +
        // celebration fill the two slots that location + privacy would otherwise
        // occupy.
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = true,
            schedulePromoEligible = false,
            celebrationEligible = true,
            hasForecast = true,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.CLOTHES, PromoBanner.CELEBRATION)
    }

    @Test
    fun `location always wins a slot when required`() {
        promoBannersToShow(
            locationActionRequired = true,
            telemetryNoticeVisible = false,
            clothesPromoEligible = true,
            schedulePromoEligible = false,
            celebrationEligible = true,
            hasForecast = true,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.LOCATION, PromoBanner.CLOTHES)
    }

    @Test
    fun `schedule promo is held back until a forecast exists`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = true,
            celebrationEligible = false,
            hasForecast = false,
        ) shouldBe emptySet()
    }

    @Test
    fun `schedule promo shows once a forecast exists`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = true,
            celebrationEligible = false,
            hasForecast = true,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.SCHEDULE)
    }

    @Test
    fun `schedule promo outranks celebration under the cap`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = true,
            celebrationEligible = true,
            hasForecast = true,
            maxVisible = 1,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.SCHEDULE)
    }

    @Test
    fun `clothes promo outranks schedule under the cap`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = true,
            schedulePromoEligible = true,
            celebrationEligible = false,
            hasForecast = true,
            maxVisible = 1,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.CLOTHES)
    }

    @Test
    fun `nothing eligible shows nothing`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            celebrationEligible = false,
            hasForecast = true,
        ) shouldBe emptySet()
    }
}
