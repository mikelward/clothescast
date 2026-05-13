package app.clothescast.insight

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import app.clothescast.R
import app.clothescast.core.domain.model.AlertClause
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherCondition
import java.time.LocalTime
import java.util.Locale

/**
 * Renders a structured [InsightSummary] into the spoken / displayed prose.
 *
 * Strings live in `values[-xx]/strings.xml`; the formatter only composes them.
 * The injected [resources] is expected to have a Configuration whose locale
 * matches [locale] — call sites use [forRegion] (or [forContext]) to obtain a
 * properly localized Resources for the user's [Region] choice.
 *
 * Article picking (English "a sweater" / "an umbrella" / bare "shorts") is
 * locale-specific and dispatched via [ClothesPhraser.forLocale]. Languages
 * without an explicit phraser fall back to a no-article join.
 *
 * Times are rendered as natural language: English uses 12h named forms
 * ("midnight", "2am", "noon", "3pm"); all other locales use 24h templates
 * from `insight_time_hour` / `insight_time_hour_minutes` resources (e.g.
 * "15 Uhr", "15時") so TTS reads them as words rather than digit-colon-digit.
 * Early-morning precip peaks (00:00–04:59) always collapse to "overnight" —
 * the previous "only when no tie-in pins this hour" carve-out is gone now
 * that tie-in clauses no longer name a specific time.
 */
