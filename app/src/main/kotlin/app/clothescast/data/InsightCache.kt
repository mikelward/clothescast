package app.clothescast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.DailyHistoryEntry
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.repository.ForecastBundle
import app.clothescast.core.domain.usecase.DailyInsightResult
import app.clothescast.core.domain.usecase.DeriveInsight
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
import java.time.ZoneId

/**
 * Persists the most recently captured upstream [ForecastSnapshot]s — the raw
 * weather bundle from Open-Meteo plus the calendar events we read off the
 * device — so consumers can re-derive an [app.clothescast.core.domain.model.Insight]
 * against the *current* [UserPreferences] at any time without re-fetching the
 * forecast. Caching at this layer (the upstream inputs) rather than at the
 * derived [Insight] layer means a settings change — clothes rules, default
 * top / bottom, delta threshold, clothes-mention mode, range / clothes format
 * — re-renders the Today screen, the Format settings page preview, the home-
 * screen widget, the cast surface and the MQTT payload in the same frame as
 * the dropdown closes, with no preservation / re-gating logic on the cache.
 *
 * Two slots:
 *  - [Slot.THIS_PERIOD] — the snapshot for the 12-hour window the user is
 *    currently in (daytime in the morning, tonight in the evening). The Today
 *    screen's pager surfaces this on page 1. May be a few hours old by the
 *    time the user opens the app — the period itself is still "this one",
 *    even if the bytes were captured at the period's start.
 *  - [Slot.NEXT_PERIOD] — the snapshot pre-captured for the next 12-hour window.
 *    The morning alarm pre-renders tonight (same date); the evening alarm
 *    pre-renders tomorrow's daytime. The Today screen's pager surfaces this
 *    on page 2 so the "next" card always reads the next 12 hours, never the
 *    previous one.
 *
 * The role-based naming keeps the pager logic trivial — page 1 derives from
 * [Slot.THIS_PERIOD], page 2 from [Slot.NEXT_PERIOD] — and decouples the cache
 * layout from the [ForecastPeriod] kind (which is recorded inside the stored
 * snapshot's [ForecastSnapshot.period]).
 *
 * The cache stores the full [ForecastBundle] (so the delta clause keeps its
 * yesterday data, the precip clause keeps its per-model series, and severe
 * alerts survive a settings-driven re-render) plus a minimal projection of
 * each [CalendarEvent] — start / end / allDay / a location-presence boolean —
 * matching the subset [app.clothescast.core.domain.usecase.RenderInsightSummary]
 * actually reads. Event titles and free-form location strings are
 * deliberately not persisted, keeping the on-disk privacy posture identical
 * to what the previous derived-insight cache held (see PRIVACY.md).
 *
 * The cache is intentionally a single slot per role — there's no historical
 * browsing planned, and bounding storage at 2 entries keeps the cost of
 * corruption (deserialization failure → drop and regenerate) trivial.
 */
