package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.TriggeredOutfit

/**
 * Resolves the day's outfit from the forecast: the user's threshold
 * [ClothesRule]s that match, plus the per-tier default rule that covers
 * each slot no threshold rule did. Returned as a [TriggeredOutfit] so the
 * home-screen icon, the bulleted recommendations, and the prose clothes
 * clause all read from the same place and agree on what the user is wearing.
 *
 * Slot membership goes through [Garment.fromKey], which absorbs case /
 * whitespace / alias differences ("Jacket" / " jacket " → JACKET,
 * "trousers" → PANTS), so a matched rule whose item is the same garment
 * as the user's default — or any other top-slot garment, including
 * `t-shirt` — suppresses the default for that slot rather than landing
 * alongside it as a duplicate.
 *
 * Input order of [rules] is preserved in [TriggeredOutfit.rules]; the
 * per-tier default rule contributes its item to [TriggeredOutfit.fallbacks]
 * top-first then bottom-first when (and only when) no threshold rule in its
 * tier matched. The user's default is just as much a rule as their threshold
 * ones — its condition is "no threshold rule in my tier matched today"
 * instead of a numeric cutoff — so a comfort-gap day still resolves to a
 * full outfit even when none of the threshold conditions are satisfied.
 */
class EvaluateClothesRules {
    operator fun invoke(
        forecast: DailyForecast,
        rules: List<ClothesRule>,
        defaultTop: OutfitSuggestion.Top = OutfitSuggestion.Top.TSHIRT,
        defaultBottom: OutfitSuggestion.Bottom = OutfitSuggestion.Bottom.LONG_PANTS,
    ): TriggeredOutfit {
        val matched = rules.filter { it.appliesTo(forecast) }
        val matchedSlots = matched.mapNotNullTo(mutableSetOf()) { Garment.fromKey(it.item)?.slot }
        // A matched threshold rule whose item isn't in the [Garment] catalog
        // (e.g. a legacy free-form "cardigan" rule from before the catalog
        // landed) still represents a garment the user is wearing — we just
        // can't tell which slot it occupies. Suppress both fallbacks rather
        // than appending them on top, otherwise the prose contradicts itself
        // ("Today, wear a cardigan, a t-shirt, and pants."). Precipitation
        // rules are exempt: they describe carried accessories (umbrella, etc.)
        // that don't claim an outfit slot, so they shouldn't suppress either
        // default.
        val hasUnclassifiedGarmentRule = matched.any { rule ->
            Garment.fromKey(rule.item) == null && when (rule.condition) {
                is ClothesRule.TemperatureBelow, is ClothesRule.TemperatureAbove -> true
                is ClothesRule.PrecipitationProbabilityAbove -> false
            }
        }
        val fallbacks = buildList {
            if (!hasUnclassifiedGarmentRule && Garment.Slot.TOP !in matchedSlots) {
                add(defaultTop.itemKey())
            }
            if (!hasUnclassifiedGarmentRule && Garment.Slot.BOTTOM !in matchedSlots) {
                add(defaultBottom.itemKey())
            }
        }
        return TriggeredOutfit(rules = matched, fallbacks = fallbacks)
    }
}

