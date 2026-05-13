package app.clothescast.core.domain.model

import java.time.LocalTime

/**
 * The structured form of a daily insight. Each clause is a pure-data description
 * of one of the seven independent rules in
 * [app.clothescast.core.domain.usecase.RenderInsightSummary] (alert, band, delta,
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
    val alert: AlertClause? = null,
    val delta: DeltaClause? = null,
    val clothes: ClothesClause? = null,
    val precip: PrecipClause? = null,
    val calendarTieIn: CalendarTieInClause? = null,
    /**
     * A second tie-in for morning insights: an evening event paired with a
     * clothing tip derived from the *evening* forecast slice. Renders as
     * "Bring a jacket for your dinner tonight." — no time, since the user
     * already knows when their event is and the wording reads more like a
     * conversational heads-up than a calendar reminder.
     *
     * Only emitted on TODAY when the user has the "Mention evening events"
     * setting on. The TONIGHT pass uses [calendarTieIn] for its own event
     * tie-ins, which keeps the time + title because it's anchored to a
     * precip-peak hour the listener doesn't already know about.
     */
    val eveningEventTieIn: EveningEventTieInClause? = null,
)

/**
 * Highest-severity SEVERE/EXTREME alert event name (e.g. "Tornado Warning"). Pulled
 * verbatim from the upstream warning feed; the formatter renders it as
 * "Alert: <event>." with no further interpretation.
 */
data class AlertClause(val event: String)

/**
 * The feels-like temperature band(s) for the period. When [low] == [high] the
 * formatter emits a single label ("Today will be mild."); otherwise it emits a
 * range ("Today will be cool to mild.") in low-to-high order.
 */
data class BandClause(val low: TemperatureBand, val high: TemperatureBand)

/**
 * Yesterday-to-today feels-like delta, rounded to whole degrees. [degrees] is
 * always positive; [direction] carries the sign. Only emitted when the unrounded
 * larger-of-(min,max) delta is ≥ 3°C.
 */
data class DeltaClause(val degrees: Int, val direction: Direction) {
    enum class Direction { WARMER, COOLER }
}

/**
 * Clothes items that triggered this period, in user-rule order. Each [item] is
 * the verbatim rule key (e.g. "sweater", "umbrella", "shorts"); the formatter's
 * per-language phraser maps known English keys to localized vocab at format
 * time (e.g. "sweater" → "Pullover" in German), with anything not in the table
 * falling through unchanged.
 */
data class ClothesClause(val items: List<String>)

/**
 * Peak precipitation hour: a [condition] (RAIN, SNOW, etc.) at a wall-clock [time].
 * The formatter capitalises and humanises the condition name ("Rain at 15:00.").
 *
 * [likelihood] tracks how confident we are. [PrecipLikelihood.LIKELY] renders as
 * the bare condition ("Rain at 3pm."); [PrecipLikelihood.POSSIBLE] hedges
 * ("Chance of rain at 3pm.") because only a single per-model series flagged
 * the hour while others stayed dry. Defaults to LIKELY so legacy cached
 * payloads — written before the field existed — keep their original wording.
 */
data class PrecipClause(
    val condition: WeatherCondition,
    val time: LocalTime,
    val likelihood: PrecipLikelihood = PrecipLikelihood.LIKELY,
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
 * Evening-event tie-in for the morning insight: a clothes [item] hinted at by
 * an evening calendar event. The formatter renders this as "Bring <item>
 * tonight." — no event title, for the same off-device-prose reason as
 * [CalendarTieInClause]. Only emitted on [ForecastPeriod.TODAY] when the user
 * has the "Mention evening events" setting on.
 *
 * When the evening forecast slice has rain ≥ 30%, [rainTime] carries the peak
 * hour and the formatter folds it into the same sentence ("Bring an umbrella
 * tonight, rain at 9pm.") — keeps the morning insight's mention of evening rain
 * tied to the evening event, rather than emitting a second precip clause that
 * would compete with the morning slice's own precip clause.
 *
 * [item] is nullable so the clause can also fire on per-model evening rain
 * alone, with no clothes rule triggered for the tonight window — the user
 * still wants to know that a model spotted rain when they have an evening
 * event coming up, even without a precip-keyed rule on the books. In that
 * case the formatter renders a bare "Rain tonight at 9pm." (or "Chance of
 * rain tonight at 9pm." when [likelihood] is POSSIBLE). [likelihood] is only
 * meaningful when [rainTime] is set; it defaults to LIKELY for back-compat
 * with cached payloads written before the field existed.
 */
data class EveningEventTieInClause(
    val item: String?,
    val rainTime: LocalTime? = null,
    val likelihood: PrecipLikelihood = PrecipLikelihood.LIKELY,
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
