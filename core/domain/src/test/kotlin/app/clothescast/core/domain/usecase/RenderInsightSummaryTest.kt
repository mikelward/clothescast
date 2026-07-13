package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.DeltaFormat
import app.clothescast.core.domain.model.EveningEventExtrasClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RenderInsightSummaryTest {
    private val subject = RenderInsightSummary()

    private val yesterday = DailyForecast(
        date = LocalDate.of(2026, 4, 24),
        temperatureMinC = 12.0,
        temperatureMaxC = 18.0,
        feelsLikeMinC = 10.0,
        feelsLikeMaxC = 17.0,
        precipitationProbabilityMaxPct = 5.0,
        precipitationMmTotal = 0.0,
        condition = WeatherCondition.PARTLY_CLOUDY,
    )

    private val mildToday = DailyForecast(
        date = LocalDate.of(2026, 4, 25),
        temperatureMinC = 16.0,
        temperatureMaxC = 22.0,
        feelsLikeMinC = 18.0,
        feelsLikeMaxC = 22.0,
        precipitationProbabilityMaxPct = 5.0,
        precipitationMmTotal = 0.0,
        condition = WeatherCondition.PARTLY_CLOUDY,
    )

    // The TONIGHT calendar extras dates the precip peak with the tonightWindow
    // wrap convention: hours before the default 19:00 tonight start — like the
    // 15:00 peak these fixtures use — fall on the day *after* mildToday.date,
    // so events meant to overlap (or be compared against) that peak are dated
    // tomorrow.
    private val tonightPeakDate = mildToday.date.plusDays(1)

    @Test
    fun `band clause is always emitted`() {
        val out = subject(mildToday, yesterday, emptyList())
        out.band.low shouldBe TemperatureBand.MILD
        out.band.high shouldBe TemperatureBand.MILD
    }

    @Test
    fun `band clause carries low and high separately when they fall in different bands`() {
        val today = mildToday.copy(feelsLikeMinC = 15.0, feelsLikeMaxC = 23.0)
        val out = subject(today, yesterday, emptyList())
        out.band.low shouldBe TemperatureBand.COOL
        out.band.high shouldBe TemperatureBand.MILD
    }

    @Test
    fun `delta clause emits when feels-like high differs by at least 3 degrees`() {
        // yesterday max 17, today max 22 → +5 warmer.
        val today = mildToday.copy(feelsLikeMaxC = 22.0, feelsLikeMinC = yesterday.feelsLikeMinC)
        val out = subject(today, yesterday, emptyList())
        out.delta.shouldNotBeNull()
        out.delta!!.degrees shouldBe 5
        out.delta!!.direction shouldBe DeltaClause.Direction.WARMER
    }

    @Test
    fun `delta clause is omitted when the unrounded delta is under 3 even if it rounds to 3`() {
        // 2.6° rounds to 3 — but the rule is "≥3° true delta", not "≥3° after rounding".
        val today = mildToday.copy(
            feelsLikeMaxC = yesterday.feelsLikeMaxC + 2.6,
            feelsLikeMinC = yesterday.feelsLikeMinC,
        )
        subject(today, yesterday, emptyList()).delta.shouldBeNull()
    }

    @Test
    fun `delta clause is omitted when both deltas are under 3 degrees`() {
        val today = mildToday.copy(feelsLikeMinC = 12.0, feelsLikeMaxC = 19.0)
        subject(today, yesterday, emptyList()).delta.shouldBeNull()
    }

    @Test
    fun `delta uses the larger absolute delta when high and low disagree`() {
        // yesterday: max 17, min 10
        // today:    max 16 (-1), min 4 (-6) → cooler 6
        val today = mildToday.copy(feelsLikeMinC = 4.0, feelsLikeMaxC = 16.0)
        val out = subject(today, yesterday, emptyList()).delta
        out.shouldNotBeNull()
        out!!.degrees shouldBe 6
        out.direction shouldBe DeltaClause.Direction.COOLER
    }

    @Test
    fun `delta clause is omitted when the threshold is null off`() {
        // +5 would clear the default 3° rule, but a null threshold disables the
        // clause entirely.
        val today = mildToday.copy(feelsLikeMaxC = 22.0, feelsLikeMinC = yesterday.feelsLikeMinC)
        subject(today, yesterday, emptyList(), deltaThresholdC = null).delta.shouldBeNull()
    }

    @Test
    fun `delta clause respects a custom threshold above the actual delta`() {
        // +5 clears the 3° default but not an 8° threshold.
        val today = mildToday.copy(feelsLikeMaxC = 22.0, feelsLikeMinC = yesterday.feelsLikeMinC)
        subject(today, yesterday, emptyList(), deltaThresholdC = 8.0).delta.shouldBeNull()
        subject(today, yesterday, emptyList(), deltaThresholdC = 5.0).delta.shouldNotBeNull()
    }

    @Test
    fun `delta clause is omitted for the tonight period`() {
        // The morning pass already covered the yesterday-vs-today comparison; tonight
        // shouldn't repeat it even when the threshold is crossed.
        val today = mildToday.copy(feelsLikeMaxC = 22.0, feelsLikeMinC = yesterday.feelsLikeMinC)
        subject(today, yesterday, emptyList(), period = ForecastPeriod.TONIGHT).delta.shouldBeNull()
    }

    @Test
    fun `band-format delta names today's high band when it changes`() {
        // yesterday high 17 → COOL; today high 26 → WARM. Announces today's band.
        val today = mildToday.copy(feelsLikeMaxC = 26.0)
        val out = subject(today, yesterday, emptyList(), deltaFormat = DeltaFormat.BANDS).delta
        out.shouldNotBeNull()
        out!!.style shouldBe DeltaClause.Style.BANDS
        out.band shouldBe TemperatureBand.WARM
    }

    @Test
    fun `band-format delta names the cooler band when the high band drops`() {
        // yesterday high 26 → WARM; today high 17 → COOL.
        val warmYesterday = yesterday.copy(feelsLikeMaxC = 26.0)
        val today = mildToday.copy(feelsLikeMaxC = 17.0)
        val out = subject(today, warmYesterday, emptyList(), deltaFormat = DeltaFormat.BANDS).delta
        out.shouldNotBeNull()
        out!!.style shouldBe DeltaClause.Style.BANDS
        out.band shouldBe TemperatureBand.COOL
    }

    @Test
    fun `band-format delta is omitted when the high band is unchanged`() {
        // Both highs land in MILD (18–23.9): yesterday 17 is COOL, so bump it to 20.
        val mildYesterday = yesterday.copy(feelsLikeMaxC = 20.0)
        val today = mildToday.copy(feelsLikeMaxC = 23.0)
        subject(today, mildYesterday, emptyList(), deltaFormat = DeltaFormat.BANDS).delta.shouldBeNull()
    }

    @Test
    fun `band-format delta fires on a band crossing even below the degree threshold`() {
        // 17.9 → COOL, 18.1 → MILD: a 0.2° change the degree clause would never
        // surface, but it crosses a band boundary so band mode emits it.
        val coolYesterday = yesterday.copy(feelsLikeMaxC = 17.9)
        val today = mildToday.copy(feelsLikeMaxC = 18.1)
        val out = subject(today, coolYesterday, emptyList(), deltaFormat = DeltaFormat.BANDS).delta
        out.shouldNotBeNull()
        out!!.band shouldBe TemperatureBand.MILD
    }

    @Test
    fun `band-format delta is still omitted for the tonight period`() {
        val today = mildToday.copy(feelsLikeMaxC = 26.0)
        subject(
            today,
            yesterday,
            emptyList(),
            period = ForecastPeriod.TONIGHT,
            deltaFormat = DeltaFormat.BANDS,
        ).delta.shouldBeNull()
    }

    @Test
    fun `clothes clause carries items in rule order`() {
        val out = subject(mildToday, yesterday, listOf("sweater", "jacket", "umbrella"))
        out.clothes.shouldNotBeNull()
        out.clothes!!.items.shouldContainExactly("sweater", "jacket", "umbrella")
    }

    @Test
    fun `clothes clause is omitted when no rules trigger`() {
        subject(mildToday, yesterday, emptyList()).clothes.shouldBeNull()
    }

    @Test
    fun `clothes mention ALWAYS emits regardless of yesterday`() {
        val out = subject(
            mildToday,
            yesterday,
            listOf("sweater"),
            clothesMentionMode = ClothesMentionMode.ALWAYS,
            yesterdayTriggeredItems = listOf("sweater"),
        )
        out.clothes.shouldNotBeNull()
        out.clothes!!.items.shouldContainExactly("sweater")
    }

    @Test
    fun `clothes mention NEVER suppresses the clause even when rules fire`() {
        subject(
            mildToday,
            yesterday,
            listOf("sweater"),
            clothesMentionMode = ClothesMentionMode.NEVER,
        ).clothes.shouldBeNull()
    }

    @Test
    fun `carried accessories survive clothes-mention suppression`() {
        // Clothes = Never nulls the wear clause, but a fired umbrella rule still
        // rides on carriedAccessories so the formatter can fold "bring an
        // umbrella" into the precip clause regardless of the clothes setting.
        val out = subject(
            mildToday,
            yesterday,
            listOf("sweater", "umbrella"),
            clothesMentionMode = ClothesMentionMode.NEVER,
        )
        out.clothes.shouldBeNull()
        out.carriedAccessories.shouldContainExactly("umbrella")
    }

    @Test
    fun `clothes mention IF_CHANGED suppresses when items match yesterday`() {
        subject(
            mildToday,
            yesterday,
            listOf("sweater", "jacket"),
            clothesMentionMode = ClothesMentionMode.IF_CHANGED,
            // Same set, different case/whitespace — still counts as unchanged.
            yesterdayTriggeredItems = listOf(" Jacket ", "SWEATER"),
        ).clothes.shouldBeNull()
    }

    @Test
    fun `clothes mention IF_CHANGED emits when items differ from yesterday`() {
        val out = subject(
            mildToday,
            yesterday,
            listOf("sweater", "jacket"),
            clothesMentionMode = ClothesMentionMode.IF_CHANGED,
            yesterdayTriggeredItems = listOf("sweater"),
        )
        out.clothes.shouldNotBeNull()
        out.clothes!!.items.shouldContainExactly("sweater", "jacket")
    }

    @Test
    fun `clothes mention IF_CHANGED canonicalizes legacy aliases when comparing to yesterday`() {
        // Codex-flagged: yesterday fires a legacy "trousers" rule, today
        // falls back to the canonical "pants" default. Same garment
        // semantically (Garment.fromKey resolves both to PANTS), but a
        // raw lowercase comparison treats them as different and emits the
        // clause even though the outfit hasn't changed. Canonicalize via
        // Garment.fromKey().itemKey before comparing.
        subject(
            mildToday,
            yesterday,
            listOf("t-shirt", "pants"),
            clothesMentionMode = ClothesMentionMode.IF_CHANGED,
            yesterdayTriggeredItems = listOf("t-shirt", "trousers"),
        ).clothes.shouldBeNull()
    }

    @Test
    fun `clothes mention IF_CHANGED ignores a carried accessory difference`() {
        // The umbrella default fires today (rain likely) but not on the dry
        // yesterday. The worn garments are identical, so IF_CHANGED must
        // suppress the wear clause — the umbrella is not a wear change, it
        // rides the precip clause via carriedAccessories. Otherwise a rainy
        // mild day would emit a redundant baseline "Wear a t-shirt and pants."
        val out = subject(
            mildToday,
            yesterday,
            listOf("t-shirt", "pants", "umbrella"),
            clothesMentionMode = ClothesMentionMode.IF_CHANGED,
            yesterdayTriggeredItems = listOf("t-shirt", "pants"),
        )
        out.clothes.shouldBeNull()
        // The umbrella still surfaces independently for the precip clause.
        out.carriedAccessories.shouldContainExactly("umbrella")
    }

    @Test
    fun `clothes mention mode is ignored on TONIGHT`() {
        // TONIGHT has no yesterday-overnight comparison, so it always names
        // clothing regardless of mode.
        val out = subject(
            mildToday,
            yesterday,
            listOf("sweater"),
            period = ForecastPeriod.TONIGHT,
            clothesMentionMode = ClothesMentionMode.NEVER,
        )
        out.clothes.shouldNotBeNull()
        out.clothes!!.items.shouldContainExactly("sweater")
    }

    @Test
    fun `precip clause emits with peak hour and condition when chance clears the rain bar`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(9, 0), 18.0, 18.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
                HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN),
            ),
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.condition shouldBe WeatherCondition.RAIN
        out.time shouldBe LocalTime.of(15, 0)
    }

    @Test
    fun `precip clause falls back to noon when no hourly entry crosses the threshold`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 40.0,
            condition = WeatherCondition.DRIZZLE,
            hourly = emptyList(),
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.condition shouldBe WeatherCondition.DRIZZLE
        out.time shouldBe LocalTime.NOON
    }

    @Test
    fun `precip clause is omitted on a dry day`() {
        subject(mildToday, yesterday, emptyList()).precip.shouldBeNull()
    }

    @Test
    fun `precip clause coerces a high-POP cloudy peak hour to rain`() {
        // A 60% chance with an overcast code is rain by the numbers (high
        // probability, ~0 modeled accumulation). The umbrella rule and the
        // conditions strip already fire from the probability, so the prose agrees
        // instead of going silent on the dry code: the clause emits as RAIN.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.CLOUDY,
            hourly = listOf(
                HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.CLOUDY),
            ),
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.condition shouldBe WeatherCondition.RAIN
        out.time shouldBe LocalTime.of(15, 0)
        out.likelihood shouldBe PrecipLikelihood.LIKELY
    }

    @Test
    fun `noon fallback coerces a dry day-level chance to rain`() {
        // Day-level chance 40% with no hourly entries: the noon fallback fires, and
        // a non-precip day condition no longer drops the clause — 40% is a
        // chance-of-rain by the numbers, so it emits as RAIN at noon (POSSIBLE).
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 40.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
            hourly = emptyList(),
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.condition shouldBe WeatherCondition.RAIN
        out.time shouldBe LocalTime.NOON
        out.likelihood shouldBe PrecipLikelihood.POSSIBLE
    }

    @Test
    fun `precip clause is POSSIBLE in the 10 to 49 percent chance band`() {
        // The blended-consensus chance sits in the chance-of-rain band, so the
        // clause hedges as "chance of rain" (POSSIBLE), not a confident "rain".
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 30.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 18.0, 18.0, 30.0, WeatherCondition.RAIN)),
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.POSSIBLE
    }

    @Test
    fun `precip clause is POSSIBLE right at the 10 percent chance bar`() {
        // Inclusive boundary: exactly 10% fires the chance-of-rain clause, lining
        // up with the umbrella default's gate and the conditions strip.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 10.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 18.0, 18.0, 10.0, WeatherCondition.RAIN)),
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.POSSIBLE
    }

    @Test
    fun `precip clause is LIKELY at and above the 50 percent chance bar`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 50.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 18.0, 18.0, 50.0, WeatherCondition.RAIN)),
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.LIKELY
    }

    @Test
    fun `precip clause is omitted below the 10 percent chance bar`() {
        // 9% is under the chance-of-rain bar — nothing fires, even with a rain code.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 9.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 18.0, 18.0, 9.0, WeatherCondition.RAIN)),
        )
        subject(today, yesterday, emptyList()).precip.shouldBeNull()
    }

    @Test
    fun `evening event extras passes through whatever the caller built`() {
        // The renderer no longer composes the evening extras — GenerateDailyInsight
        // builds it by re-running the renderer against the night slice and
        // folding clothes + precip into the clause. The renderer just passes
        // it through.
        val extras = EveningEventExtrasClause(
            items = listOf("jacket"),
            rainTime = LocalTime.of(21, 0),
            likelihood = PrecipLikelihood.POSSIBLE,
        )
        val out = subject(
            today = mildToday,
            yesterday = yesterday,
            todayItems = emptyList(),
            eveningEventExtras = extras,
        )
        out.eveningEventExtras shouldBe extras
    }

    @Test
    fun `evening event extras defaults to null when caller doesn't supply one`() {
        subject(mildToday, yesterday, emptyList()).eveningEventExtras.shouldBeNull()
    }

    @Test
    fun `calendar extras is suppressed on the today period`() {
        // The morning insight no longer chains a "Bring an umbrella." sentence
        // after "Rain at 3pm." — the listener already knows
        // about their morning event and the bare precip clause is enough.
        // Tonight events get a separate evening-event extras via
        // [eveningEventExtras] when the user has the "Mention evening events"
        // setting on.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN)),
        )
        val event = CalendarEvent("standup", mildToday.date.atTime(14, 30), mildToday.date.atTime(16, 0))
        subject(today, yesterday, listOf("umbrella"), events = listOf(event)).calendarExtras.shouldBeNull()
    }

    @Test
    fun `calendar extras fires on a high-POP cloudy peak once it coerces to rain`() {
        // A cloudy peak at 60% now coerces to a RAIN precip clause (the single
        // number drives the prose), so an overlapping event plus the umbrella rule
        // motivate the extras — agreeing with the umbrella the rule already
        // suggests, rather than leaving it unanchored.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.CLOUDY,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.CLOUDY)),
        )
        val event = CalendarEvent("park run", tonightPeakDate.atTime(14, 30), tonightPeakDate.atTime(16, 0))
        val out = subject(
            today, yesterday, listOf("umbrella"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarExtras
        out.shouldNotBeNull()
        out!!.item shouldBe "umbrella"
    }

    @Test
    fun `full insight composes band + delta + clothes + precipitation`() {
        val today = mildToday.copy(
            feelsLikeMinC = 15.0,
            feelsLikeMaxC = 23.0,
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN),
            ),
        )
        val out = subject(
            today, yesterday,
            todayItems = listOf("sweater", "umbrella"),
        )
        out.band.low shouldBe TemperatureBand.COOL
        out.band.high shouldBe TemperatureBand.MILD
        out.delta!!.degrees shouldBe 6
        out.delta!!.direction shouldBe DeltaClause.Direction.WARMER
        out.clothes!!.items.shouldContainExactly("sweater", "umbrella")
        out.precip!!.condition shouldBe WeatherCondition.RAIN
        out.precip!!.time shouldBe LocalTime.of(15, 0)
        out.calendarExtras.shouldBeNull()
    }

    @Test
    fun `calendar extras is omitted when the only clothes items are tier defaults`() {
        // Codex-flagged: passing the full TriggeredOutfit.items into the
        // renderer (so the prose `clothes` clause can name the baseline
        // outfit on mild days) used to also feed the calendar extras,
        // which would then surface "Bring a t-shirt." on
        // any rainy mild evening with an overlapping event. The extras
        // takes a separate todayRuleItems list now — defaults aren't
        // "extras to bring," so they shouldn't trigger the clause.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN),
            ),
        )
        val event = CalendarEvent("park run", tonightPeakDate.atTime(14, 30), tonightPeakDate.atTime(16, 0))
        subject(
            today, yesterday, listOf("t-shirt", "pants"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
            todayRuleItems = emptyList(),
        ).calendarExtras.shouldBeNull()
    }

    @Test
    fun `calendar extras fires when clothes + precip + overlapping event all present`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN),
            ),
        )
        val event = CalendarEvent(
            title = "park run",
            start = tonightPeakDate.atTime(14, 30),
            end = tonightPeakDate.atTime(16, 0),
        )
        val out = subject(
            today, yesterday, listOf("umbrella"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarExtras
        out.shouldNotBeNull()
        out!!.item shouldBe "umbrella"
        // No time or title in the clause — the clause only carries the
        // clothes item now; the precip clause's own time covers "Rain at
        // 3pm.", and event titles never flow off-device via the rendered
        // prose.
    }

    @Test
    fun `calendar extras picks the first triggered item in rule order`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN)),
        )
        val event = CalendarEvent("park run", tonightPeakDate.atTime(14, 0), tonightPeakDate.atTime(16, 0))
        val out = subject(
            today, yesterday, listOf("sweater", "jacket"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarExtras
        out.shouldNotBeNull()
        out!!.item shouldBe "sweater"
    }

    @Test
    fun `calendar extras is omitted when no event overlaps the precip peak`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN)),
        )
        val event = CalendarEvent("breakfast", tonightPeakDate.atTime(8, 0), tonightPeakDate.atTime(9, 0))
        subject(
            today, yesterday, listOf("umbrella"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarExtras.shouldBeNull()
    }

    @Test
    fun `calendar extras is omitted when no clothes rule fires`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN)),
        )
        val event = CalendarEvent("park run", tonightPeakDate.atTime(14, 30), tonightPeakDate.atTime(16, 0))
        subject(
            today, yesterday, emptyList(),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarExtras.shouldBeNull()
    }

    @Test
    fun `calendar extras is omitted on a dry day even when an event exists`() {
        val event = CalendarEvent("park run", tonightPeakDate.atTime(11, 0), tonightPeakDate.atTime(13, 0))
        subject(
            mildToday, yesterday, listOf("umbrella"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarExtras.shouldBeNull()
    }

    @Test
    fun `calendar extras skips all-day events`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN)),
        )
        val holiday = CalendarEvent(
            "public holiday",
            mildToday.date.atStartOfDay(),
            mildToday.date.atStartOfDay(),
            allDay = true,
        )
        subject(
            today, yesterday, listOf("umbrella"),
            events = listOf(holiday),
            period = ForecastPeriod.TONIGHT,
        ).calendarExtras.shouldBeNull()
    }

    @Test
    fun `precip clause is LIKELY when the base hourly is the only signal`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN),
            ),
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.LIKELY
    }

    @Test
    fun `base fallback hedges a sub-50 percent rain code as a chance`() {
        // No per-model data (failed multi-model call / older cache): the base
        // hourly carries a 25% rain code. That's the chance-of-rain band, so the
        // clause must be POSSIBLE ("Chance of rain"), not a definite LIKELY "Rain".
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 25.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 18.0, 18.0, 25.0, WeatherCondition.RAIN)),
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.POSSIBLE
    }

    @Test
    fun `base fallback calls a majority-probability rain code LIKELY`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 18.0, 18.0, 60.0, WeatherCondition.RAIN)),
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.LIKELY
    }
    @Test
    fun `temperature band classifier covers all six bands at boundaries`() {
        TemperatureBand.forCelsius(-1.0) shouldBe TemperatureBand.FREEZING
        TemperatureBand.forCelsius(3.999) shouldBe TemperatureBand.FREEZING
        TemperatureBand.forCelsius(4.0) shouldBe TemperatureBand.COLD
        TemperatureBand.forCelsius(11.999) shouldBe TemperatureBand.COLD
        TemperatureBand.forCelsius(12.0) shouldBe TemperatureBand.COOL
        TemperatureBand.forCelsius(17.999) shouldBe TemperatureBand.COOL
        TemperatureBand.forCelsius(18.0) shouldBe TemperatureBand.MILD
        TemperatureBand.forCelsius(23.999) shouldBe TemperatureBand.MILD
        TemperatureBand.forCelsius(24.0) shouldBe TemperatureBand.WARM
        TemperatureBand.forCelsius(27.999) shouldBe TemperatureBand.WARM
        TemperatureBand.forCelsius(28.0) shouldBe TemperatureBand.HOT
        TemperatureBand.forCelsius(40.0) shouldBe TemperatureBand.HOT
    }
}
