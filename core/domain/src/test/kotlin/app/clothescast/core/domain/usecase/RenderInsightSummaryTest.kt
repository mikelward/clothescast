package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.DeltaFormat
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
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
    fun `precip clause emits with peak hour and condition when chance is at least 30 percent`() {
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
    fun `precip clause is omitted when the peak hour's condition is cloudy`() {
        // "Cloudy at 15:00" doesn't earn a clause: precipitation info is what the
        // user wants to hear, not haziness. A 30%+ probability with a non-precip
        // dominant condition gets dropped entirely.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.CLOUDY,
            hourly = listOf(
                HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.CLOUDY),
            ),
        )
        subject(today, yesterday, emptyList()).precip.shouldBeNull()
    }

    @Test
    fun `precip clause is omitted when the noon fallback condition is partly cloudy`() {
        // Day-level chance 40% but no hourly entry crosses 30%, so we'd normally fall
        // back to noon with today.condition. If today.condition is non-precip we still
        // drop the clause.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 40.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
            hourly = emptyList(),
        )
        subject(today, yesterday, emptyList()).precip.shouldBeNull()
    }

    @Test
    fun `evening event tie-in passes through whatever the caller built`() {
        // The renderer no longer composes the evening tie-in — GenerateDailyInsight
        // builds it by re-running the renderer against the night slice and
        // folding clothes + precip into the clause. The renderer just passes
        // it through.
        val tieIn = EveningEventTieInClause(
            items = listOf("jacket"),
            rainTime = LocalTime.of(21, 0),
            likelihood = PrecipLikelihood.POSSIBLE,
        )
        val out = subject(
            today = mildToday,
            yesterday = yesterday,
            todayItems = emptyList(),
            eveningEventTieIn = tieIn,
        )
        out.eveningEventTieIn shouldBe tieIn
    }

    @Test
    fun `evening event tie-in defaults to null when caller doesn't supply one`() {
        subject(mildToday, yesterday, emptyList()).eveningEventTieIn.shouldBeNull()
    }

    @Test
    fun `calendar tie-in is suppressed on the today period`() {
        // The morning insight no longer chains a "Bring an umbrella." sentence
        // after "Rain at 3pm." — the listener already knows
        // about their morning event and the bare precip clause is enough.
        // Tonight events get a separate evening-event tie-in via
        // [eveningEventTieIn] when the user has the "Mention evening events"
        // setting on.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN)),
        )
        val event = CalendarEvent("standup", LocalTime.of(14, 30), LocalTime.of(16, 0))
        subject(today, yesterday, listOf("umbrella"), events = listOf(event)).calendarTieIn.shouldBeNull()
    }

    @Test
    fun `calendar tie-in is omitted when the peak condition is non-precipitation`() {
        // Even with the umbrella rule firing on day-level probability and an event
        // overlapping the peak hour, a cloudy peak shouldn't motivate a tie-in —
        // there's no precipitation clause to anchor it to.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.CLOUDY,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.CLOUDY)),
        )
        val event = CalendarEvent("park run", LocalTime.of(14, 30), LocalTime.of(16, 0))
        subject(
            today, yesterday, listOf("umbrella"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarTieIn.shouldBeNull()
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
        out.calendarTieIn.shouldBeNull()
    }

    @Test
    fun `calendar tie-in is omitted when the only clothes items are tier defaults`() {
        // Codex-flagged: passing the full TriggeredOutfit.items into the
        // renderer (so the prose `clothes` clause can name the baseline
        // outfit on mild days) used to also feed the calendar tie-in,
        // which would then surface "Bring a t-shirt." on
        // any rainy mild evening with an overlapping event. The tie-in
        // takes a separate todayRuleItems list now — defaults aren't
        // "extras to bring," so they shouldn't trigger the clause.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN),
            ),
        )
        val event = CalendarEvent("park run", LocalTime.of(14, 30), LocalTime.of(16, 0))
        subject(
            today, yesterday, listOf("t-shirt", "pants"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
            todayRuleItems = emptyList(),
        ).calendarTieIn.shouldBeNull()
    }

    @Test
    fun `calendar tie-in fires when clothes + precip + overlapping event all present`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN),
            ),
        )
        val event = CalendarEvent(
            title = "park run",
            start = LocalTime.of(14, 30),
            end = LocalTime.of(16, 0),
        )
        val out = subject(
            today, yesterday, listOf("umbrella"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarTieIn
        out.shouldNotBeNull()
        out!!.item shouldBe "umbrella"
        // No time or title in the clause — the clause only carries the
        // clothes item now; the precip clause's own time covers "Rain at
        // 3pm.", and event titles never flow off-device via the rendered
        // prose.
    }

    @Test
    fun `calendar tie-in picks the first triggered item in rule order`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN)),
        )
        val event = CalendarEvent("park run", LocalTime.of(14, 0), LocalTime.of(16, 0))
        val out = subject(
            today, yesterday, listOf("sweater", "jacket"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarTieIn
        out.shouldNotBeNull()
        out!!.item shouldBe "sweater"
    }

    @Test
    fun `calendar tie-in is omitted when no event overlaps the precip peak`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN)),
        )
        val event = CalendarEvent("breakfast", LocalTime.of(8, 0), LocalTime.of(9, 0))
        subject(
            today, yesterday, listOf("umbrella"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarTieIn.shouldBeNull()
    }

    @Test
    fun `calendar tie-in is omitted when no clothes rule fires`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN)),
        )
        val event = CalendarEvent("park run", LocalTime.of(14, 30), LocalTime.of(16, 0))
        subject(
            today, yesterday, emptyList(),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarTieIn.shouldBeNull()
    }

    @Test
    fun `calendar tie-in is omitted on a dry day even when an event exists`() {
        val event = CalendarEvent("park run", LocalTime.of(11, 0), LocalTime.of(13, 0))
        subject(
            mildToday, yesterday, listOf("umbrella"),
            events = listOf(event),
            period = ForecastPeriod.TONIGHT,
        ).calendarTieIn.shouldBeNull()
    }

    @Test
    fun `calendar tie-in skips all-day events`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 60.0, WeatherCondition.RAIN)),
        )
        val holiday = CalendarEvent("public holiday", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, allDay = true)
        subject(
            today, yesterday, listOf("umbrella"),
            events = listOf(holiday),
            period = ForecastPeriod.TONIGHT,
        ).calendarTieIn.shouldBeNull()
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
    fun `precip clause is LIKELY when majority of three models hit 50 percent at the same hour`() {
        // The user's exact scenario, inverted: GFS + ECMWF both flag 10am wet,
        // ICON disagrees. Two of three is majority → confident "Rain at 10am."
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 30.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
            hourly = listOf(
                HourlyForecast(LocalTime.of(10, 0), 18.0, 18.0, 30.0, WeatherCondition.PARTLY_CLOUDY),
                HourlyForecast(LocalTime.of(15, 0), 22.0, 22.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
            ),
        )
        val perModel = perModelHourly(
            "gfs_seamless" to listOf(
                perModelHour(LocalTime.of(10, 0), 80.0),
                perModelHour(LocalTime.of(15, 0), 5.0),
            ),
            "ecmwf_ifs04" to listOf(
                perModelHour(LocalTime.of(10, 0), 70.0),
                perModelHour(LocalTime.of(15, 0), 5.0),
            ),
            "icon_seamless" to listOf(
                perModelHour(LocalTime.of(10, 0), 10.0),
                perModelHour(LocalTime.of(15, 0), 5.0),
            ),
        )
        val out = subject(today, yesterday, emptyList(), perModelHourly = perModel).precip
        out.shouldNotBeNull()
        out!!.time shouldBe LocalTime.of(10, 0)
        out.likelihood shouldBe PrecipLikelihood.LIKELY
        // Base condition at 10:00 is PARTLY_CLOUDY and the day-level is too,
        // so we default to RAIN — the per-model tier triggered on probability
        // alone and "Rain" reads honestly when the base under-called the type.
        out.condition shouldBe WeatherCondition.RAIN
    }

    @Test
    fun `precip clause is POSSIBLE when only one of three models hits 50 percent`() {
        // Exact mirror of the user's screenshot: GFS at ~80% at 10am, the others
        // stay near zero. Single-model disagreement → hedged "Chance of rain".
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 30.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
            hourly = listOf(
                HourlyForecast(LocalTime.of(10, 0), 18.0, 18.0, 30.0, WeatherCondition.PARTLY_CLOUDY),
            ),
        )
        val perModel = perModelHourly(
            "gfs_seamless" to listOf(perModelHour(LocalTime.of(10, 0), 80.0)),
            "ecmwf_ifs04" to listOf(perModelHour(LocalTime.of(10, 0), 5.0)),
            "icon_seamless" to listOf(perModelHour(LocalTime.of(10, 0), 10.0)),
        )
        val out = subject(today, yesterday, emptyList(), perModelHourly = perModel).precip
        out.shouldNotBeNull()
        out!!.time shouldBe LocalTime.of(10, 0)
        out.likelihood shouldBe PrecipLikelihood.POSSIBLE
        out.condition shouldBe WeatherCondition.RAIN
    }

    @Test
    fun `precip clause is POSSIBLE when a single model passes 30 but no model passes 50`() {
        // Soft signal — one model in the 30-49% band, others dry. Hedged form
        // wins; nobody crosses the LIKELY bar.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 30.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(11, 0), 18.0, 18.0, 30.0, WeatherCondition.RAIN),
            ),
        )
        val perModel = perModelHourly(
            "gfs_seamless" to listOf(perModelHour(LocalTime.of(11, 0), 40.0)),
            "ecmwf_ifs04" to listOf(perModelHour(LocalTime.of(11, 0), 5.0)),
            "icon_seamless" to listOf(perModelHour(LocalTime.of(11, 0), 5.0)),
        )
        val out = subject(today, yesterday, emptyList(), perModelHourly = perModel).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.POSSIBLE
    }

    @Test
    fun `precip clause is omitted when every model stays below 30 percent`() {
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 10.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
            hourly = listOf(
                HourlyForecast(LocalTime.of(10, 0), 18.0, 18.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
            ),
        )
        val perModel = perModelHourly(
            "gfs_seamless" to listOf(perModelHour(LocalTime.of(10, 0), 20.0)),
            "ecmwf_ifs04" to listOf(perModelHour(LocalTime.of(10, 0), 25.0)),
            "icon_seamless" to listOf(perModelHour(LocalTime.of(10, 0), 5.0)),
        )
        subject(today, yesterday, emptyList(), perModelHourly = perModel).precip.shouldBeNull()
    }

    @Test
    fun `precip clause is LIKELY when both of two available models hit 50 percent`() {
        // Majority of 2 is 2 (both). ECMWF dropped (its run wasn't ready in the
        // wider pipeline); GFS + ICON both at 60% is still majority agreement.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 20.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
            hourly = listOf(
                HourlyForecast(LocalTime.of(13, 0), 18.0, 18.0, 20.0, WeatherCondition.PARTLY_CLOUDY),
            ),
        )
        val perModel = perModelHourly(
            "gfs_seamless" to listOf(perModelHour(LocalTime.of(13, 0), 60.0)),
            "icon_seamless" to listOf(perModelHour(LocalTime.of(13, 0), 70.0)),
        )
        val out = subject(today, yesterday, emptyList(), perModelHourly = perModel).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.LIKELY
        out.time shouldBe LocalTime.of(13, 0)
    }

    @Test
    fun `precip clause is LIKELY when reporting models agree at a sparse hour`() {
        // Codex case: the lenient evening-tie-in slice keeps models with
        // only partial tonight-window coverage. Two models report at 02:00
        // and both hit ≥ LIKELY_THRESHOLD; the other two are in [byModel]
        // (they reported at other hours like 21:00) but have no 02:00
        // entry. Majority must be computed over the two that actually
        // reported at 02:00, not over the four in the map — otherwise the
        // genuine 2-of-2 consensus gets silently downgraded to POSSIBLE
        // because the absent models inflate `majorityNeeded` to 3.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 20.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
            hourly = listOf(
                HourlyForecast(LocalTime.of(2, 0), 8.0, 8.0, 20.0, WeatherCondition.PARTLY_CLOUDY),
            ),
        )
        val perModel = perModelHourly(
            // best_match + ECMWF report at 02:00, both wet.
            PerModelHourly.BEST_MATCH_MODEL_ID to listOf(perModelHour(LocalTime.of(2, 0), 80.0)),
            "ecmwf_ifs04" to listOf(perModelHour(LocalTime.of(2, 0), 75.0)),
            // GFS + ICON only have an unrelated 21:00 entry — no 02:00 data.
            "gfs_seamless" to listOf(perModelHour(LocalTime.of(21, 0), 5.0)),
            "icon_seamless" to listOf(perModelHour(LocalTime.of(21, 0), 10.0)),
        )
        val out = subject(today, yesterday, emptyList(), perModelHourly = perModel).precip
        out.shouldNotBeNull()
        out!!.time shouldBe LocalTime.of(2, 0)
        out.likelihood shouldBe PrecipLikelihood.LIKELY
    }

    @Test
    fun `precip clause is POSSIBLE when only one model reports a sparse hour`() {
        // Counterpart to the sparse-LIKELY test above: a single model
        // reports at 02:00 — even if it's at 80%, "one model says rain"
        // is the textbook POSSIBLE case the per-model tier exists to
        // express. The floor of two readings keeps a single-reading
        // sparse hour from getting promoted to LIKELY.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 10.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
            hourly = listOf(
                HourlyForecast(LocalTime.of(2, 0), 8.0, 8.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
            ),
        )
        val perModel = perModelHourly(
            "ecmwf_ifs04" to listOf(perModelHour(LocalTime.of(2, 0), 80.0)),
            // Other models cover other hours but not 02:00.
            "gfs_seamless" to listOf(perModelHour(LocalTime.of(21, 0), 5.0)),
            "icon_seamless" to listOf(perModelHour(LocalTime.of(21, 0), 10.0)),
        )
        val out = subject(today, yesterday, emptyList(), perModelHourly = perModel).precip
        out.shouldNotBeNull()
        out!!.time shouldBe LocalTime.of(2, 0)
        out.likelihood shouldBe PrecipLikelihood.POSSIBLE
    }

    @Test
    fun `precip clause is POSSIBLE when one of two available models hits 50 percent`() {
        // Majority of 2 is 2 — one passing isn't a majority, so the LIKELY
        // tier doesn't fire; the lone 60% still earns the hedged form.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 20.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(13, 0), 18.0, 18.0, 20.0, WeatherCondition.RAIN),
            ),
        )
        val perModel = perModelHourly(
            "gfs_seamless" to listOf(perModelHour(LocalTime.of(13, 0), 60.0)),
            "icon_seamless" to listOf(perModelHour(LocalTime.of(13, 0), 10.0)),
        )
        val out = subject(today, yesterday, emptyList(), perModelHourly = perModel).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.POSSIBLE
    }

    @Test
    fun `precip clause uses base hour condition when the peak hour itself is precipitating`() {
        // Per-model triggers LIKELY at 17:00 where the base hour says
        // THUNDERSTORM — we should keep THUNDERSTORM rather than defaulting
        // to RAIN, because the base carries a more specific code at the peak.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 80.0,
            condition = WeatherCondition.THUNDERSTORM,
            hourly = listOf(
                HourlyForecast(LocalTime.of(17, 0), 22.0, 22.0, 80.0, WeatherCondition.THUNDERSTORM),
            ),
        )
        val perModel = perModelHourly(
            "gfs_seamless" to listOf(perModelHour(LocalTime.of(17, 0), 80.0)),
            "ecmwf_ifs04" to listOf(perModelHour(LocalTime.of(17, 0), 70.0)),
            "icon_seamless" to listOf(perModelHour(LocalTime.of(17, 0), 60.0)),
        )
        val out = subject(today, yesterday, emptyList(), perModelHourly = perModel).precip
        out.shouldNotBeNull()
        out!!.condition shouldBe WeatherCondition.THUNDERSTORM
        out.likelihood shouldBe PrecipLikelihood.LIKELY
    }

    @Test
    fun `precip clause picks the wettest hour when several clear the LIKELY bar`() {
        // 10:00 has 2 models ≥ 50 (60, 55); 16:00 has 3 models ≥ 50 (90, 80, 70).
        // 16:00 wins because its max single-model reading is higher.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 70.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(10, 0), 18.0, 18.0, 50.0, WeatherCondition.RAIN),
                HourlyForecast(LocalTime.of(16, 0), 22.0, 22.0, 70.0, WeatherCondition.RAIN),
            ),
        )
        val perModel = perModelHourly(
            "gfs_seamless" to listOf(
                perModelHour(LocalTime.of(10, 0), 60.0),
                perModelHour(LocalTime.of(16, 0), 90.0),
            ),
            "ecmwf_ifs04" to listOf(
                perModelHour(LocalTime.of(10, 0), 55.0),
                perModelHour(LocalTime.of(16, 0), 80.0),
            ),
            "icon_seamless" to listOf(
                perModelHour(LocalTime.of(10, 0), 20.0),
                perModelHour(LocalTime.of(16, 0), 70.0),
            ),
        )
        val out = subject(today, yesterday, emptyList(), perModelHourly = perModel).precip
        out.shouldNotBeNull()
        out!!.time shouldBe LocalTime.of(16, 0)
        out.likelihood shouldBe PrecipLikelihood.LIKELY
    }

    @Test
    fun `precip clause is all-day when likely rain covers most of the daytime hours`() {
        // The screenshot scenario: a single combined series sits ≥ 50% every
        // hour from 07:00–18:00. No one hour represents the day, so the clause
        // should drop the single peak and flag all-day. (One series → the
        // per-model majority path can't fire; this exercises the base fallback.)
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 88.0,
            condition = WeatherCondition.RAIN,
            hourly = (7..18).map { hour ->
                HourlyForecast(LocalTime.of(hour, 0), 14.0, 14.0, 70.0, WeatherCondition.RAIN)
            },
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.LIKELY
        out.allDay shouldBe true
    }

    @Test
    fun `precip clause is all-day when likely rain falls in two separated spells`() {
        // Two distinct rainy runs with a dry gap between them — neither covers a
        // majority of the window (4 of 7 hours = 57%, under the coverage bar),
        // but two separated spells alone trip all-day per the "either" rule.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 80.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(8, 0), 14.0, 14.0, 70.0, WeatherCondition.RAIN),
                HourlyForecast(LocalTime.of(9, 0), 14.0, 14.0, 65.0, WeatherCondition.RAIN),
                HourlyForecast(LocalTime.of(10, 0), 14.0, 14.0, 10.0, WeatherCondition.RAIN),
                HourlyForecast(LocalTime.of(11, 0), 14.0, 14.0, 10.0, WeatherCondition.RAIN),
                HourlyForecast(LocalTime.of(12, 0), 14.0, 14.0, 10.0, WeatherCondition.RAIN),
                HourlyForecast(LocalTime.of(16, 0), 14.0, 14.0, 80.0, WeatherCondition.RAIN),
                HourlyForecast(LocalTime.of(17, 0), 14.0, 14.0, 75.0, WeatherCondition.RAIN),
            ),
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.allDay shouldBe true
    }

    @Test
    fun `precip clause names a single hour for one short rain spell`() {
        // One brief shower in an otherwise dry day — a single peak, not all-day.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 70.0,
            condition = WeatherCondition.RAIN,
            hourly = (10..18).map { hour ->
                val pct = if (hour == 14 || hour == 15) 65.0 else 10.0
                HourlyForecast(LocalTime.of(hour, 0), 14.0, 14.0, pct, WeatherCondition.RAIN)
            },
        )
        val out = subject(today, yesterday, emptyList()).precip
        out.shouldNotBeNull()
        out!!.allDay shouldBe false
        out.time shouldBe LocalTime.of(14, 0)
    }

    @Test
    fun `precip clause is all-day when the model majority is wet across most hours`() {
        // Per-model path: two of three models clear 50% at nearly every hour
        // 09:00–17:00, so the majority-agreement hours blanket the window.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 80.0,
            condition = WeatherCondition.RAIN,
            hourly = (9..17).map { hour ->
                HourlyForecast(LocalTime.of(hour, 0), 14.0, 14.0, 70.0, WeatherCondition.RAIN)
            },
        )
        val perModel = perModelHourly(
            "gfs_seamless" to (9..17).map { perModelHour(LocalTime.of(it, 0), 75.0) },
            "ecmwf_ifs04" to (9..17).map { perModelHour(LocalTime.of(it, 0), 65.0) },
            "icon_seamless" to (9..17).map { perModelHour(LocalTime.of(it, 0), 10.0) },
        )
        val out = subject(today, yesterday, emptyList(), perModelHourly = perModel).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.LIKELY
        out.allDay shouldBe true
    }

    @Test
    fun `precip clause is not all-day for the POSSIBLE tier`() {
        // A single model hits 80% across every hour while the others stay dry —
        // never a majority, so it stays POSSIBLE and keeps naming one hour even
        // though that lone model is wet all day.
        val today = mildToday.copy(
            precipitationProbabilityMaxPct = 30.0,
            condition = WeatherCondition.RAIN,
            hourly = (9..17).map { hour ->
                HourlyForecast(LocalTime.of(hour, 0), 14.0, 14.0, 30.0, WeatherCondition.RAIN)
            },
        )
        val perModel = perModelHourly(
            "gfs_seamless" to (9..17).map { perModelHour(LocalTime.of(it, 0), 80.0) },
            "ecmwf_ifs04" to (9..17).map { perModelHour(LocalTime.of(it, 0), 5.0) },
            "icon_seamless" to (9..17).map { perModelHour(LocalTime.of(it, 0), 5.0) },
        )
        val out = subject(today, yesterday, emptyList(), perModelHourly = perModel).precip
        out.shouldNotBeNull()
        out!!.likelihood shouldBe PrecipLikelihood.POSSIBLE
        out.allDay shouldBe false
    }

    // PerModelHour now carries a LocalDateTime — paired against an arbitrary
    // anchor date so the test fixtures don't have to thread a date through
    // every call. The renderer reads `time.toLocalTime()` at output points;
    // the date is only used internally for tonight-wrap disambiguation,
    // which these tests don't exercise.
    private val perModelDate: java.time.LocalDate = java.time.LocalDate.of(2026, 5, 13)

    private fun perModelHour(time: LocalTime, precipPct: Double): PerModelHour =
        PerModelHour(
            time = java.time.LocalDateTime.of(perModelDate, time),
            apparentTemperatureC = 18.0,
            temperatureC = 18.0,
            precipitationProbabilityPct = precipPct,
        )

    private fun perModelHourly(vararg models: Pair<String, List<PerModelHour>>): PerModelHourly =
        PerModelHourly(models.toMap())

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
