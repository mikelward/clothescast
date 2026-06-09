package app.clothescast.core.domain.model

import app.clothescast.core.domain.usecase.EvaluateClothesRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class OutfitSuggestionTest {
    private val date = LocalDate.of(2026, 4, 29)
    private val rules = ClothesRule.DEFAULTS

    private fun forecast(
        feelsLikeMin: Double,
        feelsLikeMax: Double,
        hourly: List<HourlyForecast> = emptyList(),
        precipMaxPct: Double = 0.0,
        condition: WeatherCondition = WeatherCondition.CLEAR,
    ): DailyForecast = DailyForecast(
        date = date,
        temperatureMinC = feelsLikeMin,
        temperatureMaxC = feelsLikeMax,
        feelsLikeMinC = feelsLikeMin,
        feelsLikeMaxC = feelsLikeMax,
        precipitationProbabilityMaxPct = precipMaxPct,
        precipitationMmTotal = 0.0,
        condition = condition,
        hourly = hourly,
    )

    private fun hour(time: LocalTime, feelsLikeC: Double): HourlyForecast = HourlyForecast(
        time = time,
        temperatureC = feelsLikeC,
        feelsLikeC = feelsLikeC,
        precipitationProbabilityPct = 0.0,
        condition = WeatherCondition.CLEAR,
    )

    @Test
    fun `sweater rationale names the deciding hour and the sweater rule`() {
        val hourly = listOf(
            hour(LocalTime.of(7, 0), 13.0),
            hour(LocalTime.of(12, 0), 17.5),
            hour(LocalTime.of(17, 0), 16.0),
        )
        val rationale = OutfitSuggestion.explainFromForecast(
            forecast(feelsLikeMin = 13.0, feelsLikeMax = 17.5, hourly = hourly),
            rules,
        )
        rationale.top.facts shouldBe listOf(
            Fact(
                metric = Fact.Metric.FEELS_LIKE_MIN,
                observedC = 13.0,
                observedAt = LocalTime.of(7, 0),
                thresholdC = 16.0,
                ruleItem = Garment.SWEATER,
                comparison = Fact.Comparison.BELOW,
            ),
        )
    }

    @Test
    fun `thick jacket rationale cites the jacket rule`() {
        // 8°C is below the jacket threshold (10°C) but above the coat threshold (5°C),
        // so only the jacket rule fires and the rationale should cite "jacket".
        val hourly = listOf(hour(LocalTime.of(6, 0), 8.0), hour(LocalTime.of(15, 0), 9.0))
        val rationale = OutfitSuggestion.explainFromForecast(
            forecast(feelsLikeMin = 8.0, feelsLikeMax = 9.0, hourly = hourly),
            rules,
        )
        val fact = rationale.top.facts.single()
        fact.thresholdC shouldBe 10.0
        fact.ruleItem shouldBe Garment.JACKET
        fact.observedC shouldBe 8.0
        fact.observedAt shouldBe LocalTime.of(6, 0)
        fact.comparison shouldBe Fact.Comparison.BELOW
    }

    @Test
    fun `tshirt rationale records the feels-like min above the sweater cutoff`() {
        val hourly = listOf(hour(LocalTime.of(7, 0), 19.0), hour(LocalTime.of(14, 0), 25.0))
        val rationale = OutfitSuggestion.explainFromForecast(
            forecast(feelsLikeMin = 19.0, feelsLikeMax = 25.0, hourly = hourly),
            rules,
        )
        val fact = rationale.top.facts.single()
        fact.observedC shouldBe 19.0
        fact.thresholdC shouldBe 16.0
        fact.ruleItem shouldBe Garment.SWEATER
        fact.comparison shouldBe Fact.Comparison.AT_OR_ABOVE
    }

    @Test
    fun `shorts rationale cites the shorts rule and warmest hour`() {
        val hourly = listOf(hour(LocalTime.of(7, 0), 20.0), hour(LocalTime.of(14, 0), 26.0))
        val rationale = OutfitSuggestion.explainFromForecast(
            forecast(feelsLikeMin = 20.0, feelsLikeMax = 26.0, hourly = hourly),
            rules,
        )
        val fact = rationale.bottom.facts.single()
        fact.metric shouldBe Fact.Metric.FEELS_LIKE_MAX
        fact.ruleItem shouldBe Garment.SHORTS
        fact.thresholdC shouldBe 23.0
        fact.observedC shouldBe 26.0
        fact.observedAt shouldBe LocalTime.of(14, 0)
        fact.comparison shouldBe Fact.Comparison.AT_OR_ABOVE
    }

    @Test
    fun `long pants rationale cites the shorts rule's unmet cutoff`() {
        val rationale = OutfitSuggestion.explainFromForecast(
            forecast(feelsLikeMin = 12.0, feelsLikeMax = 22.0),
            rules,
        )
        val fact = rationale.bottom.facts.single()
        fact.metric shouldBe Fact.Metric.FEELS_LIKE_MAX
        fact.ruleItem shouldBe Garment.SHORTS
        fact.thresholdC shouldBe 23.0
        fact.observedC shouldBe 22.0
        fact.comparison shouldBe Fact.Comparison.BELOW
    }

    @Test
    fun `rationale omits observedAt when hourly is empty`() {
        val rationale = OutfitSuggestion.explainFromForecast(
            forecast(feelsLikeMin = 13.0, feelsLikeMax = 17.0),
            rules,
        )
        rationale.top.facts.single().observedAt shouldBe null
    }

    @Test
    fun `rationale matches the suggestion across boundary cases`() {
        // Pinned cases that exercise each branch — keeps fromForecast and
        // explainFromForecast from drifting against the default clothes rules
        // (sweater 16, jacket 10, coat 5, shorts 23). Includes exact-equality
        // cases at each rule threshold so a rule-operator flip (`<` ↔ `<=`,
        // `>` ↔ `>=`) shows up here rather than being silently swallowed.
        val cases = listOf(
            // Strictly below jacket (10): THICK_JACKET fires.
            Triple(7.5, 9.0, OutfitSuggestion(OutfitSuggestion.Top.THICK_JACKET, OutfitSuggestion.Bottom.LONG_PANTS)),
            // Exactly at jacket cutoff (10): TemperatureBelow needs strictly
            // less, so 10.0 lands on SWEATER (only sweater rule fires).
            Triple(10.0, 16.0, OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS)),
            Triple(13.0, 17.0, OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS)),
            // Exactly at sweater cutoff (16): SWEATER ends strictly before 16,
            // so 16.0 is TSHIRT.
            Triple(16.0, 21.0, OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.LONG_PANTS)),
            // Above shorts cutoff (23): SHORTS fires.
            Triple(19.0, 26.0, OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS)),
            // Exactly at shorts cutoff (23): TemperatureAbove is strictly
            // greater-than, so 23.0 doesn't fire — stays LONG_PANTS.
            Triple(15.0, 23.0, OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS)),
        )
        cases.forEach { (min, max, expected) ->
            OutfitSuggestion.fromForecast(forecast(min, max), rules) shouldBe expected
        }
    }

    @Test
    fun `fromTriggeredOutfit agrees with fromForecast for the same rules and forecast`() {
        // The displayed icon derives from the TriggeredOutfit the prose uses
        // (see DeriveInsight), so the two entry points must agree — otherwise
        // the icon and the bullet text drift apart, the bug this split prevents.
        val evaluate = EvaluateClothesRules()
        listOf(7.5 to 9.0, 10.0 to 16.0, 16.0 to 21.0, 19.0 to 26.0, 15.0 to 23.0).forEach { (min, max) ->
            val fc = forecast(min, max)
            OutfitSuggestion.fromTriggeredOutfit(evaluate(fc, rules)) shouldBe
                OutfitSuggestion.fromForecast(fc, rules)
        }
    }

    @Test
    fun `fromTriggeredOutfit reads firing rules and falls through to defaults`() {
        // A firing coat rule drives the top tier; with no bottom rule firing the
        // bottom falls to the supplied default — the per-tier default is not an
        // icon tier of its own, matching fromForecast.
        val triggered = TriggeredOutfit(
            rules = listOf(ClothesRule(Garment.COAT, ClothesRule.TemperatureBelow(5.0))),
            fallbacks = listOf("pants"),
        )
        OutfitSuggestion.fromTriggeredOutfit(
            triggered,
            defaultBottom = OutfitSuggestion.Bottom.JEANS,
        ) shouldBe OutfitSuggestion(OutfitSuggestion.Top.THICK_COAT, OutfitSuggestion.Bottom.JEANS)
    }

    @Test
    fun `customised shorts rule flips the bottom recommendation`() {
        // With the default 23°C shorts rule, max 22°C lands on LONG_PANTS.
        // Lower the rule to 20°C and the same forecast picks SHORTS — the
        // home-screen icon and the bullet text now share the same threshold.
        val warmer = listOf(ClothesRule(Garment.SHORTS, ClothesRule.TemperatureAbove(20.0)))
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 18.0, feelsLikeMax = 22.0),
            warmer,
        )
        outfit.bottom shouldBe OutfitSuggestion.Bottom.SHORTS
    }

    @Test
    fun `Fahrenheit-typed rule converts to Celsius for the comparison`() {
        // The rule says "shorts above 75°F" (≈ 23.89°C). A 24°C max fires it.
        val rule = ClothesRule(Garment.SHORTS, ClothesRule.TemperatureAbove(75.0, TemperatureUnit.FAHRENHEIT))
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 18.0, feelsLikeMax = 24.0),
            listOf(rule),
        )
        outfit.bottom shouldBe OutfitSuggestion.Bottom.SHORTS
        val rationale = OutfitSuggestion.explainFromForecast(
            forecast(feelsLikeMin = 18.0, feelsLikeMax = 24.0),
            listOf(rule),
        )
        // thresholdC is reported in °C even when the rule was typed in °F.
        rationale.bottom.facts.single().thresholdC shouldBe (75.0 - 32.0) * 5.0 / 9.0
    }

    @Test
    fun `deleted shorts rule disables the SHORTS icon`() {
        // No shorts rule → the home screen never picks shorts, no matter how
        // hot. The rationale still has something to cite (catalog default) so
        // the dialog renders coherently.
        val noShorts = ClothesRule.DEFAULTS.filterNot { it.item == Garment.SHORTS }
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 22.0, feelsLikeMax = 30.0),
            noShorts,
        )
        outfit.bottom shouldBe OutfitSuggestion.Bottom.LONG_PANTS

        val fact = OutfitSuggestion.explainFromForecast(
            forecast(feelsLikeMin = 22.0, feelsLikeMax = 30.0),
            noShorts,
        ).bottom.facts.single()
        fact.ruleItem shouldBe Garment.SHORTS
        fact.thresholdC shouldBe 23.0
    }

    @Test
    fun `firing pants rule picks LONG_PANTS over a SHORTS default`() {
        // A user with a shorts default and a "pants below 15°C" rule: the prose
        // names pants when the rule fires, so the icon must show long pants
        // too rather than falling through to the shorts default.
        val pantsRule = listOf(ClothesRule(Garment.PANTS, ClothesRule.TemperatureBelow(15.0)))
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 10.0, feelsLikeMax = 14.0),
            pantsRule,
            defaultBottom = OutfitSuggestion.Bottom.SHORTS,
        )
        outfit.bottom shouldBe OutfitSuggestion.Bottom.LONG_PANTS
    }

    @Test
    fun `firing pants rule is cited by the bottom rationale`() {
        // The "Why this outfit?" sheet should explain the day via the pants
        // rule that decided it, not the un-crossed shorts threshold.
        val pantsRule = listOf(ClothesRule(Garment.PANTS, ClothesRule.TemperatureBelow(15.0)))
        val fact = OutfitSuggestion.explainFromForecast(
            forecast(feelsLikeMin = 10.0, feelsLikeMax = 14.0),
            pantsRule,
        ).bottom.facts.single()
        fact.ruleItem shouldBe Garment.PANTS
        fact.thresholdC shouldBe 15.0
    }

    @Test
    fun `defaultBottom flips the fallback from LONG_PANTS to JEANS`() {
        // A denim-everyday user picks Jeans as their standard bottom. With no
        // jeans rule on file and a forecast that doesn't trigger shorts/skirt,
        // fromForecast lands on the user's chosen default rather than the
        // hardcoded LONG_PANTS.
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 12.0, feelsLikeMax = 22.0),
            rules,
            defaultBottom = OutfitSuggestion.Bottom.JEANS,
        )
        outfit.bottom shouldBe OutfitSuggestion.Bottom.JEANS
    }

    @Test
    fun `defaultBottom is ignored when a warmer-tier rule fires`() {
        // Even with JEANS picked as default, a firing shorts rule still wins —
        // the default is the *fallback*, not an override.
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 20.0, feelsLikeMax = 26.0),
            rules,
            defaultBottom = OutfitSuggestion.Bottom.JEANS,
        )
        outfit.bottom shouldBe OutfitSuggestion.Bottom.SHORTS
    }

    @Test
    fun `short-skirt rule lands on SHORT_SKIRT`() {
        // A user adds a "short-skirt > 22°C" rule. A warm day fires it, and
        // the home-screen icon shows the mini-skirt silhouette rather than
        // the full-length one.
        val rule = ClothesRule(Garment.SHORT_SKIRT, ClothesRule.TemperatureAbove(22.0))
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 18.0, feelsLikeMax = 25.0),
            listOf(rule),
        )
        outfit.bottom shouldBe OutfitSuggestion.Bottom.SHORT_SKIRT
    }

    @Test
    fun `short-skirt outranks long skirt when both rules fire`() {
        // Mini wins over full-length: the shorter / more exposed garment is
        // the right pick on a warmer day, and the priority order mirrors
        // SHORTS-over-LONG_SKIRT on the bottom tier.
        val rules = listOf(
            ClothesRule(Garment.SHORT_SKIRT, ClothesRule.TemperatureAbove(22.0)),
            ClothesRule(Garment.SKIRT, ClothesRule.TemperatureAbove(16.0)),
        )
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 18.0, feelsLikeMax = 25.0),
            rules,
        )
        outfit.bottom shouldBe OutfitSuggestion.Bottom.SHORT_SKIRT
    }

    @Test
    fun `long skirt still fires when only the skirt rule crosses its threshold`() {
        // The short-skirt rule sits at a hotter threshold than skirt; on a
        // mild day only the skirt rule fires and LONG_SKIRT is the right pick.
        val rules = listOf(
            ClothesRule(Garment.SHORT_SKIRT, ClothesRule.TemperatureAbove(22.0)),
            ClothesRule(Garment.SKIRT, ClothesRule.TemperatureAbove(16.0)),
        )
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 15.0, feelsLikeMax = 20.0),
            rules,
        )
        outfit.bottom shouldBe OutfitSuggestion.Bottom.LONG_SKIRT
    }

    @Test
    fun `defaultBottom SHORT_SKIRT falls back to a short skirt`() {
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 12.0, feelsLikeMax = 22.0),
            rules,
            defaultBottom = OutfitSuggestion.Bottom.SHORT_SKIRT,
        )
        outfit.bottom shouldBe OutfitSuggestion.Bottom.SHORT_SKIRT
    }

    @Test
    fun `defaultBottom LONG_SKIRT falls back to a long skirt`() {
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 12.0, feelsLikeMax = 22.0),
            rules,
            defaultBottom = OutfitSuggestion.Bottom.LONG_SKIRT,
        )
        outfit.bottom shouldBe OutfitSuggestion.Bottom.LONG_SKIRT
    }

    @Test
    fun `defaultTop flips the fallback from TSHIRT to POLO`() {
        // A polo-shirt-everyday user picks Polo as their standard top. With no
        // cold-weather rule firing, the fallback lands on Polo instead of the
        // hardcoded T-shirt.
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 20.0, feelsLikeMax = 22.0),
            rules,
            defaultTop = OutfitSuggestion.Top.POLO,
        )
        outfit.top shouldBe OutfitSuggestion.Top.POLO
    }

    @Test
    fun `defaultTop is ignored when a colder-tier rule fires`() {
        // Even with Polo picked as default, a firing sweater rule still wins —
        // the default is the *fallback*, not an override.
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 12.0, feelsLikeMax = 16.0),
            rules,
            defaultTop = OutfitSuggestion.Top.POLO,
        )
        outfit.top shouldBe OutfitSuggestion.Top.SWEATER
    }

    @Test
    fun `firing t-shirt rule lands on TSHIRT even when defaultTop is POLO`() {
        // Reproduces the field bug: a "t-shirt above 24°C" rule fires on a warm
        // day, so the prose and recommended items name a t-shirt — but the icon
        // picker had no t-shirt tier and fell through to the configured
        // defaultTop (POLO), leaving the outfit card's icon contradicting its
        // text ("Wear a t-shirt" next to a polo silhouette).
        val rules = listOf(
            ClothesRule(Garment.TSHIRT, ClothesRule.TemperatureAbove(24.0)),
            ClothesRule(Garment.SHORTS, ClothesRule.TemperatureAbove(24.0)),
        )
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 17.9, feelsLikeMax = 27.9),
            rules,
            defaultTop = OutfitSuggestion.Top.POLO,
        )
        outfit.top shouldBe OutfitSuggestion.Top.TSHIRT
        outfit.bottom shouldBe OutfitSuggestion.Bottom.SHORTS
    }

    @Test
    fun `polo rule outranks t-shirt rule when both fire`() {
        // Within the base layer the catalog ranks POLO ahead of TSHIRT, so when
        // a user has both rules firing the polo wins — matching the prose's
        // layer-reduction, which keeps the polo and drops the t-shirt.
        val rules = listOf(
            ClothesRule(Garment.TSHIRT, ClothesRule.TemperatureAbove(24.0)),
            ClothesRule(Garment.POLO, ClothesRule.TemperatureAbove(24.0)),
        )
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 17.9, feelsLikeMax = 27.9),
            rules,
        )
        outfit.top shouldBe OutfitSuggestion.Top.POLO
    }

    @Test
    fun `t-shirt rule drives the top rationale against the day's high`() {
        // A warm "t-shirt above 24°C" rule lives in the top slot but keys off
        // the day's high. The rationale must compare against the feels-like max
        // at the warmest hour — not the min — or the sheet would show the
        // threshold as uncrossed (18°C < 24°C) on a day the rule actually fired
        // (max 28°C), and wire its ±1° controls to that misleading fact.
        val hourly = listOf(hour(LocalTime.of(7, 0), 18.0), hour(LocalTime.of(15, 0), 28.0))
        val rules = listOf(ClothesRule(Garment.TSHIRT, ClothesRule.TemperatureAbove(24.0)))
        val fact = OutfitSuggestion.explainFromForecast(
            forecast(feelsLikeMin = 18.0, feelsLikeMax = 28.0, hourly = hourly),
            rules,
        ).top.facts.single()
        fact.ruleItem shouldBe Garment.TSHIRT
        fact.metric shouldBe Fact.Metric.FEELS_LIKE_MAX
        fact.observedC shouldBe 28.0
        fact.observedAt shouldBe LocalTime.of(15, 0)
        fact.thresholdC shouldBe 24.0
        fact.comparison shouldBe Fact.Comparison.AT_OR_ABOVE
    }

    @Test
    fun `precipitation-keyed t-shirt rule drives the icon without crashing the rationale`() {
        // Settings lets a user key any garment — a top included — off
        // precipitation. On a rainy day such a rule fires and drives the icon
        // (TSHIRT here, beating the POLO default), but it carries no temperature
        // threshold: the rationale must skip it and fall back to a temperature
        // rule rather than throw in toFact.
        val rules = listOf(ClothesRule(Garment.TSHIRT, ClothesRule.PrecipitationProbabilityAbove(50.0)))
        val rainy = forecast(feelsLikeMin = 12.0, feelsLikeMax = 20.0, precipMaxPct = 60.0)

        OutfitSuggestion.fromForecast(
            rainy,
            rules,
            defaultTop = OutfitSuggestion.Top.POLO,
        ).top shouldBe OutfitSuggestion.Top.TSHIRT

        val fact = OutfitSuggestion.explainFromForecast(rainy, rules).top.facts.single()
        fact.ruleItem shouldBe Garment.SWEATER
        fact.metric shouldBe Fact.Metric.FEELS_LIKE_MIN
        fact.thresholdC shouldBe 16.0
    }

    @Test
    fun `coat rule alone drives THICK_COAT when it fires`() {
        // Coat has its own icon tier (THICK_COAT) separate from jacket (THICK_JACKET).
        val coatOnly = listOf(ClothesRule(Garment.COAT, ClothesRule.TemperatureBelow(6.0)))
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 3.0, feelsLikeMax = 8.0),
            coatOnly,
        )
        outfit.top shouldBe OutfitSuggestion.Top.THICK_COAT
        OutfitSuggestion.explainFromForecast(
            forecast(feelsLikeMin = 3.0, feelsLikeMax = 8.0),
            coatOnly,
        ).top.facts.single().ruleItem shouldBe Garment.COAT
    }

    @Test
    fun `a firing gloves rule sets the hands slot`() {
        // The gloves default fires below 4°C: a freezing day lights the optional
        // hands tier alongside the top/bottom pick.
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = -2.0, feelsLikeMax = 2.0),
            rules,
        )
        outfit.hands shouldBe OutfitSuggestion.Hands.GLOVES
    }

    @Test
    fun `hands stays null when no gloves rule fires`() {
        // Hands is opt-in with no fallback: a cool-but-not-freezing day leaves it
        // null rather than promoting to a default the way top/bottom do, so no
        // glove icon shows when the user hasn't earned one.
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 8.0, feelsLikeMax = 14.0),
            rules,
        )
        outfit.hands shouldBe null
    }

    @Test
    fun `fromTriggeredOutfit carries the hands slot from a firing gloves rule`() {
        // The icon derives from the same TriggeredOutfit the prose uses, so a
        // firing gloves rule has to reach the hands slot through this path too.
        val triggered = TriggeredOutfit(
            rules = listOf(
                ClothesRule(Garment.COAT, ClothesRule.TemperatureBelow(5.0)),
                ClothesRule(Garment.GLOVES, ClothesRule.TemperatureBelow(4.0)),
            ),
            fallbacks = emptyList(),
        )
        OutfitSuggestion.fromTriggeredOutfit(triggered) shouldBe OutfitSuggestion(
            OutfitSuggestion.Top.THICK_COAT,
            OutfitSuggestion.Bottom.LONG_PANTS,
            OutfitSuggestion.Hands.GLOVES,
        )
    }

    @Test
    fun `hands itemKey round-trips through the garment catalog`() {
        // The slot's catalog key must resolve back to the HANDS-slot garment so
        // prose / persistence / a future glove icon all key off the same string.
        OutfitSuggestion.Hands.entries.forEach { hands ->
            Garment.fromKey(hands.itemKey())?.slot shouldBe Garment.Slot.HANDS
        }
    }

    @Test
    fun `a firing umbrella rule sets the carried slot`() {
        // An umbrella rule keyed on rain chance lights the optional carried tier
        // when the day's precip probability crosses its threshold and the
        // condition is wet (rain).
        val umbrellaRules = listOf(
            ClothesRule(Garment.UMBRELLA, ClothesRule.PrecipitationProbabilityAbove(30.0)),
        )
        val outfit = OutfitSuggestion.fromForecast(
            forecast(
                feelsLikeMin = 12.0,
                feelsLikeMax = 18.0,
                precipMaxPct = 60.0,
                condition = WeatherCondition.RAIN,
            ),
            umbrellaRules,
        )
        outfit.carried shouldBe OutfitSuggestion.Carried.UMBRELLA
    }

    @Test
    fun `a thunderstorm day still lights the carried umbrella`() {
        // Thunderstorm is treated as rain: it's a wet forecast the user wants an
        // umbrella for, so the carried slot fires (consistent with the prose).
        val umbrellaRules = listOf(
            ClothesRule(Garment.UMBRELLA, ClothesRule.PrecipitationProbabilityAbove(30.0)),
        )
        val outfit = OutfitSuggestion.fromForecast(
            forecast(
                feelsLikeMin = 12.0,
                feelsLikeMax = 18.0,
                precipMaxPct = 60.0,
                condition = WeatherCondition.THUNDERSTORM,
            ),
            umbrellaRules,
        )
        outfit.carried shouldBe OutfitSuggestion.Carried.UMBRELLA
    }

    @Test
    fun `a high rain chance coded overcast still lights the carried umbrella`() {
        // The bug case: 88% chance of rain, but the peak-probability hour's
        // weather code is overcast (high probability, ~0mm accumulation), so the
        // raw daily condition is CLOUDY rather than a wet code. The umbrella must
        // still light, matching the prose, since only snow suppresses it.
        val umbrellaRules = listOf(
            ClothesRule(Garment.UMBRELLA, ClothesRule.PrecipitationProbabilityAbove(30.0)),
        )
        val outfit = OutfitSuggestion.fromForecast(
            forecast(
                feelsLikeMin = 9.0,
                feelsLikeMax = 15.0,
                precipMaxPct = 88.0,
                condition = WeatherCondition.CLOUDY,
            ),
            umbrellaRules,
        )
        outfit.carried shouldBe OutfitSuggestion.Carried.UMBRELLA
    }

    @Test
    fun `a snowy day suppresses the carried umbrella even above the gate`() {
        // The umbrella default keys off aggregate precip probability, which can
        // clear its gate on a snowy day — but snow isn't wet-in-the-umbrella
        // sense, so the carried slot stays null and no umbrella icon shows.
        val umbrellaRules = listOf(
            ClothesRule(Garment.UMBRELLA, ClothesRule.PrecipitationProbabilityAbove(30.0)),
        )
        val outfit = OutfitSuggestion.fromForecast(
            forecast(
                feelsLikeMin = -2.0,
                feelsLikeMax = 2.0,
                precipMaxPct = 80.0,
                condition = WeatherCondition.SNOW,
            ),
            umbrellaRules,
        )
        outfit.carried shouldBe null
    }

    @Test
    fun `carried stays null when no umbrella rule fires`() {
        // Carried is opt-in with no fallback: a dry day (or no umbrella rule)
        // leaves it null rather than promoting to a default, so no umbrella icon
        // shows when the user hasn't earned one.
        val umbrellaRules = listOf(
            ClothesRule(Garment.UMBRELLA, ClothesRule.PrecipitationProbabilityAbove(30.0)),
        )
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 12.0, feelsLikeMax = 18.0, precipMaxPct = 10.0),
            umbrellaRules,
        )
        outfit.carried shouldBe null
    }

    @Test
    fun `gloves and umbrella light the hands and carried slots independently`() {
        // The two opt-in tiers don't compete: a cold, rainy day with both rules
        // firing lights gloves on the hands slot and the umbrella on carried.
        val triggered = TriggeredOutfit(
            rules = listOf(
                ClothesRule(Garment.COAT, ClothesRule.TemperatureBelow(5.0)),
                ClothesRule(Garment.GLOVES, ClothesRule.TemperatureBelow(4.0)),
                ClothesRule(Garment.UMBRELLA, ClothesRule.PrecipitationProbabilityAbove(30.0)),
            ),
            fallbacks = emptyList(),
        )
        OutfitSuggestion.fromTriggeredOutfit(triggered) shouldBe OutfitSuggestion(
            OutfitSuggestion.Top.THICK_COAT,
            OutfitSuggestion.Bottom.LONG_PANTS,
            OutfitSuggestion.Hands.GLOVES,
            OutfitSuggestion.Carried.UMBRELLA,
        )
    }

    @Test
    fun `carried itemKey round-trips through the garment catalog`() {
        // The slot's catalog key must resolve back to the CARRIED-slot garment so
        // prose / persistence / the umbrella icon all key off the same string.
        OutfitSuggestion.Carried.entries.forEach { carried ->
            Garment.fromKey(carried.itemKey())?.slot shouldBe Garment.Slot.CARRIED
        }
    }

    @Test
    fun `a firing rain-jacket rule sets the outer slot without replacing the top`() {
        // The rain jacket is an additional outer shell, not a top tier: a firing
        // rule lights the outer slot and leaves whatever warmth tier the other
        // rules picked (here the sweater) intact underneath.
        val rules = listOf(
            ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(16.0)),
            ClothesRule(Garment.RAIN_JACKET, ClothesRule.PrecipitationProbabilityAbove(30.0)),
        )
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 12.0, feelsLikeMax = 15.0, precipMaxPct = 60.0),
            rules,
        )
        outfit.top shouldBe OutfitSuggestion.Top.SWEATER
        outfit.outer shouldBe OutfitSuggestion.Outer.RAIN_JACKET
    }

    @Test
    fun `outer stays null when no rain-jacket rule fires`() {
        // Outer is opt-in with no fallback: a default rule set (no rain-jacket
        // rule) leaves the slot null and no rain-jacket overlay shows.
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 12.0, feelsLikeMax = 15.0, precipMaxPct = 90.0),
            rules,
        )
        outfit.outer shouldBe null
    }

    @Test
    fun `a rain-jacket rule firing alone overlays the default top`() {
        // With only a rain-jacket rule firing, the top tier falls through to the
        // user's default and the rain jacket paints over it — the shell adds to
        // the outfit rather than standing in for a missing top.
        val rules = listOf(
            ClothesRule(Garment.RAIN_JACKET, ClothesRule.PrecipitationProbabilityAbove(30.0)),
        )
        val outfit = OutfitSuggestion.fromForecast(
            forecast(feelsLikeMin = 18.0, feelsLikeMax = 24.0, precipMaxPct = 70.0),
            rules,
            defaultTop = OutfitSuggestion.Top.TSHIRT,
        )
        outfit.top shouldBe OutfitSuggestion.Top.TSHIRT
        outfit.outer shouldBe OutfitSuggestion.Outer.RAIN_JACKET
    }

    @Test
    fun `outer itemKey round-trips through the garment catalog`() {
        // The slot's catalog key must resolve back to the OUTER-layer TOP-slot
        // garment so prose / persistence / the rain-jacket icon all key off the
        // same string.
        OutfitSuggestion.Outer.entries.forEach { outer ->
            val garment = Garment.fromKey(outer.itemKey())
            garment?.slot shouldBe Garment.Slot.TOP
            garment?.layer shouldBe Garment.Layer.OUTER
        }
    }
}