class InsightFormatter(
    private val resources: Resources,
    private val locale: Locale = Locale.getDefault(),
) {
    private val phraser: ClothesPhraser = ClothesPhraser.forLocale(resources, locale)

    fun format(summary: InsightSummary): String = buildList {
        summary.alert?.let { add(formatAlert(it)) }
        add(formatBand(summary.period, summary.band))
        summary.delta?.let { add(formatDelta(it)) }
        summary.clothes?.let(::formatClothes)?.let(::add)
        summary.precip?.let { add(formatPrecip(it)) }
        // Both tie-in clauses share the item-only "Bring a X tonight." form;
        // the evening one additionally folds in the evening forecast's rain
        // time when it's ≥ 30%, since the morning's own precip clause only
        // covers the morning slice and otherwise wouldn't surface evening
        // rain. The clauses are separate fields because they're gated
        // differently (TONIGHT precip-peak overlap vs. TODAY evening-event
        // opt-in).
        summary.calendarTieIn?.let { formatTieIn(it.item) }?.let(::add)
        summary.eveningEventTieIn?.let(::formatEveningEventTieIn)?.let(::add)
    }.joinToString(" ")

    private fun formatAlert(alert: AlertClause): String =
        resources.getString(R.string.insight_alert, alert.event)

    private fun formatBand(period: ForecastPeriod, band: BandClause): String {
        val lead = resources.getString(leadRes(period))
        val low = resources.getString(bandRes(band.low))
        return if (band.low == band.high) {
            resources.getString(R.string.insight_band_single, lead, low)
        } else {
            val high = resources.getString(bandRes(band.high))
            resources.getString(R.string.insight_band_range, lead, low, high)
        }
    }

    private fun formatDelta(delta: DeltaClause): String {
        val template = when (delta.direction) {
            DeltaClause.Direction.WARMER -> R.string.insight_delta_warmer
            DeltaClause.Direction.COOLER -> R.string.insight_delta_cooler
        }
        return resources.getString(template, delta.degrees)
    }

    // TODO: "Wear an umbrella" reads awkwardly — umbrellas are carried, not worn.
    // Either drop umbrella from the clothes list (it's already implied by the
    // precip clause and the tonight calendar tie-in) or split the clause into
    // a "Wear …" sentence for garments and a "Bring …" sentence for accessories
    // like umbrella. Probably wants a small domain-side classification on
    // ClothesRule (item kind: garment / accessory) rather than hard-coding
    // umbrella here.
    private fun formatClothes(clothes: ClothesClause): String? {
        val items = phraser.joinItems(clothes.items)
        if (items.isBlank()) return null
        return resources.getString(R.string.insight_clothes_wear, items)
    }

    private fun formatPrecip(precip: PrecipClause): String {
        val rawType = resources.getString(conditionRes(precip.condition))
        // "Rain at 02:00" sounds robotic and a precise hour adds little value
        // when the user is asleep — collapse early-morning peaks to "overnight".
        val timePhrase = if (precip.time.hour in OVERNIGHT_HOURS) {
            resources.getString(R.string.insight_precip_overnight)
        } else {
            resources.getString(R.string.insight_precip_at_time, spokenTime(precip.time))
        }
        return when (precip.likelihood) {
            PrecipLikelihood.LIKELY ->
                resources.getString(R.string.insight_precip, rawType, timePhrase)
            // "Chance of Rain at 3pm" reads odd with the condition title-cased
            // mid-sentence; downcase the noun so the lead "Chance of" sits
            // naturally. Other locales' condition resources may already be
            // lowercase or have grammatical case to handle — this lowering is
            // safe for English ("Rain" → "rain") and a no-op for languages
            // where the condition resource is already in lower form.
            PrecipLikelihood.POSSIBLE ->
                resources.getString(R.string.insight_precip_chance, rawType.lowercase(locale), timePhrase)
        }
    }

    private fun formatTieIn(item: String): String? {
        // Short-circuit before article picking: prefixArticle("") emits "a "
        // via the "a %1$s" template, which is non-blank and would slip past a
        // post-rendering isBlank() check.
        if (item.isBlank()) return null
        val renderedItem = phraser.withArticle(item)
        if (renderedItem.isBlank()) return null
        return resources.getString(R.string.insight_tie_in, renderedItem)
    }

    private fun formatEveningEventTieIn(tieIn: EveningEventTieInClause): String? {
        // Local captures so null checks enable smart cast — the properties
        // live on a :core:domain data class and Kotlin won't smart-cast a
        // public API property across modules.
        val item = tieIn.item
        val rainTime = tieIn.rainTime
        if (item == null) {
            // Bare-rain path: no clothes rule triggered (the renderer only
            // emits item=null in this case), so the rain mention is the
            // entire clause. The renderer also pairs this shape with a
            // non-null rainTime — return null if not (malformed input,
            // rather than emit a "Rain tonight at ." sentence).
            if (rainTime == null) return null
            val template = when (tieIn.likelihood) {
                PrecipLikelihood.LIKELY -> R.string.insight_evening_rain
                PrecipLikelihood.POSSIBLE -> R.string.insight_evening_rain_chance
            }
            return resources.getString(template, spokenTime(rainTime))
        }
        // Item-led path: blank item indicates malformed input; suppress the
        // whole clause rather than fall through to bare-rain — a blank
        // item with rainTime set isn't the renderer's bare-rain emission
        // (that path produces item=null specifically), so treat it as a
        // data error.
        if (item.isBlank()) return null
        val renderedItem = phraser.withArticle(item)
        if (renderedItem.isBlank()) return null
        rainTime ?: return resources.getString(R.string.insight_tie_in, renderedItem)
        // Hedge the item-led wording when only one model spotted the rain,
        // matching the bare-rain path's chance-of-rain template. Otherwise
        // a clothes rule triggering for warmth would always promote a
        // single-model rain reading to a confident "Bring a jacket
        // tonight, rain at 9pm." even when the per-model tier was POSSIBLE.
        val template = when (tieIn.likelihood) {
            PrecipLikelihood.LIKELY -> R.string.insight_tie_in_with_rain
            PrecipLikelihood.POSSIBLE -> R.string.insight_tie_in_with_rain_chance
        }
        return resources.getString(template, renderedItem, spokenTime(rainTime))
    }

    private fun leadRes(period: ForecastPeriod): Int = when (period) {
        ForecastPeriod.TODAY -> R.string.insight_lead_today
        ForecastPeriod.TONIGHT -> R.string.insight_lead_tonight
    }

    private fun bandRes(band: TemperatureBand): Int = when (band) {
        TemperatureBand.FREEZING -> R.string.insight_band_freezing
        TemperatureBand.COLD -> R.string.insight_band_cold
        TemperatureBand.COOL -> R.string.insight_band_cool
        TemperatureBand.MILD -> R.string.insight_band_mild
        TemperatureBand.WARM -> R.string.insight_band_warm
        TemperatureBand.HOT -> R.string.insight_band_hot
    }

    private fun conditionRes(condition: WeatherCondition): Int = when (condition) {
        WeatherCondition.CLEAR -> R.string.insight_condition_clear
        WeatherCondition.PARTLY_CLOUDY -> R.string.insight_condition_partly_cloudy
        WeatherCondition.CLOUDY -> R.string.insight_condition_cloudy
        WeatherCondition.FOG -> R.string.insight_condition_fog
        WeatherCondition.DRIZZLE -> R.string.insight_condition_drizzle
        WeatherCondition.RAIN -> R.string.insight_condition_rain
        WeatherCondition.SNOW -> R.string.insight_condition_snow
        WeatherCondition.THUNDERSTORM -> R.string.insight_condition_thunderstorm
        WeatherCondition.UNKNOWN -> R.string.insight_condition_unknown
    }

    /**
     * 24h LocalTime → spoken form for TTS and display.
     *
     * English (`en-*`): 12h named forms ("midnight", "2am", "noon", "3:30pm").
     * All other locales: 24h templates driven by `insight_time_hour` /
     * `insight_time_hour_minutes` resource strings so each locale can express
     * its natural spoken form ("%1$d Uhr", "%1$d時", etc.) rather than
     * digit-colon-digit pairs that TTS engines mispronounce.
     */
    private fun spokenTime(time: LocalTime): String {
        val hour = time.hour
        val minute = time.minute
        if (locale.language == "en") {
            if (hour == 0 && minute == 0) return resources.getString(R.string.insight_time_midnight)
            if (hour == 12 && minute == 0) return resources.getString(R.string.insight_time_noon)
            val h12 = ((hour + 11) % 12) + 1
            val template = when {
                hour < 12 && minute == 0 -> R.string.insight_time_am
                hour < 12 -> R.string.insight_time_am_minutes
                minute == 0 -> R.string.insight_time_pm
                else -> R.string.insight_time_pm_minutes
            }
            return if (minute == 0) resources.getString(template, h12)
            else resources.getString(template, h12, minute)
        }
        return if (minute == 0) {
            resources.getString(R.string.insight_time_hour, hour)
        } else {
            resources.getString(R.string.insight_time_hour_minutes, hour, minute)
        }
    }

    companion object {
        // Hours treated as "overnight" when collapsing precip times. 5am is borderline
        // morning rather than night, so the band stops at 4:59.
        private val OVERNIGHT_HOURS = 0..4

        /** Build a formatter that renders prose in [locale] using [context]'s resources. */
        fun forContext(context: Context, locale: Locale = context.currentResourcesLocale()): InsightFormatter =
            InsightFormatter(context.localizedResources(locale), locale)

        /** Convenience for the common path: render in the user's [Region]-derived locale. */
        fun forRegion(context: Context, region: Region): InsightFormatter {
            val locale = region.toJavaLocale() ?: context.currentResourcesLocale()
            return forContext(context, locale)
        }
    }
}

private fun Region.toJavaLocale(): Locale? = bcp47?.let { Locale.forLanguageTag(it) }

private fun Context.localizedResources(locale: Locale): Resources {
    val config = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(config).resources
}

private fun Context.currentResourcesLocale(): Locale {
    val locales = resources.configuration.locales
    if (!locales.isEmpty) return locales[0]
    @Suppress("DEPRECATION")
    return resources.configuration.locale
}
