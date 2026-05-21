package app.clothescast.insight

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import app.clothescast.R
import app.clothescast.core.domain.model.AlertClause
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.toUnit
import java.time.LocalTime
import java.util.Locale
import kotlin.math.roundToInt

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
    private val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    /**
     * How the temperature-range sentence is rendered:
     *  - [RangeFormat.NONE] drops it and folds the "Today" / "Tonight" lead into
     *    the next clause ("Today, wear a sweater.") — the user keeps the prose
     *    free of numbers they can already read off the smart-display thermometer.
     *  - [RangeFormat.DEGREES] (default) renders the numeric range
     *    ("Today, it will be 14° to 20°.").
     *  - [RangeFormat.BANDS] renders band words ("Today, it will be cool to mild.").
     * The [BandClause] always rides along in the summary for the smart-display card.
     */
    private val rangeFormat: RangeFormat = RangeFormat.DEGREES,
) {
    private val phraser: ClothesPhraser = ClothesPhraser.forLocale(resources, locale)

    /**
     * Render [summary] as prose. When [isFutureDay] is true the [ForecastPeriod.TODAY]
     * lead-in switches from "Today" to "Tomorrow" — this is the page-2 case where
     * the evening worker has cached a daytime insight for the next calendar date
     * and rendering "Today, …" would be wrong. The TONIGHT lead is left alone:
     * the worker never pairs a primary insight with tomorrow night, so the only
     * tomorrow-shaped payload that reaches the formatter is a daytime one.
     */
    fun format(summary: InsightSummary, isFutureDay: Boolean = false): String {
        // Accessories (umbrella, etc.) are filtered out of the rendered prose
        // entirely — we only surface temperature-driven clothing for now. The
        // user's umbrella rule still triggers and the precip clause still
        // says "Rain at 3pm.", but the umbrella itself doesn't show up in any
        // sentence: "Wear an umbrella" reads wrong (it's carried), "Bring an
        // umbrella" needs a time anchor that breaks for daytime-firing rules,
        // and the rain mention already implies the umbrella for the typical
        // precip-keyed rule. The accessory TODO below is the proper home for
        // a re-introduction.
        val wearItems = summary.clothes?.items.orEmpty().filterNot(::isAccessory)
        // Items already in the wear sentence shouldn't be repeated by a
        // tie-in — a calendar tie-in that picks "sweater" when the wear
        // sentence already said "Wear a sweater" adds nothing. Dedup on the
        // post-filter list (umbrella isn't surfaced anywhere, so it can't
        // dedup against anything either).
        val mentionedKeys = wearItems.map(::normalizeItemKey).toSet()
        // The alert (if any) always leads. The rest splits into daytime content
        // (band / delta / clothes / precip) and tie-in clauses. Tie-ins carry
        // their own temporal lead ("Tonight, bring …"), so when we omit the
        // range the day lead is folded only into the first daytime clause —
        // never a tie-in, which would double the lead ("Today, tonight, …").
        // See [renderLeadOnly].
        val alert = summary.alert?.let(::formatAlert)
        val primaryClauses = buildList {
            when (rangeFormat) {
                RangeFormat.NONE -> Unit
                RangeFormat.DEGREES -> add(formatBand(summary.period, summary.band, isFutureDay))
                RangeFormat.BANDS -> add(formatBandWords(summary.period, summary.band, isFutureDay))
            }
            // When the range is omitted there's no band sentence ahead of the
            // delta, so it leads the temperature content and must introduce
            // itself ("it will be 5° warmer than yesterday.") rather than ride
            // as a bare fragment that the period lead folds into subject-less
            // ("Today, 5° warmer than yesterday.").
            summary.delta?.let { add(formatDelta(it, leadsTemperature = rangeFormat == RangeFormat.NONE)) }
            if (wearItems.isNotEmpty()) formatClothesWear(wearItems)?.let(::add)
            summary.precip?.let { add(formatPrecip(it)) }
        }
        val tieInClauses = buildList {
            summary.calendarTieIn?.let { tieIn ->
                if (isAccessory(tieIn.item)) return@let
                if (normalizeItemKey(tieIn.item) in mentionedKeys) return@let
                formatTieIn(summary.period, tieIn.item)?.let(::add)
            }
            summary.eveningEventTieIn?.let(::formatEveningEventTieIn)?.let(::add)
        }
        val body = if (rangeFormat == RangeFormat.NONE) {
            renderLeadOnly(summary.period, isFutureDay, primaryClauses, tieInClauses)
        } else {
            (primaryClauses + tieInClauses).joinToString(" ")
        }
        return listOfNotNull(alert, body.ifBlank { null }).joinToString(" ")
    }

    /**
     * Build the body when the temperature range is omitted. The period lead
     * ("Today" / "Tonight" / "Tomorrow") is folded into the first daytime
     * clause, lowercasing its first letter so it reads as a continuation —
     * "Today, wear a sweater. Rain at 3pm." Tie-in clauses are appended as-is:
     * they already front their own "Tonight, …" lead, so prepending the day
     * lead would double it. When there's no daytime clause the tie-ins stand
     * on their own ("Tonight, bring a jacket."); when nothing survives at all
     * the lead stands alone as "Today." so the prose is never empty.
     */
    private fun renderLeadOnly(
        period: ForecastPeriod,
        isFutureDay: Boolean,
        primaryClauses: List<String>,
        tieInClauses: List<String>,
    ): String {
        val lead = resources.getString(leadRes(period, isFutureDay))
        if (primaryClauses.isEmpty()) {
            if (tieInClauses.isEmpty()) return resources.getString(R.string.insight_lead_only, lead)
            return tieInClauses.joinToString(" ")
        }
        val first = resources.getString(
            R.string.insight_lead_continues,
            lead,
            decapitalize(primaryClauses.first()),
        )
        return (listOf(first) + primaryClauses.drop(1) + tieInClauses).joinToString(" ")
    }

    /** Lowercase only the first character (locale-aware), leaving the rest untouched. */
    private fun decapitalize(text: String): String {
        if (text.isEmpty()) return text
        return text.substring(0, 1).lowercase(locale) + text.substring(1)
    }

    // TODO(insight-tweak): when the morning precip clause already names a
    //  daytime peak ("Rain at 3pm.") and the evening tie-in also names an
    //  evening rain ("…, rain at 9pm, bring a jacket."), the listener hears
    //  two distinct rain times back-to-back. Consider folding both peaks into
    //  one mention ("Rain at 3pm and 9pm.") or suppressing the second when
    //  the tie-in adds no new clothes vocabulary beyond rain.
    //
    // TODO(accessories-catalog): accessories are silenced rather than
    //  rendered. To bring them back, build a domain-side ClothesRule
    //  item-kind classification (garment / accessory) so each item knows
    //  whether it's worn or carried, decide a per-item rule for whether a
    //  precip mention should suppress it (umbrella: yes; sunscreen: no),
    //  and pick a temporally-correct template ("Bring a hat today." vs
    //  "Tonight, bring a hat." vs no-prefix). Until then we ship
    //  temperature-driven clothing only and accept that user-typed
    //  umbrella rules are silent — the precip clause still warns about
    //  rain, which is the main thing.
    private fun isAccessory(item: String): Boolean =
        item.trim().equals("umbrella", ignoreCase = true)

    private fun normalizeItemKey(item: String): String = item.trim().lowercase(Locale.ROOT)

    private fun formatAlert(alert: AlertClause): String =
        resources.getString(R.string.insight_alert, alert.event)

    private fun formatBand(period: ForecastPeriod, band: BandClause, isFutureDay: Boolean): String {
        val lead = resources.getString(leadRes(period, isFutureDay))
        val low = band.feelsLikeMinC.toUnit(temperatureUnit).roundToInt()
        val high = band.feelsLikeMaxC.toUnit(temperatureUnit).roundToInt()
        return if (low == high) {
            resources.getString(R.string.insight_band_single, lead, low)
        } else {
            resources.getString(R.string.insight_band_range, lead, low, high)
        }
    }

    private fun formatBandWords(period: ForecastPeriod, band: BandClause, isFutureDay: Boolean): String {
        val lead = resources.getString(leadRes(period, isFutureDay))
        val low = resources.getString(bandRes(band.low))
        val high = resources.getString(bandRes(band.high))
        return if (band.low == band.high) {
            resources.getString(R.string.insight_band_words_single, lead, low)
        } else {
            resources.getString(R.string.insight_band_words_range, lead, low, high)
        }
    }

    private fun bandRes(band: TemperatureBand): Int = when (band) {
        TemperatureBand.FREEZING -> R.string.insight_band_freezing
        TemperatureBand.COLD -> R.string.insight_band_cold
        TemperatureBand.COOL -> R.string.insight_band_cool
        TemperatureBand.MILD -> R.string.insight_band_mild
        TemperatureBand.WARM -> R.string.insight_band_warm
        TemperatureBand.HOT -> R.string.insight_band_hot
    }

    private fun formatDelta(delta: DeltaClause, leadsTemperature: Boolean = false): String {
        // The English delta is a bare fragment ("5° warmer than yesterday.")
        // built to trail a band sentence; when it instead leads the temperature
        // content it needs the "it will be …" subject. That self-introducing
        // form has a localized template (insight_delta_*_lead) only for English
        // so far. Other locales phrase their own delta as a full sentence
        // already (German "Heute wird es 5° wärmer."), so they keep their
        // existing string rather than have English spliced into localized
        // prose — same en-only gating as spokenTime(). Widen the gate per
        // locale as the _lead keys get translated.
        val lead = leadsTemperature && locale.language == "en"
        val template = when (delta.direction) {
            DeltaClause.Direction.WARMER ->
                if (lead) R.string.insight_delta_warmer_lead else R.string.insight_delta_warmer
            DeltaClause.Direction.COOLER ->
                if (lead) R.string.insight_delta_cooler_lead else R.string.insight_delta_cooler
        }
        // DeltaClause.degrees is the absolute Celsius delta. Temperature
        // *differences* convert with the ratio only (no +32 offset), so
        // 5°C of warming surfaces as 9°F to a Fahrenheit user — without
        // this the band sentence would say "46° to 57°" while the delta
        // beside it still said "5° warmer", reading as mixed units.
        val degrees = when (temperatureUnit) {
            TemperatureUnit.CELSIUS -> delta.degrees
            TemperatureUnit.FAHRENHEIT -> (delta.degrees * 9.0 / 5.0).roundToInt()
        }
        return resources.getString(template, degrees)
    }

    private fun formatClothesWear(items: List<String>): String? {
        val phrase = phraser.joinItems(items)
        if (phrase.isBlank()) return null
        return resources.getString(R.string.insight_clothes_wear, phrase)
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

    /**
     * Single-item tie-in / clothes-carry sentence. Period-aware: on TODAY the
     * sentence introduces the evening with "Tonight, bring …"; on TONIGHT the
     * band lead already established the night context, so we use the short
     * "Bring …" template to avoid a redundant second "Tonight" intro.
     */
    private fun formatTieIn(period: ForecastPeriod, item: String): String? {
        // Short-circuit before article picking: prefixArticle("") emits "a "
        // via the "a %1$s" template, which is non-blank and would slip past a
        // post-rendering isBlank() check.
        if (item.isBlank()) return null
        val renderedItem = phraser.withArticle(item)
        if (renderedItem.isBlank()) return null
        val template = if (period == ForecastPeriod.TONIGHT) {
            R.string.insight_tie_in_at_night
        } else {
            R.string.insight_tie_in
        }
        return resources.getString(template, renderedItem)
    }

    private fun formatEveningEventTieIn(tieIn: EveningEventTieInClause): String? {
        val rainTime = tieIn.rainTime
        // Accessories (umbrella) are silenced here for the same reason they're
        // silenced in the main wear-list: until the accessory catalog lands,
        // we only name temperature-driven clothing. The rain mention, if any,
        // still surfaces through the bare-rain path below.
        val items = tieIn.items.filterNot(::isAccessory)
        val renderedItems = if (items.isEmpty()) "" else phraser.joinItems(items)
        if (renderedItems.isBlank()) {
            // No items left to name. If there's a rain time, the clause
            // collapses to the bare-rain prose (the only signal left);
            // otherwise the whole tie-in is empty and we drop it.
            if (rainTime == null) return null
            val template = when (tieIn.likelihood) {
                PrecipLikelihood.LIKELY -> R.string.insight_evening_rain
                PrecipLikelihood.POSSIBLE -> R.string.insight_evening_rain_chance
            }
            return resources.getString(template, spokenTime(rainTime))
        }
        // No rain — bare item-led sentence. Always uses the TODAY-context
        // "Tonight, bring …" template because the evening tie-in only fires
        // on TODAY (the TONIGHT pass uses calendarTieIn for event-anchored
        // tie-ins).
        rainTime ?: return resources.getString(R.string.insight_tie_in, renderedItems)
        // Hedge the item-led wording when only one model spotted the rain,
        // matching the bare-rain path's chance-of-rain template.
        val template = when (tieIn.likelihood) {
            PrecipLikelihood.LIKELY -> R.string.insight_tie_in_with_rain
            PrecipLikelihood.POSSIBLE -> R.string.insight_tie_in_with_rain_chance
        }
        return resources.getString(template, renderedItems, spokenTime(rainTime))
    }

    private fun leadRes(period: ForecastPeriod, isFutureDay: Boolean): Int = when (period) {
        ForecastPeriod.TODAY -> if (isFutureDay) R.string.insight_lead_tomorrow else R.string.insight_lead_today
        ForecastPeriod.TONIGHT -> R.string.insight_lead_tonight
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
     * Public entry point for callers (e.g. the Nest-Hub outfit card) that
     * want a single time rendered the same way the prose renders it —
     * "3pm" in English, "15 Uhr" in German, etc. — without having to build
     * a full [InsightSummary] first.
     */
    fun formatTime(time: LocalTime): String = spokenTime(time)

    /**
     * "Peak 60% at 3pm" — the localized peak-rain row shown on the Nest-Hub
     * outfit card and the Today screen's precipitation card. Uses the same
     * resources / locale as the prose so the card matches what the prose
     * would say for the same hour.
     */
    fun formatPeakRain(percent: Int, time: LocalTime): String =
        resources.getString(R.string.today_precipitation_peak, percent, spokenTime(time))

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
        fun forContext(
            context: Context,
            locale: Locale = context.currentResourcesLocale(),
            temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
            rangeFormat: RangeFormat = RangeFormat.DEGREES,
        ): InsightFormatter =
            InsightFormatter(context.localizedResources(locale), locale, temperatureUnit, rangeFormat)

        /** Convenience for the common path: render in the user's [Region]-derived locale. */
        fun forRegion(
            context: Context,
            region: Region,
            temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
            rangeFormat: RangeFormat = RangeFormat.DEGREES,
        ): InsightFormatter {
            val locale = region.toJavaLocale() ?: context.currentResourcesLocale()
            return forContext(context, locale, temperatureUnit, rangeFormat)
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
