package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.CalendarTieInClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.DeltaFormat
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.PrecipProbability.ALL_DAY_COVERAGE_FRACTION
import app.clothescast.core.domain.model.PrecipProbability.LIKELY_THRESHOLD
import app.clothescast.core.domain.model.PrecipProbability.POSSIBLE_THRESHOLD
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.isPrecipitation
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds the structured [InsightSummary] for a single period (TODAY or TONIGHT).
 * Pure function: same forecast slice, same clauses. The renderer no longer
 * special-cases evening data — the morning's heads-up about a cold or rainy
 * evening event is computed by the caller by running this same render against
 * the night slice and folding the resulting clothes + precip clauses into an
 * [EveningEventTieInClause] (see [GenerateDailyInsight]).
 *
 * Rules (each yields 0 or 1 clause):
 * 1. [BandClause] — classify feels-like low and high into bands. Always emitted.
 * 2. [DeltaClause] — yesterday vs today; only emitted when the larger absolute
 *    feels-like delta is ≥ 3°C, and only for [ForecastPeriod.TODAY] (yesterday's
 *    overnight comparison isn't useful, and the morning pass already covers it).
 * 3. [ClothesClause] — items triggered by the user's rule list, in rule order.
 * 4. [PrecipClause] — fires in tiers off the single blended-consensus chance of
 *    rain (the same number the umbrella default and the conditions strip use):
 *    [PrecipLikelihood.LIKELY] ("Rain at 3pm.") at/above
 *    [PrecipProbability.LIKELY_THRESHOLD] (50%), [PrecipLikelihood.POSSIBLE]
 *    ("Chance of rain at 3pm.") in the [PrecipProbability.POSSIBLE_THRESHOLD]–49%
 *    band (10–49%). Below 10% nothing fires. The peak hour is the wettest hour
 *    of [today.hourly]; when that series is empty or sub-10% it falls back to a
 *    noon synthesis from the day-level [DailyForecast.precipitationProbabilityMaxPct].
 * 5. [CalendarTieInClause] — when clothes + precip both fired AND a calendar
 *    event overlaps the precip peak hour. Names the first triggered item in
 *    rule order, mirroring rule 3's ordering (no umbrella priority — the
 *    formatter silences accessories, so see the inline comment where the
 *    clause is built).
 *    **Only emitted on [ForecastPeriod.TONIGHT].** On TODAY the bare precip
 *    clause ("Rain at 3pm.") is enough — the listener already knows about
 *    their morning event, so chaining a tie-in just repeats what they heard.
 * 6. [EveningEventTieInClause] — passes through whatever the caller built. The
 *    renderer doesn't know how to compose one because it requires consulting
 *    the night forecast slice, which is the caller's job.
 *
 * All temperature comparisons use feels-like values, matching the clothes rules.
 */
