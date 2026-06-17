package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.TriggeredOutfit
import app.clothescast.core.domain.model.isFrozenPrecipitation

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
            // Rain gear (the umbrella, the rain jacket — see [Garment.isRainGear])
            // surfaces once its gate clears, *unless* the precipitation is frozen.
            // Both default rain-gear rules key off aggregate precipitation
            // probability, which can clear their gate on a snowy day — and you
            // don't umbrella or rain-jacket snow — so gate the rain-gear *items*
            // on snow here, at the single rule-evaluation chokepoint the icon, the
            // recommendations, and the prose all read from. Worn warmth garments
            // are unaffected — even a user's own precip-keyed worn rule (e.g.
            // "gloves when rain ≥ 80%") still fires on a snowy day; only the
            // umbrella / rain jacket are rain gear. We exclude *only* snow rather
            // than requiring a wet code ([warrantsRainAccessory]): the raw daily
            // condition is the peak-*probability* hour's weather code, which can
            // read "overcast" at 88% chance of rain when accumulation is ~0, and
            // requiring a wet code there would drop the rain gear on exactly the
            // day the prose still announces rain. See [isFrozenPrecipitation].
            .filterNot {
                it.item.isRainGear && forecast.condition.isFrozenPrecipitation()
            }
        // Every [ClothesRule.item] is a catalog [Garment], so each matched rule
        // claims a known slot. A slot covered by a matched rule (whether keyed
        // on temperature or precipitation) doesn't also get its per-tier default.
        //
        // The base top is the exception: a [Garment.Layer.OUTER] shell (the rain
        // jacket) is worn *over* a base top, not in place of one, so it doesn't
        // satisfy the TOP slot on its own. When the only matched top is the rain
        // jacket, still add the default top — so a rain-jacket-only rule reads
        // "Wear a t-shirt and a rain jacket" in the prose, matching the icon
        // (which already paints the shell over the default top). Without this the
        // prose would collapse to a bare "rain jacket" while the icon shows the
        // base top underneath — a text/icon mismatch.
        val matchedSlots = matched.mapTo(mutableSetOf()) { it.item.slot }
        val baseTopMatched = matched.any {
            it.item.slot == Garment.Slot.TOP && it.item.layer != Garment.Layer.OUTER
        }
        val fallbacks = buildList {
            if (!baseTopMatched) add(defaultTop.itemKey())
            if (Garment.Slot.BOTTOM !in matchedSlots) add(defaultBottom.itemKey())
        }
        return TriggeredOutfit(rules = matched, fallbacks = fallbacks)
    }
}

