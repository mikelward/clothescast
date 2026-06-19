package app.clothescast.core.domain.model

import java.time.LocalTime

/**
 * The structured form of a daily insight. Each clause is a pure-data description
 * of one of the seven independent rules in
 * [app.clothescast.core.domain.usecase.RenderInsightSummary] (band, delta,
 * clothes, precip, calendar tie-in, evening-event tie-in); the corresponding
 * prose is produced by an Android-side formatter that resolves item keys and
 * templates through string resources.
 *
 * Splitting "what to say" (this) from "how to say it" (the formatter) lets us:
 *  - Localize clothes vocab per region without re-fetching the forecast — the
 *    same cached [InsightSummary] can re-render as "sweater" or "Pullover"
 *    depending on the user's Region setting (see GermanClothesPhraser).
 *  - Keep [app.clothescast.core.domain.usecase.RenderInsightSummary] free of any
 *    Android dependency, so it stays pure-Kotlin testable on the JVM.
 *
 * The [period] determines a couple of presentation choices: the band sentence's
 * lead-in ("Today will be …" vs "Tonight will be …"), whether the [delta]
 * clause is emitted at all (tonight skips the yesterday-vs-today comparison the
 * morning pass already covered), which tie-in clause carries event-mentions
 * (TONIGHT uses [calendarTieIn]; TODAY uses [eveningEventTieIn] when the user
 * opts in via "Mention evening events"), and whether [calendarTieIn] is
 * suppressed entirely (it is on TODAY — the bare precip clause is enough,
 * since the morning event the listener is mentally parsed against is already
 * known to them).
 */
data class InsightSummary(
    val period: ForecastPeriod,
    val band: BandClause,
    val delta: DeltaClause? = null,
    val clothes: ClothesClause? = null,
    val precip: PrecipClause? = null,
    val calendarTieIn: CalendarTieInClause? = null,
    /**
     * A second tie-in for morning insights: an evening event paired with a
     * clothing tip derived from the *evening* forecast slice. Renders as
     * "Tonight, bring a jacket." — no event title (see [EveningEventTieInClause]
     * for why a calendar event name never reaches the rendered prose).
     *
     * Only emitted on TODAY when the user has the "Mention evening events"
     * setting on. The TONIGHT pass uses [calendarTieIn] for its own event
     * tie-ins; that clause is anchored to a precip-peak hour the listener
     * doesn't already know about, and likewise carries no event title.
     */
    val eveningEventTieIn: EveningEventTieInClause? = null,
    /**
     * Carried accessories (today only umbrella) the user's rules fired,
     * independent of [clothes]. The formatter folds these into the precip
     * clause ("Rain at 3pm, bring an umbrella."), so they must survive the
     * clothes-mention gating that nulls [clothes] under
     * [ClothesMentionMode.NEVER] / IF_CHANGED — a rain accessory isn't a
     * "clothes" mention, and an opted-in umbrella should still be named on a
     * rainy day even when the wear clause is suppressed.
     */
    val carriedAccessories: List<String> = emptyList(),
    /**
     * Marks the *ongoing overnight* window — the night that began yesterday
     * evening and ends this morning (post-midnight tail), as opposed to the
     * coming night ("tonight"). Always with [period] == TONIGHT. Carried on the
     * summary (not just the [Insight]) so the formatter — which receives the
     * summary, not the full insight — leads the prose with "Overnight" instead
     * of "Tonight" on every surface (card, notification, TTS, cast) without each
     * call site having to thread a separate flag. Drives the "Overnight" page /
     * outfit labels too, so the visible/spoken wording can't contradict them.
     */
    val overnight: Boolean = false,
)

/**
 * The feels-like temperature window for the period, carried both as bands
 * (for legacy consumers and for the cache's pre-temp back-compat fallback)
 * and as the raw °C extremes the band is derived from. The formatter renders
 * the raw temps — rounded to whole degrees in the user's chosen unit — as
 * "Today, it will be 8° to 14°." (or "Today, it will be 14°." when the
 * rounded extremes collapse to a single value). [feelsLikeMinC] /
 * [feelsLikeMaxC] default to a representative midpoint of their respective
 * band so legacy construction sites and pre-temp cache reads still render
 * sensibly without explicit temps.
 */
data class BandClause(
    val low: TemperatureBand,
    val high: TemperatureBand,
    val feelsLikeMinC: Double = low.midpointC(),
    val feelsLikeMaxC: Double = high.midpointC(),
)

