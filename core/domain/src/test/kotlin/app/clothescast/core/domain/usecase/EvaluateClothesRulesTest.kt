package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EvaluateClothesRulesTest {
    private val subject = EvaluateClothesRules()
    private val date = LocalDate.of(2026, 4, 25)

    private fun forecast(min: Double, max: Double, precip: Double = 0.0): DailyForecast =
        DailyForecast(
            date = date,
            temperatureMinC = min,
            temperatureMaxC = max,
            feelsLikeMinC = min,
            feelsLikeMaxC = max,
            precipitationProbabilityMaxPct = precip,
            precipitationMmTotal = 0.0,
            condition = WeatherCondition.CLEAR,
        )

    @Test
    fun `empty threshold rule list resolves both tiers to their default rules`() {
        // Even with no threshold rules at all, the user has a baseline outfit
        // they've configured (defaultTop + defaultBottom) — that's the
        // per-tier default rule, and it always matches the "no threshold
        // rule in my tier did" condition.
        val out = subject(forecast(min = 5.0, max = 15.0), rules = emptyList())
        out.rules.shouldBeEmpty()
        out.fallbacks.shouldContainExactly("t-shirt", "pants")
        out.items.shouldContainExactly("t-shirt", "pants")
    }

    @Test
    fun `temperate day with no matching threshold rule resolves to both defaults`() {
        // The comfort gap: feels-like 18-22°C, nothing in DEFAULTS matches
        // (sweater <16°C, jacket <10°C, coat <4°C, shorts >23°C). Each
        // tier's default rule resolves its slot.
        val out = subject(forecast(min = 18.0, max = 22.0), ClothesRule.DEFAULTS)
        out.rules.shouldBeEmpty()
        out.items.shouldContainExactly("t-shirt", "pants")
    }

    @Test
    fun `matching top-tier threshold rule replaces the default top, bottom still defaults`() {
        // Cold morning, mild afternoon — sweater matches, no bottom-tier
        // threshold rule does. Top slot gets the matching rule's item; bottom
        // slot gets the default.
        val out = subject(forecast(min = 14.0, max = 20.0), ClothesRule.DEFAULTS)
        out.rules.map { it.item }.shouldContainExactly("sweater")
        out.fallbacks.shouldContainExactly("pants")
        out.items.shouldContainExactly("sweater", "pants")
    }

    @Test
    fun `matching bottom-tier threshold rule replaces the default bottom, top still defaults`() {
        // Hot day — shorts matches, no top-tier threshold rule does.
        // Bottom slot gets the matching rule's item; top slot gets the default.
        // items orders tops before bottoms so the prose reads
        // "Wear a t-shirt and shorts." regardless of which clause produced
        // the t-shirt (rule vs default fallback).
        val out = subject(forecast(min = 22.0, max = 28.0), ClothesRule.DEFAULTS)
        out.rules.map { it.item }.shouldContainExactly("shorts")
        out.fallbacks.shouldContainExactly("t-shirt")
        out.items.shouldContainExactly("t-shirt", "shorts")
    }

    @Test
    fun `cold morning warm afternoon resolves both tiers via threshold rules, no default needed`() {
        // Sweater + jacket match on the cold morning; shorts matches on the
        // warm afternoon. Both tiers have a matching threshold rule → no
        // default contributes.
        val out = subject(forecast(min = 8.0, max = 25.0), ClothesRule.DEFAULTS)
        out.rules.map { it.item }.shouldContainExactly("sweater", "jacket", "shorts")
        out.fallbacks.shouldBeEmpty()
    }

    @Test
    fun `wet cold day matches cold-weather rules, bottom slot resolves to the default`() {
        // Defaults no longer include umbrella — the precip clause announces rain,
        // and the wet-weather accessory will become a personalised setting.
        val out = subject(forecast(min = 9.0, max = 15.0, precip = 70.0), ClothesRule.DEFAULTS)
        out.rules.map { it.item }.shouldContainExactly("sweater", "jacket")
        out.items.shouldContainExactly("sweater", "jacket", "pants")
    }

    @Test
    fun `matching non-tier threshold rule does not displace either tier default`() {
        // Umbrella is a precip-keyed threshold rule and doesn't claim either
        // outfit tier, so a mild rainy day still resolves to both tier
        // defaults alongside it.
        val rules = listOf(
            ClothesRule("umbrella", ClothesRule.PrecipitationProbabilityAbove(50.0)),
        )
        val out = subject(forecast(min = 18.0, max = 22.0, precip = 80.0), rules)
        out.rules.map { it.item }.shouldContainExactly("umbrella")
        out.items.shouldContainExactly("umbrella", "t-shirt", "pants")
    }

    @Test
    fun `user-selected default rules flow through to the resolved items`() {
        // The default rule honours the user's Settings picks — a denim-everyday
        // user sees "jeans"; a polo-everyday user sees "polo".
        val out = subject(
            forecast(min = 18.0, max = 22.0),
            rules = emptyList(),
            defaultTop = OutfitSuggestion.Top.POLO,
            defaultBottom = OutfitSuggestion.Bottom.JEANS,
        )
        out.items.shouldContainExactly("polo", "jeans")
    }

    @Test
    fun `matching rule with the same item as the default suppresses the default rather than duplicating it`() {
        // A legitimate threshold rule like "wear a t-shirt above 20°C"
        // matches the same item the default would otherwise contribute.
        // Both shouldn't land in the items list — the rule covers the top
        // slot, so the top default sits this one out (bottom default still
        // fires because no bottom-slot rule matched).
        val rules = listOf(ClothesRule("t-shirt", ClothesRule.TemperatureAbove(20.0)))
        val out = subject(forecast(min = 21.0, max = 25.0), rules)
        out.rules.map { it.item }.shouldContainExactly("t-shirt")
        // Top slot covered by the rule; only the bottom default contributes.
        out.fallbacks.shouldContainExactly("pants")
        out.items.shouldContainExactly("t-shirt", "pants")
    }

    @Test
    fun `matching rule whose item is any top-slot garment suppresses the default top`() {
        // Garments like SHIRT, POLO, TSHIRT live in the top slot but aren't
        // in the icon picker's cold-priority key sets. A rule whose item is
        // any of them still covers the top slot, so the default shouldn't
        // also fire — otherwise the user gets "wear a shirt and a t-shirt"
        // for a single tier.
        val rules = listOf(ClothesRule("shirt", ClothesRule.TemperatureAbove(15.0)))
        val out = subject(forecast(min = 16.0, max = 22.0), rules)
        out.rules.map { it.item }.shouldContainExactly("shirt")
        // Top covered by "shirt", bottom slot has no firing rule → defaults to pants.
        out.items.shouldContainExactly("shirt", "pants")
    }

    @Test
    fun `legacy case and whitespace variants normalize for tier membership`() {
        // Pre-catalog rules saved with capitalized or whitespace-padded items
        // ("Jacket", " jacket ") still fire on the forecast, and they should
        // also be recognized as top-slot for default-suppression — Garment.fromKey
        // already normalizes for the rest of the pipeline (tie-in delta,
        // formatter), and the evaluator follows the same contract.
        val rules = listOf(ClothesRule("Jacket", ClothesRule.TemperatureBelow(12.0)))
        val out = subject(forecast(min = 5.0, max = 10.0), rules)
        out.rules.map { it.item }.shouldContainExactly("Jacket")
        // Without normalization, the engine would add "t-shirt" on top of
        // "Jacket" — contradictory advice. With normalization the top slot
        // is correctly covered.
        out.items.shouldContainExactly("Jacket", "pants")
    }

    @Test
    fun `unclassified temperature rule suppresses both fallbacks instead of duplicating them`() {
        // Codex-flagged: a legacy free-form temperature rule like "cardigan"
        // (typed before the Garment catalog landed and not recognized today)
        // still represents a garment the user is wearing — we just can't
        // tell which slot it occupies. Pre-fix: the engine couldn't classify
        // it and appended both defaults on top, producing the contradictory
        // "cardigan + t-shirt + pants" prose. Now: suppress both fallbacks
        // so the user's rule stands as written.
        val rules = listOf(ClothesRule("cardigan", ClothesRule.TemperatureBelow(15.0)))
        val out = subject(forecast(min = 8.0, max = 14.0), rules)
        out.rules.map { it.item }.shouldContainExactly("cardigan")
        out.fallbacks.shouldBeEmpty()
        out.items.shouldContainExactly("cardigan")
    }

    @Test
    fun `unclassified precipitation rule does not suppress slot fallbacks`() {
        // An unclassified precipitation rule (umbrella-style) describes a
        // carried accessory, not a garment that takes up an outfit slot.
        // It shouldn't suppress either default — the user still needs the
        // baseline t-shirt + pants alongside it.
        val rules = listOf(ClothesRule("umbrella", ClothesRule.PrecipitationProbabilityAbove(50.0)))
        val out = subject(forecast(min = 18.0, max = 22.0, precip = 80.0), rules)
        out.rules.map { it.item }.shouldContainExactly("umbrella")
        out.fallbacks.shouldContainExactly("t-shirt", "pants")
        out.items.shouldContainExactly("umbrella", "t-shirt", "pants")
    }

    @Test
    fun `classified rule covers its slot even when an unclassified accessory rule also fires`() {
        // Mixed-condition cold rainy day: sweater rule (classified, TOP) +
        // umbrella rule (unclassified, precip). Top slot is covered by
        // sweater so its default doesn't fire; umbrella is an accessory so
        // it doesn't claim a slot; bottom default still applies normally.
        val rules = listOf(
            ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)),
            ClothesRule("umbrella", ClothesRule.PrecipitationProbabilityAbove(50.0)),
        )
        val out = subject(forecast(min = 10.0, max = 16.0, precip = 70.0), rules)
        out.rules.map { it.item }.shouldContainExactly("sweater", "umbrella")
        out.items.shouldContainExactly("sweater", "umbrella", "pants")
    }

    @Test
    fun `firing base-layer rule is dropped when a mid-layer rule also fires`() {
        // The user has both a "wear a t-shirt below 30°C" rule (BASE) and the
        // default sweater rule (MID, < 18°C). On a 14°C morning both fire, but
        // the t-shirt is implicit under the sweater — it shouldn't read out as
        // "Wear a jumper, t-shirt, and jeans." Items drops the BASE; rules
        // keeps every firing rule (the rationale + tie-in delta logic still
        // need access to all matches).
        val rules = listOf(
            ClothesRule("t-shirt", ClothesRule.TemperatureBelow(30.0)),
            ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)),
            ClothesRule("jeans", ClothesRule.TemperatureBelow(20.0)),
        )
        val out = subject(forecast(min = 10.0, max = 14.0), rules)
        out.rules.map { it.item }.shouldContainExactly("t-shirt", "sweater", "jeans")
        out.items.shouldContainExactly("sweater", "jeans")
    }

    @Test
    fun `firing base-layer rule is dropped when a shell-layer rule also fires`() {
        // Same suppression as the MID-layer case: any outer layer firing
        // makes the BASE layer implicit. Here only the t-shirt and jacket
        // rules fire; the sweater rule doesn't because the minimum is 14°C.
        val rules = listOf(
            ClothesRule("t-shirt", ClothesRule.TemperatureBelow(30.0)),
            ClothesRule("jacket", ClothesRule.TemperatureBelow(15.0)),
        )
        val out = subject(forecast(min = 12.0, max = 14.0), rules)
        out.rules.map { it.item }.shouldContainExactly("t-shirt", "jacket")
        out.items.shouldContainExactly("jacket", "pants")
    }

    @Test
    fun `base-layer rule survives when nothing in mid or shell fires`() {
        // A polo-loving user with rules across all layers, on a mild day where
        // only the polo rule (BASE) crosses. The base layer stays — there's
        // nothing covering it.
        val rules = listOf(
            ClothesRule("polo", ClothesRule.TemperatureBelow(30.0)),
            ClothesRule("sweater", ClothesRule.TemperatureBelow(15.0)),
            ClothesRule("jacket", ClothesRule.TemperatureBelow(10.0)),
        )
        val out = subject(forecast(min = 18.0, max = 22.0), rules)
        out.items.shouldContainExactly("polo", "pants")
    }

    @Test
    fun `within-shell-layer ties resolve to the heaviest tier`() {
        // A very cold day where sweater + jacket + coat all fire. Sweater
        // belongs to MID and survives on its own; jacket and coat both want
        // the SHELL slot, and coat (heavier tier) wins. The result reads as
        // a real outfit ("sweater under a coat") rather than naming every
        // also-ran.
        val out = subject(forecast(min = 2.0, max = 8.0), ClothesRule.DEFAULTS)
        out.rules.map { it.item }.shouldContainExactly("sweater", "jacket", "coat")
        out.items.shouldContainExactly("sweater", "coat", "pants")
    }

    @Test
    fun `duplicate rules in the same layer collapse to a single winner`() {
        // ClothesRule is a data class, so two separately configured rules
        // with the same item + condition compare equal. Layer reduction
        // needs to dedupe by *position* in the rule list, not by value —
        // otherwise both sweater rules would survive the filter and the
        // prose would read "Wear a sweater, sweater, and pants." The
        // earlier-listed instance wins; the duplicate is silently dropped.
        val rules = listOf(
            ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)),
            ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)),
        )
        val out = subject(forecast(min = 12.0, max = 16.0), rules)
        out.rules.map { it.item }.shouldContainExactly("sweater", "sweater")
        out.items.shouldContainExactly("sweater", "pants")
    }

    @Test
    fun `within-mid-layer ties resolve to the priority winner`() {
        // Two MID rules firing at the same time (the user has both a sweater
        // and a thin-jacket rule). Thin-jacket leads the MID priority order,
        // so it's the one that shows up in the outfit.
        val rules = listOf(
            ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)),
            ClothesRule("thin-jacket", ClothesRule.TemperatureBelow(20.0)),
        )
        val out = subject(forecast(min = 14.0, max = 17.0), rules)
        out.rules.map { it.item }.shouldContainExactly("sweater", "thin-jacket")
        out.items.shouldContainExactly("thin-jacket", "pants")
    }

    @Test
    fun `overlapping bottom rules collapse to the priority winner`() {
        // Bottoms substitute rather than stack — wearing pants over shorts
        // isn't an outfit. Pre-fix, both firing rules surfaced as "Wear a
        // t-shirt, shorts, and jeans." which contradicted the home-screen
        // icon (already picked shorts via OutfitSuggestion.fromForecast).
        // Now: the warmer / more exposed garment wins, matching the icon.
        val rules = listOf(
            ClothesRule("shorts", ClothesRule.TemperatureAbove(24.0)),
            ClothesRule("jeans", ClothesRule.TemperatureAbove(18.0)),
        )
        val out = subject(forecast(min = 20.0, max = 26.0), rules)
        out.rules.map { it.item }.shouldContainExactly("shorts", "jeans")
        out.items.shouldContainExactly("t-shirt", "shorts")
    }

    @Test
    fun `short-skirt outranks long skirt when both fire`() {
        // Mirrors the OutfitSuggestion.Bottom picker priority — when a
        // user has a `short-skirt > 22°C` and a `skirt > 16°C` rule and
        // both fire on a warm day, the mini wins on both the home-screen
        // icon and in the prose.
        val rules = listOf(
            ClothesRule("short-skirt", ClothesRule.TemperatureAbove(22.0)),
            ClothesRule("skirt", ClothesRule.TemperatureAbove(16.0)),
        )
        val out = subject(forecast(min = 18.0, max = 25.0), rules)
        out.rules.map { it.item }.shouldContainExactly("short-skirt", "skirt")
        out.items.shouldContainExactly("t-shirt", "short-skirt")
    }

    @Test
    fun `single firing bottom rule passes through unchanged`() {
        // Only one bottom fires — nothing to reduce, the rule lands in
        // items alongside the top default. Regression net for the
        // bottom-reduction code path: a single bottom shouldn't accidentally
        // get dropped.
        val rules = listOf(ClothesRule("jeans", ClothesRule.TemperatureAbove(18.0)))
        val out = subject(forecast(min = 18.0, max = 22.0), rules)
        out.items.shouldContainExactly("t-shirt", "jeans")
    }

    @Test
    fun `input order is preserved across matching threshold rules`() {
        // Same as before — the user picks presentation order via input order
        // of their threshold rules.
        val rules = listOf(
            ClothesRule("umbrella", ClothesRule.PrecipitationProbabilityAbove(50.0)),
            ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)),
            ClothesRule("jacket", ClothesRule.TemperatureBelow(12.0)),
        )
        val out = subject(forecast(min = 5.0, max = 12.0, precip = 80.0), rules)
        out.rules.map { it.item }.shouldContainExactly("umbrella", "sweater", "jacket")
    }
}
