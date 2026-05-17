package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayDate
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayTheme
import java.time.LocalDate

/**
 * Maps `today` + the user's set of enabled holidays to a [HolidayTheme] when
 * one fires, otherwise `null`. Pure-Kotlin, JVM-testable; no [java.time.Clock]
 * dependency — callers pass the [LocalDate] they want resolved (the
 * `TodayViewModel` reads it from its injected clock, tests pass a fixed date).
 *
 * Catalog order is calendar order, which is also precedence order if two
 * holidays ever landed on the same date — the v1 list has no collisions.
 *
 * Constructor takes the catalog so tests can inject a smaller list (a one-
 * entry catalog isolates "did the predicate match?" from "did the right
 * theme come back?"). Production callers use the default.
 */
class HolidayResolver(
    private val catalog: List<Pair<HolidayDate, HolidayTheme>> = HolidayCatalog.all,
) {
    fun resolve(date: LocalDate, enabled: Set<HolidayId>): HolidayTheme? {
        if (enabled.isEmpty()) return null
        return catalog.firstOrNull { (predicate, theme) ->
            theme.id in enabled && predicate.matches(date)
        }?.second
    }
}
