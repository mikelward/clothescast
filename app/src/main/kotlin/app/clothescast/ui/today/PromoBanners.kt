package app.clothescast.ui.today

/**
 * The setup / promo cards on the Today screen, in descending priority. Used by
 * [promoBannersToShow] to decide which of them render — distinct from the
 * operational banners (update / crash / work-status / holiday), which aren't
 * capped.
 */
internal enum class PromoBanner { LOCATION, TELEMETRY, CLOTHES, PLAY, GEMINI, SCHEDULE, CELEBRATION }

/**
 * Decides which promo cards the Today screen shows, capping the stack so a
 * fresh user isn't buried under "set this up" noise. Eligible cards are taken
 * in priority order (location > privacy > clothes > play > gemini > schedule > celebration) up
 * to [maxVisible]; the rest wait until a higher one is resolved or dismissed.
 *
 * The five customization nudges — clothes ([clothesPromoEligible]),
 * schedule ([schedulePromoEligible]), play ([playPromoEligible]),
 * Gemini voices ([geminiPromoEligible]), and
 * holiday/birthday theming ([celebrationEligible]) — plus the location prompt
 * ([locationActionRequired]) are held back until [hasForecast]:
 * the user has received at least one forecast (foreground or background), i.e.
 * is no longer brand-new. The location banner is forecast-gated because the
 * empty-cache screen already carries its own "set up your location" placeholder
 * ([EmptyState]); doubling it up with a banner would prompt the same fix twice.
 * Once a (possibly stale) forecast is on screen the empty state is gone, so the
 * banner takes over as the place that explains why the visible forecast can't
 * refresh. The telemetry disclosure isn't gated this way — it shouldn't be
 * silently withheld from a first-run user.
 *
 * [telemetryInviteDismissedForSession] is the X on that invitation, which
 * stores nothing and means "not now". It is applied here rather than only
 * where the card renders: hidden at render time alone, `TELEMETRY` went on
 * holding one of the [maxVisible] slots while showing nothing, so a
 * lower-priority setup card stayed hidden for the rest of the session
 * (Codex, PR #1161).
 */
internal fun promoBannersToShow(
    locationActionRequired: Boolean,
    telemetryNoticeVisible: Boolean,
    telemetryInviteDismissedForSession: Boolean = false,
    clothesPromoEligible: Boolean,
    schedulePromoEligible: Boolean,
    playPromoEligible: Boolean,
    geminiPromoEligible: Boolean,
    celebrationEligible: Boolean,
    hasForecast: Boolean,
    maxVisible: Int = 2,
): Set<PromoBanner> {
    val eligible = buildList {
        if (locationActionRequired && hasForecast) add(PromoBanner.LOCATION)
        if (telemetryNoticeVisible && !telemetryInviteDismissedForSession) {
            add(PromoBanner.TELEMETRY)
        }
        if (clothesPromoEligible && hasForecast) add(PromoBanner.CLOTHES)
        if (playPromoEligible && hasForecast) add(PromoBanner.PLAY)
        if (geminiPromoEligible && hasForecast) add(PromoBanner.GEMINI)
        if (schedulePromoEligible && hasForecast) add(PromoBanner.SCHEDULE)
        if (celebrationEligible && hasForecast) add(PromoBanner.CELEBRATION)
    }
    return eligible.take(maxVisible).toSet()
}
