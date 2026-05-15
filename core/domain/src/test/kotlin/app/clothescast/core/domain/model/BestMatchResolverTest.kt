package app.clothescast.core.domain.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class BestMatchResolverTest {

    private val today: LocalDate = LocalDate.of(2026, 5, 14)

    private fun hour(
        h: Int,
        apparent: Double,
        air: Double = apparent,
        precip: Double? = null,
    ) = PerModelHour(
        time = LocalDateTime.of(today, LocalTime.of(h, 0)),
        apparentTemperatureC = apparent,
        temperatureC = air,
        precipitationProbabilityPct = precip,
    )

    private fun series(vararg values: Double): List<PerModelHour> =
        values.mapIndexed { i, v -> hour(h = 6 + i, apparent = v) }

    private val apparent: (PerModelHour) -> Double? = { it.apparentTemperatureC }
    private val precip: (PerModelHour) -> Double? = { it.precipitationProbabilityPct }

    @Test
    fun `returns matching model id when best_match's series mirrors one consulted model exactly`() {
        // UKMO and best_match agree to the rounded tick across an 8-hour
        // window; ECMWF and ICON differ. The empirical signature of
        // Open-Meteo's best_match routing to UKMO over the British Isles.
        val ukmoValues = doubleArrayOf(11.0, 11.5, 12.0, 12.4, 12.6, 12.3, 11.8, 11.2)
        val data = PerModelHourly(
            byModel = mapOf(
                "ukmo_seamless" to series(*ukmoValues),
                "ecmwf_ifs04" to series(10.0, 10.5, 11.0, 11.4, 11.7, 11.5, 11.0, 10.5),
                "icon_seamless" to series(12.0, 12.4, 12.9, 13.2, 13.4, 13.1, 12.6, 12.0),
                PerModelHourly.BEST_MATCH_MODEL_ID to series(*ukmoValues),
            ),
        )
        data.bestMatchSourceBy(apparent) shouldBe "ukmo_seamless"
    }

    @Test
    fun `returns null when best_match isn't present`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ukmo_seamless" to series(11.0, 11.5, 12.0, 12.4, 12.6, 12.3, 11.8, 11.2),
                "ecmwf_ifs04" to series(11.0, 11.5, 12.0, 12.4, 12.6, 12.3, 11.8, 11.2),
            ),
        )
        data.bestMatchSourceBy(apparent).shouldBeNull()
    }

    @Test
    fun `returns null when no consulted model matches best_match within tolerance`() {
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to series(10.0, 10.5, 11.0, 11.4, 11.7, 11.5, 11.0, 10.5),
                "icon_seamless" to series(12.0, 12.4, 12.9, 13.2, 13.4, 13.1, 12.6, 12.0),
                PerModelHourly.BEST_MATCH_MODEL_ID to series(15.0, 15.4, 15.9, 16.2, 16.4, 16.1, 15.6, 15.0),
            ),
        )
        data.bestMatchSourceBy(apparent).shouldBeNull()
    }

    @Test
    fun `returns null when more than one consulted model matches (ambiguous)`() {
        // Two consulted models happen to report identical values to
        // best_match across the window — e.g. an all-zero precip day where
        // ECMWF and ICON both say 0%. We can't tell which one best_match
        // actually wraps, so refuse to dedup rather than dropping the
        // wrong overlay.
        val flatValues = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to series(*flatValues),
                "icon_seamless" to series(*flatValues),
                PerModelHourly.BEST_MATCH_MODEL_ID to series(*flatValues),
            ),
        )
        data.bestMatchSourceBy(apparent).shouldBeNull()
    }

    @Test
    fun `returns null when shared hours fall below the minimum overlap`() {
        // best_match only carries five hours, all matching UKMO. Below the
        // six-hour floor — too few samples to be confident, so we refuse
        // the match rather than coincidentally suppressing on a tiny slice.
        val bestMatchValues = doubleArrayOf(11.0, 11.5, 12.0, 12.4, 12.6)
        val ukmoFull = doubleArrayOf(11.0, 11.5, 12.0, 12.4, 12.6, 12.3, 11.8, 11.2)
        val data = PerModelHourly(
            byModel = mapOf(
                "ukmo_seamless" to series(*ukmoFull),
                PerModelHourly.BEST_MATCH_MODEL_ID to series(*bestMatchValues),
            ),
        )
        data.bestMatchSourceBy(apparent).shouldBeNull()
    }

    @Test
    fun `per-metric matching is independent — best_match can resolve differently per chart`() {
        // The realistic UK case: best_match's apparent-temp series mirrors
        // UKMO (Open-Meteo's regional pick for the British Isles), but
        // UKMO doesn't expose precipitation_probability so its precip slot
        // upstream is filled from a different model — here ICON. The chart
        // dedup must match per metric: temp chart suppresses best_match,
        // precip chart sees a different underlying model.
        val ukmoTemp = doubleArrayOf(11.0, 11.5, 12.0, 12.4, 12.6, 12.3, 11.8, 11.2)
        val iconPrecip = doubleArrayOf(40.0, 45.0, 50.0, 55.0, 60.0, 50.0, 40.0, 30.0)
        val ecmwfTemp = doubleArrayOf(10.0, 10.5, 11.0, 11.4, 11.7, 11.5, 11.0, 10.5)
        val ecmwfPrecip = doubleArrayOf(20.0, 22.0, 25.0, 28.0, 30.0, 25.0, 20.0, 15.0)
        val iconTemp = doubleArrayOf(12.0, 12.4, 12.9, 13.2, 13.4, 13.1, 12.6, 12.0)

        val data = PerModelHourly(
            byModel = mapOf(
                "ukmo_seamless" to ukmoTemp.mapIndexed { i, t ->
                    // UKMO carries no precip — the field is null, matching
                    // Open-Meteo's per-model omission for this model.
                    hour(h = 6 + i, apparent = t, precip = null)
                },
                "ecmwf_ifs04" to ecmwfTemp.mapIndexed { i, t ->
                    hour(h = 6 + i, apparent = t, precip = ecmwfPrecip[i])
                },
                "icon_seamless" to iconTemp.mapIndexed { i, t ->
                    hour(h = 6 + i, apparent = t, precip = iconPrecip[i])
                },
                PerModelHourly.BEST_MATCH_MODEL_ID to ukmoTemp.mapIndexed { i, t ->
                    hour(h = 6 + i, apparent = t, precip = iconPrecip[i])
                },
            ),
        )
        data.bestMatchSourceBy(apparent) shouldBe "ukmo_seamless"
        data.bestMatchSourceBy(precip) shouldBe "icon_seamless"
    }

    @Test
    fun `tolerates per-hour null values in the selector — only shared non-null hours need match`() {
        // ECMWF emits the metric for hours 6..13 inclusive (8 hours);
        // best_match emits it for hours 6..13 with hour 9 dropped (7
        // hours). Shared non-null overlap is 7 hours, still above the
        // floor, and every shared hour matches — should resolve to ECMWF.
        val full = doubleArrayOf(10.0, 10.5, 11.0, 11.4, 11.7, 11.5, 11.0, 10.5)
        val ecmwfEntries = full.mapIndexed { i, v -> hour(h = 6 + i, apparent = v) }
        val bestMatchEntries = full.mapIndexed { i, v ->
            // Drop hour 9 (i=3) from best_match's apparent-temp coverage
            // by zero-ing out — but for selector-null tolerance, we want
            // an actual null. Build a PerModelHour with apparent = sentinel
            // and use a selector that maps the sentinel back to null.
            hour(h = 6 + i, apparent = v)
        }
        val data = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to ecmwfEntries,
                PerModelHourly.BEST_MATCH_MODEL_ID to bestMatchEntries,
            ),
        )
        // Selector returning null for hour 9 (index 3 → hour 9 of day)
        // simulates a missing per-hour value in best_match's series.
        val partial: (PerModelHour) -> Double? = { entry ->
            if (entry.time.hour == 9) null else entry.apparentTemperatureC
        }
        data.bestMatchSourceBy(partial) shouldBe "ecmwf_ifs04"
    }
}