class RenderInsightSummary {
    operator fun invoke(
        today: DailyForecast,
        yesterday: DailyForecast,
        todayItems: List<String>,
        events: List<CalendarEvent> = emptyList(),
        period: ForecastPeriod = ForecastPeriod.TODAY,
        // The today side of the delta comparison, paired with [yesterday]. The
        // caller picks the pair so today and yesterday cover the same time range
        // — typically daytime-vs-daytime when both have hourly entries in the
        // window, falling back to 24h-vs-24h when either side lacks them.
        // Defaults to [today], which is correct when the caller hasn't sliced
        // the forecast (e.g. in unit tests that pass raw 24h fields on both sides).
        todayForDelta: DailyForecast = today,
        // Pre-built evening tie-in. The caller (GenerateDailyInsight) constructs
        // it by running render() against the night slice and folding the
        // resulting clothes + precip clauses, gated on the user having an
        // event away from home that night. This renderer is period-local and
        // doesn't compose it.
        eveningEventTieIn: EveningEventTieInClause? = null,
        // Feels-like delta (°C) the day must clear before the delta clause is
        // emitted. null disables the clause entirely. Defaults to the historical
        // 3°C threshold so existing callers/tests are unchanged.
        deltaThresholdC: Double? = 3.0,
        // How the change clause is phrased: a numeric degree delta
        // ([DeltaFormat.DEGREES], default) or a feels-like-high band transition
        // ([DeltaFormat.BANDS]). Band mode fires on a band boundary crossing
        // rather than [deltaThresholdC]; see [deltaClause]. Defaults to DEGREES
        // so existing callers/tests are unchanged.
        deltaFormat: DeltaFormat = DeltaFormat.DEGREES,
        // Controls emission of the clothes clause on [ForecastPeriod.TODAY]. See
        // [ClothesMentionMode]. Ignored on TONIGHT (always behaves as ALWAYS),
        // since [yesterdayTriggeredItems] has no overnight counterpart. Defaults
        // to ALWAYS so existing callers/tests are unchanged.
        clothesMentionMode: ClothesMentionMode = ClothesMentionMode.ALWAYS,
        // Yesterday's triggered clothing items, used only by
        // [ClothesMentionMode.IF_CHANGED] to decide whether today's set differs.
        yesterdayTriggeredItems: List<String> = emptyList(),
        // Threshold-rule matches only, separate from [todayItems] (which
        // includes per-tier default items). Drives "bring X for your
        // event" tie-ins, where a default isn't an "extra" the user
        // needs to bring — it's the baseline outfit they already have
        // on. Defaults to [todayItems] for backward-compat with callers
        // that don't distinguish (mostly tests passing synthetic rule
        // items directly); the use case `GenerateDailyInsight` plumbs
        // the two through separately so a rainy mild evening doesn't
        // emit "Bring a t-shirt." purely because TriggeredOutfit has
        // baseline items.
        todayRuleItems: List<String> = todayItems,
        // Start of the tonight window, used only to date the precip peak when
        // pairing it against the dated calendar events for the TONIGHT
        // calendar tie-in: peak hours at/after this time fall on [today]'s
        // date, earlier ones on the next day — the same wrap convention as
        // [tonightWindow], via the shared [tonightDateTime] helper. Defaults
        // to the 19:00 schedule default so existing callers/tests are
        // unchanged; [DeriveInsight] passes the user's actual setting.
        tonightStart: LocalTime = LocalTime.of(19, 0),
        // Diagnostic hook called once per render with a one-line summary
        // of the delta clause decision (today / yesterday inputs, picked
        // side, outcome). Pure-Kotlin module → no DiagLog dependency;
        // :app's worker wiring passes a DiagLog adapter so a single
        // bug-report-less log line is enough to diagnose a "5° warmer
        // than yesterday" surprise, while tests / previews / cache
        // re-derives keep the no-op default and pay nothing.
        diagLog: (String) -> Unit = {},
        // Marks the ongoing overnight (post-midnight tail). Carried onto the
        // returned summary so the formatter leads with "Overnight" instead of
        // "Tonight". Only meaningful with [period] == TONIGHT; defaults false.
        overnight: Boolean = false,
    ): InsightSummary {
        val peak = peakPrecip(today)
        return InsightSummary(
            period = period,
            overnight = overnight,
            band = bandClause(today),
            delta = if (period == ForecastPeriod.TODAY) deltaClause(todayForDelta, yesterday, deltaThresholdC, deltaFormat, diagLog) else null,
            clothes = clothesClause(todayItems, period, clothesMentionMode, yesterdayTriggeredItems),
            precip = peak?.let { PrecipClause(it.condition, it.time, it.likelihood, it.allDay) },
            // Calendar tie-in only fires on TONIGHT — pairing the precip peak
            // with an event the listener hasn't yet attended ("Bring an
            // umbrella.") is the case where it adds value. Event titles and
            // times never appear in the rendered prose — they don't flow
            // off-device through Gemini TTS — but their existence motivates
            // the heads-up. On TODAY the listener already knows about the
            // event their morning is built around, so the bare precip clause
            // ("Rain at 3pm.") is enough; chaining another "Bring an
            // umbrella." after it just repeats what the user already heard.
            //
            // Threshold-rule matches only — a tier's default isn't an
            // "extra to bring," so it shouldn't be what the tie-in
            // points at.
            calendarTieIn = if (period == ForecastPeriod.TONIGHT) {
                calendarTieInClause(todayRuleItems, peak, events, today.date, tonightStart)
            } else {
                null
            },
            eveningEventTieIn = eveningEventTieIn,
            // Carried accessories (umbrella) ride independently of the wear
            // clause: the formatter folds them into the precip clause, so they
            // must survive clothes-mention gating that suppresses [clothes].
            carriedAccessories = todayItems.filter { Garment.isAccessoryKey(it) },
        )
    }

