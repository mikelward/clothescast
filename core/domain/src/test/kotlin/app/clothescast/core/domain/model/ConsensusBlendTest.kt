package app.clothescast.core.domain.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ConsensusBlendTest {

    // Anchor date for the per-model series — every entry's full LocalDateTime
    // pairs this with the hour. Best_match is just LocalTime; the blender
    // gets [TODAY] passed in to bridge the two.
    private val today: LocalDate = LocalDate.of(2026, 5, 13)

    private fun hour(
        h: Int,
        temp: Double,
        feels: Double = temp - 1.0,
        precip: Double = 0.0,
        mm: Double = 0.0,
        wind: Double? = null,
        uv: Double? = null,
        condition: WeatherCondition = WeatherCondition.CLEAR,
    ) = HourlyForecast(
        time = LocalTime.of(h, 0),
        temperatureC = temp,
        feelsLikeC = feels,
        precipitationProbabilityPct = precip,
        precipitationMm = mm,
        windSpeedKmh = wind,
        uvIndex = uv,
        condition = condition,
    )

    private fun perModel(
        h: Int,
        apparent: Double,
        air: Double,
        precip: Double? = 0.0,
        mm: Double? = null,
        wind: Double? = null,
        uv: Double? = null,
        condition: WeatherCondition? = null,
        date: LocalDate = today,
    ) = PerModelHour(
        time = LocalDateTime.of(date, LocalTime.of(h, 0)),
        apparentTemperatureC = apparent,
        temperatureC = air,
        precipitationProbabilityPct = precip,
        precipitationMm = mm,
        windSpeedKmh = wind,
        uvIndex = uv,
        condition = condition,
    )

    @Test
    fun `returns null when per-model data is null`() {
        val best = listOf(hour(12, temp = 10.0))

        blendConsensusHourly(today, best, perModel = null).shouldBeNull()
    }

    @Test
    fun `returns null when fewer than two models reported`() {
        // Only one model present — single-model "consensus" is meaningless,
        // fall back to best_match.
        val perModel = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(perModel(12, apparent = 14.0, air = 15.0, precip = 80.0)),
            ),
        )

        blendConsensusHourly(today, listOf(hour(12, temp = 10.0)), perModel).shouldBeNull()
    }

    @Test
    fun `hour missing from best-match is synthesized from the consensus`() {
        // The mapper drops a best_match hour whose temperature_2m came back
        // null; when at least two consulted models still cover it, the blend
        // re-adds the hour so the day keeps its coverage at the forecast
        // horizon, where best_match thins out before the consulted models.
        val best = listOf(hour(12, temp = 10.0, precip = 30.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    perModel(12, apparent = 11.0, air = 12.0, precip = 40.0),
                    perModel(13, apparent = 13.0, air = 14.0, precip = 60.0, wind = 20.0),
                ),
                "icon_seamless" to listOf(
                    perModel(12, apparent = 13.0, air = 14.0, precip = 50.0),
                    perModel(13, apparent = 15.0, air = 16.0, precip = 80.0, wind = 30.0),
                ),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended.map { it.time } shouldBe listOf(LocalTime.of(12, 0), LocalTime.of(13, 0))
        val synthesized = blended[1]
        synthesized.temperatureC shouldBe (15.0 plusOrMinus 0.0001)
        synthesized.feelsLikeC shouldBe (14.0 plusOrMinus 0.0001)
        synthesized.precipitationProbabilityPct shouldBe (70.0 plusOrMinus 0.0001)
        synthesized.windSpeedKmh!! shouldBe (25.0 plusOrMinus 0.0001)
    }

    @Test
    fun `synthesis skips other days' hours and single-candidate hours`() {
        // The per-model window spans the full 14-day fetch; only this day's
        // hours may be synthesized into this day's list, and a lone model's
        // hour stays out — same one-model-isn't-a-consensus bar as the
        // replacement path.
        val best = listOf(hour(12, temp = 10.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    perModel(12, apparent = 11.0, air = 12.0),
                    perModel(13, apparent = 13.0, air = 14.0), // single candidate
                    perModel(9, apparent = 9.0, air = 10.0, date = today.plusDays(1)),
                ),
                "icon_seamless" to listOf(
                    perModel(12, apparent = 13.0, air = 14.0),
                    perModel(9, apparent = 11.0, air = 12.0, date = today.plusDays(1)),
                ),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended.map { it.time } shouldBe listOf(LocalTime.of(12, 0))
    }

    @Test
    fun `includes the best-match overlay in the consensus mean`() {
        // best_match is one of the models in [byModel] and gets folded into
        // the mean alongside the consulted models. The user picked this
        // posture deliberately: Open-Meteo's auto-pick is presumably
        // location-tuned and excluding it would dilute that signal, even at
        // the cost of implicitly double-weighting whichever underlying
        // model best_match resolved to.
        val best = listOf(hour(12, temp = 10.0, feels = 9.0, precip = 30.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(perModel(12, apparent = 14.0, air = 15.0, precip = 80.0)),
                PerModelHourly.BEST_MATCH_MODEL_ID to listOf(
                    perModel(12, apparent = 9.0, air = 10.0, precip = 30.0),
                ),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended[0].temperatureC shouldBe (12.5 plusOrMinus 0.0001) // (15 + 10) / 2
        blended[0].feelsLikeC shouldBe (11.5 plusOrMinus 0.0001)   // (14 + 9) / 2
        blended[0].precipitationProbabilityPct shouldBe (55.0 plusOrMinus 0.0001) // (80 + 30) / 2
    }

    @Test
    fun `averages all models when two or more report a given hour`() {
        // Today's pattern: best_match predicts 30% rain at 12:00 but two
        // consulted models both predict 80%+. The consensus should beat the
        // outlier even with best_match included in the mean.
        val best = listOf(hour(12, temp = 10.0, feels = 9.0, precip = 30.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(perModel(12, apparent = 11.0, air = 12.0, precip = 80.0)),
                "icon_seamless" to listOf(perModel(12, apparent = 13.0, air = 14.0, precip = 90.0)),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended.size shouldBe 1
        blended[0].time shouldBe LocalTime.of(12, 0)
        blended[0].temperatureC shouldBe (13.0 plusOrMinus 0.0001)  // (12 + 14) / 2
        blended[0].feelsLikeC shouldBe (12.0 plusOrMinus 0.0001)    // (11 + 13) / 2
        blended[0].precipitationProbabilityPct shouldBe (85.0 plusOrMinus 0.0001) // (80 + 90) / 2
        // Condition stays from best_match for now (modal aggregation is a follow-up).
        blended[0].condition shouldBe WeatherCondition.CLEAR
    }

    @Test
    fun `falls back to best-match for an hour only one model covered`() {
        // 12:00 has two models → averaged.
        // 15:00 has just one model → keeps best_match.
        val best = listOf(
            hour(12, temp = 10.0, feels = 9.0, precip = 30.0),
            hour(15, temp = 11.0, feels = 10.0, precip = 40.0),
        )
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    perModel(12, apparent = 11.0, air = 12.0, precip = 80.0),
                    perModel(15, apparent = 14.0, air = 15.0, precip = 90.0),
                ),
                "icon_seamless" to listOf(
                    perModel(12, apparent = 13.0, air = 14.0, precip = 90.0),
                    // 15:00 missing from icon.
                ),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended[0].temperatureC shouldBe (13.0 plusOrMinus 0.0001)  // consensus
        blended[1] shouldBe best[1]                                   // fell back
    }

    @Test
    fun `precipitation falls back to best-match when no candidate reported a value`() {
        // UKMO / JMA / GEM / ARPEGE routinely omit precipitation probability;
        // when *every* candidate at this hour has null precip, averaging
        // would NaN. Keep best_match's value instead so the precip chart and
        // rain-prose stay sensible.
        val best = listOf(hour(12, temp = 10.0, feels = 9.0, precip = 35.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "ukmo_seamless" to listOf(perModel(12, apparent = 11.0, air = 12.0, precip = null)),
                "jma_seamless" to listOf(perModel(12, apparent = 13.0, air = 14.0, precip = null)),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended[0].precipitationProbabilityPct shouldBe (35.0 plusOrMinus 0.0001)
        // Temp / feels still get blended — only precip falls back.
        blended[0].temperatureC shouldBe (13.0 plusOrMinus 0.0001)
        blended[0].feelsLikeC shouldBe (12.0 plusOrMinus 0.0001)
    }

    @Test
    fun `precipitation averages only the candidates that reported a value`() {
        // GFS provides a precip reading, UKMO doesn't. Including a synthetic
        // 0 for UKMO would silently halve the blended rain probability when
        // GFS predicts rain — drop the null candidate from the precip mean
        // and keep GFS's reading.
        val best = listOf(hour(12, temp = 10.0, feels = 9.0, precip = 20.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(perModel(12, apparent = 11.0, air = 12.0, precip = 80.0)),
                "ukmo_seamless" to listOf(perModel(12, apparent = 13.0, air = 14.0, precip = null)),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        // 80 (GFS only) — UKMO's null is skipped rather than averaged as 0.
        blended[0].precipitationProbabilityPct shouldBe (80.0 plusOrMinus 0.0001)
    }

    @Test
    fun `rain amount blends on hours best-match covers, like the synthesized path`() {
        // best_match says 0 mm at noon while the consulted models agree on
        // 2 mm. Keeping best_match's raw amount on covered hours (while
        // synthesized hours average the models) made the series' shape
        // reflect best_match's coverage, not the weather — the blend must
        // treat both paths the same.
        val best = listOf(hour(12, temp = 10.0, feels = 9.0, mm = 0.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(perModel(12, apparent = 11.0, air = 12.0, mm = 2.0)),
                "icon_seamless" to listOf(perModel(12, apparent = 13.0, air = 14.0, mm = 2.0)),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended[0].precipitationMm shouldBe (2.0 plusOrMinus 0.0001)
    }

    @Test
    fun `rain amount falls back to best-match when no candidate reported it`() {
        // Models reported the hour but neither carried an mm series (Open-
        // Meteo omits it for some models) — keep best_match's own amount
        // rather than zeroing a wet hour.
        val best = listOf(hour(12, temp = 10.0, feels = 9.0, mm = 1.5))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(perModel(12, apparent = 11.0, air = 12.0, mm = null)),
                "icon_seamless" to listOf(perModel(12, apparent = 13.0, air = 14.0, mm = null)),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended[0].precipitationMm shouldBe (1.5 plusOrMinus 0.0001)
    }

    @Test
    fun `wind and uv are averaged across the candidates that reported them`() {
        // A model whose per-model entry carries null wind / UV (older cached
        // payloads; model runs that omit the field) sits the hour out — here
        // best_match's entry has neither, so the blend averages only the
        // consulted models and a lone spike on the [HourlyForecast] side
        // can't survive because it isn't a candidate for these fields.
        val best = listOf(hour(12, temp = 10.0, wind = 99.0, uv = 99.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(perModel(12, apparent = 11.0, air = 12.0, wind = 20.0, uv = 5.0)),
                "icon_seamless" to listOf(perModel(12, apparent = 13.0, air = 14.0, wind = 30.0, uv = 7.0)),
                PerModelHourly.BEST_MATCH_MODEL_ID to listOf(
                    perModel(12, apparent = 9.0, air = 10.0, wind = null, uv = null),
                ),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended[0].windSpeedKmh!! shouldBe (25.0 plusOrMinus 0.0001) // (20 + 30) / 2, best_match null skipped
        blended[0].uvIndex!! shouldBe (6.0 plusOrMinus 0.0001)       // (5 + 7) / 2
    }

    @Test
    fun `wind and uv fall back to best-match when no candidate reported them`() {
        // Two models report temp (so the hour blends) but neither carries
        // wind / UV — keep best_match's own values rather than nulling them.
        val best = listOf(hour(12, temp = 10.0, wind = 42.0, uv = 8.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(perModel(12, apparent = 11.0, air = 12.0, wind = null, uv = null)),
                "icon_seamless" to listOf(perModel(12, apparent = 13.0, air = 14.0, wind = null, uv = null)),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended[0].windSpeedKmh!! shouldBe (42.0 plusOrMinus 0.0001)
        blended[0].uvIndex!! shouldBe (8.0 plusOrMinus 0.0001)
    }

    @Test
    fun `per-model entries on a different calendar day do not blend with best-match`() {
        // best_match is today's hourly only (indexed by LocalTime). Per-model
        // entries carry a full LocalDateTime, so the same hour-of-day on a
        // neighbouring date must not collide with today's slot — otherwise
        // tomorrow's 02:00 would alias against today's 02:00 in the tonight
        // wrap. With only one model covering today's 12:00 (the other is
        // tomorrow's), blending falls through and returns null.
        val tomorrow = today.plusDays(1)
        val best = listOf(hour(12, temp = 10.0, feels = 9.0, precip = 30.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(perModel(12, apparent = 11.0, air = 12.0, precip = 80.0)),
                "icon_seamless" to listOf(
                    perModel(12, apparent = 13.0, air = 14.0, precip = 90.0, date = tomorrow),
                ),
            ),
        )

        blendConsensusHourly(today, best, perModel).shouldBeNull()
    }

    @Test
    fun `a model reporting the DST fall-back hour twice votes once in the mean`() {
        // On the fall-back day Open-Meteo's local-time array repeats an hour,
        // so a model's series carries two physical entries at the same
        // LocalDateTime. The pair must collapse to one vote per model —
        // otherwise the duplicated model is double-weighted against a model
        // that reported the hour once (the double-voting policy
        // consensusPerModelAverage already documents and guards).
        val best = listOf(hour(1, temp = 12.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                // Both physical 01:00s at 10° → one collapsed 10° vote.
                "gfs_seamless" to listOf(
                    perModel(1, apparent = 9.0, air = 10.0),
                    perModel(1, apparent = 9.0, air = 10.0),
                ),
                "ecmwf_ifs04" to listOf(perModel(1, apparent = 15.0, air = 16.0)),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel)

        blended.shouldNotBeNull()
        // Equal-weight mean (10+16)/2 = 13, not the double-vote (10+10+16)/3 = 12.
        blended.single().temperatureC shouldBe (13.0 plusOrMinus 1e-9)
        blended.single().feelsLikeC shouldBe (12.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a lone model's duplicated fall-back hour is not a consensus`() {
        // One model reporting the repeated hour twice is still one model —
        // its duplicate pair must not clear the two-candidates bar and
        // overwrite best_match with a single-source "consensus".
        val best = listOf(hour(1, temp = 12.0))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    perModel(1, apparent = 9.0, air = 10.0),
                    perModel(1, apparent = 9.0, air = 10.0),
                ),
                // Second model exists but covers a different hour, so the
                // blend has no ≥2-model hour and returns null overall.
                "ecmwf_ifs04" to listOf(perModel(5, apparent = 15.0, air = 16.0)),
            ),
        )

        blendConsensusHourly(today, best, perModel).shouldBeNull()
    }

    @Test
    fun `condition is aggregated modally across models`() {
        // best_match says CLEAR but two of three consulted models say RAIN —
        // mode wins. This is the case the modal aggregation was added to
        // handle: the 90%-rain Combined line used to draw with a CLEAR icon
        // because we kept best_match's condition untouched.
        val best = listOf(hour(12, temp = 10.0, precip = 30.0, condition = WeatherCondition.CLEAR))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(perModel(12, apparent = 11.0, air = 12.0, precip = 80.0,
                    condition = WeatherCondition.RAIN)),
                "icon_seamless" to listOf(perModel(12, apparent = 13.0, air = 14.0, precip = 90.0,
                    condition = WeatherCondition.RAIN)),
                "ecmwf_ifs04" to listOf(perModel(12, apparent = 12.0, air = 13.0, precip = 60.0,
                    condition = WeatherCondition.CLOUDY)),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended[0].condition shouldBe WeatherCondition.RAIN
    }

    @Test
    fun `condition tie is broken by severity, favouring the more actionable bucket`() {
        // Two models say CLEAR, two say RAIN — mode is a 2-2 tie. Severity
        // tiebreak picks RAIN (the more actionable / outfit-relevant
        // outcome) rather than CLEAR.
        val best = listOf(hour(12, temp = 15.0, condition = WeatherCondition.CLEAR))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(perModel(12, apparent = 14.0, air = 15.0,
                    condition = WeatherCondition.CLEAR)),
                "icon_seamless" to listOf(perModel(12, apparent = 16.0, air = 17.0,
                    condition = WeatherCondition.RAIN)),
                "ecmwf_ifs04" to listOf(perModel(12, apparent = 15.0, air = 16.0,
                    condition = WeatherCondition.RAIN)),
                PerModelHourly.BEST_MATCH_MODEL_ID to listOf(perModel(12, apparent = 15.0, air = 16.0,
                    condition = WeatherCondition.CLEAR)),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended[0].condition shouldBe WeatherCondition.RAIN
    }

    @Test
    fun `condition falls back to best-match when no model reported one`() {
        // All four models present (so the numeric blend runs) but none of
        // them carried a weather code — keep best_match's condition rather
        // than rolling UNKNOWN through.
        val best = listOf(hour(12, temp = 10.0, condition = WeatherCondition.CLEAR))
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(perModel(12, apparent = 11.0, air = 12.0)),
                "icon_seamless" to listOf(perModel(12, apparent = 13.0, air = 14.0)),
            ),
        )

        val blended = blendConsensusHourly(today, best, perModel).shouldNotBeNull()

        blended[0].condition shouldBe WeatherCondition.CLEAR
    }

    @Test
    fun `returns null when no hour ended up blended`() {
        // Two consulted models present but covering different hours — no
        // single hour has 2+ models. Should signal "nothing blended" so the
        // caller leaves the upstream daily aggregates untouched.
        val best = listOf(
            hour(12, temp = 10.0, feels = 9.0, precip = 30.0),
            hour(15, temp = 11.0, feels = 10.0, precip = 40.0),
        )
        val perModel = PerModelHourly(
            byModel = mapOf(
                "gfs_seamless" to listOf(
                    perModel(12, apparent = 11.0, air = 12.0, precip = 80.0),
                    // 15:00 missing.
                ),
                "icon_seamless" to listOf(
                    // 12:00 missing.
                    perModel(15, apparent = 13.0, air = 14.0, precip = 90.0),
                ),
            ),
        )

        blendConsensusHourly(today, best, perModel).shouldBeNull()
    }

    @Test
    fun `withAggregatesFrom recomputes daily extremes from blended hourly`() {
        val daily = DailyForecast(
            date = java.time.LocalDate.of(2026, 5, 13),
            temperatureMinC = 5.0,
            temperatureMaxC = 15.0,
            feelsLikeMinC = 3.0,
            feelsLikeMaxC = 12.0,
            precipitationProbabilityMaxPct = 30.0,
            precipitationMmTotal = 2.0,
            condition = WeatherCondition.CLEAR,
        )
        val blended = listOf(
            hour(12, temp = 12.0, feels = 10.0, precip = 80.0, mm = 0.2),
            hour(15, temp = 14.0, feels = 12.0, precip = 90.0, mm = 0.5),
            hour(18, temp = 10.0, feels = 8.0, precip = 60.0, mm = 0.2),
        )

        val out = daily.withAggregatesFrom(blended)

        out.temperatureMinC shouldBe (10.0 plusOrMinus 0.0001)
        out.temperatureMaxC shouldBe (14.0 plusOrMinus 0.0001)
        out.feelsLikeMinC shouldBe (8.0 plusOrMinus 0.0001)
        out.feelsLikeMaxC shouldBe (12.0 plusOrMinus 0.0001)
        // The peak rain probability now reflects the consensus — the daily
        // umbrella rule keys off this, so without recomputation the chart
        // would show "100% rain" while the umbrella stayed unrecommended.
        out.precipitationProbabilityMaxPct shouldBe (90.0 plusOrMinus 0.0001)
        // The daily rain total is the sum of the blended per-hour amounts,
        // keeping it in step with the hourly series the chart draws.
        // Condition is recomputed from the peak-precip hour, CLEAR here too.
        out.precipitationMmTotal shouldBe (0.9 plusOrMinus 0.0001)
        out.condition shouldBe WeatherCondition.CLEAR
    }

    @Test
    fun `withAggregatesFrom takes the daily condition from the peak-precip hour`() {
        // best_match's daily code says CLEAR, but the blended hourly turns the
        // day snowy at its wettest hour. The recomputed daily condition must
        // follow it so the week-ahead headline names snow, not plain rain.
        val daily = DailyForecast(
            date = java.time.LocalDate.of(2026, 5, 13),
            temperatureMinC = 0.0,
            temperatureMaxC = 4.0,
            feelsLikeMinC = -2.0,
            feelsLikeMaxC = 2.0,
            precipitationProbabilityMaxPct = 20.0,
            precipitationMmTotal = 1.0,
            condition = WeatherCondition.CLEAR,
        )
        val blended = listOf(
            hour(12, temp = 3.0, precip = 40.0, condition = WeatherCondition.CLOUDY),
            hour(15, temp = 2.0, precip = 85.0, condition = WeatherCondition.SNOW),
            hour(18, temp = 1.0, precip = 50.0, condition = WeatherCondition.CLOUDY),
        )

        val out = daily.withAggregatesFrom(blended)

        out.precipitationProbabilityMaxPct shouldBe (85.0 plusOrMinus 0.0001)
        out.condition shouldBe WeatherCondition.SNOW
    }

    @Test
    fun `withAggregatesFrom keeps best-match condition when the peak hour is UNKNOWN`() {
        val daily = DailyForecast(
            date = java.time.LocalDate.of(2026, 5, 13),
            temperatureMinC = 5.0,
            temperatureMaxC = 15.0,
            feelsLikeMinC = 3.0,
            feelsLikeMaxC = 12.0,
            precipitationProbabilityMaxPct = 30.0,
            precipitationMmTotal = 2.0,
            condition = WeatherCondition.CLOUDY,
        )
        val blended = listOf(
            hour(12, temp = 12.0, precip = 70.0, condition = WeatherCondition.UNKNOWN),
        )

        val out = daily.withAggregatesFrom(blended)

        out.condition shouldBe WeatherCondition.CLOUDY
    }

    @Test
    fun `withAggregatesFrom leaves daily unchanged when blended hourly is empty`() {
        val daily = DailyForecast(
            date = java.time.LocalDate.of(2026, 5, 13),
            temperatureMinC = 5.0,
            temperatureMaxC = 15.0,
            feelsLikeMinC = 3.0,
            feelsLikeMaxC = 12.0,
            precipitationProbabilityMaxPct = 30.0,
            precipitationMmTotal = 2.0,
            condition = WeatherCondition.CLEAR,
        )

        daily.withAggregatesFrom(emptyList()) shouldBe daily
    }
}
