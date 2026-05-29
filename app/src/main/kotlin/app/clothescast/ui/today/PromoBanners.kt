package app.clothescast.ui.today

/**
 * The setup / promo cards on the Today screen, in descending priority. Used by
 * [promoBannersToShow] to decide which of them render — distinct from the
 * operational banners (update / crash / work-status / holiday), which aren't
 * capped.
 */
internal enum class PromoBanner { LOCATION, TELEMETRY, CLOTHES, SCHEDULE, PLAY, GEMINI, CELEBRATION }

/**
 * Decides which promo cards the Today screen shows, capping the stack so a
 * fresh user isn't buried under "set this up" noise. Eligible cards are taken
 * in priority order (location > privacy > clothes > schedule > play > gemini > celebration) up
 * to [maxVisible]; the rest wait until a higher one is resolved or dismissed.
 *
 * The five customization nudges — clothes ([clothesPromoEligible]),
 * schedule ([schedulePromoEligible]), play ([playPromoEligible]),
 * Gemini voices ([geminiPromoEligible]), and
 * holiday/birthday theming ([celebrationEligible]) — are additionally held
 * back until [hasForecast]:
 * the user has received at least one forecast (foreground or background), i.e.
 * is no longer brand-new. So an empty-cache first-run only ever sees location +
 * privacy; the customization promos join the (still capped) pool once any
 * forecast lands. Location and privacy aren't gated this way — a brand-new user
 * with no location needs the location prompt, and the telemetry disclosure
 * shouldn't be silently withheld.
 */
internal fun promoBannersToShow(
    locationActionRequired: Boolean,
    telemetryNoticeVisible: Boolean,
    clothesPromoEligible: Boolean,
    schedulePromoEligible: Boolean,
    playPromoEligible: Boolean,
    geminiPromoEligible: Boolean,
    celebrationEligible: Boolean,
    hasForecast: Boolean,
    maxVisible: Int = 2,
): Set<PromoBanner> {
    val eligible = buildList {
        if (locationActionRequired) add(PromoBanner.LOCATION)
        if (telemetryNoticeVisible) add(PromoBanner.TELEMETRY)
        if (clothesPromoEligible && hasForecast) add(PromoBanner.CLOTHES)
        if (schedulePromoEligible && hasForecast) add(PromoBanner.SCHEDULE)
        if (playPromoEligible && hasForecast) add(PromoBanner.PLAY)
        if (geminiPromoEligible && hasForecast) add(PromoBanner.GEMINI)
        if (celebrationEligible && hasForecast) add(PromoBanner.CELEBRATION)
    }
    return eligible.take(maxVisible).toSet()
}
