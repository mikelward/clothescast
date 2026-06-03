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
            playPromoEligible = false,
            geminiPromoEligible = false,
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
            playPromoEligible = false,
            geminiPromoEligible = false,
            celebrationEligible = true,
            hasForecast = true,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.CLOTHES, PromoBanner.SCHEDULE)
    }

    @Test
    fun `only privacy shows to a brand-new user with no forecast`() {
        // The empty-cache screen carries its own "set up your location"
        // placeholder, so the location banner is forecast-gated to avoid
        // double-prompting; telemetry isn't gated, so it's the only promo a
        // first-run (empty-cache) user can see.
        promoBannersToShow(
            locationActionRequired = true,
            telemetryNoticeVisible = true,
            clothesPromoEligible = true,
            schedulePromoEligible = true,
            playPromoEligible = false,
            geminiPromoEligible = false,
            celebrationEligible = true,
            hasForecast = false,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.TELEMETRY)
    }

    @Test
    fun `location banner waits for a forecast before taking over from the empty state`() {
        // No forecast yet: the empty-state placeholder owns the location prompt,
        // so the banner stays hidden even though location is required.
        promoBannersToShow(
            locationActionRequired = true,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = false,
            geminiPromoEligible = false,
            celebrationEligible = false,
            hasForecast = false,
        ) shouldBe emptySet()
    }

    @Test
    fun `at most two promos show, taken in priority order`() {
        promoBannersToShow(
            locationActionRequired = true,
            telemetryNoticeVisible = true,
            clothesPromoEligible = true,
            schedulePromoEligible = true,
            playPromoEligible = false,
            geminiPromoEligible = false,
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
            playPromoEligible = false,
            geminiPromoEligible = false,
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
            playPromoEligible = false,
            geminiPromoEligible = false,
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
            playPromoEligible = false,
            geminiPromoEligible = false,
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
            playPromoEligible = false,
            geminiPromoEligible = false,
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
            playPromoEligible = false,
            geminiPromoEligible = false,
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
            playPromoEligible = false,
            geminiPromoEligible = false,
            celebrationEligible = false,
            hasForecast = true,
            maxVisible = 1,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.CLOTHES)
    }

    @Test
    fun `play promo outranks schedule under the cap`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = true,
            playPromoEligible = true,
            geminiPromoEligible = false,
            celebrationEligible = false,
            hasForecast = true,
            maxVisible = 1,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.PLAY)
    }

    @Test
    fun `play promo is held back until a forecast exists`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = true,
            geminiPromoEligible = false,
            celebrationEligible = false,
            hasForecast = false,
        ) shouldBe emptySet()
    }

    @Test
    fun `play promo shows once a forecast exists`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = true,
            geminiPromoEligible = false,
            celebrationEligible = false,
            hasForecast = true,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.PLAY)
    }

    @Test
    fun `play promo outranks celebration under the cap`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = true,
            geminiPromoEligible = false,
            celebrationEligible = true,
            hasForecast = true,
            maxVisible = 1,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.PLAY)
    }

    @Test
    fun `gemini promo is held back until a forecast exists`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = false,
            geminiPromoEligible = true,
            celebrationEligible = false,
            hasForecast = false,
        ) shouldBe emptySet()
    }

    @Test
    fun `gemini promo shows once a forecast exists`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = false,
            geminiPromoEligible = true,
            celebrationEligible = false,
            hasForecast = true,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.GEMINI)
    }

    @Test
    fun `gemini promo outranks celebration under the cap`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = false,
            geminiPromoEligible = true,
            celebrationEligible = true,
            hasForecast = true,
            maxVisible = 1,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.GEMINI)
    }

    @Test
    fun `smart home promo is held back until a forecast exists`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = false,
            geminiPromoEligible = false,
            celebrationEligible = false,
            smartHomePromoEligible = true,
            hasForecast = false,
        ) shouldBe emptySet()
    }

    @Test
    fun `smart home promo shows once a forecast exists`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = false,
            geminiPromoEligible = false,
            celebrationEligible = false,
            smartHomePromoEligible = true,
            hasForecast = true,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.SMART_HOME)
    }

    @Test
    fun `smart home promo waits behind celebration under the cap`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = false,
            geminiPromoEligible = false,
            celebrationEligible = true,
            smartHomePromoEligible = true,
            hasForecast = true,
            maxVisible = 1,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.CELEBRATION)
    }

    @Test
    fun `gemini promo outranks schedule under the cap`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = true,
            playPromoEligible = false,
            geminiPromoEligible = true,
            celebrationEligible = false,
            hasForecast = true,
            maxVisible = 1,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.GEMINI)
    }

    @Test
    fun `play promo outranks gemini under the cap`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = true,
            geminiPromoEligible = true,
            celebrationEligible = false,
            hasForecast = true,
            maxVisible = 1,
        ) shouldContainExactlyInAnyOrder listOf(PromoBanner.PLAY)
    }

    @Test
    fun `nothing eligible shows nothing`() {
        promoBannersToShow(
            locationActionRequired = false,
            telemetryNoticeVisible = false,
            clothesPromoEligible = false,
            schedulePromoEligible = false,
            playPromoEligible = false,
            geminiPromoEligible = false,
            celebrationEligible = false,
            hasForecast = true,
        ) shouldBe emptySet()
    }
}