    private fun bandClause(today: DailyForecast): BandClause = BandClause(
        low = TemperatureBand.forCelsius(today.feelsLikeMinC),
        high = TemperatureBand.forCelsius(today.feelsLikeMaxC),
        feelsLikeMinC = today.feelsLikeMinC,
        feelsLikeMaxC = today.feelsLikeMaxC,
    )

    private fun deltaClause(
        today: DailyForecast,
        yesterday: DailyForecast,
        thresholdC: Double?,
        format: DeltaFormat,
        diagLog: (String) -> Unit,
    ): DeltaClause? = when (format) {
        DeltaFormat.DEGREES -> degreesDeltaClause(today, yesterday, thresholdC, diagLog)
        DeltaFormat.BANDS -> bandDeltaClause(today, yesterday, diagLog)
    }

    /**
     * Absolute-band change clause: names today's feels-like *high* band when it
     * differs from yesterday's, so the prose reads "Today, it will be hot." —
     * the new band the day has moved into, with no relative comparison. Fires on any
     * band boundary crossing (the degree threshold doesn't apply — crossing
     * into a new band is itself the signal) and is omitted when the high stays
     * in the same band ("only if yesterday was not hot"). [DeltaClause.degrees]
     * / [DeltaClause.direction] carry the numeric delta for completeness; the
     * band-style prose ignores them.
     *
     * TODO(band-change-side): we compare the daily *high* band only — the
     *  headline "it'll be hot today" temperature. Revisit whether a band move
     *  on the low (a much colder morning under an unchanged afternoon high)
     *  should also surface; deferred per the initial design.
     */
    private fun bandDeltaClause(
        today: DailyForecast,
        yesterday: DailyForecast,
        diagLog: (String) -> Unit,
    ): DeltaClause? {
        val fromBand = TemperatureBand.forCelsius(yesterday.feelsLikeMaxC)
        val toBand = TemperatureBand.forCelsius(today.feelsLikeMaxC)
        val inputs = "today high=%.1f (%s) yesterday high=%.1f (%s)".format(
            Locale.US,
            today.feelsLikeMaxC, toBand,
            yesterday.feelsLikeMaxC, fromBand,
        )
        if (fromBand == toBand) {
            diagLog("delta(band): $inputs → same band, no clause")
            return null
        }
        val rawDelta = today.feelsLikeMaxC - yesterday.feelsLikeMaxC
        val direction = if (toBand.ordinal > fromBand.ordinal) {
            DeltaClause.Direction.WARMER
        } else {
            DeltaClause.Direction.COOLER
        }
        diagLog("delta(band): $inputs → announce $toBand ($direction)")
        return DeltaClause(
            degrees = abs(rawDelta.roundToInt()),
            direction = direction,
            band = toBand,
            style = DeltaClause.Style.BANDS,
        )
    }

    private fun degreesDeltaClause(
        today: DailyForecast,
        yesterday: DailyForecast,
        thresholdC: Double?,
        diagLog: (String) -> Unit,
    ): DeltaClause? {
        if (thresholdC == null) {
            diagLog("delta: threshold disabled, no clause")
            return null
        }
        val highDelta = today.feelsLikeMaxC - yesterday.feelsLikeMaxC
        val lowDelta = today.feelsLikeMinC - yesterday.feelsLikeMinC
        val pickedHigh = abs(highDelta) >= abs(lowDelta)
        val biggest = if (pickedHigh) highDelta else lowDelta
        val inputs = "today min/max=%.1f/%.1f yesterday min/max=%.1f/%.1f highDelta=%+.1f lowDelta=%+.1f picked=%s biggest=%+.1f threshold=%.1f".format(
            Locale.US,
            today.feelsLikeMinC, today.feelsLikeMaxC,
            yesterday.feelsLikeMinC, yesterday.feelsLikeMaxC,
            highDelta, lowDelta,
            if (pickedHigh) "high" else "low",
            biggest, thresholdC,
        )
        // Apply the threshold against the *unrounded* delta. Otherwise 2.6°C rounds
        // to 3 and would emit a clause even though the actual delta is under the
        // configured rule.
        if (abs(biggest) < thresholdC) {
            diagLog("delta: $inputs → under threshold, no clause")
            return null
        }
        val rounded = biggest.roundToInt()
        val direction = if (rounded > 0) DeltaClause.Direction.WARMER else DeltaClause.Direction.COOLER
        diagLog("delta: $inputs → ${abs(rounded)}° $direction")
        return DeltaClause(degrees = abs(rounded), direction = direction)
    }