/**
 * Yesterday-to-today feels-like change.
 *
 * Two presentations, selected by [style] (which the renderer bakes in from the
 * user's [DeltaFormat] so the formatter doesn't need the setting threaded
 * through it):
 *  - [Style.DEGREES] (default) — a numeric delta rounded to whole degrees.
 *    [degrees] is always positive; [direction] carries the sign. Only emitted
 *    when the unrounded larger-of-(min,max) delta clears the configured
 *    threshold (historically 3°C). [band] is null.
 *  - [Style.BANDS] — names today's feels-like-high [TemperatureBand]
 *    *absolutely*, *replacing* the temperature sentence with "Today, it will
 *    be hot." (in place of the degree / range sentence — not an extra trailing
 *    clause). [band] is today's high band, emitted only when it differs from
 *    yesterday's (the renderer omits the clause otherwise) — so the user hears
 *    the new band the day has moved into, with no relative comparison.
 *    [degrees] / [direction] still carry the numeric delta for completeness,
 *    but the prose ignores them in this style.
 *
 * [style] defaults to [Style.DEGREES] and [band] to null so cached payloads
 * written before band mode existed re-render as the numeric delta they were
 * built as.
 */
data class DeltaClause(
    val degrees: Int,
    val direction: Direction,
    val band: TemperatureBand? = null,
    val style: Style = Style.DEGREES,
) {
    enum class Direction { WARMER, COOLER }

    enum class Style { DEGREES, BANDS }
}

/**
 * Clothes items that triggered this period, in user-rule order. Each [items]
 * entry is the verbatim rule key (e.g. "sweater", "umbrella", "shorts"); the
 * formatter's per-language phraser maps known English keys to localized vocab
 * at format time (e.g. "sweater" → "Pullover" in German), with anything not
 * in the table falling through unchanged.
 *
 * The slot-filtered views [tops], [bottoms], and [accessories] let consumers
 * read pre-classified subsets without rerunning [Garment.fromKey] /
 * [Garment.isAccessoryKey] themselves. Classification rules: a known
 * [Garment.Slot.BOTTOM] item is a bottom; a known accessory key (today only
 * "umbrella") is an accessory; everything else — known tops *and* free-form
 * user-typed items the catalog doesn't recognise (e.g. a legacy "cardigan"
 * rule) — counts as a top so it still gets spoken in the wear sentence. Each
 * view preserves the original input order of [items] within its subset, so
 * the article-picker on the formatter side sees the same plural-first /
 * singular-first sequences it would have if it filtered [items] itself.
 */
data class ClothesClause(val items: List<String>) {
    /** Tops and free-form unclassified items, in input order. */
    val tops: List<String>
        get() = items.filter {
            !Garment.isAccessoryKey(it) && Garment.fromKey(it)?.slot != Garment.Slot.BOTTOM
        }

    /** Recognised [Garment.Slot.BOTTOM] items (shorts, pants, …), in input order. */
    val bottoms: List<String>
        get() = items.filter { Garment.fromKey(it)?.slot == Garment.Slot.BOTTOM }

    /** Recognised accessory keys (today only `umbrella`), in input order. */
    val accessories: List<String>
        get() = items.filter { Garment.isAccessoryKey(it) }
}

/**
 * Peak precipitation hour: a [condition] (RAIN, SNOW, etc.) at a wall-clock [time].
 * The prose no longer speaks the hour — the formatter renders the bare
 * condition ("Rain.") because a pinned peak hour claimed precision the
 * hourly forecast can't back — but [time] stays on the clause for consumers
 * that want it (the chart's scrub anchor, the Nest-Hub card).
 *
 * [likelihood] tracks how confident we are. [PrecipLikelihood.LIKELY] renders as
 * the bare condition ("Rain."); [PrecipLikelihood.POSSIBLE] hedges
 * ("Chance of rain.") because only a single per-model series flagged
 * the hour while others stayed dry. Defaults to LIKELY so legacy cached
 * payloads — written before the field existed — keep their original wording.
 *
 * [allDay] is set when LIKELY rain runs across the whole period rather than
 * peaking at one hour — either it covers most of the window, or it falls in
 * two or more separated spells. Carried for the same data-consumer reasons
 * as [time]; the spoken prose reads identically either way now that the
 * time phrase is gone.
 */
data class PrecipClause(
    val condition: WeatherCondition,
    val time: LocalTime,
    val likelihood: PrecipLikelihood = PrecipLikelihood.LIKELY,
    val allDay: Boolean = false,
)

/**
 * Confidence tier for [PrecipClause]. The two values map to two prose forms:
 * "Rain at 3pm." (LIKELY) when models agree, "Chance of rain at 3pm."
 * (POSSIBLE) when only one model flags the hour. The renderer picks the tier
 * based on cross-model agreement at the peak hour; the formatter picks the
 * template.
 */
