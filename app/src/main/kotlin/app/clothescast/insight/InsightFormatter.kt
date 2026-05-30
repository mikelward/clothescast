package app.clothescast.insight

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import app.clothescast.R
import app.clothescast.core.domain.model.AlertClause
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.PreambleVisibility
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.RainAccessory
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.WeekAheadClause
import app.clothescast.core.domain.model.WeekAheadInsight
import app.clothescast.core.domain.model.toUnit
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The surface a rendered insight is bound for. Drives how the period / wear
 * preambles are treated under [PreambleVisibility.SPEECH_ONLY]:
 *  - [SPEECH] — the spoken (TTS) briefing; keeps the preambles.
 *  - [VISUAL] — every visual/text surface (Today card, notification, cast card,
 *    MQTT, bug report); drops the preambles.
 *  - [SETTINGS_PREVIEW] — the Format-settings preview; shows the dropped
 *    preambles in parentheses ("(Today, it will be) 14° to 20°.") so the user
 *    sees they're spoken but hidden on screen.
 *
 * [ALWAYS] and [NEVER] ignore the surface (present everywhere / nowhere).
 */
enum class InsightSurface { SPEECH, VISUAL, SETTINGS_PREVIEW }

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
    /**
     * How the clothes clause is rendered. [ClothesFormat.ITEMS] (default) names
     * each triggered garment as before; [ClothesFormat.LAYER_COUNT] collapses
     * the firing tops to a perceived-warmth count via [Garment.layerCount] and
     * drops bottoms from the wear clause entirely — "Wear 2 layers." — so the
     * mode reads as a single warmth signal. The bottom suppression happens
     * in [format] by reading only [ClothesClause.tops] in this mode; the
     * domain exposes pre-classified tops / bottoms / accessories views so
     * the formatter doesn't have to re-run [Garment.fromKey] on every item.
     */
    private val clothesFormat: ClothesFormat = ClothesFormat.ITEMS,
    /**
     * Whether bottoms (shorts / pants / skirts / jeans) appear in the wear
     * clause. See [BottomsFormat]. Default [BottomsFormat.IF_GARMENTS]
     * preserves the historical behaviour — bottoms surface in items mode and
     * are suppressed in layer-count mode. [BottomsFormat.ALWAYS] extends the
     * layer-count clause to append bottoms ("Wear 2 layers and shorts.");
     * [BottomsFormat.NEVER] drops them from items mode too.
     */
    private val bottomsFormat: BottomsFormat = BottomsFormat.IF_GARMENTS,
    /**
     * Optional wet-weather accessory named alongside the rain mention. With
     * [RainAccessory.NONE] (default) the precip clause stays a bare "Rain at
     * 3pm." and the evening tie-in keeps its existing prose; with
     * [RainAccessory.UMBRELLA] the precip clause becomes "Rain at 3pm, bring
     * an umbrella." and the evening tie-in folds the same accessory into its
     * items list ("Tonight, rain at 9pm, bring an umbrella."). Triggered on
     * the same threshold the rain mention already uses (POSSIBLE ≥ 30%), so
     * the accessory and the rain mention always travel together.
     */
    private val rainAccessory: RainAccessory = RainAccessory.NONE,
    /**
     * Where the period preamble ("Today, it will be …") is allowed to survive.
     * See [PreambleVisibility]. Combined with the per-call [InsightSurface] to
     * decide whether the lead is included, omitted, or shown parenthesised.
     * Applies in every locale — all ship `insight_*_no_lead` templates.
     *
     * Defaults to [PreambleVisibility.ALWAYS] — the *renderer's* default is to
     * show the full prose. The app's product default (drop the lead on visual
     * surfaces) lives on `UserPreferences.periodPreamble` and is threaded in
     * explicitly by every production call site; the constructor default only
     * governs previews / tests that construct a formatter directly.
     */
    private val periodPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
    /**
     * Where the wear preamble ("Wear " + leading article) is allowed to
     * survive. See [PreambleVisibility]. Dropping it turns "Wear a sweater."
     * into "Sweater."; the preview shows "(Wear a) sweater.". Applies in
     * every locale.
     * Defaults to [PreambleVisibility.ALWAYS] (full prose), as above.
     */
    private val wearPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
) {
    private val phraser: ClothesPhraser = ClothesPhraser.forLocale(resources, locale)

    /**
     * Render [summary] as prose. When [isFutureDay] is true the [ForecastPeriod.TODAY]
     * lead-in switches from "Today" to "Tomorrow" — this is the page-2 case where
     * the evening worker has cached a daytime insight for the next calendar date
     * and rendering "Today, …" would be wrong. The TONIGHT lead is left alone:
     * the worker never pairs a primary insight with tomorrow night, so the only
     * tomorrow-shaped payload that reaches the formatter is a daytime one.
     *
     * When no clause produces any prose (only reachable with [RangeFormat.NONE],
     * where the temperature range is dropped and nothing else fired),
     * [placeholderWhenEmpty] decides what comes back. Display surfaces (Today
     * screen, smart-display / cast card, notification) want a visible
     * "Today, it will be the same as yesterday." line so the card never renders
     * blank; the spoken TTS path passes `false` to stay silent rather than read
     * out a content-free filler line.
     *
     * The period preamble ("Today, it will be …" / "Tonight, it will be …") that
     * fronts the temperature sentence, and the wear preamble ("Wear " + leading
     * article) that fronts the clothes clause, are each gated by the user's
     * [periodPreamble] / [wearPreamble] choice combined with the bound
     * [surface]. Under [PreambleVisibility.SPEECH_ONLY] the spoken ([
     * InsightSurface.SPEECH]) path keeps a preamble, every visual surface drops
     * it, and the Format-settings preview shows it parenthesised ("(Today, it
     * will be) 14° to 20°."). Dropping / parenthesising applies in every locale
     * — each ships its own `insight_*_no_lead` templates.
     */
    fun format(
        summary: InsightSummary,
        isFutureDay: Boolean = false,
        placeholderWhenEmpty: Boolean = true,
        surface: InsightSurface = InsightSurface.VISUAL,
    ): String {
        val wearMode = wearMode(surface)
        return when (leadMode(surface)) {
            LeadMode.INCLUDE ->
                renderInsight(summary, isFutureDay, placeholderWhenEmpty, omitLead = false, wearMode)
            LeadMode.OMIT ->
                renderInsight(summary, isFutureDay, placeholderWhenEmpty, omitLead = true, wearMode)
            // Preview hint: render the body with and without the period lead and
            // wrap the recovered difference in parens — "(Today, it will be) …".
            LeadMode.PAREN -> parenthesizeLead(
                renderInsight(summary, isFutureDay, placeholderWhenEmpty, omitLead = false, wearMode),
                renderInsight(summary, isFutureDay, placeholderWhenEmpty, omitLead = true, wearMode),
            )
        }
    }

    /**
     * Render the insight body for a single lead treatment ([omitLead]) and wear
     * treatment ([wearMode]). [format] calls this once for [LeadMode.INCLUDE] /
     * [LeadMode.OMIT] and twice for [LeadMode.PAREN] (the with- and without-lead
     * forms it then splices). Dropping the lead swaps in each locale's
     * `insight_*_no_lead` templates and re-capitalises the first clause.
     */
    private fun renderInsight(
        summary: InsightSummary,
        isFutureDay: Boolean,
        placeholderWhenEmpty: Boolean,
        omitLead: Boolean,
        wearMode: WearMode,
    ): String {
        // Accessories (umbrella, etc.) are filtered out of the rendered prose
        // entirely — we only surface temperature-driven clothing for now. The
        // user's umbrella rule still triggers and the precip clause still
        // says "Rain at 3pm.", but the umbrella itself doesn't show up in any
        // sentence: "Wear an umbrella" reads wrong (it's carried), "Bring an
        // umbrella" needs a time anchor that breaks for daytime-firing rules,
        // and the rain mention already implies the umbrella for the typical
        // precip-keyed rule. The accessory TODO below is the proper home for
        // a re-introduction.
        //
        // Layer-count mode is a single warmth signal — under
        // [BottomsFormat.IF_GARMENTS] (default) and [BottomsFormat.NEVER] we
        // read [ClothesClause.tops] so bottoms never enter the wear list, and
        // an only-bottom firing emits no wear clause. Under
        // [BottomsFormat.ALWAYS] bottoms ride along — [layerCountPhrase]
        // appends them after the count ("Wear 2 layers and shorts.").
        //
        // In items mode we render all non-accessory items in their original
        // input order so the article-picker ([ClothesPhraser]) sees the same
        // plural-first / singular-first sequence it would have if it filtered
        // the raw [items] itself. [BottomsFormat.NEVER] strips bottoms before
        // the join; [BottomsFormat.ALWAYS] and [BottomsFormat.IF_GARMENTS]
        // both keep them (items mode IS the garments mode).
        val wearItems = when (clothesFormat) {
            ClothesFormat.LAYER_COUNT -> when (bottomsFormat) {
                BottomsFormat.ALWAYS -> summary.clothes?.items.orEmpty().filterNot(::isAccessory)
                BottomsFormat.IF_GARMENTS, BottomsFormat.NEVER -> summary.clothes?.tops.orEmpty()
            }
            ClothesFormat.ITEMS -> {
                val all = summary.clothes?.items.orEmpty().filterNot(::isAccessory)
                when (bottomsFormat) {
                    BottomsFormat.ALWAYS, BottomsFormat.IF_GARMENTS -> all
                    BottomsFormat.NEVER -> all.filter { Garment.fromKey(it)?.slot != Garment.Slot.BOTTOM }
                }
            }
        }
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
        // A BANDS-style delta is the user's Band change-format: today's high band
        // changed vs yesterday. It *replaces* the temperature sentence with an
        // absolute band callout ("Today, it will be hot.") in place of the
        // degree / range sentence — it doesn't trail as its own clause and adds
        // no second "it will be". When today's band is unchanged the renderer
        // emits no delta, so the configured RangeFormat sentence stands instead.
        // A DEGREES-style delta keeps its historical trailing-fragment wording.
        val bandCallout = summary.delta?.takeIf { it.style == DeltaClause.Style.BANDS }?.band
        val numericDelta = summary.delta?.takeIf { it.style == DeltaClause.Style.DEGREES }
        val primaryClauses = buildList {
            when {
                bandCallout != null ->
                    add(formatBandAbsolute(summary.period, bandCallout, isFutureDay, omitLead))
                else -> when (rangeFormat) {
                    RangeFormat.NONE -> Unit
                    RangeFormat.DEGREES -> add(formatBand(summary.period, summary.band, isFutureDay, omitLead))
                    RangeFormat.BANDS -> add(formatBandWords(summary.period, summary.band, isFutureDay, omitLead))
                }
            }
            // When the range is omitted there's no band sentence ahead of the
            // delta, so it leads the temperature content and must introduce
            // itself ("it will be 5° warmer than yesterday.") rather than ride
            // as a bare fragment that the period lead folds into subject-less
            // ("Today, 5° warmer than yesterday.").
            numericDelta?.let { add(formatDelta(it, leadsTemperature = rangeFormat == RangeFormat.NONE)) }
            if (wearItems.isNotEmpty()) formatClothesWear(wearItems, wearMode)?.let(::add)
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
        // Whether a leading temperature sentence exists to carry the period
        // lead. The band callout is one (it renders "Today, it will be hot."),
        // so a NONE range with a band callout still uses the normal join — only
        // a NONE range with no band callout falls back to renderLeadOnly, which
        // folds the period lead into whatever clause comes first.
        val hasTemperatureSentence = bandCallout != null || rangeFormat != RangeFormat.NONE
        val body = if (!hasTemperatureSentence) {
            // In NONE mode the band is dropped, so when a numeric delta is
            // present it's the first primary clause. It renders as the
            // self-introducing "it will be …" fragment (insight_delta_*_lead)
            // that the period lead folds into ("Today, it will be 5° warmer …").
            renderLeadOnly(summary.period, isFutureDay, primaryClauses, tieInClauses, omitLead)
        } else {
            (primaryClauses + tieInClauses).joinToString(" ")
        }
        val rendered = listOfNotNull(alert, body.ifBlank { null }).joinToString(" ")
        if (rendered.isNotBlank()) return rendered
        // Nothing fired. Display surfaces show a "Today, it will be the same as
        // yesterday." line so the card isn't blank; TTS opts out via
        // placeholderWhenEmpty=false to stay silent.
        if (!placeholderWhenEmpty) return ""
        return resources.getString(unchangedRes(summary.period, isFutureDay, omitLead))
    }

    private fun unchangedRes(period: ForecastPeriod, isFutureDay: Boolean, omitLead: Boolean): Int = when (period) {
        ForecastPeriod.TODAY -> when {
            isFutureDay && omitLead -> R.string.insight_unchanged_tomorrow_no_lead
            isFutureDay -> R.string.insight_unchanged_tomorrow
            omitLead -> R.string.insight_unchanged_today_no_lead
            else -> R.string.insight_unchanged_today
        }
        ForecastPeriod.TONIGHT ->
            if (omitLead) R.string.insight_unchanged_tonight_no_lead else R.string.insight_unchanged_tonight
    }

    /**
     * Build the body when the temperature range is omitted. The period lead
     * ("Today" / "Tonight" / "Tomorrow") is folded into the first daytime
     * clause, lowercasing its first letter so it reads as a continuation —
     * "Today, wear a sweater. Rain at 3pm." Tie-in clauses are appended as-is:
     * they already front their own "Tonight, …" lead, so prepending the day
     * lead would double it. When there's no daytime clause the tie-ins stand
     * on their own ("Tonight, bring a jacket."); when nothing survives at all
     * the body is empty — [format] turns that into "Today, it will be the same
     * as yesterday." for display or an empty string for TTS, so we never emit a
     * bare "Today." that tells the user nothing.
     */
    private fun renderLeadOnly(
        period: ForecastPeriod,
        isFutureDay: Boolean,
        primaryClauses: List<String>,
        tieInClauses: List<String>,
        omitLead: Boolean,
    ): String {
        if (primaryClauses.isEmpty()) {
            return tieInClauses.joinToString(" ")
        }
        // No lead-in wanted (Today card): drop the period word and just
        // capitalise the first clause so it opens like a sentence — the
        // self-leading delta fragment "it will be 5° warmer …" becomes "It will
        // be 5° warmer …", and an already-capital clause ("Wear a sweater.")
        // is unchanged. Tie-ins keep their own "Tonight, …" lead untouched.
        if (omitLead) {
            val first = capitalize(primaryClauses.first())
            return (listOf(first) + primaryClauses.drop(1) + tieInClauses).joinToString(" ")
        }
        val lead = resources.getString(leadRes(period, isFutureDay))
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

    /** Uppercase only the first character (locale-aware), leaving the rest untouched. */
    private fun capitalize(text: String): String {
        if (text.isEmpty()) return text
        return text.substring(0, 1).uppercase(locale) + text.substring(1)
    }

    /** How the period lead is rendered for a clause (see [leadMode]). */
    private enum class LeadMode { INCLUDE, OMIT, PAREN }

    /** How the wear preamble is rendered (see [wearMode]). */
    private enum class WearMode { FULL, BARE, PAREN }

    /**
     * Resolve the period-preamble treatment for [surface] under the user's
     * [periodPreamble]. Every locale ships no-lead templates (the
     * `insight_*_no_lead` strings), so dropping / parenthesising applies
     * across languages.
     */
    private fun leadMode(surface: InsightSurface): LeadMode {
        return when (periodPreamble) {
            PreambleVisibility.ALWAYS -> LeadMode.INCLUDE
            PreambleVisibility.NEVER -> LeadMode.OMIT
            PreambleVisibility.SPEECH_ONLY -> when (surface) {
                InsightSurface.SPEECH -> LeadMode.INCLUDE
                InsightSurface.VISUAL -> LeadMode.OMIT
                InsightSurface.SETTINGS_PREVIEW -> LeadMode.PAREN
            }
        }
    }

    /**
     * Resolve the wear-preamble treatment for [surface] under the user's
     * [wearPreamble]. Dropping "Wear" + the leading article reduces the clause
     * to the (capitalised) garment body via `insight_clothes_bare`, which every
     * locale now ships, so it applies across languages.
     */
    private fun wearMode(surface: InsightSurface): WearMode {
        return when (wearPreamble) {
            PreambleVisibility.ALWAYS -> WearMode.FULL
            PreambleVisibility.NEVER -> WearMode.BARE
            PreambleVisibility.SPEECH_ONLY -> when (surface) {
                InsightSurface.SPEECH -> WearMode.FULL
                InsightSurface.VISUAL -> WearMode.BARE
                InsightSurface.SETTINGS_PREVIEW -> WearMode.PAREN
            }
        }
    }

    /**
     * Wrap the period preamble in parentheses for the Format-settings preview.
     * Given the lead-included ([withLead]) and lead-omitted ([noLead]) renders,
     * the dropped preamble is the prefix [withLead] carries over [noLead]:
     * recover it and show it parenthesised — "(Today, it will be) 14° to 20°.".
     * The no-lead form is lowercased first so it reads as a continuation after
     * the closing paren ("…) cool to mild." not "…) Cool to mild."). Falls back
     * to [withLead] unchanged if the suffix doesn't line up (e.g. an alert
     * prefix shifts the strings) — a harmless "lead shown without parens".
     */
    private fun parenthesizeLead(withLead: String, noLead: String): String {
        if (noLead.isBlank()) return withLead
        val tail = decapitalize(noLead)
        if (!withLead.endsWith(tail)) return withLead
        val preamble = withLead.removeSuffix(tail).trimEnd()
        if (preamble.isEmpty()) return withLead
        return "($preamble) $tail"
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
    private fun isAccessory(item: String): Boolean = Garment.isAccessoryKey(item)

    private fun normalizeItemKey(item: String): String = item.trim().lowercase(Locale.ROOT)

    private fun formatAlert(alert: AlertClause): String =
        resources.getString(R.string.insight_alert, alert.event)

    private fun formatBand(period: ForecastPeriod, band: BandClause, isFutureDay: Boolean, omitLead: Boolean): String {
        val low = band.feelsLikeMinC.toUnit(temperatureUnit).roundToInt()
        val high = band.feelsLikeMaxC.toUnit(temperatureUnit).roundToInt()
        if (omitLead) {
            return if (low == high) {
                resources.getString(R.string.insight_band_single_no_lead, low)
            } else {
                resources.getString(R.string.insight_band_range_no_lead, low, high)
            }
        }
        val lead = resources.getString(leadRes(period, isFutureDay))
        return if (low == high) {
            resources.getString(R.string.insight_band_single, lead, low)
        } else {
            resources.getString(R.string.insight_band_range, lead, low, high)
        }
    }

    private fun formatBandWords(period: ForecastPeriod, band: BandClause, isFutureDay: Boolean, omitLead: Boolean): String {
        val low = resources.getString(bandRes(band.low))
        val high = resources.getString(bandRes(band.high))
        if (omitLead) {
            // Band words are lowercase ("cool"); capitalise the leading word so
            // the no-lead sentence still opens like a sentence ("Cool to mild.").
            return if (band.low == band.high) {
                resources.getString(R.string.insight_band_words_single_no_lead, capitalize(low))
            } else {
                resources.getString(R.string.insight_band_words_range_no_lead, capitalize(low), high)
            }
        }
        val lead = resources.getString(leadRes(period, isFutureDay))
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
        // The delta is normally a bare fragment ("5° warmer than yesterday.")
        // built to trail a band sentence. When it instead leads the temperature
        // content (range omitted) it needs the self-introducing "it will be …"
        // form, which every locale ships as insight_delta_*_lead; the period
        // lead is then folded in front of it by [renderLeadOnly].
        val lead = leadsTemperature
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

    /**
     * Render the Band change-format callout as the temperature sentence: names
     * today's [band] absolutely ("Today, it will be hot.") in place of the
     * degree / range sentence. Reuses the single-band templates the band-words
     * range sentence uses, so the lead handling (the folded "Today, it will
     * be …" vs the no-lead "Hot.") matches the rest of the temperature prose
     * and is localized for free.
     *
     * Only reached when today's high band changed vs yesterday (the renderer
     * omits the [DeltaClause] otherwise); when unchanged the configured
     * RangeFormat sentence stands instead.
     */
    private fun formatBandAbsolute(
        period: ForecastPeriod,
        band: TemperatureBand,
        isFutureDay: Boolean,
        omitLead: Boolean,
    ): String {
        val word = resources.getString(bandRes(band))
        if (omitLead) {
            // Band words are lowercase ("hot"); capitalise so the no-lead
            // sentence opens like a sentence ("Hot.").
            return resources.getString(R.string.insight_band_words_single_no_lead, capitalize(word))
        }
        val lead = resources.getString(leadRes(period, isFutureDay))
        return resources.getString(R.string.insight_band_words_single, lead, word)
    }

    private fun formatClothesWear(items: List<String>, wearMode: WearMode): String? {
        return when (wearMode) {
            WearMode.FULL -> {
                val phrase = wearPhrase(items, leadingArticle = true) ?: return null
                resources.getString(R.string.insight_clothes_wear, phrase)
            }
            // Drop "Wear" + the leading article: "Wear a sweater." → "Sweater."
            // The body opens the sentence, so capitalise its first letter.
            WearMode.BARE -> {
                val phrase = wearPhrase(items, leadingArticle = false) ?: return null
                resources.getString(R.string.insight_clothes_bare, capitalize(phrase))
            }
            // Preview hint: show what's dropped on screen in parens —
            // "(Wear a) sweater." / "(Wear) shorts.". The leading article is
            // exactly the prefix the article-on render adds over the article-off
            // one, recovered by splicing (same idea as [parenthesizeLead]).
            WearMode.PAREN -> {
                val withArticle = wearPhrase(items, leadingArticle = true) ?: return null
                val without = wearPhrase(items, leadingArticle = false) ?: return null
                val full = resources.getString(R.string.insight_clothes_wear, withArticle)
                val tail = resources.getString(R.string.insight_clothes_bare, without)
                if (!full.endsWith(tail)) return full
                "(${full.removeSuffix(tail).trimEnd()}) $tail"
            }
        }
    }

    /**
     * The body of the wear sentence (no "Wear " wrapper, no trailing period):
     * the joined garment list in items mode ("a sweater and jacket" /
     * "sweater and jacket" depending on [leadingArticle]) or the perceived-warmth
     * phrase in layer-count mode ("2 layers", which carries no article).
     * Returns null when nothing renders (e.g. a bottoms-only layer-count firing).
     */
    private fun wearPhrase(items: List<String>, leadingArticle: Boolean): String? {
        val phrase = when (clothesFormat) {
            ClothesFormat.ITEMS -> phraser.joinItems(items, leadingArticle)
            ClothesFormat.LAYER_COUNT -> layerCountPhrase(items) ?: phraser.joinItems(items, leadingArticle)
        }
        return phrase.ifBlank { null }
    }

    /**
     * Render the body of the wear sentence in layer-count mode. Tops collapse
     * to the max [Garment.layerCount] across firing rules (the heaviest tier
     * defines the warmth, layering a sweater under a jacket lands at 3 not 5).
     * Under [BottomsFormat.IF_GARMENTS] / [BottomsFormat.NEVER] bottoms can't
     * reach here ([format] drops them) so the phrase is purely about top
     * warmth ("Wear 2 layers."). Under [BottomsFormat.ALWAYS] bottoms ride
     * along in [items] and are appended after the count via the phraser
     * ("Wear 2 layers and shorts."). Returns `null` when no top is
     * classifiable, letting the caller fall back to the items rendering
     * (free-form user-typed garments don't map to a layer count).
     */
    private fun layerCountPhrase(items: List<String>): String? {
        var topCount = 0
        val bottoms = mutableListOf<String>()
        for (item in items) {
            val garment = Garment.fromKey(item) ?: return null
            if (garment.slot == Garment.Slot.TOP && garment.layerCount > topCount) {
                topCount = garment.layerCount
            } else if (garment.slot == Garment.Slot.BOTTOM) {
                bottoms.add(item)
            }
        }
        if (topCount == 0) return null
        val countPhrase = resources.getQuantityString(R.plurals.insight_clothes_layer_count, topCount, topCount)
        if (bottoms.isEmpty()) return countPhrase
        // Route the bottoms through the phraser so they get localized
        // garment names and the plural-vs-singular article rules, but join
        // the count + bottoms with the localized "and" template directly —
        // passing "1 layer" into joinItems as a list item makes the
        // English phraser treat it as an article-taking noun and emit
        // "a 1 layer and shorts", which is the broken output we're avoiding.
        val bottomsPhrase = phraser.joinItems(bottoms)
        if (bottomsPhrase.isBlank()) return countPhrase
        return resources.getString(R.string.insight_clothes_join_two, countPhrase, bottomsPhrase)
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
        // The accessory is rain-keyed by name ("Rain accessory: Umbrella");
        // gate it to RAIN / DRIZZLE so "Snow overnight, bring an umbrella."
        // doesn't slip through. THUNDERSTORM is intentionally excluded too —
        // an umbrella under lightning is bad practice, and the user will
        // hear "Thunderstorm at 3pm." either way.
        val accessoryPhrase = rainAccessoryItemKey()
            ?.takeIf { precip.condition.warrantsRainAccessory() }
            ?.let(phraser::withArticle)
        return when (precip.likelihood) {
            PrecipLikelihood.LIKELY -> if (accessoryPhrase != null) {
                resources.getString(R.string.insight_precip_with_accessory, rawType, timePhrase, accessoryPhrase)
            } else {
                resources.getString(R.string.insight_precip, rawType, timePhrase)
            }
            // "Chance of Rain at 3pm" reads odd with the condition title-cased
            // mid-sentence; downcase the noun so the lead "Chance of" sits
            // naturally. Other locales' condition resources may already be
            // lowercase or have grammatical case to handle — this lowering is
            // safe for English ("Rain" → "rain") and a no-op for languages
            // where the condition resource is already in lower form.
            PrecipLikelihood.POSSIBLE -> if (accessoryPhrase != null) {
                resources.getString(
                    R.string.insight_precip_chance_with_accessory,
                    rawType.lowercase(locale),
                    timePhrase,
                    accessoryPhrase,
                )
            } else {
                resources.getString(R.string.insight_precip_chance, rawType.lowercase(locale), timePhrase)
            }
        }
    }

    /**
     * The item key the user's [RainAccessory] choice maps to ("umbrella" for
     * [RainAccessory.UMBRELLA]), or null when the user picked
     * [RainAccessory.NONE]. The key flows through the same [ClothesPhraser]
     * the wear-list uses, so the English path renders "an umbrella" and the
     * German EN→DE map renders "Regenschirm" without a phraser change.
     */
    private fun rainAccessoryItemKey(): String? = when (rainAccessory) {
        RainAccessory.NONE -> null
        RainAccessory.UMBRELLA -> "umbrella"
    }

    /**
     * Conditions where a "bring an umbrella" mention reads correctly. SNOW
     * and FOG aren't actually wet-in-the-umbrella sense; THUNDERSTORM has
     * rain but recommending an umbrella under lightning is bad practice.
     * Anything else (CLEAR / cloud / UNKNOWN) doesn't reach the precip
     * clause at all (RenderInsightSummary already filters via
     * isPrecipitation()).
     */
    private fun WeatherCondition.warrantsRainAccessory(): Boolean = when (this) {
        WeatherCondition.RAIN,
        WeatherCondition.DRIZZLE -> true
        WeatherCondition.SNOW,
        WeatherCondition.THUNDERSTORM,
        WeatherCondition.FOG,
        WeatherCondition.CLEAR,
        WeatherCondition.PARTLY_CLOUDY,
        WeatherCondition.CLOUDY,
        WeatherCondition.UNKNOWN -> false
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
        // Accessories (umbrella) are silenced from the incoming items list for
        // the same reason they're silenced in the main wear-list: until the
        // accessory catalog lands we only name temperature-driven clothing
        // there. If the user has opted into [RainAccessory] and the evening's
        // peak condition is rain-like (RAIN / DRIZZLE), we re-inject the
        // chosen accessory below so the existing insight_tie_in_with_rain
        // template carries it ("…, bring a jacket and an umbrella.") and the
        // bare-rain path is promoted to the item-led template. SNOW /
        // THUNDERSTORM peaks (or a null condition on a pre-field cached
        // payload) skip the injection — same gating as formatPrecip — so
        // "Tonight, rain at 9pm, bring an umbrella." doesn't slip out when
        // the underlying peak is actually snow.
        val filteredItems = tieIn.items.filterNot(::isAccessory)
        val accessoryKey = rainAccessoryItemKey()
            ?.takeIf { rainTime != null && tieIn.precipCondition?.warrantsRainAccessory() == true }
        val items = if (accessoryKey != null) filteredItems + accessoryKey else filteredItems
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
     * Render a [WeekAheadInsight] as the single-line headline shown above
     * the 7-day chart deck. Each clause that fires ([WeekAheadInsight.rain],
     * [WeekAheadInsight.firstWarmer], [WeekAheadInsight.firstCooler],
     * [WeekAheadInsight.persistence]) contributes one phrase; the phrases
     * join chronologically by their date (persistence sorts first since it
     * covers the whole window) so a noisy week reads "Warmer tomorrow,
     * cooler Sunday." or "Hot all week, rain Friday." instead of collapsing
     * to one.
     *
     * The day reference inside each phrase resolves to "tomorrow" when the
     * clause carries `isTomorrow = true`; otherwise it renders the long
     * day-of-week name in the user's locale ("Thursday", "Donnerstag" via
     * the resource template). Persistence phrases carry no date.
     */
    fun formatWeekAhead(insight: WeekAheadInsight): String {
        // (sortKey, phrase). Persistence has no date and sorts first; the
        // remaining clauses sort by date. Phrases are stripped of their
        // trailing "." so we can join with the locale separator and append a
        // single final period — this relies on every today_week_ahead_*
        // template ending in a literal ".". If a locale needs different
        // terminal punctuation, split into terminal / non-terminal templates.
        val clauses = mutableListOf<Pair<LocalDate?, String>>()
        insight.persistence?.let { clauses += null to renderClause(it) }
        insight.firstWarmer?.let { clauses += clauseDate(it) to renderClause(it) }
        insight.firstCooler?.let { clauses += clauseDate(it) to renderClause(it) }
        insight.rain?.let { clauses += clauseDate(it) to renderClause(it) }
        val sorted = clauses
            .sortedWith(compareBy(nullsFirst()) { it.first })
            .map { it.second.trimEnd('.') }
        // Lowercase the first letter of each non-leading clause so the joined
        // sentence reads naturally ("…, cooler Sunday." rather than "…,
        // Cooler Sunday."). Templates are authored with a sentence-leading
        // capital so the solo case still reads correctly. Locale-naïve for
        // non-English translations that capitalize nouns mid-sentence
        // (e.g. German "Regen") — translators can split into leading /
        // mid-sentence templates if needed.
        val joined = sorted.mapIndexed { index, phrase ->
            if (index == 0) phrase else phrase.replaceFirstChar { it.lowercase(locale) }
        }
        val separator = resources.getString(R.string.today_week_ahead_separator)
        return joined.joinToString(separator = separator, postfix = ".")
    }

    private fun renderClause(clause: WeekAheadClause): String = when (clause) {
        is WeekAheadClause.Rain -> resources.getString(
            weekAheadPrecipRes(clause.condition),
            dayReference(clause.date, clause.isTomorrow),
        )
        is WeekAheadClause.Warmer -> resources.getString(
            R.string.today_week_ahead_warmer,
            dayReference(clause.date, clause.isTomorrow),
        )
        is WeekAheadClause.Cooler -> resources.getString(
            R.string.today_week_ahead_cooler,
            dayReference(clause.date, clause.isTomorrow),
        )
        WeekAheadClause.StaysHot -> resources.getString(R.string.today_week_ahead_stays_hot)
        WeekAheadClause.StaysCold -> resources.getString(R.string.today_week_ahead_stays_cold)
    }

    private fun clauseDate(clause: WeekAheadClause): LocalDate? = when (clause) {
        is WeekAheadClause.Rain -> clause.date
        is WeekAheadClause.Cooler -> clause.date
        is WeekAheadClause.Warmer -> clause.date
        WeekAheadClause.StaysHot, WeekAheadClause.StaysCold -> null
    }

    private fun weekAheadPrecipRes(condition: WeatherCondition): Int = when (condition) {
        WeatherCondition.SNOW -> R.string.today_week_ahead_snow_likely
        // RAIN / DRIZZLE / THUNDERSTORM / anything else precipitating reads
        // naturally as "rain" at the weekly headline coarseness. The today /
        // tonight insight covers the fine-grained condition word.
        else -> R.string.today_week_ahead_rain_likely
    }

    private fun dayReference(date: LocalDate, isTomorrow: Boolean): String {
        if (isTomorrow) return resources.getString(R.string.today_week_ahead_day_tomorrow)
        val dayName = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        return resources.getString(R.string.today_week_ahead_day_on_named, dayName)
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
     * As [formatPeakRain] but without the "Peak" lead-in — for the compact
     * conditions widget where the surrounding context already reads as "the
     * chance of rain", so "60% at 3pm" carries the meaning in fewer pixels.
     * Reuses the generic [R.string.today_chart_readout] "value at time" pattern
     * (and the same "${pct}%" value formatting the precipitation chart readout
     * uses) rather than a bespoke string.
     */
    fun formatPeakRainShort(percent: Int, time: LocalTime): String =
        resources.getString(R.string.today_chart_readout, "$percent%", spokenTime(time))

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
            clothesFormat: ClothesFormat = ClothesFormat.ITEMS,
            bottomsFormat: BottomsFormat = BottomsFormat.IF_GARMENTS,
            rainAccessory: RainAccessory = RainAccessory.NONE,
            periodPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
            wearPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
        ): InsightFormatter =
            InsightFormatter(
                context.localizedResources(locale),
                locale,
                temperatureUnit,
                rangeFormat,
                clothesFormat,
                bottomsFormat,
                rainAccessory,
                periodPreamble,
                wearPreamble,
            )

        /** Convenience for the common path: render in the user's [Region]-derived locale. */
        fun forRegion(
            context: Context,
            region: Region,
            temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
            rangeFormat: RangeFormat = RangeFormat.DEGREES,
            clothesFormat: ClothesFormat = ClothesFormat.ITEMS,
            bottomsFormat: BottomsFormat = BottomsFormat.IF_GARMENTS,
            rainAccessory: RainAccessory = RainAccessory.NONE,
            periodPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
            wearPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
        ): InsightFormatter {
            val locale = region.toJavaLocale() ?: context.currentResourcesLocale()
            return forContext(
                context,
                locale,
                temperatureUnit,
                rangeFormat,
                clothesFormat,
                bottomsFormat,
                rainAccessory,
                periodPreamble,
                wearPreamble,
            )
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