    private fun clothesClause(
        items: List<String>,
        period: ForecastPeriod,
        mode: ClothesMentionMode,
        yesterdayItems: List<String>,
    ): ClothesClause? {
        if (items.isEmpty()) return null
        // Mode gating is morning-only: TONIGHT has no yesterday-overnight
        // comparison, so it always names clothing (the historical behaviour).
        if (period != ForecastPeriod.TODAY) return ClothesClause(items)
        return when (mode) {
            ClothesMentionMode.ALWAYS -> ClothesClause(items)
            ClothesMentionMode.NEVER -> null
            ClothesMentionMode.IF_CHANGED -> {
                // Comparison canonicalizes through Garment.fromKey().itemKey so
                // legacy aliases collapse to the same garment: yesterday's
                // "trousers" rule and today's default "pants" both resolve to
                // PANTS / "pants" and the clause is correctly suppressed as
                // unchanged. fromKey internally trims + lowercases and maps
                // the tolerated aliases (trousers, jumper, tshirt) onto the
                // canonical key, so this also picks up the case / whitespace
                // tolerance the previous normalize() pass was doing. Unknown
                // items (anything off-catalog) fall back to the trimmed
                // lowercase string so they still compare consistently.
                //
                // Accessories (umbrella) are excluded from the comparison: the
                // formatter strips them from the wear sentence and renders them
                // through the precip clause instead, so an umbrella that fired
                // today but not yesterday isn't a *wear* change — letting it
                // through would emit a redundant baseline "Wear a t-shirt and
                // pants." just because the umbrella's default gate cleared. The
                // umbrella still surfaces via [carriedAccessories] → precip
                // clause regardless of this gate.
                val canonicalize: (String) -> String = { item ->
                    Garment.fromKey(item)?.itemKey ?: item.trim().lowercase(Locale.ROOT)
                }
                val wornToday = items.filterNot { Garment.isAccessoryKey(it) }.map(canonicalize).toSet()
                val wornYesterday = yesterdayItems.filterNot { Garment.isAccessoryKey(it) }.map(canonicalize).toSet()
                if (wornToday == wornYesterday) {
                    null
                } else {
                    ClothesClause(items)
                }
            }
        }
    }