enum class PrecipLikelihood { LIKELY, POSSIBLE }

/**
 * Calendar tie-in: a clothes [item] keyed off the precipitation peak overlapping
 * a calendar event. The formatter renders this as "Bring <item> tonight." — no
 * event title, because we never want a calendar event name in the rendered prose
 * (the prose is fed to off-device TTS engines), and no time, because the matched
 * event is already pinned to the precip clause's hour. Only emitted on
 * [ForecastPeriod.TONIGHT]; on [ForecastPeriod.TODAY] the bare precip clause
 * carries the message.
 */
data class CalendarTieInClause(val item: String)

/**
 * Evening-event tie-in for the morning insight: clothes [items] hinted at by
 * an evening calendar event. The formatter renders this as "Tonight, bring
 * <items>." — no event title, for the same off-device-prose reason as
 * [CalendarTieInClause]. Only emitted on [ForecastPeriod.TODAY] when the user
 * has the "Mention evening events" setting on.
 *
 * [items] carries every clothes item the night needs that the day didn't
 * already announce — when the night triggers a jacket *and* coat the day
 * didn't, both surface ("Tonight, bring a jacket and coat."). Empty list
 * indicates the bare-rain emission path (per-model rain spotted, no clothes
 * delta) where the rain mention is the entire clause; in that case the
 * formatter renders "Tonight, rain at 9pm." (or "Tonight, chance of rain at
 * 9pm." when [likelihood] is POSSIBLE).
 *
 * When the evening forecast slice has rain ≥ 20%, [rainTime] carries the peak
 * hour and the formatter folds it into the same sentence ("Tonight, rain at
 * 9pm, bring a jacket.") — keeps the morning insight's mention of evening rain
 * tied to the evening event, rather than emitting a second precip clause that
 * would compete with the morning slice's own precip clause. "umbrella" is
 * stripped from [items] in that case — the rain mention already covers what
 * an umbrella's for — falling back to bare-rain prose if it was the only
 * item.
 *
 * [likelihood] is only meaningful when [rainTime] is set; it defaults to
 * LIKELY for back-compat with cached payloads written before the field
 * existed.
 *
 * [allDay] mirrors [PrecipClause.allDay] for the evening slice. Like the
 * peak time, it's carried for data consumers rather than the prose — the
 * formatter no longer speaks the hour or an "all night" phrase ("Tonight,
 * rain, bring a jacket." reads the same either way). Only
 * meaningful when [rainTime] is set and [likelihood] is LIKELY.
 *
 * [precipCondition] mirrors the same per-model peak's [WeatherCondition] so
 * the formatter can decide whether a wet-weather accessory mention makes
 * sense — see [warrantsRainAccessory] (umbrella for RAIN / DRIZZLE /
 * THUNDERSTORM, but never for SNOW). Null on cached payloads written before
 * this field existed — the formatter treats that as "unknown, skip the
 * accessory" rather than guess.
 */
data class EveningEventTieInClause(
    val items: List<String> = emptyList(),
    val rainTime: LocalTime? = null,
    val likelihood: PrecipLikelihood = PrecipLikelihood.LIKELY,
    val precipCondition: WeatherCondition? = null,
    val allDay: Boolean = false,
)

/**
 * Bands classify a feels-like temperature (°C) into a glanceable label. Boundaries
 * are inclusive at the lower edge: 12.0°C is COOL, 11.9°C is COLD.
 *
 * The enum names are presentation-neutral; the formatter maps each to a localized
 * `temperature_band_<name>` string resource.
 */
enum class TemperatureBand {
    FREEZING,
    COLD,
    COOL,
    MILD,
    WARM,
    HOT;

    /**
     * A representative °C inside this band. Used as the fallback temp when a
     * [BandClause] is constructed without explicit feels-like values — e.g.
     * legacy cached payloads written before the temps were carried, or test
     * fixtures that only care about the band classification.
     */
    fun midpointC(): Double = when (this) {
        FREEZING -> 0.0
        COLD -> 8.0
        COOL -> 15.0
        MILD -> 21.0
        WARM -> 26.0
        HOT -> 30.0
    }

    companion object {
        fun forCelsius(c: Double): TemperatureBand = when {
            c < 4.0 -> FREEZING
            c < 12.0 -> COLD
            c < 18.0 -> COOL
            c < 24.0 -> MILD
            c < 28.0 -> WARM
            else -> HOT
        }
    }
}
