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
    ): HolidayTheme? = resolveAll(date, overrides, enabledCountries).firstOrNull()

    /**
     * Every catalog theme that fires on [date] for this user, in catalog
     * (calendar / precedence) order. Same per-theme firing rule as
     * [resolve] — [HolidayOverride.ON] always, [HolidayOverride.OFF] never,
     * [HolidayOverride.AUTO] when a country matches. [ThemeForToday] uses
     * this to compose a joined banner when several celebrations land on the
     * same day (e.g. a UK Spring Bank Holiday alongside Towel Day).
     */
    fun resolveAll(
        date: LocalDate,
        overrides: Map<HolidayId, HolidayOverride>,
        enabledCountries: Set<String>,
    ): List<HolidayTheme> = catalog.filter { (predicate, theme) ->
        if (!predicate.matches(date)) return@filter false
        when (overrides[theme.id] ?: HolidayOverride.AUTO) {
            HolidayOverride.ON -> true
            HolidayOverride.OFF -> false
            HolidayOverride.AUTO -> theme.countries.any { it in enabledCountries }
        }
    }.map { it.second }

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

    /**
     * `true` iff any catalog entry's date predicate matches [date] AND
     * the entry is one the user could see — i.e. at least one of its
     * countries is in [enabledCountries]. Used by [ThemeForToday] to
     * suppress calendar-sourced holiday fallbacks on dates the curated
     * catalog has an *eligible* opinion about.
     *
     * The country filter is what keeps unrelated catalog entries from
     * silencing legitimate calendar holidays. Example: a Portuguese
     * user opting into calendar holidays sees Apr 25's "Freedom Day"
     * from their PT holiday calendar, even though the catalog has
     * Anzac Day (AU/NZ) and Italian Liberation Day (IT) on the same
     * date — the PT user has none of those countries enabled, so the
     * catalog has no opinion *they* could act on, and the calendar
     * fallback fires correctly.
     *
     * Per-holiday overrides aren't checked here: an ON override falls
     * out of [resolve] before this is called, so we only reach the
     * fallback when resolve returned null. An OFF override on a holiday
     * whose country *is* enabled still suppresses the fallback — the
     * user's explicit opt-out beats a calendar event with the same date.
     */
    fun hasCatalogMatch(date: LocalDate, enabledCountries: Set<String>): Boolean =
        catalog.any { (predicate, theme) ->
            predicate.matches(date) && theme.countries.any { it in enabledCountries }
        }
}
