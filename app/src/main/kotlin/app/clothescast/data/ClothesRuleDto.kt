package app.clothescast.data

import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.TemperatureUnit
import kotlinx.serialization.Serializable

/**
 * On-disk representation of a [ClothesRule]. The domain type uses a sealed interface
 * for [ClothesRule.Condition], which is awkward to serialize directly — this DTO
 * flattens it to (type, value, unit) and round-trips through [toDomain] / [toDto].
 *
 * [item] is stored as a string key so JSON stays stable, but the domain type is a
 * typed [Garment]: [toDomain] resolves the key via [Garment.fromKey] (which folds
 * legacy spellings / case / whitespace onto the canonical catalog key) and returns
 * `null` for any key not in the catalog — a legacy free-form item from before the
 * catalog existed. Callers drop those nulls, so unrecognised stored rules are
 * silently discarded rather than kept as untyped strings.
 *
 * `type` strings are intentionally non-camelCase so they're stable identifiers in JSON
 * even if class names are renamed. [unit] is nullable so JSON written by app versions
 * before unit-aware thresholds existed still deserialises (legacy = always Celsius);
 * unit is meaningless for precipitation rules and stays null there.
 */
@Serializable
internal data class ClothesRuleDto(
    val item: String,
    val type: String,
    val value: Double,
    val unit: String? = null,
) {
    /**
     * Returns the domain rule, or `null` if [item] isn't a catalog [Garment]
     * or [type] isn't a known condition. Unknown types degrade per-item like
     * unknown garments do: one forward-compat rule (written by a future build,
     * then the app downgraded) costs only itself, not the user's entire rule
     * list — throwing here would trip the whole-list `runCatching` in
     * `parseRules` and silently reset every threshold to defaults.
     */
    fun toDomain(): ClothesRule? {
        val garment = Garment.fromKey(item) ?: return null
        val condition = when (type) {
            TYPE_TEMP_BELOW -> ClothesRule.TemperatureBelow(value, parseUnit(unit))
            TYPE_TEMP_ABOVE -> ClothesRule.TemperatureAbove(value, parseUnit(unit))
            TYPE_PRECIP_ABOVE -> ClothesRule.PrecipitationProbabilityAbove(value)
            else -> return null
        }
        return ClothesRule(item = garment, condition = condition)
    }

    companion object {
        const val TYPE_TEMP_BELOW = "temp_below"
        const val TYPE_TEMP_ABOVE = "temp_above"
        const val TYPE_PRECIP_ABOVE = "precip_above"

        /** Falls back to Celsius for legacy data (`null`) or unrecognised tokens. */
        private fun parseUnit(raw: String?): TemperatureUnit =
            raw?.let { runCatching { TemperatureUnit.valueOf(it) }.getOrNull() }
                ?: TemperatureUnit.CELSIUS
    }
}

internal fun ClothesRule.toDto(): ClothesRuleDto = when (val c = condition) {
    is ClothesRule.TemperatureBelow ->
        ClothesRuleDto(item.itemKey, ClothesRuleDto.TYPE_TEMP_BELOW, c.value, c.unit.name)
    is ClothesRule.TemperatureAbove ->
        ClothesRuleDto(item.itemKey, ClothesRuleDto.TYPE_TEMP_ABOVE, c.value, c.unit.name)
    is ClothesRule.PrecipitationProbabilityAbove ->
        ClothesRuleDto(item.itemKey, ClothesRuleDto.TYPE_PRECIP_ABOVE, c.percent)
}
