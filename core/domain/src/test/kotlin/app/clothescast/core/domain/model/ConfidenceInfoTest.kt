package app.clothescast.core.domain.model

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ConfidenceInfoTest {

    private val today: LocalDate = LocalDate.of(2026, 5, 14)

    private fun hour(h: Int, apparent: Double, precip: Double = 0.0) = PerModelHour(
        time = LocalDateTime.of(today, LocalTime.of(h, 0)),
        apparentTemperatureC = apparent,
        temperatureC = apparent,
        precipitationProbabilityPct = precip,
    )

    @Test
    fun `null when fewer than two consulted models have data`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(hour(12, 20.0)),
            ),
        )
        ConfidenceInfo.computeFrom(data).shouldBeNull()
    }

    @Test
    fun `excludes best_match from the consulted set`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(hour(12, 20.0)),
                PerModelHourly.BEST_MATCH_MODEL_ID to listOf(hour(12, 99.0)),
            ),
        )
        // Only one model remains after dropping best_match — not enough
        // to compute a spread.
        ConfidenceInfo.computeFrom(data).shouldBeNull()
    }

    @Test
    fun `tight spread emits HIGH tier`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(hour(12, 20.0, 5.0)),
                "gfs_seamless" to listOf(hour(12, 20.5, 10.0)),
                "icon_seamless" to listOf(hour(12, 19.8, 8.0)),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.level shouldBe ForecastConfidence.HIGH
        info.tempSpreadC shouldBe (0.7 plusOrMinus 0.0001)
        info.precipSpreadPp shouldBe (5.0 plusOrMinus 0.0001)
        info.modelsConsulted shouldContainExactlyInAnyOrder
            listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless")
    }

    @Test
    fun `temp spread alone can drive LOW`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(hour(12, 18.0)),
                "gfs_seamless" to listOf(hour(12, 24.5)),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (6.5 plusOrMinus 0.0001)
    }

    @Test
    fun `precip spread alone can drive LOW`() {
        // Tight temps, wide rain.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(hour(12, 20.0, 10.0)),
                "gfs_seamless" to listOf(hour(12, 20.5, 55.0)),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (0.5 plusOrMinus 0.0001)
        info.precipSpreadPp shouldBe (45.0 plusOrMinus 0.0001)
    }

    @Test
    fun `spread is computed over the hours present, so slicing changes the tier`() {
        // Both models peak around 20°C during the day. ECMWF then sees a
        // sustained 25°C late-evening warm front that GFS doesn't pick up
        // (GFS cools normally to 16°C). Because the divergence lasts two
        // hours it survives the spike filter, so the full-day high becomes
        // 25 / 20.5 — a 4.5°C spread, LOW tier. The daytime-only slice
        // drops the evening hours, so the high is 20.5 / 20.5 — HIGH tier.
        // Validates the whole point of windowed compute: the chip's title
        // reflects the interval the user is actually shown, not the full
        // calendar day.
        val fullDay = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    hour(12, 20.0), hour(13, 20.5), hour(14, 20.0),
                    hour(21, 25.0), hour(22, 25.0), // sustained late-evening warm front
                ),
                "gfs_seamless" to listOf(
                    hour(12, 20.5), hour(13, 20.0), hour(14, 20.5),
                    hour(21, 16.0), hour(22, 16.0), // normal evening cooling
                ),
            ),
        )
        val fullDayInfo = ConfidenceInfo.computeFrom(fullDay).shouldNotBeNull()
        fullDayInfo.level shouldBe ForecastConfidence.LOW
        fullDayInfo.tempSpreadC shouldBe (4.5 plusOrMinus 0.0001)

        // Daytime-only slice — drops the evening hours.
        val daytime = PerModelHourly(
            byModel = fullDay.byModel.mapValues { (_, hours) ->
                hours.filter { it.time.toLocalTime() < LocalTime.of(21, 0) }
            },
        )
        val daytimeInfo = ConfidenceInfo.computeFrom(daytime).shouldNotBeNull()
        daytimeInfo.level shouldBe ForecastConfidence.HIGH
    }

    @Test
    fun `a lone single-hour spike does not downgrade the tier`() {
        // Both models agree the day tops out around 20°C, but GFS has an
        // isolated 14:00 feels-like spike to 24°C that ECMWF doesn't see.
        // The raw per-model max would read 24 / 20 — a 4°C spread, LOW
        // tier — but a single divergent hour shouldn't define the day. The
        // 3-hour median filter erases the lone interior spike, so the highs
        // agree and the tier stays HIGH. This is the whole point of the
        // change. (An hour at the very edge of the window is only damped,
        // not erased — there's no neighbor on the far side to vote it down
        // — so the spike sits mid-window here, where the daytime slice puts
        // a real mid-afternoon disagreement.)
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    hour(12, 20.0), hour(13, 20.0), hour(14, 20.0), hour(15, 20.0), hour(16, 20.0),
                ),
                "gfs_seamless" to listOf(
                    hour(12, 20.0), hour(13, 20.0), hour(14, 24.0), hour(15, 20.0), hour(16, 20.0),
                ),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.level shouldBe ForecastConfidence.HIGH
        info.tempSpreadC shouldBe (0.0 plusOrMinus 0.0001)
    }

    @Test
    fun `a sustained two-hour divergence still downgrades the tier`() {
        // Same shape as the spike test, but GFS now runs 4°C hot for two
        // consecutive hours (15:00 and 16:00). A real two-hour disagreement
        // survives the median filter, so the high spread stays 4°C and the
        // tier drops — the filter removes noise, not genuine divergence.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    hour(12, 20.0), hour(13, 20.0), hour(14, 20.0), hour(15, 20.0), hour(16, 20.0),
                ),
                "gfs_seamless" to listOf(
                    hour(12, 20.0), hour(13, 20.0), hour(14, 20.0), hour(15, 24.0), hour(16, 24.0),
                ),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (4.0 plusOrMinus 0.0001)
    }

    @Test
    fun `a real extreme isolated by dropped-hour gaps is not filtered as a spike`() {
        // GFS's genuine 24°C daily high sits at 14:00, but 13:00 and 15:00
        // were dropped (null temperature_2m), so in the list it's flanked by
        // the 12:00 and 16:00 samples — two hours away on each side. If the
        // filter ignored timestamps it would read [20, 24, 20] as a one-hour
        // spike, suppress the 24 down to 20, and report HIGH agreement with
        // ECMWF — overconfident. Because the filter checks clock adjacency,
        // the gap-isolated 24 is kept, the high spread is the real 4°C, and
        // the tier correctly drops.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    hour(12, 20.0), hour(13, 20.0), hour(14, 20.0), hour(15, 20.0), hour(16, 20.0),
                ),
                "gfs_seamless" to listOf(
                    hour(12, 20.0), hour(14, 24.0), hour(16, 20.0), // 13:00 + 15:00 dropped
                ),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (4.0 plusOrMinus 0.0001)
    }

    @Test
    fun `disagreement on the daily low alone can drive the tier down`() {
        // Both models agree on the daytime high (20°C), but split on the
        // overnight low: ECMWF bottoms out at 18°C, GFS at 12°C. The low
        // disagreement is the wider of the two spreads, so it drives the
        // tier — models that line up on the peak but not the low are still
        // telling the user different things.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    hour(12, 20.0), hour(13, 20.0), hour(14, 18.0), hour(15, 18.0), hour(16, 18.0),
                ),
                "gfs_seamless" to listOf(
                    hour(12, 20.0), hour(13, 20.0), hour(14, 12.0), hour(15, 12.0), hour(16, 12.0),
                ),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.level shouldBe ForecastConfidence.LOW
        info.tempSpreadC shouldBe (6.0 plusOrMinus 0.0001)
    }

    @Test
    fun `models with empty hour lists are skipped`() {
        // A model whose slice yields no hours (e.g. dropped during the
        // tonight-window slice when the run started after midnight) is
        // ignored rather than crashing on maxOf over an empty list.
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to emptyList(),
                "gfs_seamless" to listOf(hour(12, 20.0)),
                "icon_seamless" to listOf(hour(12, 21.0)),
            ),
        )
        val info = ConfidenceInfo.computeFrom(data).shouldNotBeNull()
        info.tempSpreadC shouldBe (1.0 plusOrMinus 0.0001)
        info.modelsConsulted shouldContainExactlyInAnyOrder
            listOf("gfs_seamless", "icon_seamless")
    }
}
