package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayDate
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayTheme
import java.time.LocalDate

/**
 * Maps `today` + the user's set of enabled holidays + their enabled-country
 * filter to a [HolidayTheme] when one fires, otherwise `null`. Pure-Kotlin,
 * JVM-testable; no [java.time.Clock] dependency — callers pass the
 * [LocalDate] they want resolved (the `TodayViewModel` reads it from its
 * injected clock, tests pass a fixed date).
 *
 * A theme fires when its date predicate matches, its id is in [enabled],
 * AND at least one of its [HolidayTheme.countries] is in
 * `enabledCountries`. The country filter narrows the per-holiday list the
 * user toggles in Settings; an Australian on the default "Auto" mode never
 * sees Bastille Day even with its per-holiday switch on.
 *
 * Catalog order is calendar order, which is also precedence order if two
 * holidays ever landed on the same date — the v1 list has one collision
 * (St David's vs Korean Independence on Mar 1) resolved by the country
 * filter for users in either KR or GB but still by catalog order for any
 * user with both countries enabled.
 *
 * Constructor takes the catalog so tests can inject a smaller list (a one-
 * entry catalog isolates "did the predicate match?" from "did the right
 * theme come back?"). Production callers use the default.
 */
class HolidayResolver(
    private val catalog: List<Pair<HolidayDate, HolidayTheme>> = HolidayCatalog.all,
) {
    fun resolve(
        date: LocalDate,
        enabled: Set<HolidayId>,
        enabledCountries: Set<String>,
    ): HolidayTheme? {
        if (enabled.isEmpty() || enabledCountries.isEmpty()) return null
        return catalog.firstOrNull { (predicate, theme) ->
            theme.id in enabled &&
                theme.countries.any { it in enabledCountries } &&
                predicate.matches(date)
        }?.second
    }
}
