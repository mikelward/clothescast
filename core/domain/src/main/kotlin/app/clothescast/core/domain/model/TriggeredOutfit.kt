package app.clothescast.core.domain.model

/**
 * The clothes the user is "wearing today" given a forecast — the rules that
 * matched for each slot. The home-screen icon, the bulleted recommendations,
 * and the prose `clothes` clause all read from this so they agree on the same
 * outfit byte-for-byte.
 *
 * The user has two kinds of [ClothesRule]: *threshold* rules they configure
 * in Settings (sweater <18°C, shorts >24°C, …), and a *default* rule per
 * outfit tier — the "If no rules match" picker's [UserPreferences.defaultTop]
 * / [UserPreferences.defaultBottom] — that matches when no threshold rule in
 * the tier did. Both kinds are rules; the default's condition is just "no
 * threshold rule in my tier matched today" rather than a numeric threshold.
 *
 * [rules] is the subset of the user's threshold rules whose condition
 * matched, in input order. [fallbacks] is the per-tier default rule's item
 * key (e.g. `"t-shirt"`, `"pants"`) for each slot the threshold rules left
 * uncovered, top first then bottom.
 *
 * The split is preserved (rather than a single flat items list) because the
 * evening-event tie-in's delta — "extra clothing the night needs that the
 * morning didn't already announce" — compares only threshold-rule matches on
 * each side. The per-tier default is the user's baseline outfit, not "extra"
 * for the evening, so folding it into the comparison would surface a spurious
 * "Bring a t-shirt tonight" whenever the morning matched a cold-weather
 * threshold rule and the night's tier resolved to the default top.
 */
data class TriggeredOutfit(
    val rules: List<ClothesRule>,
    val fallbacks: List<String>,
) {
    /** Every matched rule's item — threshold matches first, defaults second. */
    val items: List<String> get() = rules.map { it.item } + fallbacks
}
