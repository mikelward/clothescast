package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayDate
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.HolidayTheme
import java.time.LocalDate

/**
 * Maps `today` + the user's per-holiday overrides + their enabled-country
 * filter to a [HolidayTheme] when one fires, otherwise `null`. Pure-Kotlin,
 * JVM-testable; no [java.time.Clock] dependency — callers pass the
 * [LocalDate] they want resolved.
 *
 * A theme fires when its date predicate matches *and*:
 *  - [HolidayOverride.ON]: always (the country picker is bypassed — a
 *    user can force-on Bastille Day from Australia).
 *  - [HolidayOverride.AUTO] (the default): at least one of the theme's
 *    [HolidayTheme.countries] is in `enabledCountries`. Missing entries
 *    in [overrides] are treated as AUTO.
 *  - [HolidayOverride.OFF]: never (the country picker is bypassed in the
 *    opposite direction).
 *
 * Catalog order is calendar order, which is also precedence order if two
 * holidays ever land on the same date — the v1 list has one collision
 * (St David's vs Korean Independence on Mar 1), resolved by the country
 * picker for any user not in both GB and KR.
 *
 * Constructor takes the catalog so tests can inject a smaller list.
 */
class HolidayResolver(
    private val catalog: List<Pair<HolidayDate, HolidayTheme>> = HolidayCatalog.all,
) {
    fun resolve(
        date: LocalDate,
        overrides: Map<HolidayId, HolidayOverride>,
        enabledCountries: Set<String>,
    ): HolidayTheme? = catalog.firstOrNull { (predicate, theme) ->
        if (!predicate.matches(date)) return@firstOrNull false
        when (overrides[theme.id] ?: HolidayOverride.AUTO) {
            HolidayOverride.ON -> true
            HolidayOverride.OFF -> false
            HolidayOverride.AUTO -> theme.countries.any { it in enabledCountries }
        }
    }?.second

    /**
     * Computes the effective Auto state for [id] given the current country
     * set — what the resolver would return if [id]'s override were
     * [HolidayOverride.AUTO]. Used by the Settings UI to render the
     * "Auto (on)" / "Auto (off)" dropdown label without flipping the
     * per-holiday state in the user's head.
     */
    fun autoResolution(id: HolidayId, enabledCountries: Set<String>): Boolean {
        val theme = catalog.firstOrNull { it.second.id == id }?.second ?: return false
        return theme.countries.any { it in enabledCountries }
    }
}
