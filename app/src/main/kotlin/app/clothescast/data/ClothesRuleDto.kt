package app.clothescast.data

import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.TemperatureUnit
import kotlinx.serialization.Serializable

/**
 * On-disk representation of a [ClothesRule]. The domain type uses a sealed interface
 * for [ClothesRule.Condition], which is awkward to serialize directly — this DTO
 * flattens the condition to (type, value, unit) and round-trips through
 * [toDomain] / [toDto].
 *
 * [item] is stored as a string key so JSON stays stable, but the domain type is a
 * typed [Garment]: [toDomain] resolves the key via [Garment.fromKey] (which folds
 * legacy spellings / case / whitespace onto the canonical catalog key) and returns
 * `null` for any key not in the catalog — a legacy free-form item from before the
 * catalog existed. Callers drop those nulls, so unrecognised stored rules are
 * silently discarded rather than kept as untyped strings.
 *
 * `type` strings are intentionally non-camelCase so they're stable identifiers in JSON
 * even if class names are renamed. The flat scalar fields ([value], [unit]) are kept
 * for the legacy single-condition types so JSON written by older app versions still
 * deserialises unchanged (legacy temperature / precip rules are byte-identical on
 * re-write). [unit] is nullable so JSON predating unit-aware thresholds still
 * deserialises (legacy = always Celsius); unit is meaningless for non-temperature
 * conditions and stays null there.
 *
 * The retired `rain_code` / `any_of` shapes (a weather-code floor / a probability-OR-code
 * composite, from before all rain surfaces collapsed onto the blended-consensus
 * probability) still *decode* — [codeFloor] and [any] are read but never written —
 * so [rainGearProbabilityMigration] can rewrite stored rows onto a single probability
 * gate. A `rain_code` / `any_of` row that survives to read time without migrating
 * resolves via [toDomain]'s collapse: an `any_of` keeps its probability arm; a bare
 * `rain_code` (or an `any_of` with no probability arm) substitutes the new
 * probability default for the umbrella (10%) / rain jacket (50%) and drops for any
 * other item.
 */
