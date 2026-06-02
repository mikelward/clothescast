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
 * even if class names are renamed. Rain rules write `"rain_above"`; the older
 * `"precip_above"` token (written before the precipitation→rain rename) is still read
 * via [TYPE_RAIN_ABOVE_LEGACY] so rules saved by earlier builds keep loading. [unit] is
 * nullable so JSON written by app versions before unit-aware thresholds existed still
 * deserialises (legacy = always Celsius); unit is meaningless for rain rules and stays
 * null there.
 */
@Serializable
internal data class ClothesRuleDto(
    val item: String,
    val type: String,
    val value: Double,
    val unit: String? = null,
) {
    /** Returns the domain rule, or `null` if [item] isn't a catalog [Garment]. */
    fun toDomain(): ClothesRule? {
        val garment = Garment.fromKey(item) ?: return null
        return ClothesRule(
            item = garment,
            condition = when (type) {
                TYPE_TEMP_BELOW -> ClothesRule.TemperatureBelow(value, parseUnit(unit))
                TYPE_TEMP_ABOVE -> ClothesRule.TemperatureAbove(value, parseUnit(unit))
                TYPE_RAIN_ABOVE, TYPE_RAIN_ABOVE_LEGACY -> ClothesRule.RainProbabilityAbove(value)
                else -> error("Unknown clothes rule type: $type")
            },
        )
    }

    companion object {
        const val TYPE_TEMP_BELOW = "temp_below"
        const val TYPE_TEMP_ABOVE = "temp_above"
        const val TYPE_RAIN_ABOVE = "rain_above"

        /**
         * Legacy wire token for rain rules, written before the precipitation→rain
         * rename. Read-only: [toDomain] still accepts it so saved rules keep
         * loading, but [toDto] always writes the current [TYPE_RAIN_ABOVE].
         */
        const val TYPE_RAIN_ABOVE_LEGACY = "precip_above"

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
    is ClothesRule.RainProbabilityAbove ->
        ClothesRuleDto(item.itemKey, ClothesRuleDto.TYPE_RAIN_ABOVE, c.percent)
}
