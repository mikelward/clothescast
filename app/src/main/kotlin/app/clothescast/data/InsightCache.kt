package app.clothescast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.clothescast.core.domain.model.AlertClause
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.CalendarTieInClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.Fact
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.GarmentReason
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitRationale
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherCondition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Persists the most recently generated [Insight] so the daily worker can avoid
 * regenerating twice for the same day. The cache key is the insight's `forDate`
 * (LocalDate), which means two runs on the same calendar day reuse the same
 * insight even across process kills, reboots, or "Fire insight now" debug taps.
 *
 * The cache stores the structured [InsightSummary] (each clause as typed data)
 * rather than rendered prose, so a Region-setting change re-renders the cached
 * insight in the new locale without re-fetching the forecast.
 *
 * The cache is intentionally a single slot per period — there's no historical
 * browsing planned, and bounding storage at 2 entries (TODAY + TONIGHT) keeps the
 * cost of corruption (deserialization failure → drop and regenerate) trivial.
 */
class InsightCache(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * The most recent insight written to the cache, regardless of period. The Today
     * screen surfaces this so opening the app after the tonight alarm fired shows
     * the tonight insight (the freshest read of what the user is about to walk
     * into), while opening the app at noon still shows the morning insight.
     */
    val latest: Flow<Insight?> = dataStore.data.map { prefs ->
        listOfNotNull(
            prefs.readSlot(TODAY_INSIGHT_JSON),
            prefs.readSlot(TONIGHT_INSIGHT_JSON),
        ).maxByOrNull { it.generatedAt }
    }

    fun latestForPeriod(period: ForecastPeriod): Flow<Insight?> = dataStore.data.map { prefs ->
        prefs.readSlot(keyFor(period))
    }

    suspend fun store(insight: Insight) {
        dataStore.edit { it[keyFor(insight.period)] = json.encodeToString(insight.toDto()) }
    }

    /**
     * Re-derives the [Insight.outfit] / [Insight.outfitRationale] for every cached
     * slot against the supplied [clothesRules] and [defaultBottom], and writes the
     * updated insight back. Used by the settings UI after a clothes-rule edit so
     * the home-screen icon flips immediately instead of waiting for the next
     * worker run; without this, changing the picker has no visible effect on the
     * current forecast until the next scheduled or manual refresh.
     *
     * Each slot's [Insight.nextOutfit] is recomputed from the *paired* slot's
     * hourly data — TODAY's `nextOutfit` is TONIGHT's pick, so we read TONIGHT's
     * hourly to re-derive it (and vice versa is moot — TONIGHT's `nextOutfit` is
     * tomorrow's pick, which we don't cache, so it stays as the worker last
     * wrote it). Without this, the home screen's "Today + Tonight" preview row
     * would flip the today icon but leave the tonight icon stale, making the
     * setting look half-applied.
     *
     * Slots without enough hourly entries to reconstruct a [DailyForecast] are
     * skipped — the rest of the insight stays as-is and the picker just inherits
     * the stale outfit until the next worker run.
     */
    suspend fun recomputeOutfits(
        clothesRules: List<ClothesRule>,
        defaultBottom: OutfitSuggestion.Bottom,
    ) {
        dataStore.edit { prefs ->
            // Read both slots up front so TODAY's `nextOutfit` can borrow
            // TONIGHT's hourly when re-deriving.
            val todayInsight = prefs[TODAY_INSIGHT_JSON]?.let { decodeInsight(it) }
            val tonightInsight = prefs[TONIGHT_INSIGHT_JSON]?.let { decodeInsight(it) }
            // Only pair the slots when their forDate matches — during a morning
            // settings edit, the TONIGHT slot may still be yesterday's evening
            // insight while the TODAY slot is current. Rewriting today's
            // `nextOutfit` from yesterday's overnight hourly would silently
            // backdate the home-screen "Tonight" icon to last night's conditions
            // (which already happened); preserving the existing `nextOutfit` is
            // the honest choice until the next worker run rebuilds TONIGHT.
            val tonightPair = tonightInsight?.takeIf { it.forDate == todayInsight?.forDate }
            todayInsight?.recomputed(
                clothesRules = clothesRules,
                defaultBottom = defaultBottom,
                nextSlot = tonightPair,
            )?.let { prefs[TODAY_INSIGHT_JSON] = json.encodeToString(it.toDto()) }
            tonightInsight?.recomputed(
                clothesRules = clothesRules,
                defaultBottom = defaultBottom,
                nextSlot = null,
            )?.let { prefs[TONIGHT_INSIGHT_JSON] = json.encodeToString(it.toDto()) }
        }
    }

    private fun decodeInsight(raw: String): Insight? =
        runCatching { json.decodeFromString<InsightDto>(raw).toDomain() }.getOrNull()

    /**
     * Returns this insight with its `outfit` and (when [nextSlot] supplies the
     * paired hourly) `nextOutfit` re-derived against the supplied rules. Returns
     * `null` when this slot has no hourly data — there's nothing to recompute
     * against, so the caller leaves the slot alone.
     *
     * When [nextSlot] is null or hourly-less, this slot's existing `nextOutfit`
     * / `nextOutfitRationale` are preserved verbatim — only the next worker run
     * can refresh them.
     */
    private fun Insight.recomputed(
        clothesRules: List<ClothesRule>,
        defaultBottom: OutfitSuggestion.Bottom,
        nextSlot: Insight?,
    ): Insight? {
        val selfForecast = toReconstructedForecast() ?: return null
        val nextForecast = nextSlot?.toReconstructedForecast()
        return copy(
            outfit = OutfitSuggestion.fromForecast(selfForecast, clothesRules, defaultBottom),
            outfitRationale = OutfitSuggestion.explainFromForecast(selfForecast, clothesRules),
            nextOutfit = if (nextForecast != null) {
                OutfitSuggestion.fromForecast(nextForecast, clothesRules, defaultBottom)
            } else nextOutfit,
            nextOutfitRationale = if (nextForecast != null) {
                OutfitSuggestion.explainFromForecast(nextForecast, clothesRules)
            } else nextOutfitRationale,
        )
    }

    /**
     * Rebuilds the daily-aggregate fields ([DailyForecast.feelsLikeMinC] etc.)
     * from the cached hourly entries. Rule conditions only look at the min /
     * max / probability aggregates and at `hourly` itself, so a forecast
     * reconstructed this way is sufficient for [OutfitSuggestion.fromForecast]
     * even though we don't cache `precipitationMmTotal` or the high-level
     * [WeatherCondition] (the picker doesn't read either).
     */
    private fun Insight.toReconstructedForecast(): DailyForecast? {
        if (hourly.isEmpty()) return null
        return DailyForecast(
            date = forDate,
            temperatureMinC = hourly.minOf { it.temperatureC },
            temperatureMaxC = hourly.maxOf { it.temperatureC },
            feelsLikeMinC = hourly.minOf { it.feelsLikeC },
            feelsLikeMaxC = hourly.maxOf { it.feelsLikeC },
            precipitationProbabilityMaxPct = hourly.maxOf { it.precipitationProbabilityPct },
            precipitationMmTotal = 0.0,
            condition = WeatherCondition.CLEAR,
            hourly = hourly,
        )
    }

    suspend fun forToday(today: LocalDate): Insight? = forPeriodToday(today, ForecastPeriod.TODAY)

    suspend fun forPeriodToday(today: LocalDate, period: ForecastPeriod): Insight? {
        val cached = latestForPeriod(period).first() ?: return null
        return cached.takeIf { it.forDate == today }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(TODAY_INSIGHT_JSON)
            it.remove(TONIGHT_INSIGHT_JSON)
        }
    }

    private fun Preferences.readSlot(key: androidx.datastore.preferences.core.Preferences.Key<String>): Insight? {
        val raw = this[key] ?: return null
        return runCatching { json.decodeFromString<InsightDto>(raw).toDomain() }.getOrNull()
    }

    private fun keyFor(period: ForecastPeriod): androidx.datastore.preferences.core.Preferences.Key<String> =
        when (period) {
            ForecastPeriod.TODAY -> TODAY_INSIGHT_JSON
            ForecastPeriod.TONIGHT -> TONIGHT_INSIGHT_JSON
        }

    @Serializable
    private data class InsightDto(
        val summary: InsightSummaryDto,
        val recommendedItems: List<String>,
        val generatedAtEpochMillis: Long,
        val forDateEpochDays: Long,
        val hourly: List<HourlyDto> = emptyList(),
        val outfit: OutfitDto? = null,
        val nextOutfit: OutfitDto? = null,
        val outfitRationale: OutfitRationaleDto? = null,
        val nextOutfitRationale: OutfitRationaleDto? = null,
        val period: String = ForecastPeriod.TODAY.name,
        val hasEvents: Boolean = false,
        val location: LocationDto? = null,
        val confidence: ConfidenceInfoDto? = null,
        val perModelHourly: PerModelHourlyDto? = null,
    ) {
        fun toDomain(): Insight {
            val date = LocalDate.ofEpochDay(forDateEpochDays)
            return Insight(
                summary = summary.toDomain(),
                recommendedItems = recommendedItems,
                generatedAt = Instant.ofEpochMilli(generatedAtEpochMillis),
                forDate = date,
                location = location?.toDomain(),
                hourly = hourly.map { it.toDomain() },
                confidence = confidence?.toDomain(),
                perModelHourly = perModelHourly?.toDomain(date),
                outfit = outfit?.toDomain(),
                nextOutfit = nextOutfit?.toDomain(),
                outfitRationale = outfitRationale?.toDomain(),
                nextOutfitRationale = nextOutfitRationale?.toDomain(),
                period = runCatching { ForecastPeriod.valueOf(period) }.getOrDefault(ForecastPeriod.TODAY),
                hasEvents = hasEvents,
            )
        }
    }

    @Serializable
    private data class PerModelHourlyDto(
        val byModel: Map<String, List<PerModelHourDto>>,
    ) {
        // Returns null when any entry on any model lacks [PerModelHourDto.temperatureC]
        // — i.e., the cached payload was written by a version that only carried
        // apparent temperature. Dropping the whole overlay (rather than backfilling
        // a placeholder 0°C) means the air-temp model lines simply hide until the
        // next worker refresh repopulates the cache; the alternative would draw a
        // glaringly-wrong flat-0 line.
        //
        // [date] is the surrounding insight's `forDate` — used to rebuild each
        // entry's [PerModelHour.time] LocalDateTime. GenerateDailyInsight only
        // ever caches today's per-model hours (the slice in `slicedTo` narrows
        // to today's daytime), so pairing with `forDate` reconstructs the
        // correct date for every entry.
        fun toDomain(date: LocalDate): PerModelHourly? {
            val out = LinkedHashMap<String, List<PerModelHour>>(byModel.size)
            for ((model, hours) in byModel) {
                val converted = ArrayList<PerModelHour>(hours.size)
                for (dto in hours) converted += dto.toDomain(date) ?: return null
                out[model] = converted
            }
            return PerModelHourly(byModel = out)
        }
    }

    @Serializable
    private data class PerModelHourDto(
        val secondOfDay: Int,
        val apparentTemperatureC: Double,
        // Nullable for backwards compat: pre-temperatureC cache payloads omit it,
        // and we surface that as "no overlay" rather than a fake 0°C line.
        val temperatureC: Double? = null,
        val precipitationProbabilityPct: Double,
        // Diagnostic fields — nullable in the domain too, so missing values just
        // hide that model from the wind / humidity / cloud chart rather than
        // dropping the temperature overlay entirely.
        val windSpeedKmh: Double? = null,
        val relativeHumidityPct: Double? = null,
        val cloudCoverPct: Double? = null,
        // Weather code bucket, stored as the enum name so a future enum
        // rename trips deserialisation rather than silently mapping to the
        // wrong bucket. Nullable for back-compat with payloads written
        // before the modal condition aggregation landed.
        val conditionName: String? = null,
    ) {
        fun toDomain(date: LocalDate): PerModelHour? {
            val airTemp = temperatureC ?: return null
            return PerModelHour(
                time = LocalDateTime.of(date, LocalTime.ofSecondOfDay(secondOfDay.toLong())),
                apparentTemperatureC = apparentTemperatureC,
                temperatureC = airTemp,
                precipitationProbabilityPct = precipitationProbabilityPct,
                windSpeedKmh = windSpeedKmh,
                relativeHumidityPct = relativeHumidityPct,
                cloudCoverPct = cloudCoverPct,
                condition = conditionName?.let {
                    runCatching { WeatherCondition.valueOf(it) }.getOrNull()
                },
            )
        }
    }

    @Serializable
    private data class ConfidenceInfoDto(
        val level: String,
        val tempSpreadC: Double,
        val precipSpreadPp: Double,
        val modelsConsulted: List<String>,
    ) {
        fun toDomain(): ConfidenceInfo = ConfidenceInfo(
            level = runCatching { ForecastConfidence.valueOf(level) }
                .getOrDefault(ForecastConfidence.MEDIUM),
            tempSpreadC = tempSpreadC,
            precipSpreadPp = precipSpreadPp,
            modelsConsulted = modelsConsulted,
        )
    }

    @Serializable
    private data class LocationDto(
        val latitude: Double,
        val longitude: Double,
        val displayName: String? = null,
    ) {
        fun toDomain(): Location = Location(
            latitude = latitude,
            longitude = longitude,
            displayName = displayName,
        )
    }

    @Serializable
    private data class InsightSummaryDto(
        val period: String,
        val band: BandDto,
        val alert: AlertDto? = null,
        val delta: DeltaDto? = null,
        val clothes: ClothesDto? = null,
        val precip: PrecipDto? = null,
        val calendarTieIn: CalendarTieInDto? = null,
        val eveningEventTieIn: EveningEventTieInDto? = null,
    ) {
        fun toDomain(): InsightSummary = InsightSummary(
            period = runCatching { ForecastPeriod.valueOf(period) }.getOrDefault(ForecastPeriod.TODAY),
            band = band.toDomain(),
            alert = alert?.toDomain(),
            delta = delta?.toDomain(),
            clothes = clothes?.toDomain(),
            precip = precip?.toDomain(),
            calendarTieIn = calendarTieIn?.toDomain(),
            eveningEventTieIn = eveningEventTieIn?.toDomain(),
        )
    }

    @Serializable
    private data class BandDto(val low: String, val high: String) {
        fun toDomain(): BandClause = BandClause(
            low = runCatching { TemperatureBand.valueOf(low) }.getOrDefault(TemperatureBand.MILD),
            high = runCatching { TemperatureBand.valueOf(high) }.getOrDefault(TemperatureBand.MILD),
        )
    }

    @Serializable
    private data class AlertDto(val event: String) {
        fun toDomain(): AlertClause = AlertClause(event)
    }

    @Serializable
    private data class DeltaDto(val degrees: Int, val direction: String) {
        fun toDomain(): DeltaClause = DeltaClause(
            degrees = degrees,
            direction = runCatching { DeltaClause.Direction.valueOf(direction) }
                .getOrDefault(DeltaClause.Direction.WARMER),
        )
    }

    @Serializable
    private data class ClothesDto(val items: List<String>) {
        fun toDomain(): ClothesClause = ClothesClause(items)
    }

    @Serializable
    private data class PrecipDto(
        val condition: String,
        val secondOfDay: Int,
        // Default to LIKELY so cached payloads written before the field existed
        // round-trip as the pre-tier "Rain at 3pm." form they were originally
        // generated with — same rationale as PrecipClause's domain default.
        val likelihood: String = PrecipLikelihood.LIKELY.name,
    ) {
        fun toDomain(): PrecipClause = PrecipClause(
            condition = runCatching { WeatherCondition.valueOf(condition) }
                .getOrDefault(WeatherCondition.UNKNOWN),
            time = LocalTime.ofSecondOfDay(secondOfDay.toLong()),
            likelihood = runCatching { PrecipLikelihood.valueOf(likelihood) }
                .getOrDefault(PrecipLikelihood.LIKELY),
        )
    }

    @Serializable
    private data class CalendarTieInDto(val item: String) {
        fun toDomain(): CalendarTieInClause = CalendarTieInClause(item = item)
    }

    @Serializable
    private data class EveningEventTieInDto(
        // The current shape carries every night-only clothes item the tie-in
        // names ("a jacket and coat"). Defaults to an empty list so payloads
        // written before this field landed decode without an explicit list.
        val items: List<String> = emptyList(),
        // Kept for back-compat: pre-multi-item payloads stored the single
        // chosen item here. On read we fold it into [items] when [items] is
        // empty; on write we also populate it with the first list entry so a
        // downgraded build reading new payloads still surfaces at least one
        // item.
        val item: String? = null,
        val rainSecondOfDay: Int? = null,
        // Nullable for back-compat: payloads written before the field landed
        // decode with `null`, and [toDomain] folds that to LIKELY — the same
        // wording the older code produced unconditionally.
        val likelihood: String? = null,
    ) {
        fun toDomain(): EveningEventTieInClause = EveningEventTieInClause(
            items = items.ifEmpty { item?.let { listOf(it) }.orEmpty() },
            rainTime = rainSecondOfDay?.let { LocalTime.ofSecondOfDay(it.toLong()) },
            likelihood = likelihood
                ?.let { runCatching { PrecipLikelihood.valueOf(it) }.getOrNull() }
                ?: PrecipLikelihood.LIKELY,
        )
    }

    @Serializable
    private data class OutfitDto(val top: String, val bottom: String) {
        fun toDomain(): OutfitSuggestion? {
            val t = runCatching { OutfitSuggestion.Top.valueOf(top) }.getOrNull() ?: return null
            val b = runCatching { OutfitSuggestion.Bottom.valueOf(bottom) }.getOrNull() ?: return null
            return OutfitSuggestion(t, b)
        }
    }

    @Serializable
    private data class OutfitRationaleDto(
        val top: GarmentReasonDto,
        val bottom: GarmentReasonDto,
    ) {
        fun toDomain(): OutfitRationale = OutfitRationale(
            top = top.toDomain(),
            bottom = bottom.toDomain(),
        )
    }

    @Serializable
    private data class GarmentReasonDto(val facts: List<FactDto>) {
        fun toDomain(): GarmentReason = GarmentReason(facts = facts.map { it.toDomain() })
    }

    @Serializable
    private data class FactDto(
        val metric: String,
        val observedC: Double,
        val observedAtSecondOfDay: Int? = null,
        val thresholdC: Double,
        val ruleItem: String,
        val comparison: String,
    ) {
        fun toDomain(): Fact = Fact(
            metric = runCatching { Fact.Metric.valueOf(metric) }
                .getOrDefault(Fact.Metric.FEELS_LIKE_MIN),
            observedC = observedC,
            observedAt = observedAtSecondOfDay?.let { LocalTime.ofSecondOfDay(it.toLong()) },
            thresholdC = thresholdC,
            ruleItem = ruleItem,
            comparison = runCatching { Fact.Comparison.valueOf(comparison) }
                .getOrDefault(Fact.Comparison.AT_OR_ABOVE),
        )
    }

    @Serializable
    private data class HourlyDto(
        val secondOfDay: Int,
        val temperatureC: Double,
        val feelsLikeC: Double,
        val precipitationProbabilityPct: Double,
        val condition: String,
    ) {
        fun toDomain(): HourlyForecast = HourlyForecast(
            time = LocalTime.ofSecondOfDay(secondOfDay.toLong()),
            temperatureC = temperatureC,
            feelsLikeC = feelsLikeC,
            precipitationProbabilityPct = precipitationProbabilityPct,
            condition = runCatching { WeatherCondition.valueOf(condition) }
                .getOrDefault(WeatherCondition.UNKNOWN),
        )
    }

    private fun Insight.toDto(): InsightDto = InsightDto(
        summary = summary.toDto(),
        recommendedItems = recommendedItems,
        generatedAtEpochMillis = generatedAt.toEpochMilli(),
        forDateEpochDays = forDate.toEpochDay(),
        hourly = hourly.map { it.toDto() },
        outfit = outfit?.let { OutfitDto(it.top.name, it.bottom.name) },
        nextOutfit = nextOutfit?.let { OutfitDto(it.top.name, it.bottom.name) },
        outfitRationale = outfitRationale?.toDto(),
        nextOutfitRationale = nextOutfitRationale?.toDto(),
        period = period.name,
        hasEvents = hasEvents,
        location = location?.let { LocationDto(it.latitude, it.longitude, it.displayName) },
        confidence = confidence?.toDto(),
        perModelHourly = perModelHourly?.toDto(),
    )

    private fun ConfidenceInfo.toDto(): ConfidenceInfoDto = ConfidenceInfoDto(
        level = level.name,
        tempSpreadC = tempSpreadC,
        precipSpreadPp = precipSpreadPp,
        modelsConsulted = modelsConsulted,
    )

    private fun PerModelHourly.toDto(): PerModelHourlyDto = PerModelHourlyDto(
        byModel = byModel.mapValues { (_, hours) -> hours.map { it.toDto() } },
    )

    private fun PerModelHour.toDto(): PerModelHourDto = PerModelHourDto(
        // Only the hour-of-day is persisted; the surrounding insight's
        // `forDate` rebuilds the LocalDateTime on read. Today's per-model
        // slice is the only thing GenerateDailyInsight caches, so the date
        // is unambiguous.
        secondOfDay = time.toLocalTime().toSecondOfDay(),
        apparentTemperatureC = apparentTemperatureC,
        temperatureC = temperatureC,
        precipitationProbabilityPct = precipitationProbabilityPct,
        windSpeedKmh = windSpeedKmh,
        relativeHumidityPct = relativeHumidityPct,
        cloudCoverPct = cloudCoverPct,
        conditionName = condition?.name,
    )

    private fun OutfitRationale.toDto(): OutfitRationaleDto = OutfitRationaleDto(
        top = top.toDto(),
        bottom = bottom.toDto(),
    )

    private fun GarmentReason.toDto(): GarmentReasonDto =
        GarmentReasonDto(facts = facts.map { it.toDto() })

    private fun Fact.toDto(): FactDto = FactDto(
        metric = metric.name,
        observedC = observedC,
        observedAtSecondOfDay = observedAt?.toSecondOfDay(),
        thresholdC = thresholdC,
        ruleItem = ruleItem,
        comparison = comparison.name,
    )

    private fun InsightSummary.toDto(): InsightSummaryDto = InsightSummaryDto(
        period = period.name,
        band = BandDto(band.low.name, band.high.name),
        alert = alert?.let { AlertDto(it.event) },
        delta = delta?.let { DeltaDto(it.degrees, it.direction.name) },
        clothes = clothes?.let { ClothesDto(it.items) },
        precip = precip?.let {
            PrecipDto(it.condition.name, it.time.toSecondOfDay(), it.likelihood.name)
        },
        calendarTieIn = calendarTieIn?.let { CalendarTieInDto(it.item) },
        eveningEventTieIn = eveningEventTieIn?.let {
            EveningEventTieInDto(
                items = it.items,
                // Mirror the first item into the legacy single-item field so a
                // downgraded build reading this payload still surfaces something
                // ("a jacket" instead of nothing) — the older reader doesn't
                // know about the multi-item list.
                item = it.items.firstOrNull(),
                rainSecondOfDay = it.rainTime?.toSecondOfDay(),
                likelihood = it.likelihood.name,
            )
        },
    )

    private fun HourlyForecast.toDto(): HourlyDto = HourlyDto(
        secondOfDay = time.toSecondOfDay(),
        temperatureC = temperatureC,
        feelsLikeC = feelsLikeC,
        precipitationProbabilityPct = precipitationProbabilityPct,
        condition = condition.name,
    )

    companion object {
        // Bumped when [Fact] swapped its `thresholdKind` enum for a free-form
        // [Fact.ruleItem] string keyed to a [ClothesRule.item] — old payloads
        // carried the enum name and the serializer can't infer a clothes-rule
        // key from it. Old payloads are dropped on first read; the next worker
        // run repopulates with the new schema.
        private val TODAY_INSIGHT_JSON = stringPreferencesKey("latest_insight_v5")
        private val TONIGHT_INSIGHT_JSON = stringPreferencesKey("latest_tonight_insight_v4")

        fun create(context: Context): InsightCache = InsightCache(context.insightDataStore)
    }
}

private val Context.insightDataStore: DataStore<Preferences> by preferencesDataStore(name = "insight_cache")