    /**
     * Resolves the precipitation peak hour the way the precip rule needs it. Lifted
     * out of the precip clause assembly so the calendar-tie-in rule can pair an
     * event window against the same time without re-running the logic and getting
     * out of sync.
     *
     * Reads the single blended-consensus chance of rain — the same number the
     * umbrella default's gate and the conditions strip key off — so all three
     * surface rain together. The peak hour is the wettest hour of [today.hourly];
     * when that series is empty or its peak is below [POSSIBLE_THRESHOLD] it falls
     * back to a noon synthesis from the day-level
     * [DailyForecast.precipitationProbabilityMaxPct]. Tiers:
     *  - [PrecipLikelihood.LIKELY] ("Rain.") at/above [LIKELY_THRESHOLD] (50%).
     *  - [PrecipLikelihood.POSSIBLE] ("Chance of rain.") in the
     *    [POSSIBLE_THRESHOLD]–49% band (10–49%).
     * Below [POSSIBLE_THRESHOLD] (10%), or when the resolved condition isn't a
     * precipitating type, nothing fires.
     *
     * Condition resolution: prefers the peak hour's weather code when it's a
     * precipitating type, falling back to the day-level condition (after
     * `slicedFor…` this is already the wettest in-window hour's code) when the
     * hour is UNKNOWN; the noon fallback uses the day-level condition directly.
     */
    private fun peakPrecip(today: DailyForecast): PeakPrecip? {
        val peak = today.hourly.maxByOrNull { it.precipitationProbabilityPct }
        val time: LocalTime
        val condition: WeatherCondition
        val peakProb: Double
        if (peak == null || peak.precipitationProbabilityPct < POSSIBLE_THRESHOLD) {
            if (today.precipitationProbabilityMaxPct < POSSIBLE_THRESHOLD) return null
            time = LocalTime.NOON
            condition = today.condition
            peakProb = today.precipitationProbabilityMaxPct
        } else {
            time = peak.time
            condition = if (peak.condition == WeatherCondition.UNKNOWN) today.condition else peak.condition
            peakProb = peak.precipitationProbabilityPct
        }
        if (!condition.isPrecipitation()) return null
        // All-day is measured against the LIKELY bar (≥ 50%), not the 10% peak
        // threshold above — "rain all day" should mean confident rain, so a day
        // that only ever nudges 10–49% still names its single peak hour. The base
        // hourly list is already in window order, so adjacency in the list is
        // adjacency in time (no LocalTime midnight-wrap aliasing).
        val allDay = isAllDayRain(today.hourly.map { it.precipitationProbabilityPct >= LIKELY_THRESHOLD })
        // Grade by the blended-consensus chance of rain: a confident "Rain" only
        // at/above LIKELY (≥ 50%), a hedged "Chance of rain" in the 10–49% band.
        val likelihood =
            if (peakProb >= LIKELY_THRESHOLD) PrecipLikelihood.LIKELY else PrecipLikelihood.POSSIBLE
        return PeakPrecip(time, condition, likelihood, allDay)
    }

    private fun calendarTieInClause(
        items: List<String>,
        peak: PeakPrecip?,
        events: List<CalendarEvent>,
        todayDate: LocalDate,
        tonightStart: LocalTime,
    ): CalendarTieInClause? {
        if (items.isEmpty() || peak == null || events.isEmpty()) return null
        // Need an overlapping event to motivate the clause, but we don't capture
        // the event's title or time — neither is in the rendered prose, and we
        // never want a calendar event title flowing through to off-device TTS
        // (the prose is fed to Gemini over the BYOK key).
        //
        // The peak is a wall-clock hour inside the tonight window (this clause
        // only fires on TONIGHT); date it via the shared tonight wrap
        // convention before pairing against the dated events.
        val peakAt = tonightDateTime(todayDate, tonightStart, peak.time)
        events.firstOrNull { it.overlaps(peakAt) } ?: return null
        // Pick the first triggered item, mirroring rule 3's ordering. The formatter
        // silences accessories (umbrella) before they reach the rendered prose, so
        // there's no point picking a specifically-precip-motivated item here — until
        // the accessory catalog lands, calendar tie-ins are garment-only.
        return CalendarTieInClause(item = items.first())
    }

    /**
     * Decides whether LIKELY rain runs the whole period rather than peaking at a
     * single hour. [likelyByHour] is the period's reported hours in chronological
     * order, each flagged true when it cleared the LIKELY rain bar. Two ways to
     * trip (matching the user's two mental models):
     *  - **Coverage** — a clear majority of the hours are wet
     *    (≥ [ALL_DAY_COVERAGE_FRACTION]); a single time would undersell a chart
     *    that's ≥ 50% across nearly every hour.
     *  - **Multiple spells** — rain falls in two or more separated runs (a dry
     *    gap between them), so no one hour represents the day.
     * Needs at least two wet hours either way — one isolated hour is just a peak,
     * not an all-day pattern.
     */
    private fun isAllDayRain(likelyByHour: List<Boolean>): Boolean {
        val total = likelyByHour.size
        val wet = likelyByHour.count { it }
        if (wet < 2) return false
        val coverage = wet.toDouble() / total >= ALL_DAY_COVERAGE_FRACTION
        var runs = 0
        var inRun = false
        for (likely in likelyByHour) {
            if (likely && !inRun) runs++
            inRun = likely
        }
        return coverage || runs >= 2
    }

    private data class PeakPrecip(
        val time: LocalTime,
        val condition: WeatherCondition,
        val likelihood: PrecipLikelihood,
        val allDay: Boolean = false,
    )
}