@Serializable
internal data class ClothesRuleDto(
    val item: String,
    val type: String,
    // Required (no default) so it's *always* encoded. An older build's DTO requires
    // `value`; if the encoder omitted it (defaults aren't written), that build would
    // fail to decode the *whole* stored list — not just the one unknown row — and
    // parseRules would reset every threshold to defaults, losing the user's
    // customisations. Keeping it encoded lets a downgrade drop only the rule it
    // can't understand.
    val value: Double,
    val unit: String? = null,
    // Read-only legacy fields for the retired rain_code / any_of shapes. Never
    // written (the current encoder emits only temp_below / temp_above / precip_above),
    // but kept so stored rows from before the consensus-probability collapse still
    // deserialise and can be migrated / collapsed rather than rejected wholesale.
    val codeFloor: String? = null,
    val any: List<ClothesConditionDto>? = null,
) {
    /**
     * Returns the domain rule, or `null` if [item] isn't a catalog [Garment]
     * or the condition can't be reconstructed. Unknown types degrade per-item
     * like unknown garments do: one forward-compat rule (written by a future
     * build, then the app downgraded) costs only itself, not the user's entire
     * rule list — throwing here would trip the whole-list `runCatching` in
     * `parseRules` and silently reset every threshold to defaults.
     *
     * Legacy `rain_code` / `any_of` rows collapse onto a single probability gate:
     * an `any_of` keeps its probability arm; a bare `rain_code` (or an `any_of`
     * with no probability arm) takes the new probability default for the umbrella
     * (10%) / rain jacket (50%) and is dropped for any other item.
     */
    fun toDomain(): ClothesRule? {
        val garment = Garment.fromKey(item) ?: return null
        val condition = resolveCondition(garment) ?: return null
        return ClothesRule(item = garment, condition = condition)
    }

    private fun resolveCondition(garment: Garment): ClothesRule.Condition? = when (type) {
        TYPE_TEMP_BELOW -> ClothesRule.TemperatureBelow(value, parseUnit(unit))
        TYPE_TEMP_ABOVE -> ClothesRule.TemperatureAbove(value, parseUnit(unit))
        TYPE_PRECIP_ABOVE -> ClothesRule.PrecipitationProbabilityAbove(value)
        // Retired shapes: collapse onto the single probability gate.
        TYPE_RAIN_CODE -> probabilityDefaultFor(garment)
        TYPE_ANY_OF -> any
            ?.firstNotNullOfOrNull { it.probabilityPercent() }
            ?.let { ClothesRule.PrecipitationProbabilityAbove(it) }
            ?: probabilityDefaultFor(garment)
        else -> null
    }

    companion object {
        const val TYPE_TEMP_BELOW = "temp_below"
        const val TYPE_TEMP_ABOVE = "temp_above"
        const val TYPE_PRECIP_ABOVE = "precip_above"
        // Retired condition types, still recognised for decode/migration only.
        const val TYPE_RAIN_CODE = "rain_code"
        const val TYPE_ANY_OF = "any_of"

        /** Falls back to Celsius for legacy data (`null`) or unrecognised tokens. */
        private fun parseUnit(raw: String?): TemperatureUnit =
            raw?.let { runCatching { TemperatureUnit.valueOf(it) }.getOrNull() }
                ?: TemperatureUnit.CELSIUS

        /**
         * The new single-probability default for a rain-gear garment, or `null` for
         * anything else — used when collapsing a code-only legacy rule that carries
         * no probability of its own. Umbrella → 10% (chance of rain), rain jacket →
         * 50% (likely rain); any other item's code-only rule is dropped.
         */
        private fun probabilityDefaultFor(garment: Garment): ClothesRule.Condition? = when (garment) {
            Garment.UMBRELLA -> ClothesRule.PrecipitationProbabilityAbove(10.0)
            Garment.RAIN_JACKET -> ClothesRule.PrecipitationProbabilityAbove(50.0)
            else -> null
        }
    }
}

/**
 * The serialized form of a [ClothesRule.Condition]. Carries no `item` — a condition
 * is item-agnostic. The current writer only ever emits the temperature / probability
 * shapes; the legacy [codeFloor] / [any] fields are kept read-only so a stored
 * `any_of` composite's children still deserialise for the rain-gear migration.
 */
@Serializable
internal data class ClothesConditionDto(
    val type: String,
    val value: Double = 0.0,
    val unit: String? = null,
    val codeFloor: String? = null,
    val any: List<ClothesConditionDto>? = null,
) {
    /**
     * The probability percent this condition (or, for an `any_of`, its first
     * probability child) carries, or `null` when it has no probability arm.
     * Used to collapse a legacy composite onto its probability gate.
     */
    fun probabilityPercent(): Double? = when (type) {
        ClothesRuleDto.TYPE_PRECIP_ABOVE -> value
        ClothesRuleDto.TYPE_ANY_OF -> any?.firstNotNullOfOrNull { it.probabilityPercent() }
        else -> null
    }
}

internal fun ClothesRule.toDto(): ClothesRuleDto {
    val c = condition.toConditionDto()
    return ClothesRuleDto(
        item = item.itemKey,
        type = c.type,
        value = c.value,
        unit = c.unit,
    )
}

private fun ClothesRule.Condition.toConditionDto(): ClothesConditionDto = when (this) {
    is ClothesRule.TemperatureBelow ->
        ClothesConditionDto(ClothesRuleDto.TYPE_TEMP_BELOW, value = value, unit = unit.name)
    is ClothesRule.TemperatureAbove ->
        ClothesConditionDto(ClothesRuleDto.TYPE_TEMP_ABOVE, value = value, unit = unit.name)
    is ClothesRule.PrecipitationProbabilityAbove ->
        ClothesConditionDto(ClothesRuleDto.TYPE_PRECIP_ABOVE, value = percent)
}
