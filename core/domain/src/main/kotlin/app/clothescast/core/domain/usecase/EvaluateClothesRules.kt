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
 * Each [ClothesRule.item] is a typed [Garment] with a known slot, so a matched
 * rule whose item is the same garment as the user's default — or any other
 * garment in that slot, including `t-shirt` — suppresses the default for that
 * slot rather than landing alongside it as a duplicate.
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
        // Every [ClothesRule.item] is a catalog [Garment], so each matched rule
        // claims a known slot. A slot covered by a matched rule (whether keyed
        // on temperature or precipitation) doesn't also get its per-tier default.
        val matchedSlots = matched.mapTo(mutableSetOf()) { it.item.slot }
        val fallbacks = buildList {
            if (Garment.Slot.TOP !in matchedSlots) add(defaultTop.itemKey())
            if (Garment.Slot.BOTTOM !in matchedSlots) add(defaultBottom.itemKey())
        }
        return TriggeredOutfit(rules = matched, fallbacks = fallbacks)
    }
}

