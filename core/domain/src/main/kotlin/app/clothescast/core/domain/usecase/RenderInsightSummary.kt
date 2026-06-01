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
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.PrecipProbability.LIKELY_THRESHOLD
import app.clothescast.core.domain.model.PrecipProbability.POSSIBLE_THRESHOLD
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.isPrecipitation
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
 * 4. [PrecipClause] — fires in two tiers driven by cross-model agreement:
 *    [PrecipLikelihood.LIKELY] when a majority of consulted models hit ≥ 50%
 *    at the same hour ("Rain at 3pm."), [PrecipLikelihood.POSSIBLE] when at
 *    least one model hits ≥ 30% but the majority bar isn't cleared ("Chance
 *    of rain at 3pm."). Falls back to the base hourly series — and ultimately
 *    a noon synthesis from the day-level field — when per-model data isn't
 *    available; both fallbacks render as LIKELY (the existing behaviour).
 * 5. [CalendarTieInClause] — when clothes + precip both fired AND a calendar
 *    event overlaps the precip peak hour. Picks "umbrella" when on the clothes
 *    list, otherwise the first triggered item, mirroring rule 3's ordering.
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
        // Per-model hourly series for the same period [today] covers. When
        // present, the precip clause uses cross-model agreement to pick its
        // tier (see [PrecipLikelihood]); when null we fall back to the base
        // hourly + day-level field. Caller is responsible for slicing the
        // per-model series to the same window as [today.hourly].
        perModelHourly: PerModelHourly? = null,
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
        // Diagnostic hook called once per render with a one-line summary
        // of the delta clause decision (today / yesterday inputs, picked
        // side, outcome). Pure-Kotlin module → no DiagLog dependency;
        // :app's worker wiring passes a DiagLog adapter so a single
        // bug-report-less log line is enough to diagnose a "5° warmer
        // than yesterday" surprise, while tests / previews / cache
        // re-derives keep the no-op default and pay nothing.
        diagLog: (String) -> Unit = {},
    ): InsightSummary {
        val peak = peakPrecip(today, perModelHourly)
        return InsightSummary(
            period = period,
            band = bandClause(today),
            delta = if (period == ForecastPeriod.TODAY) deltaClause(todayForDelta, yesterday, deltaThresholdC, deltaFormat, diagLog) else null,
            clothes = clothesClause(todayItems, period, clothesMentionMode, yesterdayTriggeredItems),
            precip = peak?.let { PrecipClause(it.condition, it.time, it.likelihood) },
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
            calendarTieIn = if (period == ForecastPeriod.TONIGHT) calendarTieInClause(todayRuleItems, peak, events) else null,
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
     * Tier selection when [perModelHourly] is supplied:
     *  - [PrecipLikelihood.LIKELY] when a *majority* of consulted models hit
     *    ≥ [LIKELY_THRESHOLD]% probability at the same hour ("majority" = more
     *    than half: 2 of 3, both of 2, 1 of 1). Wettest such hour wins.
     *  - [PrecipLikelihood.POSSIBLE] when at least one model hits ≥
     *    [POSSIBLE_THRESHOLD]% at some hour but the LIKELY bar isn't cleared.
     *    The hour carrying the single biggest per-model reading wins.
     *
     * Falls back to the base hourly series (and finally a noon synthesis from
     * the day-level field) when per-model data isn't available. Both fallback
     * paths emit LIKELY so legacy behaviour and cached payloads keep their
     * original wording. The 30% bar matches the historical fallback threshold.
     *
     * Condition resolution: prefers the base hour's weather code at the peak
     * time when it's a precipitating type; falls back to the day-level
     * condition (after `slicedFor…`, this is already the wettest in-window
     * hour's code) when the base hour is missing, UNKNOWN, or non-precip; and
     * finally defaults to [WeatherCondition.RAIN] when the per-model tier
     * triggered but the base forecast carries no precipitating code at all —
     * which is the exact "base under-called both probability *and* type"
     * scenario the per-model tier exists to catch. The base-only fallback
     * (no per-model data) keeps the original strict "suppress when no
     * precipitating code" behaviour, because there's no extra signal to
     * justify overriding the base condition.
     */
    private fun peakPrecip(today: DailyForecast, perModelHourly: PerModelHourly?): PeakPrecip? {
        val perModelHit = perModelHourly?.let { pickPerModelPeak(today, it) }
        if (perModelHit != null) return perModelHit

        val peak = today.hourly.maxByOrNull { it.precipitationProbabilityPct }
        val time: LocalTime
        val condition: WeatherCondition
        if (peak == null || peak.precipitationProbabilityPct < POSSIBLE_THRESHOLD) {
            if (today.precipitationProbabilityMaxPct < POSSIBLE_THRESHOLD) return null
            time = LocalTime.NOON
            condition = today.condition
        } else {
            time = peak.time
            condition = if (peak.condition == WeatherCondition.UNKNOWN) today.condition else peak.condition
        }
        if (!condition.isPrecipitation()) return null
        return PeakPrecip(time, condition, PrecipLikelihood.LIKELY)
    }

    private fun pickPerModelPeak(today: DailyForecast, perModelHourly: PerModelHourly): PeakPrecip? {
        val models = perModelHourly.byModel.values.toList()
        if (models.isEmpty()) return null
        // Walk every hour any model reported, scoring by "models hitting LIKELY
        // at this hour" first and the single biggest per-model reading second
        // (tie-break, also doubles as the POSSIBLE fallback pivot). Hours are
        // LocalDateTimes (per-model entries carry a date so the tonight wrap
        // doesn't alias); convert to LocalTime at emission, which is all the
        // downstream prose / chart consumers need.
        //
        // Majority is computed PER HOUR over the models that actually reported
        // a reading there, not over the total number of models in [byModel].
        // Floor of 2 readings keeps a single-model agreement honest as a
        // POSSIBLE rather than promoting it to LIKELY: "one model says rain"
        // is the textbook chance-of-rain case the per-model tier exists to
        // express.
        val hours = models.flatMap { entries -> entries.map { it.time } }.toSortedSet()
        val likelyHour = hours
            .mapNotNull { hour ->
                // Readings with usable precip only: a model that reported
                // temperature for this hour but no precipitation_probability
                // (per the parser's null-precip handling) shouldn't count
                // toward the per-hour majority for a rain decision. Filtering
                // here also keeps the rain peak honest in mixed selections
                // (e.g. ECMWF + UKMO) where one model has no precip series
                // at all — without it, the missing-data model would be
                // implicitly counted as "below threshold" and could veto
                // the rain call.
                val readings = models
                    .mapNotNull { entries -> entries.firstOrNull { it.time == hour } }
                    .filter { it.precipitationProbabilityPct != null }
                if (readings.size < 2) return@mapNotNull null
                val majorityOfReporters = readings.size / 2 + 1
                val likelyCount = readings.count { (it.precipitationProbabilityPct ?: 0.0) >= LIKELY_THRESHOLD }
                if (likelyCount >= majorityOfReporters) hour to readings else null
            }
            .maxByOrNull { (_, readings) -> readings.maxOf { it.precipitationProbabilityPct ?: 0.0 } }
            ?.first
            ?.toLocalTime()
        if (likelyHour != null) {
            return PeakPrecip(likelyHour, perModelConditionAt(likelyHour, today), PrecipLikelihood.LIKELY)
        }

        val possibleHour = models.asSequence()
            .flatten()
            .filter { (it.precipitationProbabilityPct ?: 0.0) >= POSSIBLE_THRESHOLD }
            .maxByOrNull { it.precipitationProbabilityPct ?: 0.0 }
            ?.time
            ?.toLocalTime()
        if (possibleHour != null) {
            return PeakPrecip(possibleHour, perModelConditionAt(possibleHour, today), PrecipLikelihood.POSSIBLE)
        }
        return null
    }

    private fun perModelConditionAt(time: LocalTime, today: DailyForecast): WeatherCondition {
        val baseHourCondition = today.hourly.firstOrNull { it.time == time }
            ?.condition
            ?.takeIf { it != WeatherCondition.UNKNOWN }
        if (baseHourCondition != null && baseHourCondition.isPrecipitation()) return baseHourCondition
        if (today.condition.isPrecipitation()) return today.condition
        // The per-model tier triggered on probability alone but no part of the
        // base forecast carries a precipitating code. Default to RAIN — the
        // model is the source of truth here and "Chance of rain" / "Rain" reads
        // honestly even when the base under-called the precipitation type.
        return WeatherCondition.RAIN
    }

    private fun calendarTieInClause(
        items: List<String>,
        peak: PeakPrecip?,
        events: List<CalendarEvent>,
    ): CalendarTieInClause? {
        if (items.isEmpty() || peak == null || events.isEmpty()) return null
        // Need an overlapping event to motivate the clause, but we don't capture
        // the event's title or time — neither is in the rendered prose, and we
        // never want a calendar event title flowing through to off-device TTS
        // (the prose is fed to Gemini over the BYOK key).
        events.firstOrNull { it.overlaps(peak.time) } ?: return null
        // Pick the first triggered item, mirroring rule 3's ordering. The formatter
        // silences accessories (umbrella) before they reach the rendered prose, so
        // there's no point picking a specifically-precip-motivated item here — until
        // the accessory catalog lands, calendar tie-ins are garment-only.
        return CalendarTieInClause(item = items.first())
    }

    private data class PeakPrecip(
        val time: LocalTime,
        val condition: WeatherCondition,
        val likelihood: PrecipLikelihood,
    )
}
