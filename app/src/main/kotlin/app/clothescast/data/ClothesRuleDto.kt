package app.clothescast.data

import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WeatherCondition
import kotlinx.serialization.Serializable

/**
 * On-disk representation of a [ClothesRule]. The domain type uses a sealed interface
 * for [ClothesRule.Condition], which is awkward to serialize directly — this DTO
 * flattens the condition to (type, value, unit, codeFloor, any) and round-trips
 * through [toDomain] / [toDto].
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
 * re-write). The newer condition shapes add [codeFloor] (the [ClothesRule.RainCode]
 * floor) and [any] (the [ClothesRule.AnyOf] children, a recursive list of
 * [ClothesConditionDto]); both are nullable with defaults so they're omitted from
 * legacy rows. [unit] is nullable so JSON predating unit-aware thresholds still
 * deserialises (legacy = always Celsius); unit is meaningless for non-temperature
 * conditions and stays null there.
 */
@Serializable
internal data class ClothesRuleDto(
    val item: String,
    val type: String,
    // Required (no default) so it's *always* encoded, even for the newer
    // `rain_code` / `any_of` rows where the value is a meaningless 0.0. An older
    // build's DTO requires `value`; if the encoder omitted it (defaults aren't
    // written), that build would fail to decode the *whole* stored list — not
    // just the one unknown row — and parseRules would reset every threshold to
    // defaults, losing the user's customisations. Keeping it encoded lets a
    // downgrade drop only the rule it can't understand.
    val value: Double,
    val unit: String? = null,
    val codeFloor: String? = null,
    val any: List<ClothesConditionDto>? = null,
) {
    /**
     * Returns the domain rule, or `null` if [item] isn't a catalog [Garment]
     * or the condition can't be reconstructed (unknown [type] / floor). Unknown
     * types degrade per-item like unknown garments do: one forward-compat rule
     * (written by a future build, then the app downgraded) costs only itself,
     * not the user's entire rule list — throwing here would trip the whole-list
     * `runCatching` in `parseRules` and silently reset every threshold to
     * defaults.
     */
    fun toDomain(): ClothesRule? {
        val garment = Garment.fromKey(item) ?: return null
        val condition = ClothesConditionDto(type, value, unit, codeFloor, any).toDomain()
            ?: return null
        return ClothesRule(item = garment, condition = condition)
    }

    companion object {
        const val TYPE_TEMP_BELOW = "temp_below"
        const val TYPE_TEMP_ABOVE = "temp_above"
        const val TYPE_PRECIP_ABOVE = "precip_above"
        const val TYPE_RAIN_CODE = "rain_code"
        const val TYPE_ANY_OF = "any_of"
    }
}

/**
 * The serialized form of a [ClothesRule.Condition], reused both at the rule's top
 * level (via [ClothesRuleDto]'s mirrored fields) and as the recursive child shape
 * inside an [ClothesRule.AnyOf] (the [ClothesRuleDto.any] list). Carries no `item`
 * — a condition is item-agnostic; the owning rule names the garment.
 */
@Serializable
internal data class ClothesConditionDto(
    val type: String,
    val value: Double = 0.0,
    val unit: String? = null,
    val codeFloor: String? = null,
    val any: List<ClothesConditionDto>? = null,
) {
    /** The domain condition, or `null` for an unknown [type] / unparseable floor. */
    fun toDomain(): ClothesRule.Condition? = when (type) {
        ClothesRuleDto.TYPE_TEMP_BELOW -> ClothesRule.TemperatureBelow(value, parseUnit(unit))
        ClothesRuleDto.TYPE_TEMP_ABOVE -> ClothesRule.TemperatureAbove(value, parseUnit(unit))
        ClothesRuleDto.TYPE_PRECIP_ABOVE -> ClothesRule.PrecipitationProbabilityAbove(value)
        ClothesRuleDto.TYPE_RAIN_CODE -> parseFloor(codeFloor)?.let { ClothesRule.RainCode(it) }
        // Drop only the children that fail to parse, not the whole rule: an
        // umbrella whose forward-compat code arm is unknown still works off its
        // probability arm. `any == null` (a malformed any_of with no children)
        // is unrecoverable, so the rule degrades to null.
        ClothesRuleDto.TYPE_ANY_OF -> any?.let { ClothesRule.AnyOf(it.mapNotNull(ClothesConditionDto::toDomain)) }
        else -> null
    }

    companion object {
        /** Falls back to Celsius for legacy data (`null`) or unrecognised tokens. */
        private fun parseUnit(raw: String?): TemperatureUnit =
            raw?.let { runCatching { TemperatureUnit.valueOf(it) }.getOrNull() }
                ?: TemperatureUnit.CELSIUS

        /** The rain-code floor [WeatherCondition], or `null` for a missing / bad token. */
        private fun parseFloor(raw: String?): WeatherCondition? =
            raw?.let { runCatching { WeatherCondition.valueOf(it) }.getOrNull() }
    }
}

internal fun ClothesRule.toDto(): ClothesRuleDto {
    val c = condition.toConditionDto()
    return ClothesRuleDto(
        item = item.itemKey,
        type = c.type,
        value = c.value,
        unit = c.unit,
        codeFloor = c.codeFloor,
        any = c.any,
    )
}

private fun ClothesRule.Condition.toConditionDto(): ClothesConditionDto = when (this) {
    is ClothesRule.TemperatureBelow ->
        ClothesConditionDto(ClothesRuleDto.TYPE_TEMP_BELOW, value = value, unit = unit.name)
    is ClothesRule.TemperatureAbove ->
        ClothesConditionDto(ClothesRuleDto.TYPE_TEMP_ABOVE, value = value, unit = unit.name)
    is ClothesRule.PrecipitationProbabilityAbove ->
        ClothesConditionDto(ClothesRuleDto.TYPE_PRECIP_ABOVE, value = percent)
    is ClothesRule.RainCode ->
        ClothesConditionDto(ClothesRuleDto.TYPE_RAIN_CODE, codeFloor = floor.name)
    is ClothesRule.AnyOf ->
        ClothesConditionDto(ClothesRuleDto.TYPE_ANY_OF, any = conditions.map { it.toConditionDto() })
}
