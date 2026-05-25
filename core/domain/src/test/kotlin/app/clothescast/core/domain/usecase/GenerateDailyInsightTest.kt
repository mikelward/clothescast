package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.UpcomingCalendarEvent
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.core.domain.repository.ForecastBundle
import app.clothescast.core.domain.repository.WeatherRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class GenerateDailyInsightTest {
    private val clockInstant = Instant.parse("2026-04-25T07:00:00Z")
    private val clock = Clock.fixed(clockInstant, ZoneOffset.UTC)

    private val london = Location(latitude = 51.5074, longitude = -0.1278, displayName = "London")

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

    private val today = DailyForecast(
        date = LocalDate.of(2026, 4, 25),
        temperatureMinC = 8.0,
        temperatureMaxC = 25.0,
        feelsLikeMinC = 6.0,
        feelsLikeMaxC = 25.0,
        precipitationProbabilityMaxPct = 60.0,
        precipitationMmTotal = 4.5,
        condition = WeatherCondition.RAIN,
    )

    private val prefs = UserPreferences(
        schedule = Schedule.default(ZoneOffset.UTC),
        deliveryMode = DeliveryMode.NOTIFICATION_ONLY,
        temperatureUnit = TemperatureUnit.CELSIUS,
        distanceUnit = DistanceUnit.KILOMETERS,
        clothesRules = ClothesRule.DEFAULTS,
        // Master calendar switch on; individual cases flip useCalendarEvents to
        // exercise the per-feature gate (events read only when both are on).
        calendarEnabled = true,
    )

    private class FakeWeatherRepository(private val bundle: ForecastBundle) : WeatherRepository {
        var lastQueriedLocation: Location? = null
            private set

        override suspend fun fetchForecast(location: Location): ForecastBundle {
            lastQueriedLocation = location
            return bundle
        }
    }

    private class FakeCalendarEventReader(
        private val events: List<CalendarEvent> = emptyList(),
        private val throws: Throwable? = null,
    ) : CalendarEventReader {
        var lastDate: LocalDate? = null
            private set
        var lastZone: ZoneId? = null
            private set

        override suspend fun eventsForDay(date: LocalDate, zoneId: ZoneId): List<CalendarEvent> {
            lastDate = date
            lastZone = zoneId
            throws?.let { throw it }
            return events
        }

        override suspend fun upcomingCelebrations(
            startInclusive: LocalDate,
            endExclusive: LocalDate,
            zoneId: ZoneId,
        ): List<UpcomingCalendarEvent> = emptyList()
    }

    @Test
    fun `produces insight with rule items, deterministic summary, clock-based timestamp, today's date`() = runTest {
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val insight = subject(london, prefs).insight

        // today: feels-like 6→25 → cold to warm; +8°C high vs yesterday → 8° warmer;
        // clothes defaults at this temperature: sweater, jacket, shorts (umbrella was
        // dropped — the precip clause carries the rain message);
        // 60% precipitation → noon fallback (no hourly entries on `today`).
        insight.summary.band.low shouldBe TemperatureBand.COLD
        insight.summary.band.high shouldBe TemperatureBand.WARM
        insight.summary.delta.shouldNotBeNull()
        insight.summary.delta!!.degrees shouldBe 8
        insight.summary.delta!!.direction shouldBe DeltaClause.Direction.WARMER
        insight.summary.clothes!!.items.shouldContainExactly("sweater", "jacket", "shorts")
        insight.summary.precip!!.condition shouldBe WeatherCondition.RAIN
        insight.summary.precip!!.time shouldBe LocalTime.NOON
        insight.recommendedItems.shouldContainExactly("sweater", "jacket", "shorts")
        insight.generatedAt shouldBe clockInstant
        insight.forDate shouldBe today.date
    }

    @Test
    fun `IF_CHANGED suppresses the clothes clause when clothing matches yesterday, keeping the outfit`() = runTest {
        // Both days trigger only "sweater" (feels-like 15→17): unchanged.
        val sameYesterday = yesterday.copy(feelsLikeMinC = 15.0, feelsLikeMaxC = 17.0)
        val sameToday = today.copy(
            feelsLikeMinC = 15.0,
            feelsLikeMaxC = 17.0,
            precipitationProbabilityMaxPct = 5.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
        )
        val weather = FakeWeatherRepository(ForecastBundle(sameToday, sameYesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val insight = subject(london, prefs.copy(clothesMentionMode = ClothesMentionMode.IF_CHANGED)).insight

        insight.summary.clothes.shouldBeNull()
        // Prose is suppressed, but the recommendation and outfit card stand.
        // No threshold rule matches in the bottom tier, so the engine
        // resolves it via the user's default bottom rule ("pants") alongside
        // the matching sweater rule.
        insight.recommendedItems.shouldContainExactly("sweater", "pants")
        insight.outfit.shouldNotBeNull()
    }

    @Test
    fun `IF_CHANGED emits the clothes clause when clothing differs from yesterday`() = runTest {
        // yesterday (10→17) triggers sweater + jacket; today (6→25) adds shorts.
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val insight = subject(london, prefs.copy(clothesMentionMode = ClothesMentionMode.IF_CHANGED)).insight

        insight.summary.clothes!!.items.shouldContainExactly("sweater", "jacket", "shorts")
    }

    @Test
    fun `NEVER suppresses the clothes clause but keeps the recommendation and outfit`() = runTest {
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val insight = subject(london, prefs.copy(clothesMentionMode = ClothesMentionMode.NEVER)).insight

        insight.summary.clothes.shouldBeNull()
        insight.recommendedItems.shouldContainExactly("sweater", "jacket", "shorts")
        insight.outfit.shouldNotBeNull()
    }

    @Test
    fun `forwards location to weather repository`() = runTest {
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        subject(london, prefs)

        weather.lastQueriedLocation shouldBe london
    }

    @Test
    fun `recommended items resolve to the per-tier default rule when no threshold rule matches`() = runTest {
        // Feels-like 19-22°C sits in the comfort gap of DEFAULTS (sweater <18°C,
        // jacket <12°C, coat <6°C, shorts >24°C) — no threshold rule in
        // either tier matches. The engine resolves both slots via the user's
        // per-tier default rules (defaultTop + defaultBottom).
        val mildToday = today.copy(
            temperatureMinC = 19.0,
            temperatureMaxC = 22.0,
            feelsLikeMinC = 19.0,
            feelsLikeMaxC = 22.0,
            precipitationProbabilityMaxPct = 10.0,
        )
        val weather = FakeWeatherRepository(ForecastBundle(mildToday, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs)

        result.insight.recommendedItems.shouldContainExactly("t-shirt", "pants")
    }

    @Test
    fun `ALWAYS names the outfit baseline on a mild day when only the default rule matches`() = runTest {
        // Feels-like 19-22°C sits in the comfort gap between sweater (<18°C)
        // and shorts (>24°C), so no threshold rule matches. The per-tier
        // defaults (defaultTop + defaultBottom) are themselves rules — their
        // condition is "no threshold rule in my tier matched today" — and
        // the engine surfaces them in `recommendedItems` so "Always" lives
        // up to its name and the prose, the bulleted list, and the icon all
        // agree.
        val mildToday = today.copy(
            temperatureMinC = 19.0,
            temperatureMaxC = 22.0,
            feelsLikeMinC = 19.0,
            feelsLikeMaxC = 22.0,
            precipitationProbabilityMaxPct = 10.0,
        )
        val weather = FakeWeatherRepository(ForecastBundle(mildToday, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val insight = subject(london, prefs).insight

        insight.summary.clothes!!.items.shouldContainExactly("t-shirt", "pants")
        insight.recommendedItems.shouldContainExactly("t-shirt", "pants")
        insight.outfit shouldBe OutfitSuggestion(
            top = OutfitSuggestion.Top.TSHIRT,
            bottom = OutfitSuggestion.Bottom.LONG_PANTS,
        )
    }

    @Test
    fun `warm day with shorts rule and t-shirt fallback lists the t-shirt first`() = runTest {
        // Feels-like 22-28°C trips the shorts >24°C threshold but no top
        // threshold rule fires, so the t-shirt comes from the default
        // fallback. Items put tops before bottoms regardless of which
        // clause produced them, so the prose's article picker lands on the
        // singular t-shirt ("Wear a t-shirt and shorts.") rather than the
        // leading plural shorts ("Wear shorts and t-shirt." — the regression
        // this guards against).
        val hotToday = today.copy(
            temperatureMinC = 22.0,
            temperatureMaxC = 28.0,
            feelsLikeMinC = 22.0,
            feelsLikeMaxC = 28.0,
            precipitationProbabilityMaxPct = 10.0,
        )
        val weather = FakeWeatherRepository(ForecastBundle(hotToday, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val insight = subject(london, prefs).insight

        insight.summary.clothes!!.items.shouldContainExactly("t-shirt", "shorts")
        insight.recommendedItems.shouldContainExactly("t-shirt", "shorts")
    }

    @Test
    fun `outfit baseline honours the user-selected default bottom`() = runTest {
        // A denim-everyday user flips defaultBottom to JEANS in Settings; the
        // default rule that resolves the bottom slot should match the
        // home-screen icon — so "jeans" not "pants" — without the user
        // adding any threshold rules.
        val mildToday = today.copy(
            temperatureMinC = 19.0,
            temperatureMaxC = 22.0,
            feelsLikeMinC = 19.0,
            feelsLikeMaxC = 22.0,
            precipitationProbabilityMaxPct = 10.0,
        )
        val weather = FakeWeatherRepository(ForecastBundle(mildToday, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val insight = subject(
            london,
            prefs.copy(defaultBottom = OutfitSuggestion.Bottom.JEANS),
        ).insight

        insight.summary.clothes!!.items.shouldContainExactly("t-shirt", "jeans")
    }

    @Test
    fun `base layer is suppressed from prose and recommendations when a mid layer fires`() = runTest {
        // A user has both a "wear a t-shirt below 30°C" rule (BASE) and the
        // default jumper / jeans rules. On a 14°C morning every one of those
        // rules fires, but the t-shirt is implicit under the jumper — the
        // insight should read "Wear a jumper and jeans." not "Wear a jumper,
        // t-shirt, and jeans." The same suppression drives the bulleted
        // recommendation list and the cached outfit snapshot, so the prose,
        // the bullets, and the home-screen card all agree.
        val coldToday = today.copy(
            temperatureMinC = 10.0,
            temperatureMaxC = 14.0,
            feelsLikeMinC = 10.0,
            feelsLikeMaxC = 14.0,
            precipitationProbabilityMaxPct = 10.0,
        )
        val rules = listOf(
            ClothesRule("t-shirt", ClothesRule.TemperatureBelow(30.0)),
            ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)),
            ClothesRule("jeans", ClothesRule.TemperatureBelow(20.0)),
        )
        val weather = FakeWeatherRepository(ForecastBundle(coldToday, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val insight = subject(london, prefs.copy(clothesRules = rules)).insight

        insight.summary.clothes!!.items.shouldContainExactly("sweater", "jeans")
        insight.recommendedItems.shouldContainExactly("sweater", "jeans")
    }

    @Test
    fun `within-shell-layer also-rans are dropped on a very cold day`() = runTest {
        // Default rules: sweater (<18), jacket (<12), coat (<6), shorts (>24).
        // At feels-like 2-8°C all three cold rules fire — sweater on MID,
        // jacket + coat both on SHELL. The user only wears one outer layer,
        // so the engine picks coat (heaviest tier) and silently drops jacket
        // rather than read out every match. Verified at the integration
        // boundary so the prose + recommendations stay in sync with the
        // home-screen icon, which already picks the shell-most top.
        val freezingToday = today.copy(
            temperatureMinC = 2.0,
            temperatureMaxC = 8.0,
            feelsLikeMinC = 2.0,
            feelsLikeMaxC = 8.0,
            precipitationProbabilityMaxPct = 10.0,
        )
        val weather = FakeWeatherRepository(ForecastBundle(freezingToday, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val insight = subject(london, prefs).insight

        insight.summary.clothes!!.items.shouldContainExactly("sweater", "coat", "pants")
        insight.recommendedItems.shouldContainExactly("sweater", "coat", "pants")
    }

    @Test
    fun `severe alerts are surfaced in result and woven into the summary`() = runTest {
        val severe = WeatherAlert(
            event = "Severe Thunderstorm Warning",
            severity = AlertSeverity.SEVERE,
            headline = "Damaging hail expected",
            description = null,
            onset = clockInstant,
            expires = clockInstant.plusSeconds(3600),
        )
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday, alerts = listOf(severe)))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs)

        result.alerts.shouldContainExactly(severe)
        result.insight.summary.alert!!.event shouldBe "Severe Thunderstorm Warning"
    }

    @Test
    fun `today's hourly forecast is forwarded into the produced insight`() = runTest {
        val hourly = listOf(
            HourlyForecast(
                time = LocalTime.of(8, 0),
                temperatureC = 10.0,
                feelsLikeC = 8.0,
                precipitationProbabilityPct = 5.0,
                condition = WeatherCondition.CLEAR,
            ),
            HourlyForecast(
                time = LocalTime.of(15, 0),
                temperatureC = 24.0,
                feelsLikeC = 24.0,
                precipitationProbabilityPct = 70.0,
                condition = WeatherCondition.RAIN,
            ),
        )
        val todayWithHourly = today.copy(hourly = hourly)
        val weather = FakeWeatherRepository(ForecastBundle(todayWithHourly, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs)

        result.insight.hourly.shouldContainExactly(hourly)
    }

    @Test
    fun `calendar reader is consulted for today's date and zone when opted in`() = runTest {
        val zone = ZoneId.of("Europe/London")
        val event = CalendarEvent(
            title = "park run",
            start = LocalTime.of(14, 30),
            end = LocalTime.of(16, 0),
        )
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(event))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val result = subject(
            location = london,
            prefs = prefs.copy(
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
        )

        calendar.lastDate shouldBe today.date
        calendar.lastZone shouldBe zone
        // The morning rain tie-in is suppressed on TODAY (PR #149); the tonight
        // pass still carries the existing CalendarTieInClause — see the
        // `tonight period` test below for the firing case. This test only cares
        // that the reader was consulted with the right (date, zone).
        result.insight.summary.calendarTieIn.shouldBeNull()
    }

    @Test
    fun `calendar reader is not consulted when the toggle is off`() = runTest {
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(
            CalendarEvent("standup", LocalTime.of(9, 0), LocalTime.of(9, 30)),
        ))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val result = subject(london, prefs.copy(useCalendarEvents = false))

        calendar.lastDate.shouldBeNull()
        result.insight.summary.calendarTieIn.shouldBeNull()
    }

    @Test
    fun `calendar reader failure degrades to no events without failing the insight`() = runTest {
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val calendar = FakeCalendarEventReader(throws = SecurityException("permission denied"))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val result = subject(london, prefs.copy(useCalendarEvents = true))

        // The summary still composes — the band clause is always present (it's non-null
        // by the type system) — we just lose the calendar tie-in.
        result.insight.summary.calendarTieIn.shouldBeNull()
    }

    @Test
    fun `today period slices hourly to the daytime window so peak precip ignores past dawn and late evening`() = runTest {
        val todayHourly = listOf(
            // Pre-morning: 6:00 with 80% rain — already past, must not surface.
            HourlyForecast(LocalTime.of(6, 0), 8.0, 6.0, 80.0, WeatherCondition.RAIN),
            // In-window: 09:00 quiet, 15:00 rain at 35%.
            HourlyForecast(LocalTime.of(9, 0), 14.0, 12.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 20.0, 35.0, WeatherCondition.RAIN),
            // Late evening: 23:00 cloudy with 60% precip — user is asleep, must not
            // be the headline for the daytime insight. (Cloudy conditions never
            // earn a precip clause anyway, but the slicing assertion still cares
            // that this entry doesn't reach `peakPrecip` in the first place.)
            HourlyForecast(LocalTime.of(23, 0), 12.0, 10.0, 60.0, WeatherCondition.CLOUDY),
        )
        val todayWithHourly = today.copy(hourly = todayHourly)
        val weather = FakeWeatherRepository(ForecastBundle(todayWithHourly, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs)

        // Only hours in [07:00, 19:00) survive — the 06:00 and 23:00 entries are dropped.
        result.insight.hourly.map { it.time } shouldBe listOf(LocalTime.of(9, 0), LocalTime.of(15, 0))
        // Peak precip is the wettest in-window hour (15:00, rain at 35%) — not the
        // 23:00 spike, which is what the pre-slice peak would have surfaced.
        result.insight.summary.precip!!.condition shouldBe WeatherCondition.RAIN
        result.insight.summary.precip!!.time shouldBe LocalTime.of(15, 0)
    }

    @Test
    fun `today period recomputes aggregates from the daytime slice, not from the day-level fields`() = runTest {
        val todayHourly = listOf(
            // The day-level feelsLikeMinC on `today` is 6.0°C — but that's the pre-dawn
            // low. The actual daytime range is 12→20°C, which is what the band sentence
            // and clothes rules should see.
            HourlyForecast(LocalTime.of(6, 0), 8.0, 6.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(9, 0), 14.0, 12.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 20.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(23, 0), 6.0, 4.0, 5.0, WeatherCondition.CLEAR),
        )
        val todayWithHourly = today.copy(hourly = todayHourly)
        val weather = FakeWeatherRepository(ForecastBundle(todayWithHourly, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs)

        // Daytime feels-like 12→20 → COOL to MILD. The 6.0°C / 4.0°C edges of the day
        // are correctly ignored.
        result.insight.summary.band.low shouldBe TemperatureBand.COOL
        result.insight.summary.band.high shouldBe TemperatureBand.MILD
    }

    @Test
    fun `today period trims a one-hour exposure buffer off each edge of the band min and max`() = runTest {
        // Notification window is [07:00, 19:00) by default, but the user isn't outside
        // at 7am — they're getting dressed. A sharp pre-dawn dip at 07:00 (feels-like
        // 4°C) and a still-warm reading at 18:00 (24°C) would otherwise widen the
        // band to COLD..WARM, dragging the recommendation toward a jacket the user
        // doesn't need by 8am. The exposure buffer trims those edge hours, so the
        // band reflects what the user actually feels between 08:00 and 18:00.
        val todayHourly = listOf(
            HourlyForecast(LocalTime.of(7, 0), 6.0, 4.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(9, 0), 14.0, 12.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 20.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(18, 0), 26.0, 24.0, 5.0, WeatherCondition.CLEAR),
        )
        val todayWithHourly = today.copy(hourly = todayHourly)
        val weather = FakeWeatherRepository(ForecastBundle(todayWithHourly, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs)

        result.insight.summary.band.feelsLikeMinC shouldBe 12.0
        result.insight.summary.band.feelsLikeMaxC shouldBe 20.0
        result.insight.summary.band.low shouldBe TemperatureBand.COOL
        result.insight.summary.band.high shouldBe TemperatureBand.MILD
    }

    @Test
    fun `today period still surfaces rain in the buffered edge hours`() = runTest {
        // The exposure buffer narrows the band's min/max, but peak precipitation
        // still walks the full [morningStart, eveningEnd) slice. A 7:30am-ish
        // shower (7am hour, 80% rain) is exactly the warning the morning insight
        // exists to deliver, even though the user isn't outside until 8.
        val todayHourly = listOf(
            HourlyForecast(LocalTime.of(7, 0), 12.0, 11.0, 80.0, WeatherCondition.RAIN),
            HourlyForecast(LocalTime.of(9, 0), 14.0, 12.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 20.0, 5.0, WeatherCondition.CLEAR),
        )
        val todayWithHourly = today.copy(hourly = todayHourly)
        val weather = FakeWeatherRepository(ForecastBundle(todayWithHourly, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs)

        result.insight.summary.precip.shouldNotBeNull()
        result.insight.summary.precip!!.time shouldBe LocalTime.of(7, 0)
        result.insight.summary.precip!!.condition shouldBe WeatherCondition.RAIN
    }

    @Test
    fun `today period falls back to day-level fields when no hourly is available`() = runTest {
        // The default fixture has no hourly array; the slice is empty and the band
        // sentence should still come through from the daily aggregates rather than
        // collapsing to defaults.
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs)

        result.insight.summary.band.low shouldBe TemperatureBand.COLD
        result.insight.summary.band.high shouldBe TemperatureBand.WARM
    }

    @Test
    fun `delta compares daytime-vs-daytime when both sides have hourly data`() = runTest {
        // Scenario: yesterday and today share the same daytime profile (12°C low,
        // 20°C high in the 07:00–19:00 window) but yesterday had an unusually warm
        // overnight hour at 18°C that bumped its 24h min above today's pre-dawn
        // 4°C. A 24h-vs-24h comparison would emit "14° warmer" on the low — a
        // spurious headline, since the listener experiences the daytime, not the
        // overnight. With the fix, both sides slice to the same daytime window
        // and the deltas come out near zero.
        val todayHourly = listOf(
            HourlyForecast(LocalTime.of(4, 0), 6.0, 4.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(9, 0), 14.0, 12.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 20.0, 5.0, WeatherCondition.CLEAR),
        )
        val yesterdayHourly = listOf(
            HourlyForecast(LocalTime.of(2, 0), 20.0, 18.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(9, 0), 14.0, 12.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 20.0, 5.0, WeatherCondition.CLEAR),
        )
        val sameDaytimeToday = today.copy(
            feelsLikeMinC = 4.0,
            feelsLikeMaxC = 20.0,
            hourly = todayHourly,
        )
        val sameDaytimeYesterday = yesterday.copy(
            feelsLikeMinC = 18.0,
            feelsLikeMaxC = 20.0,
            hourly = yesterdayHourly,
        )
        val weather = FakeWeatherRepository(ForecastBundle(sameDaytimeToday, sameDaytimeYesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs)

        // Daytime delta: high = 20 - 20 = 0, low = 12 - 12 = 0 → no clause.
        // (24h delta would have been low = 4 - 18 = -14, emitting a spurious
        // "14° cooler" — exactly what the daytime slice is meant to suppress.)
        result.insight.summary.delta.shouldBeNull()
    }

    @Test
    fun `delta falls back to 24h fields when yesterday lacks hourly data`() = runTest {
        // Legacy / sparse fixtures (yesterday with no hourly entries) can't be
        // sliced to a daytime window. The use case falls back to comparing 24h
        // fields on both sides, keeping the comparison symmetric.
        val sameDayHourly = listOf(
            HourlyForecast(LocalTime.of(9, 0), 14.0, 12.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 20.0, 5.0, WeatherCondition.CLEAR),
        )
        val sameDayToday = today.copy(
            feelsLikeMinC = 4.0,
            feelsLikeMaxC = 20.0,
            hourly = sameDayHourly,
        )
        // Yesterday: same 24h aggregates, no hourly — the fallback path.
        val sameDayYesterday = yesterday.copy(
            feelsLikeMinC = 4.0,
            feelsLikeMaxC = 20.0,
        )
        val weather = FakeWeatherRepository(ForecastBundle(sameDayToday, sameDayYesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs)

        // 24h delta: high = 20 - 20 = 0, low = 4 - 4 = 0 → no clause.
        result.insight.summary.delta.shouldBeNull()
    }

    @Test
    fun `confidence falls back to the bundle aggregate when perModelHourly is absent`() = runTest {
        // Older cached bundles only carry the daily-endpoint confidence,
        // not the per-model hourly. In that case the insight should still
        // surface the bundle's day-scope aggregate rather than nothing.
        val info = ConfidenceInfo(
            level = ForecastConfidence.LOW,
            tempSpreadC = 4.2,
            precipSpreadPp = 35.0,
            modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless"),
        )
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday, confidence = info))
        val subject = GenerateDailyInsight(weather, clock = clock)

        subject(london, prefs, ForecastPeriod.TODAY).insight.confidence shouldBe info
        subject(london, prefs, ForecastPeriod.TONIGHT).insight.confidence shouldBe info
    }

    @Test
    fun `confidence is null when perModelHourly is present but the window slice is sparse`() = runTest {
        // perModelHourly carries only one consulted model (the other is
        // missing from the bundle) — the windowed compute returns null.
        // We must *not* fall back to bundle.confidence here, because the
        // bundle's value is full-calendar-day-scope and would silently
        // reintroduce the window mismatch this PR is fixing. The chip
        // treats null as "unknown" and hides itself, which is the
        // honest answer.
        val oneModel = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    PerModelHour(
                        time = LocalDateTime.of(today.date, LocalTime.of(12, 0)),
                        apparentTemperatureC = 18.0,
                        temperatureC = 17.0,
                        precipitationProbabilityPct = 10.0,
                    ),
                ),
            ),
        )
        val staleBundleConfidence = ConfidenceInfo(
            level = ForecastConfidence.LOW,
            tempSpreadC = 6.0,
            precipSpreadPp = 40.0,
            modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless"),
        )
        val weather = FakeWeatherRepository(
            ForecastBundle(today, yesterday, confidence = staleBundleConfidence, perModelHourly = oneModel),
        )
        val subject = GenerateDailyInsight(weather, clock = clock)
        subject(london, prefs, ForecastPeriod.TODAY).insight.confidence.shouldBeNull()
    }

    @Test
    fun `confidence is recomputed over the rendered window when perModelHourly is present`() = runTest {
        // Disagreeing daytime hours (HIGH-tier-busting temp spread) + a
        // tight late-evening peak. TODAY is rendered over the daytime
        // slice so its confidence should reflect the wide daytime spread
        // (LOW). The bundle's full-day aggregate is *not* what the chip
        // should see — that's the whole point of the windowed compute.
        val ecmwf = (0..23).map { h ->
            PerModelHour(
                time = LocalDateTime.of(today.date, LocalTime.of(h, 0)),
                apparentTemperatureC = if (h in 10..16) 18.0 else 14.0,
                temperatureC = 14.0,
                precipitationProbabilityPct = 10.0,
            )
        }
        val gfs = (0..23).map { h ->
            PerModelHour(
                time = LocalDateTime.of(today.date, LocalTime.of(h, 0)),
                apparentTemperatureC = if (h in 10..16) 24.5 else 14.5,
                temperatureC = 14.0,
                precipitationProbabilityPct = 10.0,
            )
        }
        val bundleHourly = PerModelHourly(
            byModel = mapOf("ecmwf_ifs04" to ecmwf, "gfs_seamless" to gfs),
        )
        val todayWithHourly = today.copy(
            hourly = listOf(
                HourlyForecast(LocalTime.of(8, 0), 14.0, 14.0, 10.0, WeatherCondition.CLEAR),
                HourlyForecast(LocalTime.of(12, 0), 21.0, 21.0, 10.0, WeatherCondition.CLEAR),
                HourlyForecast(LocalTime.of(15, 0), 21.0, 21.0, 10.0, WeatherCondition.CLEAR),
            ),
        )
        // Bundle's confidence is a HIGH-tier daily aggregate that doesn't
        // match the daytime-window spread — confirms the insight picks
        // the windowed value, not the bundle's fallback.
        val staleBundleConfidence = ConfidenceInfo(
            level = ForecastConfidence.HIGH,
            tempSpreadC = 0.5,
            precipSpreadPp = 5.0,
            modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless"),
        )
        val weather = FakeWeatherRepository(
            ForecastBundle(
                todayWithHourly,
                yesterday,
                confidence = staleBundleConfidence,
                perModelHourly = bundleHourly,
            ),
        )
        val subject = GenerateDailyInsight(weather, clock = clock)

        val info = subject(london, prefs, ForecastPeriod.TODAY).insight.confidence.shouldNotBeNull()
        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (6.5 plusOrMinus 0.0001)
    }

    @Test
    fun `per-model hourly rides the today insight but is stripped from tonight`() = runTest {
        // The bundle's per-model hourly covers two days (forecast_days=2); the
        // daytime window slice on TODAY should drop everything outside the
        // morning–evening window so the chart overlays line up with the
        // blended hourly that the rest of the screen renders. TONIGHT drops
        // the field entirely — today's per-model series + the tonight wrap
        // both describe today, not the night ahead.
        val ecmwfFull = perModelDay(today.date, 10.0, 12.0, 5.0) +
            perModelDay(today.date.plusDays(1), 8.0, 10.0, 4.0)
        val gfsFull = perModelDay(today.date, 11.0, 13.0, 6.0) +
            perModelDay(today.date.plusDays(1), 9.0, 11.0, 5.0)
        val bundleHourly = PerModelHourly(
            byModel = mapOf("ecmwf_ifs04" to ecmwfFull, "gfs_seamless" to gfsFull),
        )
        // Today's blended hourly slice — the chart plots overlays at the same
        // indices as this list, so the test asserts the overlay times match
        // these times exactly.
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 12.0, 11.0, 10.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(12, 0), 18.0, 17.0, 30.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 21.0, 40.0, WeatherCondition.CLEAR),
        )
        val todayWithHourly = today.copy(hourly = daytime)
        val weather = FakeWeatherRepository(
            ForecastBundle(todayWithHourly, yesterday, perModelHourly = bundleHourly),
        )
        val subject = GenerateDailyInsight(weather, clock = clock)

        val todayInsight = subject(london, prefs, ForecastPeriod.TODAY).insight
        val overlay = todayInsight.perModelHourly.shouldNotBeNull()
        overlay.byModel.keys shouldContainExactlyInAnyOrder listOf("ecmwf_ifs04", "gfs_seamless")
        overlay.byModel.getValue("ecmwf_ifs04").map { it.time.toLocalTime() } shouldBe daytime.map { it.time }
        overlay.byModel.getValue("gfs_seamless").map { it.time.toLocalTime() } shouldBe daytime.map { it.time }

        subject(london, prefs, ForecastPeriod.TONIGHT).insight.perModelHourly.shouldBeNull()
    }

    /** 24 hours of [PerModelHour] anchored on [date], starting at 00:00. */
    private fun perModelDay(
        date: LocalDate,
        apparentBase: Double,
        airBase: Double,
        precipScale: Double,
    ): List<PerModelHour> = (0..23).map { hour ->
        PerModelHour(
            time = LocalDateTime.of(date, LocalTime.of(hour, 0)),
            apparentTemperatureC = apparentBase + hour,
            temperatureC = airBase + hour,
            precipitationProbabilityPct = precipScale * hour,
        )
    }

    @Test
    fun `model with a missing in-window hour is dropped from the overlay`() = runTest {
        // parseHourly drops the per-hour entry for a model whose run hasn't
        // landed that hour (temp or precip null); the resulting series has a
        // gap that, if surfaced, would compact the surviving points left and
        // misalign the line with the blended hourly. Keep the model out of
        // the overlay entirely rather than draw a silently-shifted curve.
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 12.0, 11.0, 10.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(12, 0), 18.0, 17.0, 30.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 21.0, 40.0, WeatherCondition.CLEAR),
        )
        val incompleteEcmwf = listOf(
            // 12:00 hour missing — the model is dropped from the overlay.
            PerModelHour(LocalDateTime.of(today.date, LocalTime.of(8, 0)), 11.5, 12.5, 9.0),
            PerModelHour(LocalDateTime.of(today.date, LocalTime.of(15, 0)), 21.5, 22.5, 38.0),
        )
        val completeGfs = daytime.map { h ->
            PerModelHour(
                LocalDateTime.of(today.date, h.time),
                h.feelsLikeC + 0.5,
                h.temperatureC + 0.5,
                h.precipitationProbabilityPct + 2.0,
            )
        }
        val bundleHourly = PerModelHourly(
            byModel = mapOf("ecmwf_ifs04" to incompleteEcmwf, "gfs_seamless" to completeGfs),
        )
        val todayWithHourly = today.copy(hourly = daytime)
        val weather = FakeWeatherRepository(
            ForecastBundle(todayWithHourly, yesterday, perModelHourly = bundleHourly),
        )
        val subject = GenerateDailyInsight(weather, clock = clock)

        val overlay = subject(london, prefs, ForecastPeriod.TODAY).insight.perModelHourly
            .shouldNotBeNull()
        overlay.byModel.keys shouldContainExactlyInAnyOrder listOf("gfs_seamless")
        overlay.byModel.getValue("gfs_seamless").map { it.time.toLocalTime() } shouldBe daytime.map { it.time }
    }

    @Test
    fun `evening tie-in picks up rain that only one model spots in the per-model series`() = runTest {
        // The TODAY pass evaluates the evening-event tie-in against the tonight
        // slice. When base hourly under-calls precip (< 30% at every evening
        // hour) but one per-model series sees real rain at an evening hour,
        // the renderer's per-model tier should catch it. The fetch is now
        // forecast_days=2, so the per-model series spans the full
        // wrap-past-midnight tonight window — pre-midnight (today) AND the
        // tomorrow-morning portion both feed the tie-in's rain time.
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 16.0, 15.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(12, 0), 18.0, 17.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 18.0, 17.0, 5.0, WeatherCondition.CLEAR),
        )
        // Tonight slice (19:00–06:00 wrap) cold enough to trigger the jacket
        // rule (feelsLikeMin < 12) but with low base precip probability so the
        // base-only fallback would leave `rainTime` null.
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 11.0, 9.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
            HourlyForecast(LocalTime.of(20, 0), 10.5, 8.5, 10.0, WeatherCondition.PARTLY_CLOUDY),
            HourlyForecast(LocalTime.of(21, 0), 10.0, 8.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
            HourlyForecast(LocalTime.of(22, 0), 10.0, 8.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 10.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
        )
        // One model spots evening rain at 21:00 (today). Tomorrow's pre-dawn
        // hours stay dry — they're in scope after the forecast_days=2 widening
        // but the peak should still pick the louder 21:00 reading. Covering
        // both days is what the renderer expects from the wider fetch.
        val ecmwfFull = perModelDay(today.date, 11.0, 9.0, precipScale = 0.0).mapIndexed { hour, entry ->
            if (hour == 21) entry.copy(precipitationProbabilityPct = 75.0) else entry.copy(precipitationProbabilityPct = 10.0)
        } + perModelDay(today.date.plusDays(1), 9.0, 7.0, precipScale = 0.0).map {
            it.copy(precipitationProbabilityPct = 5.0)
        }
        val perModelHourly = PerModelHourly(byModel = mapOf("ecmwf_ifs04" to ecmwfFull))
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(
            ForecastBundle(baseHourly, yesterday, perModelHourly = perModelHourly),
        )
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        // Only-the-jacket rule isolates the assertion: with no other rule
        // triggering, todayItems is empty and evening items = [jacket] —
        // not a subset of today's, so the tie-in is not suppressed.
        val jacketOnly = listOf(ClothesRule("jacket", ClothesRule.TemperatureBelow(12.0)))
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = jacketOnly,
                useCalendarEvents = true,
            ),
            period = ForecastPeriod.TODAY,
        )

        val tie = result.insight.summary.eveningEventTieIn
        tie.shouldNotBeNull()
        tie!!.items shouldBe listOf("jacket")
        tie.rainTime shouldBe LocalTime.of(21, 0)
    }

    @Test
    fun `evening tie-in picks up post-midnight rain that only one model spots`() = runTest {
        // Same setup as the pre-midnight case above, but the rain hour is at
        // 02:00 tomorrow — the part of the tonight window that crosses
        // midnight. Pre-`forecast_days=2`, this hour was outside per-model
        // coverage entirely; pre-LocalDateTime migration, it would have
        // aliased against today's 02:00 (which is sleepy, dry, and not in the
        // tonight slice). With both fixes, the post-midnight reading flows
        // straight through to the tie-in's rain time.
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 16.0, 15.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(12, 0), 18.0, 17.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 18.0, 17.0, 5.0, WeatherCondition.CLEAR),
        )
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 11.0, 9.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
            HourlyForecast(LocalTime.of(22, 0), 10.0, 8.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
        )
        val tomorrowMorning = listOf(
            HourlyForecast(LocalTime.of(2, 0), 9.0, 7.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
            HourlyForecast(LocalTime.of(5, 0), 9.0, 7.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 10.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
        )
        // Today entries dry, tomorrow's 02:00 wet (75%). Both days in the
        // per-model series; the slice picks tomorrow's because that's the
        // hour the tonight window points to.
        val tomorrowEntries = perModelDay(today.date.plusDays(1), 9.0, 7.0, precipScale = 0.0).map { entry ->
            if (entry.time.toLocalTime() == LocalTime.of(2, 0)) {
                entry.copy(precipitationProbabilityPct = 75.0)
            } else {
                entry.copy(precipitationProbabilityPct = 10.0)
            }
        }
        val ecmwfFull = perModelDay(today.date, 11.0, 9.0, precipScale = 0.0).map {
            it.copy(precipitationProbabilityPct = 10.0)
        } + tomorrowEntries
        val perModelHourly = PerModelHourly(byModel = mapOf("ecmwf_ifs04" to ecmwfFull))
        val diner = CalendarEvent(
            title = "after-hours",
            start = LocalTime.of(22, 0),
            end = LocalTime.of(23, 30),
            location = "Theatre",
        )
        val weather = FakeWeatherRepository(
            ForecastBundle(baseHourly, yesterday, perModelHourly = perModelHourly, tomorrowHourly = tomorrowMorning),
        )
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val jacketOnly = listOf(ClothesRule("jacket", ClothesRule.TemperatureBelow(12.0)))
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = jacketOnly,
                useCalendarEvents = true,
            ),
            period = ForecastPeriod.TODAY,
        )

        val tie = result.insight.summary.eveningEventTieIn
        tie.shouldNotBeNull()
        tie!!.items shouldBe listOf("jacket")
        tie.rainTime shouldBe LocalTime.of(2, 0)
    }

    @Test
    fun `evening tie-in is omitted when the user has no event away from home`() = runTest {
        // The away-from-home gate is the only behavioural asymmetry between
        // the day and night passes. With no located event in the night window
        // — only a morning event, or an unlocated evening one — the morning
        // insight stays silent about the night even if clothes rules would
        // fire on the night slice.
        val zone = ZoneId.of("Europe/London")
        val coldEvening = listOf(
            HourlyForecast(LocalTime.of(8, 0), 16.0, 15.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(21, 0), 10.0, 8.0, 5.0, WeatherCondition.CLEAR),
        )
        val baseHourly = today.copy(
            hourly = coldEvening,
            precipitationProbabilityMaxPct = 5.0,
            condition = WeatherCondition.CLEAR,
        )
        val unlocated = CalendarEvent(
            title = "call",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(22, 0),
            location = null,
        )
        val weather = FakeWeatherRepository(ForecastBundle(baseHourly, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(unlocated))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val jacketOnly = listOf(ClothesRule("jacket", ClothesRule.TemperatureBelow(12.0)))
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = jacketOnly,
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
            period = ForecastPeriod.TODAY,
        )

        result.insight.summary.eveningEventTieIn.shouldBeNull()
    }

    @Test
    fun `evening tie-in mirrors what the night notification would itself say`() = runTest {
        // The day's evening tie-in is the night insight, dictated by the
        // night bundle. When the night insight would say "Bring a jacket;
        // rain at 9pm.", the tie-in carries the same item + rain time.
        val zone = ZoneId.of("Europe/London")
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 16.0, 15.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 18.0, 17.0, 5.0, WeatherCondition.CLEAR),
        )
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 11.0, 9.0, 60.0, WeatherCondition.RAIN),
            HourlyForecast(LocalTime.of(21, 0), 10.0, 8.0, 80.0, WeatherCondition.RAIN),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 80.0,
            condition = WeatherCondition.RAIN,
        )
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(ForecastBundle(baseHourly, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val jacketOnly = listOf(ClothesRule("jacket", ClothesRule.TemperatureBelow(12.0)))
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = jacketOnly,
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
            period = ForecastPeriod.TODAY,
        )

        val tie = result.insight.summary.eveningEventTieIn
        tie.shouldNotBeNull()
        tie!!.items shouldBe listOf("jacket")
        tie.rainTime shouldBe LocalTime.of(21, 0)
    }

    @Test
    fun `evening tie-in carries only the clothing delta vs the morning insight`() = runTest {
        // Today triggers sweater on a cool daytime; night gets colder and
        // additionally needs a jacket. The tie-in carries the new item
        // ("jacket"), not "sweater" — the morning insight already mentioned
        // sweater, so repeating it adds nothing.
        val zone = ZoneId.of("Europe/London")
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 17.0, 16.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 17.0, 16.0, 5.0, WeatherCondition.CLEAR),
        )
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 11.0, 9.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(21, 0), 10.0, 8.0, 5.0, WeatherCondition.CLEAR),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 5.0,
            condition = WeatherCondition.CLEAR,
        )
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(ForecastBundle(baseHourly, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val rules = listOf(
            ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)),
            ClothesRule("jacket", ClothesRule.TemperatureBelow(12.0)),
        )
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = rules,
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
            period = ForecastPeriod.TODAY,
        )

        val tie = result.insight.summary.eveningEventTieIn
        tie.shouldNotBeNull()
        tie!!.items shouldBe listOf("jacket")
        tie.rainTime.shouldBeNull()
    }

    @Test
    fun `evening tie-in carries every night-only item, not just the first`() = runTest {
        // Today triggers shorts (warm afternoon, mild evening); the night
        // drops cold enough for jeans + a sweater. Delta straddles both slots
        // — bottom (jeans, swapping in for the warm-day shorts) plus top
        // (sweater, new since the day was bare) — and both surface in the
        // tie-in's items list rather than the engine dropping one silently.
        // Picked across slots rather than two SHELL tops because tops
        // layer-reduce within their layer: jacket + coat firing together
        // would collapse to coat alone (you wear one outer, not both),
        // which is correct but doesn't exercise "multiple delta items".
        val zone = ZoneId.of("Europe/London")
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 26.0, 25.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 28.0, 27.0, 5.0, WeatherCondition.CLEAR),
        )
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 14.0, 13.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(21, 0), 12.0, 11.0, 5.0, WeatherCondition.CLEAR),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 5.0,
            condition = WeatherCondition.CLEAR,
        )
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(ForecastBundle(baseHourly, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val rules = listOf(
            ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)),
            ClothesRule("jeans", ClothesRule.TemperatureBelow(20.0)),
            ClothesRule("shorts", ClothesRule.TemperatureAbove(24.0)),
        )
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = rules,
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
            period = ForecastPeriod.TODAY,
        )

        val tie = result.insight.summary.eveningEventTieIn
        tie.shouldNotBeNull()
        tie!!.items shouldBe listOf("sweater", "jeans")
        tie.rainTime.shouldBeNull()
    }

    @Test
    fun `evening tie-in collapses to bare rain when night items are a subset of today`() = runTest {
        // Today and night both trigger the same item (jacket — cold all
        // day). With no clothing delta the tie-in skips the item part and
        // surfaces just the rain mention, because evening rain is still new
        // information the morning slice didn't cover.
        val zone = ZoneId.of("Europe/London")
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 8.0, 6.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 10.0, 9.0, 5.0, WeatherCondition.CLEAR),
        )
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 9.0, 7.0, 60.0, WeatherCondition.RAIN),
            HourlyForecast(LocalTime.of(21, 0), 8.0, 6.0, 80.0, WeatherCondition.RAIN),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 80.0,
            condition = WeatherCondition.RAIN,
        )
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(ForecastBundle(baseHourly, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val jacketOnly = listOf(ClothesRule("jacket", ClothesRule.TemperatureBelow(12.0)))
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = jacketOnly,
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
            period = ForecastPeriod.TODAY,
        )

        val tie = result.insight.summary.eveningEventTieIn
        tie.shouldNotBeNull()
        tie!!.items.shouldBeEmpty()
        tie.rainTime shouldBe LocalTime.of(21, 0)
    }

    @Test
    fun `evening tie-in is omitted when delta is empty and there is no rain`() = runTest {
        // Same items today and night, no rain — there's genuinely nothing
        // to add for the evening, so the tie-in stays silent.
        val zone = ZoneId.of("Europe/London")
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 8.0, 6.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 10.0, 9.0, 5.0, WeatherCondition.CLEAR),
        )
        val evening = listOf(
            HourlyForecast(LocalTime.of(21, 0), 8.0, 6.0, 5.0, WeatherCondition.CLEAR),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 5.0,
            condition = WeatherCondition.CLEAR,
        )
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(ForecastBundle(baseHourly, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val jacketOnly = listOf(ClothesRule("jacket", ClothesRule.TemperatureBelow(12.0)))
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = jacketOnly,
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
            period = ForecastPeriod.TODAY,
        )

        result.insight.summary.eveningEventTieIn.shouldBeNull()
    }

    @Test
    fun `evening tie-in is silent when the day already wears the night's rule garment as the user's default top`() = runTest {
        // Codex-flagged: defaultTop = SWEATER for someone who runs cold.
        // Daytime is mild (no threshold rule matches) so they wear their
        // default sweater all day. Night drops below the sweater rule's
        // 18°C threshold, so the rule matches at night. The tie-in must
        // NOT say "Bring a sweater tonight" — they had it on all day.
        // Tops layer, so a night top-tier match already covered by the
        // day's outfit (whether via rule or default) means no action.
        val zone = ZoneId.of("Europe/London")
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 20.0, 19.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 21.0, 5.0, WeatherCondition.CLEAR),
        )
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 16.0, 15.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(21, 0), 15.0, 14.0, 5.0, WeatherCondition.CLEAR),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 5.0,
            condition = WeatherCondition.CLEAR,
        )
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(ForecastBundle(baseHourly, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val sweaterRule = listOf(ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)))
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = sweaterRule,
                defaultTop = OutfitSuggestion.Top.SWEATER,
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
            period = ForecastPeriod.TODAY,
        )

        result.insight.summary.eveningEventTieIn.shouldBeNull()
    }

    @Test
    fun `evening tie-in is silent when the night top resolves to a default the day was wearing as a rule`() = runTest {
        // Cold day with a sweater rule, mild night where no threshold rule
        // matches → the night's top tier resolves to the default top
        // (TSHIRT). Tops layer, so dropping back to t-shirt is just
        // removing a layer — no need to "bring" anything. Tie-in stays
        // silent.
        val zone = ZoneId.of("Europe/London")
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 17.0, 16.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 17.0, 16.0, 5.0, WeatherCondition.CLEAR),
        )
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 22.0, 21.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(21, 0), 21.0, 20.0, 5.0, WeatherCondition.CLEAR),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 5.0,
            condition = WeatherCondition.CLEAR,
        )
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(ForecastBundle(baseHourly, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val sweaterRule = listOf(ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)))
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = sweaterRule,
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
            period = ForecastPeriod.TODAY,
        )

        result.insight.summary.eveningEventTieIn.shouldBeNull()
    }

    @Test
    fun `evening tie-in surfaces the night's bottom substitution when the day's bottom was a different rule`() = runTest {
        // Hot day fires the shorts rule, evening cools off enough that
        // shorts no longer applies and the bottom tier resolves to the
        // default (PANTS). Bottoms substitute (you swap, not layer), so
        // the tie-in surfaces "Bring pants tonight" — a real action the
        // user needs to take when leaving for the evening event.
        val zone = ZoneId.of("Europe/London")
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 25.0, 25.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 28.0, 27.0, 5.0, WeatherCondition.CLEAR),
        )
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 19.0, 18.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(21, 0), 18.0, 17.0, 5.0, WeatherCondition.CLEAR),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 5.0,
            condition = WeatherCondition.CLEAR,
        )
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(ForecastBundle(baseHourly, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val shortsRule = listOf(ClothesRule("shorts", ClothesRule.TemperatureAbove(24.0)))
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = shortsRule,
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
            period = ForecastPeriod.TODAY,
        )

        val tie = result.insight.summary.eveningEventTieIn
        tie.shouldNotBeNull()
        tie!!.items shouldBe listOf("pants")
    }

    @Test
    fun `evening tie-in still carries genuinely new night-only top rules when the day was driven by defaults`() = runTest {
        // Counterpart to the "default sweater" silent case: a mild
        // daytime resolving to defaults (defaultTop = TSHIRT) but a
        // *cold* night that triggers BOTH the sweater rule AND the
        // jacket rule. The user actually does need to bring the
        // night-only layers — they had t-shirt + pants on all day.
        val zone = ZoneId.of("Europe/London")
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 20.0, 19.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 21.0, 5.0, WeatherCondition.CLEAR),
        )
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 11.0, 9.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(21, 0), 10.0, 8.0, 5.0, WeatherCondition.CLEAR),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 5.0,
            condition = WeatherCondition.CLEAR,
        )
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(ForecastBundle(baseHourly, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val rules = listOf(
            ClothesRule("sweater", ClothesRule.TemperatureBelow(18.0)),
            ClothesRule("jacket", ClothesRule.TemperatureBelow(12.0)),
        )
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = rules,
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
            period = ForecastPeriod.TODAY,
        )

        val tie = result.insight.summary.eveningEventTieIn
        tie.shouldNotBeNull()
        tie!!.items shouldBe listOf("sweater", "jacket")
    }

    @Test
    fun `evening tie-in canonicalizes legacy aliases so trousers and pants don't cross-fire`() = runTest {
        // Codex-flagged: a legacy rule item like "trousers" (a recognized
        // Garment alias for PANTS) shouldn't surface as different from the
        // night's default "pants" — the user is already wearing the same
        // garment. Membership has to go through Garment.fromKey().itemKey,
        // not just lowercase-and-trim.
        val zone = ZoneId.of("Europe/London")
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 16.0, 15.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 17.0, 16.0, 5.0, WeatherCondition.CLEAR),
        )
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 16.0, 15.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(21, 0), 15.0, 14.0, 5.0, WeatherCondition.CLEAR),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            precipitationProbabilityMaxPct = 5.0,
            condition = WeatherCondition.CLEAR,
        )
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(ForecastBundle(baseHourly, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        // A legacy free-form rule typed as "trousers" — recognized by
        // Garment.fromKey as PANTS but stored verbatim on disk.
        val rules = listOf(ClothesRule("trousers", ClothesRule.TemperatureBelow(20.0)))
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = rules,
                useCalendarEvents = true,
                schedule = Schedule.default(zone),
            ),
            period = ForecastPeriod.TODAY,
        )

        // The day fires "trousers" (PANTS), night defaults to "pants"
        // (PANTS). Same garment — no tie-in should emit "Bring pants."
        result.insight.summary.eveningEventTieIn.shouldBeNull()
    }

    @Test
    fun `tonight period leads with TONIGHT and skips the yesterday delta clause`() = runTest {
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs, ForecastPeriod.TONIGHT)

        result.insight.summary.period shouldBe ForecastPeriod.TONIGHT
        // The morning pass already mentioned the +8°C delta against yesterday;
        // the evening pass deliberately omits it.
        result.insight.summary.delta.shouldBeNull()
        result.insight.period shouldBe ForecastPeriod.TONIGHT
    }

    @Test
    fun `tonight period wraps from today's evening hours through tomorrow's pre-morning hours`() = runTest {
        val todayHourly = listOf(
            HourlyForecast(LocalTime.of(8, 0), 10.0, 8.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 24.0, 24.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(20, 0), 16.0, 14.0, 10.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(23, 0), 12.0, 10.0, 5.0, WeatherCondition.CLEAR),
        )
        val tomorrowHourly = listOf(
            // 03:00 and 06:00 are inside the overnight window (< default morning 07:00).
            // The 03:00 feels-like (3.0°C) is the actual overnight low — it's lower
            // than anything in today's hourly slice, which is the whole point of
            // wrapping past midnight.
            HourlyForecast(LocalTime.of(3, 0), 6.0, 3.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(6, 0), 8.0, 5.0, 5.0, WeatherCondition.CLEAR),
            // 08:00 is past morning end and must be excluded.
            HourlyForecast(LocalTime.of(8, 0), 12.0, 10.0, 5.0, WeatherCondition.CLEAR),
        )
        val todayWithHourly = today.copy(hourly = todayHourly)
        val weather = FakeWeatherRepository(
            ForecastBundle(todayWithHourly, yesterday, tomorrowHourly = tomorrowHourly),
        )
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs, ForecastPeriod.TONIGHT)

        // 20:00 + 23:00 from today, then 03:00 + 06:00 from tomorrow. 08:00 is past the
        // default 07:00 morning end and is dropped.
        result.insight.hourly.map { it.time } shouldBe listOf(
            LocalTime.of(20, 0),
            LocalTime.of(23, 0),
            LocalTime.of(3, 0),
            LocalTime.of(6, 0),
        )
        // Aggregates recomputed from the slice — overnight low (3.0°C feels-like at 03:00)
        // is what drives the tonight band sentence and outfit, not today's daytime min.
        result.insight.summary.band.low shouldBe TemperatureBand.FREEZING
        result.insight.summary.band.high shouldBe TemperatureBand.COOL
    }

    @Test
    fun `today period populates nextOutfit from the overnight slice when hourly carries tonight hours`() = runTest {
        val todayHourly = listOf(
            HourlyForecast(LocalTime.of(8, 0), 22.0, 22.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 26.0, 26.0, 5.0, WeatherCondition.CLEAR),
            // Overnight hours dropping below the jacket cutoff — drives a heavier
            // nextOutfit than the daytime TSHIRT.
            HourlyForecast(LocalTime.of(20, 0), 14.0, 12.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(23, 0), 11.0, 9.0, 5.0, WeatherCondition.CLEAR),
        )
        val warmDay = today.copy(
            temperatureMinC = 20.0,
            temperatureMaxC = 26.0,
            feelsLikeMinC = 20.0,
            feelsLikeMaxC = 26.0,
            hourly = todayHourly,
        )
        val weather = FakeWeatherRepository(ForecastBundle(warmDay, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs, ForecastPeriod.TODAY)

        // Daytime min 20 / max 26 against default rules: sweater (18) and
        // jacket (12) don't fire on min 20 → TSHIRT; shorts (24) fires on
        // max 26 → SHORTS.
        result.insight.outfit shouldBe OutfitSuggestion(
            top = OutfitSuggestion.Top.TSHIRT,
            bottom = OutfitSuggestion.Bottom.SHORTS,
        )
        // Tonight slice min 9 / max 12: jacket rule (12°C) fires on min 9 →
        // THICK_JACKET; shorts (24°C) doesn't fire on max 12 → LONG_PANTS.
        result.insight.nextOutfit shouldBe OutfitSuggestion(
            top = OutfitSuggestion.Top.THICK_JACKET,
            bottom = OutfitSuggestion.Bottom.LONG_PANTS,
        )
    }

    @Test
    fun `tonight period populates nextOutfit from tomorrow's daily forecast`() = runTest {
        val tomorrow = DailyForecast(
            date = LocalDate.of(2026, 4, 26),
            temperatureMinC = 14.0,
            temperatureMaxC = 26.0,
            feelsLikeMinC = 14.0,
            feelsLikeMaxC = 26.0,
            precipitationProbabilityMaxPct = 5.0,
            precipitationMmTotal = 0.0,
            condition = WeatherCondition.CLEAR,
        )
        val weather = FakeWeatherRepository(
            ForecastBundle(today, yesterday, tomorrow = tomorrow),
        )
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs, ForecastPeriod.TONIGHT)

        // Tomorrow's feels-like 14→26 against default rules: sweater (18)
        // fires on min 14 → SWEATER; jacket (12) doesn't fire (14 ≥ 12);
        // shorts (24) fires on max 26 → SHORTS.
        result.insight.nextOutfit shouldBe OutfitSuggestion(
            top = OutfitSuggestion.Top.SWEATER,
            bottom = OutfitSuggestion.Bottom.SHORTS,
        )
    }

    @Test
    fun `tonight period slices tomorrow to the user's daytime window for nextOutfit and rationale`() = runTest {
        // Regression: a pre-dawn 06:00 trough was leaking into the
        // "Tomorrow" card's "Why this outfit?" rationale ("Feels-like
        // low 5.8°C at 06:00") even though the user's schedule kept
        // them indoors until 07:00. The unsliced tomorrow forecast
        // also let that 06:00 low drive a heavier outfit pick than
        // the user's actual daytime experience.
        val tomorrowHourly = listOf(
            // Pre-dawn trough — must be excluded by the daytime slice.
            HourlyForecast(LocalTime.of(6, 0), 6.0, 5.8, 0.0, WeatherCondition.CLEAR),
            // In-window hours stay above the jacket cutoff (12°C).
            HourlyForecast(LocalTime.of(9, 0), 14.0, 13.0, 0.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 22.0, 21.0, 0.0, WeatherCondition.CLEAR),
        )
        val tomorrow = DailyForecast(
            date = LocalDate.of(2026, 4, 26),
            temperatureMinC = 6.0,
            temperatureMaxC = 22.0,
            feelsLikeMinC = 5.8,
            feelsLikeMaxC = 21.0,
            precipitationProbabilityMaxPct = 0.0,
            precipitationMmTotal = 0.0,
            condition = WeatherCondition.CLEAR,
            hourly = tomorrowHourly,
        )
        val weather = FakeWeatherRepository(
            ForecastBundle(today, yesterday, tomorrow = tomorrow),
        )
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs, ForecastPeriod.TONIGHT)

        // In-window min is 13°C at 09:00, not 5.8°C at 06:00 — so the
        // jacket rule (12°C) doesn't fire and the rationale cites the
        // 09:00 hour.
        val topFact = result.insight.nextOutfitRationale!!.top.facts.single()
        topFact.observedAt shouldBe LocalTime.of(9, 0)
        topFact.observedC shouldBe 13.0
        result.insight.nextOutfit!!.top shouldBe OutfitSuggestion.Top.SWEATER
    }

    @Test
    fun `tonight period leaves nextOutfit null when tomorrow daily is unavailable`() = runTest {
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs, ForecastPeriod.TONIGHT)

        result.insight.nextOutfit.shouldBeNull()
    }

    @Test
    fun `tonight period falls back to today-only when tomorrow hourly is unavailable`() = runTest {
        val todayHourly = listOf(
            HourlyForecast(LocalTime.of(8, 0), 10.0, 8.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(20, 0), 16.0, 14.0, 10.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(23, 0), 12.0, 10.0, 5.0, WeatherCondition.CLEAR),
        )
        val todayWithHourly = today.copy(hourly = todayHourly)
        // No tomorrowHourly on the bundle (legacy forecast_days=1 path).
        val weather = FakeWeatherRepository(ForecastBundle(todayWithHourly, yesterday))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs, ForecastPeriod.TONIGHT)

        result.insight.hourly.map { it.time } shouldBe listOf(LocalTime.of(20, 0), LocalTime.of(23, 0))
    }

    @Test
    fun `tonight period flags hasEvents when an evening event is present and gates the calendar clause by it`() = runTest {
        val zone = ZoneId.of("Europe/London")
        val rainyEvening = today.copy(
            precipitationProbabilityMaxPct = 60.0,
            condition = WeatherCondition.RAIN,
            hourly = listOf(
                HourlyForecast(LocalTime.of(8, 0), 10.0, 8.0, 5.0, WeatherCondition.CLEAR),
                // Peak precip is in the tonight window, after 19:00.
                HourlyForecast(LocalTime.of(20, 0), 16.0, 14.0, 80.0, WeatherCondition.RAIN),
            ),
        )
        val morningStandup = CalendarEvent(
            title = "standup",
            start = LocalTime.of(9, 0),
            end = LocalTime.of(9, 30),
        )
        val eveningGig = CalendarEvent(
            title = "gig",
            start = LocalTime.of(20, 0),
            end = LocalTime.of(22, 0),
            location = "Brixton Academy",
        )
        val weather = FakeWeatherRepository(ForecastBundle(rainyEvening, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(morningStandup, eveningGig))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val result = subject(
            location = london,
            prefs = prefs.copy(useCalendarEvents = true, schedule = Schedule.default(zone)),
            period = ForecastPeriod.TONIGHT,
        )

        // Morning event must be filtered out for the tonight pass; the gig
        // must drive the tie-in. The tie-in's data class only carries the
        // clothes item now (event title + time were dropped to keep calendar
        // event metadata off-device); the precip clause still names the
        // 8pm rain. With temperature-only defaults firing on the 14°C feels-like
        // and no umbrella rule, the picked item is the first triggered: sweater.
        val tieIn = result.insight.summary.calendarTieIn
        tieIn.shouldNotBeNull()
        tieIn!!.item shouldBe "sweater"
        result.insight.summary.precip!!.time shouldBe LocalTime.of(20, 0)
        result.insight.hasEvents shouldBe true
    }

    @Test
    fun `tonight hasEvents is false when the evening event has no location`() = runTest {
        // hasEvents drives the tonight notification loudness and the
        // tonight-notify-only-on-events pref. We treat "no location" as
        // "user is at home", same gate buildEveningEventTieIn uses; an
        // unlabelled evening hold shouldn't make tonight notifications
        // chirp.
        val zone = ZoneId.of("Europe/London")
        val unlocatedHold = CalendarEvent(
            title = "block",
            start = LocalTime.of(20, 0),
            end = LocalTime.of(21, 0),
        )
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(unlocatedHold))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val result = subject(
            location = london,
            prefs = prefs.copy(useCalendarEvents = true, schedule = Schedule.default(zone)),
            period = ForecastPeriod.TONIGHT,
        )

        result.insight.hasEvents shouldBe false
    }

    @Test
    fun `tonight hasEvents is false when the only events are all-day holidays or birthdays`() = runTest {
        // FREE-availability holiday / birthday rows now reach the use-case
        // (preserved for classification) but are all-day with no location,
        // so they fall through the same predicate as unlocated holds.
        val zone = ZoneId.of("Europe/London")
        val christmas = CalendarEvent(
            title = "Christmas Day",
            start = LocalTime.MIDNIGHT,
            end = LocalTime.MIDNIGHT,
            allDay = true,
            kind = EventKind.PUBLIC_HOLIDAY,
        )
        val aliceBirthday = CalendarEvent(
            title = "Alice's birthday",
            start = LocalTime.MIDNIGHT,
            end = LocalTime.MIDNIGHT,
            allDay = true,
            kind = EventKind.BIRTHDAY,
        )
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(christmas, aliceBirthday))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val result = subject(
            location = london,
            prefs = prefs.copy(useCalendarEvents = true, schedule = Schedule.default(zone)),
            period = ForecastPeriod.TONIGHT,
        )

        result.insight.hasEvents shouldBe false
    }

    @Test
    fun `tonight period reports hasEvents=false when only morning events are present`() = runTest {
        val zone = ZoneId.of("Europe/London")
        val morningOnly = CalendarEvent(
            title = "standup",
            start = LocalTime.of(9, 0),
            end = LocalTime.of(9, 30),
        )
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday))
        val calendar = FakeCalendarEventReader(events = listOf(morningOnly))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        val result = subject(
            location = london,
            prefs = prefs.copy(useCalendarEvents = true, schedule = Schedule.default(zone)),
            period = ForecastPeriod.TONIGHT,
        )

        result.insight.hasEvents shouldBe false
    }

    @Test
    fun `expired alerts are filtered before reaching the summary and the result`() = runTest {
        val stale = WeatherAlert(
            event = "Wind Advisory",
            severity = AlertSeverity.MODERATE,
            headline = null,
            description = null,
            onset = clockInstant.minusSeconds(7200),
            expires = clockInstant.minusSeconds(60),
        )
        val weather = FakeWeatherRepository(ForecastBundle(today, yesterday, alerts = listOf(stale)))
        val subject = GenerateDailyInsight(weather, clock = clock)

        val result = subject(london, prefs)

        result.alerts shouldBe emptyList()
    }

    @Test
    fun `evening tie-in surfaces bare per-model rain when no clothes rule fires`() = runTest {
        // The case AGENTS.md flags under "Domain conventions": no clothes
        // rule triggers for the tonight window (mild evening, no
        // user-defined umbrella rule — the defaults intentionally don't
        // ship one), but a per-model series spots rain ≥ 30%. Pre-fix the
        // tie-in returned null and the morning insight stayed silent on
        // evening rain; the fix emits an item-less clause that the
        // formatter renders as a bare "Rain tonight at 9pm." (or
        // chance-of-rain on POSSIBLE).
        val daytime = listOf(
            HourlyForecast(LocalTime.of(8, 0), 16.0, 15.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(12, 0), 18.0, 17.0, 5.0, WeatherCondition.CLEAR),
            HourlyForecast(LocalTime.of(15, 0), 18.0, 17.0, 5.0, WeatherCondition.CLEAR),
        )
        // Mild evening: feels-like stays above the jacket-rule threshold
        // so nothing triggers from the base forecast.
        val evening = listOf(
            HourlyForecast(LocalTime.of(19, 0), 15.0, 14.0, 5.0, WeatherCondition.PARTLY_CLOUDY),
            HourlyForecast(LocalTime.of(21, 0), 15.0, 14.0, 10.0, WeatherCondition.PARTLY_CLOUDY),
        )
        val baseHourly = today.copy(
            hourly = daytime + evening,
            // Above the default 18°C threshold so the jacket / coat rules
            // can't fire — only feels-like-min < 12 triggers them.
            feelsLikeMinC = 14.0,
            feelsLikeMaxC = 18.0,
            precipitationProbabilityMaxPct = 10.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
        )
        // One model spots rain at 21:00 ≥ POSSIBLE_THRESHOLD; the other two
        // stay dry, so the per-model tier emits POSSIBLE.
        val ecmwfEntries = listOf(
            PerModelHour(LocalDateTime.of(today.date, LocalTime.of(19, 0)), 14.0, 15.0, 5.0),
            PerModelHour(LocalDateTime.of(today.date, LocalTime.of(21, 0)), 14.0, 15.0, 75.0),
        )
        val gfsEntries = listOf(
            PerModelHour(LocalDateTime.of(today.date, LocalTime.of(19, 0)), 14.0, 15.0, 5.0),
            PerModelHour(LocalDateTime.of(today.date, LocalTime.of(21, 0)), 14.0, 15.0, 10.0),
        )
        val iconEntries = listOf(
            PerModelHour(LocalDateTime.of(today.date, LocalTime.of(19, 0)), 14.0, 15.0, 5.0),
            PerModelHour(LocalDateTime.of(today.date, LocalTime.of(21, 0)), 14.0, 15.0, 5.0),
        )
        val perModelHourly = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to ecmwfEntries,
                "gfs_seamless" to gfsEntries,
                "icon_seamless" to iconEntries,
            ),
        )
        val diner = CalendarEvent(
            title = "dinner",
            start = LocalTime.of(21, 0),
            end = LocalTime.of(23, 0),
            location = "Restaurant",
        )
        val weather = FakeWeatherRepository(
            ForecastBundle(baseHourly, yesterday, perModelHourly = perModelHourly),
        )
        val calendar = FakeCalendarEventReader(events = listOf(diner))
        val subject = GenerateDailyInsight(weather, calendarEventReader = calendar, clock = clock)

        // No precip-keyed rule on the books — only temperature rules, none
        // of which trigger on a mild evening.
        val tempOnly = listOf(
            ClothesRule("jacket", ClothesRule.TemperatureBelow(12.0)),
            ClothesRule("coat", ClothesRule.TemperatureBelow(6.0)),
        )
        val result = subject(
            location = london,
            prefs = prefs.copy(
                clothesRules = tempOnly,
                useCalendarEvents = true,
            ),
            period = ForecastPeriod.TODAY,
        )

        val tie = result.insight.summary.eveningEventTieIn
        tie.shouldNotBeNull()
        // Bare-rain emission: no items, rain time set, POSSIBLE tier.
        tie!!.items shouldBe emptyList()
        tie.rainTime shouldBe LocalTime.of(21, 0)
        tie.likelihood shouldBe PrecipLikelihood.POSSIBLE
    }
}
