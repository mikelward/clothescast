package app.clothescast.tts

import android.content.Context
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.insight.InsightFormatter
import java.util.Locale

internal data class InsightTtsUtterance(
    val text: String,
    val locale: Locale,
)

/**
 * Render the spoken insight in the selected voice locale, not necessarily the
 * app-display region. This keeps notifications in the UI language while a
 * de-AT voice request speaks Austrian German text instead of English prose with
 * an Austrian accent.
 */
internal fun insightTtsUtterance(
    context: Context,
    summary: InsightSummary,
    region: Region,
    voiceLocale: VoiceLocale,
    temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    fallbackLocale: Locale = Locale.getDefault(),
): InsightTtsUtterance {
    val locale = voiceLocale.resolve(region.toJavaLocale() ?: fallbackLocale)
    return InsightTtsUtterance(
        text = InsightFormatter.forContext(context, locale, temperatureUnit).format(summary),
        locale = locale,
    )
}
