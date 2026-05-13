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
        fun toDomain(): Insight = Insight(
            summary = summary.toDomain(),
            recommendedItems = recommendedItems,
            generatedAt = Instant.ofEpochMilli(generatedAtEpochMillis),
            forDate = LocalDate.ofEpochDay(forDateEpochDays),
            location = location?.toDomain(),
            hourly = hourly.map { it.toDomain() },
            confidence = confidence?.toDomain(),
            perModelHourly = perModelHourly?.toDomain(),
            outfit = outfit?.toDomain(),
            nextOutfit = nextOutfit?.toDomain(),
            outfitRationale = outfitRationale?.toDomain(),
            nextOutfitRationale = nextOutfitRationale?.toDomain(),
            period = runCatching { ForecastPeriod.valueOf(period) }.getOrDefault(ForecastPeriod.TODAY),
            hasEvents = hasEvents,
        )
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
        fun toDomain(): PerModelHourly? {
            val out = LinkedHashMap<String, List<PerModelHour>>(byModel.size)
            for ((model, hours) in byModel) {
                val converted = ArrayList<PerModelHour>(hours.size)
                for (dto in hours) converted += dto.toDomain() ?: return null
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
        fun toDomain(): PerModelHour? {
            val airTemp = temperatureC ?: return null
            return PerModelHour(
                time = LocalTime.ofSecondOfDay(secondOfDay.toLong()),
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
        val item: String,
        val rainSecondOfDay: Int? = null,
    ) {
        fun toDomain(): EveningEventTieInClause = EveningEventTieInClause(
            item = item,
            rainTime = rainSecondOfDay?.let { LocalTime.ofSecondOfDay(it.toLong()) },
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
        secondOfDay = time.toSecondOfDay(),
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
            EveningEventTieInDto(it.item, it.rainTime?.toSecondOfDay())
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
