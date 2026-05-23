package app.clothescast.tts

import android.content.Context
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.RainAccessory
import app.clothescast.core.domain.model.RangeFormat
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
 *
 * On a themed day [holidayTheme] prepends a spoken greeting ("Merry Christmas!
 * Today, it will be …") localised to the same voice locale. See
 * [holidayTtsGreeting] for the privacy rules around calendar-sourced titles.
 */
internal fun insightTtsUtterance(
    context: Context,
    summary: InsightSummary,
    region: Region,
    voiceLocale: VoiceLocale,
    temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    rangeFormat: RangeFormat = RangeFormat.DEGREES,
    clothesFormat: ClothesFormat = ClothesFormat.ITEMS,
    rainAccessory: RainAccessory = RainAccessory.NONE,
    fallbackLocale: Locale = Locale.getDefault(),
    holidayTheme: HolidayTheme? = null,
): InsightTtsUtterance {
    val regionLocale = region.toJavaLocale() ?: fallbackLocale
    val locale = voiceLocale.resolve(regionLocale)
    val forecast = InsightFormatter.forContext(context, locale, temperatureUnit, rangeFormat, clothesFormat, rainAccessory)
        // Spoken playback stays silent when nothing fired — no "it will be the
        // same as yesterday." filler read aloud — unlike the display surfaces.
        .format(summary, placeholderWhenEmpty = false)
    // Override country tracks the app region (mirrors HolidayBanner) while the
    // greeting's language follows the voice locale.
    val greeting = holidayTtsGreeting(context, holidayTheme, locale, regionLocale.country)
    return InsightTtsUtterance(
        text = listOfNotNull(greeting, forecast.ifBlank { null }).joinToString(" "),
        locale = locale,
    )
}