class InsightCache(
    private val dataStore: DataStore<Preferences>,
    private val deriveInsight: DeriveInsight = DeriveInsight(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Which 12-hour window a snapshot occupies relative to the rendering
     * surface: the window the user is currently inside ([THIS_PERIOD], page 1
     * of the pager / the home-screen widget) or the upcoming one
     * ([NEXT_PERIOD], page 2). Stored in separate DataStore keys so the worker
     * can overwrite each independently.
     */
    enum class Slot { THIS_PERIOD, NEXT_PERIOD }

    /** Raw snapshot stored for page 1 — the 12-hour window the user is currently in. */
    val thisPeriod: Flow<ForecastSnapshot?> = dataStore.data.map { it.readSlot(THIS_PERIOD_KEY) }

    /** Raw snapshot stored for page 2 — the pre-captured next 12-hour window. */
    val nextPeriod: Flow<ForecastSnapshot?> = dataStore.data.map { it.readSlot(NEXT_PERIOD_KEY) }

    /**
     * Derives the [DailyInsightResult] for [slot] against [prefs] using the
     * latest cached snapshot. Re-emits whenever either input changes, so the
     * Today screen / Format settings preview / widget always render the current
     * prefs against the most recent weather data without anyone having to
     * re-trigger a recompute. `now` defaults to the snapshot's `generatedAt`
     * — alerts are filtered against that timestamp, which matches the worker's
     * "active at fetch time" semantics; pass a different clock for tests that
     * want to age alerts past expiry.
     */
    fun deriveFlow(
        slot: Slot,
        prefsFlow: Flow<UserPreferences>,
        now: (snapshot: ForecastSnapshot) -> Instant = ForecastSnapshot::generatedAt,
    ): Flow<DailyInsightResult?> {
        val snapshotFlow = when (slot) {
            Slot.THIS_PERIOD -> thisPeriod
            Slot.NEXT_PERIOD -> nextPeriod
        }
        return combine(snapshotFlow, prefsFlow) { snapshot, prefs ->
            snapshot?.let { deriveInsight(it, prefs, now(it)) }
        }
    }

    suspend fun store(slot: Slot, snapshot: ForecastSnapshot) {
        dataStore.edit { it[keyFor(slot)] = json.encodeToString(snapshot.toDto()) }
    }

    /**
     * The just-delivered insight matching [today] and [period], derived from
     * the cached snapshot against the supplied [prefs]. Returns null when the
     * THIS_PERIOD slot holds nothing matching. Used by the worker to dedup
     * "Fire insight now" debug taps against the same-day alarm — the second
     * call redelivers the cached snapshot instead of burning another fetch.
     *
     * Only the THIS_PERIOD slot is consulted; the NEXT_PERIOD slot is a
     * pre-capture that hasn't been delivered yet, and dedup-matching against
     * it would silently cause the morning alarm to redeliver yesterday-
     * evening's pre-cached Saturday-daytime insight instead of fetching a
     * fresh Saturday forecast.
     */
    suspend fun deliveredForToday(
        today: LocalDate,
        period: ForecastPeriod,
        prefs: UserPreferences,
        now: Instant? = null,
        // Forwarded to [DeriveInsight] so cache-hit deliveries (debug-tap
        // redelivery later in the day, alarm re-fires) emit the same
        // `delta:` diagnostic line a fresh fetch would. The 300-line
        // DiagLog ring buffer can evict the morning's original entry by
        // the time a same-day redelivery happens, so logging it again on
        // each delivery is the only way to keep the trail.
        diagLog: (String) -> Unit = {},
    ): DailyInsightResult? {
        val snapshot = thisPeriod.first() ?: return null
        if (snapshot.bundle.today.date != today || snapshot.period != period) return null
        return deriveInsight(snapshot, prefs, now ?: snapshot.generatedAt, diagLog = diagLog)
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(THIS_PERIOD_KEY)
            it.remove(NEXT_PERIOD_KEY)
        }
    }

    private fun Preferences.readSlot(key: androidx.datastore.preferences.core.Preferences.Key<String>): ForecastSnapshot? {
        val raw = this[key] ?: return null
        return runCatching { json.decodeFromString<ForecastSnapshotDto>(raw).toDomain() }.getOrNull()
    }

    private fun keyFor(slot: Slot): androidx.datastore.preferences.core.Preferences.Key<String> =
        when (slot) {
            Slot.THIS_PERIOD -> THIS_PERIOD_KEY
            Slot.NEXT_PERIOD -> NEXT_PERIOD_KEY
        }

    @Serializable
    private data class ForecastSnapshotDto(
        val bundle: ForecastBundleDto,
        val events: List<CalendarEventDto> = emptyList(),
        val location: LocationDto? = null,
        val period: String = ForecastPeriod.TODAY.name,
        val generatedAtEpochMillis: Long,
        val historicYesterday: DailyHistoryDto? = null,
    ) {
        fun toDomain(): ForecastSnapshot = ForecastSnapshot(
            bundle = bundle.toDomain(),
            events = events.map { it.toDomain() },
            location = location?.toDomain(),
            period = runCatching { ForecastPeriod.valueOf(period) }.getOrDefault(ForecastPeriod.TODAY),
            generatedAt = Instant.ofEpochMilli(generatedAtEpochMillis),
            historicYesterday = historicYesterday?.toDomain(),
        )
    }

    @Serializable
    private data class DailyHistoryDto(
        val dateEpochDays: Long,
        val feelsLikeMinC: Double,
        val feelsLikeMaxC: Double,
    ) {
        fun toDomain(): DailyHistoryEntry = DailyHistoryEntry(
            date = LocalDate.ofEpochDay(dateEpochDays),
            feelsLikeMinC = feelsLikeMinC,
            feelsLikeMaxC = feelsLikeMaxC,
        )
    }

    @Serializable
    private data class ForecastBundleDto(
        val today: DailyForecastDto,
        val yesterday: DailyForecastDto,
        val alerts: List<AlertDto> = emptyList(),
        val confidence: ConfidenceInfoDto? = null,
        val perModelHourly: PerModelHourlyDto? = null,
        val tomorrowHourly: List<HourlyDto> = emptyList(),
        val tomorrow: DailyForecastDto? = null,
        val forecastZoneId: String? = null,
    ) {
        fun toDomain(): ForecastBundle = ForecastBundle(
            today = today.toDomain(),
            yesterday = yesterday.toDomain(),
            alerts = alerts.map { it.toDomain() },
            confidence = confidence?.toDomain(),
            perModelHourly = perModelHourly?.toDomain(),
            tomorrowHourly = tomorrowHourly.map { it.toDomain() },
            tomorrow = tomorrow?.toDomain(),
            forecastZone = forecastZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() },
        )
    }

    @Serializable
    private data class DailyForecastDto(
        val dateEpochDays: Long,
        val temperatureMinC: Double,
        val temperatureMaxC: Double,
        val feelsLikeMinC: Double,
        val feelsLikeMaxC: Double,
        val precipitationProbabilityMaxPct: Double,
        val precipitationMmTotal: Double,
        val condition: String,
        val hourly: List<HourlyDto> = emptyList(),
    ) {
        fun toDomain(): DailyForecast = DailyForecast(
            date = LocalDate.ofEpochDay(dateEpochDays),
            temperatureMinC = temperatureMinC,
            temperatureMaxC = temperatureMaxC,
            feelsLikeMinC = feelsLikeMinC,
            feelsLikeMaxC = feelsLikeMaxC,
            precipitationProbabilityMaxPct = precipitationProbabilityMaxPct,
            precipitationMmTotal = precipitationMmTotal,
            condition = runCatching { WeatherCondition.valueOf(condition) }
                .getOrDefault(WeatherCondition.UNKNOWN),
            hourly = hourly.map { it.toDomain() },
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

    @Serializable
    private data class AlertDto(
        val event: String,
        val severity: String,
        val headline: String? = null,
        val description: String? = null,
        val onsetEpochMillis: Long,
        val expiresEpochMillis: Long,
    ) {
        fun toDomain(): WeatherAlert = WeatherAlert(
            event = event,
            severity = runCatching { AlertSeverity.valueOf(severity) }
                .getOrDefault(AlertSeverity.MINOR),
            headline = headline,
            description = description,
            onset = Instant.ofEpochMilli(onsetEpochMillis),
            expires = Instant.ofEpochMilli(expiresEpochMillis),
        )
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
    private data class PerModelHourlyDto(
        val byModel: Map<String, List<PerModelHourDto>>,
    ) {
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
        // Per-entry date so the bundle's full per-model series (today + tomorrow,
        // up to 48 h depending on the upstream window) survives the round-trip
        // intact. Pairing every entry with a single `forDate` would alias
        // tomorrow's pre-dawn hours onto today's date and break tonight's
        // wrap-past-midnight slice in [DeriveInsight].
        val dateEpochDays: Long,
        val secondOfDay: Int,
        val apparentTemperatureC: Double,
        val temperatureC: Double? = null,
        val precipitationProbabilityPct: Double? = null,
        val windSpeedKmh: Double? = null,
        val relativeHumidityPct: Double? = null,
        val cloudCoverPct: Double? = null,
        val shortwaveRadiationWm2: Double? = null,
        val sunshineDurationSec: Double? = null,
        val uvIndex: Double? = null,
        val conditionName: String? = null,
    ) {
        fun toDomain(): PerModelHour? {
            val airTemp = temperatureC ?: return null
            return PerModelHour(
                time = LocalDateTime.of(
                    LocalDate.ofEpochDay(dateEpochDays),
                    LocalTime.ofSecondOfDay(secondOfDay.toLong()),
                ),
                apparentTemperatureC = apparentTemperatureC,
                temperatureC = airTemp,
                precipitationProbabilityPct = precipitationProbabilityPct,
                windSpeedKmh = windSpeedKmh,
                relativeHumidityPct = relativeHumidityPct,
                cloudCoverPct = cloudCoverPct,
                shortwaveRadiationWm2 = shortwaveRadiationWm2,
                sunshineDurationSec = sunshineDurationSec,
                uvIndex = uvIndex,
                condition = conditionName?.let {
                    runCatching { WeatherCondition.valueOf(it) }.getOrNull()
                },
            )
        }
    }

    @Serializable
    private data class LocationDto(
        val latitude: Double,
        val longitude: Double,
        val displayName: String? = null,
        val countryCode: String? = null,
    ) {
        fun toDomain(): Location = Location(
            latitude = latitude,
            longitude = longitude,
            displayName = displayName,
            countryCode = countryCode,
        )
    }

    /**
     * Minimal projection of [CalendarEvent] for on-disk persistence: only the
     * fields `RenderInsightSummary` actually consults — `start`, `end`, `allDay`,
     * and a `hasLocation` *presence* flag — survive the round-trip.
     *
     * Titles and free-form location strings are deliberately dropped, matching
     * the privacy posture of the previous derived-insight cache (which stored
     * `CalendarTieInClause(item: String)` — a garment name, never the event
     * title). The renderer's only reads of `location` are presence checks
     * (`!it.location.isNullOrBlank()`), so on materialisation we surface a
     * placeholder `"x"` when `hasLocation == true` and `null` otherwise; titles
     * collapse to an empty string for the same reason. See PRIVACY.md.
     */
    @Serializable
    private data class CalendarEventDto(
        val startSecondOfDay: Int,
        val endSecondOfDay: Int,
        val allDay: Boolean = false,
        val hasLocation: Boolean = false,
    ) {
        fun toDomain(): CalendarEvent = CalendarEvent(
            title = "",
            start = LocalTime.ofSecondOfDay(startSecondOfDay.toLong()),
            end = LocalTime.ofSecondOfDay(endSecondOfDay.toLong()),
            location = if (hasLocation) PLACEHOLDER_LOCATION else null,
            allDay = allDay,
            kind = EventKind.NORMAL,
            ownerAccount = null,
        )
    }

    private fun ForecastSnapshot.toDto(): ForecastSnapshotDto = ForecastSnapshotDto(
        bundle = bundle.toDto(),
        events = events.map { it.toDto() },
        location = location?.let {
            LocationDto(it.latitude, it.longitude, it.displayName, it.countryCode)
        },
        period = period.name,
        generatedAtEpochMillis = generatedAt.toEpochMilli(),
        historicYesterday = historicYesterday?.let {
            DailyHistoryDto(
                dateEpochDays = it.date.toEpochDay(),
                feelsLikeMinC = it.feelsLikeMinC,
                feelsLikeMaxC = it.feelsLikeMaxC,
            )
        },
    )

    private fun ForecastBundle.toDto(): ForecastBundleDto = ForecastBundleDto(
        today = today.toDto(),
        yesterday = yesterday.toDto(),
        alerts = alerts.map { it.toDto() },
        confidence = confidence?.toDto(),
        perModelHourly = perModelHourly?.toDto(),
        tomorrowHourly = tomorrowHourly.map { it.toDto() },
        tomorrow = tomorrow?.toDto(),
        forecastZoneId = forecastZone?.id,
    )

    private fun DailyForecast.toDto(): DailyForecastDto = DailyForecastDto(
        dateEpochDays = date.toEpochDay(),
        temperatureMinC = temperatureMinC,
        temperatureMaxC = temperatureMaxC,
        feelsLikeMinC = feelsLikeMinC,
        feelsLikeMaxC = feelsLikeMaxC,
        precipitationProbabilityMaxPct = precipitationProbabilityMaxPct,
        precipitationMmTotal = precipitationMmTotal,
        condition = condition.name,
        hourly = hourly.map { it.toDto() },
    )

    private fun HourlyForecast.toDto(): HourlyDto = HourlyDto(
        secondOfDay = time.toSecondOfDay(),
        temperatureC = temperatureC,
        feelsLikeC = feelsLikeC,
        precipitationProbabilityPct = precipitationProbabilityPct,
        condition = condition.name,
    )

    private fun WeatherAlert.toDto(): AlertDto = AlertDto(
        event = event,
        severity = severity.name,
        headline = headline,
        description = description,
        onsetEpochMillis = onset.toEpochMilli(),
        expiresEpochMillis = expires.toEpochMilli(),
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
        dateEpochDays = time.toLocalDate().toEpochDay(),
        secondOfDay = time.toLocalTime().toSecondOfDay(),
        apparentTemperatureC = apparentTemperatureC,
        temperatureC = temperatureC,
        precipitationProbabilityPct = precipitationProbabilityPct,
        windSpeedKmh = windSpeedKmh,
        relativeHumidityPct = relativeHumidityPct,
        cloudCoverPct = cloudCoverPct,
        shortwaveRadiationWm2 = shortwaveRadiationWm2,
        sunshineDurationSec = sunshineDurationSec,
        uvIndex = uvIndex,
        conditionName = condition?.name,
    )

    private fun CalendarEvent.toDto(): CalendarEventDto = CalendarEventDto(
        startSecondOfDay = start.toSecondOfDay(),
        endSecondOfDay = end.toSecondOfDay(),
        allDay = allDay,
        hasLocation = !location.isNullOrBlank(),
    )

    companion object {
        // Bumped to v7 with the schema switch from a derived `Insight` payload
        // to the raw `ForecastSnapshot` (bundle + minimal events) it's derived
        // from. The previous shape (v6) doesn't deserialise into the new DTO,
        // so the cache drops to null on first read and the next worker run
        // populates the v7 keys.
        private val THIS_PERIOD_KEY = stringPreferencesKey("this_period_snapshot_v7")
        private val NEXT_PERIOD_KEY = stringPreferencesKey("next_period_snapshot_v7")

        // Surfaced on materialisation when the cached event had a location
        // string at fetch time. The renderer's only read of `location` is a
        // presence check (`!isNullOrBlank()`), so the placeholder content
        // never reaches prose, and dropping the real string on disk keeps
        // the on-disk privacy posture at parity with the previous derived-
        // insight cache.
        private const val PLACEHOLDER_LOCATION = "x"

        fun create(
            context: Context,
            deriveInsight: DeriveInsight = DeriveInsight(),
        ): InsightCache = InsightCache(context.insightDataStore, deriveInsight)
    }
}

private val Context.insightDataStore: DataStore<Preferences> by preferencesDataStore(name = "insight_cache")
