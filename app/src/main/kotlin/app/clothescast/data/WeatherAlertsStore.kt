package app.clothescast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.clothescast.core.domain.model.AlertAttribution
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.AlertSeverity
import app.clothescast.core.domain.model.GeoPoint
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.usecase.RelevantAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/**
 * How the last alerts check went. [OK] means [WeatherAlertsState.alerts] is
 * what the source had then (possibly nothing); every other outcome means we don't
 * know, and the app must never read it as all clear.
 */
enum class AlertsCheckOutcome {
    /** No check yet, or alerts were just turned on. */
    NEVER,
    OK,
    /** No source has alerts for this location. */
    UNSUPPORTED,
    FAILED,
}

/** What the app last learned about alerts at the user's location. */
data class WeatherAlertsState(
    val checkedAt: Instant? = null,
    val outcome: AlertsCheckOutcome = AlertsCheckOutcome.NEVER,
    /** The 2-decimal point the alerts were checked for. */
    val point: GeoPoint? = null,
    /** The alerts that applied at [point], most severe first (WeatherAlertRules.relevant). */
    val alerts: List<RelevantAlert> = emptyList(),
    /** Alert IDs already notified, with the level notified at, so a rise notifies again. */
    val notified: Map<String, AlertLevel> = emptyMap(),
    /**
     * Alert IDs whose Today card the user dismissed, each with the
     * [dismissKey] of the alert as it read then: any change brings it back.
     */
    val dismissed: Map<String, String> = emptyMap(),
) {
    /** The alerts still in force at [now]. */
    fun active(now: Instant): List<RelevantAlert> =
        alerts.filter { relevant -> relevant.alert.endTime?.isAfter(now) != false }

    /** Whether [relevant]'s card was dismissed and the alert hasn't changed since. */
    fun isDismissed(relevant: RelevantAlert): Boolean = dismissed[relevant.alert.id] == relevant.dismissKey()

    /** The highest level in force at [now], or null when nothing is. */
    fun highestLevel(now: Instant): AlertLevel? = active(now).maxOfOrNull { it.level }
}

/**
 * Persists [WeatherAlertsState] so Today, the widgets and the background
 * check share one view of the user's alerts (docs/ALERTS.md). Nothing here is
 * the user's own data beyond the coarse point the alerts were checked for,
 * which the forecast cache already holds; it never leaves the device.
 */
class WeatherAlertsStore(
    dataStore: DataStore<Preferences>,
    json: Json = Json { ignoreUnknownKeys = true },
) : JsonPreferenceStore(dataStore, json) {

    val state: Flow<WeatherAlertsState> =
        dataStore.data.map { prefs -> decodeJson(prefs[STATE_KEY], WeatherAlertsState()) { decode(it) } }

    suspend fun current(): WeatherAlertsState = state.first()

    /** Replaces the stored state with whatever [transform] makes of it. */
    suspend fun update(transform: (WeatherAlertsState) -> WeatherAlertsState) {
        dataStore.edit { prefs ->
            val before = decodeJson(prefs[STATE_KEY], WeatherAlertsState()) { decode(it) }
            prefs.writeJson(STATE_KEY, transform(before).toDto())
        }
    }

    /** Hides [relevant]'s card on Today until the alert changes. */
    suspend fun dismiss(relevant: RelevantAlert) {
        update { it.copy(dismissed = it.dismissed + (relevant.alert.id to relevant.dismissKey())) }
    }

    /** Forgets everything: alerts turned off, or the user signed out. */
    suspend fun clear() {
        dataStore.edit { it.remove(STATE_KEY) }
    }

    private fun decode(raw: String): WeatherAlertsState =
        json.decodeFromString<StateDto>(raw).toDomain()

    @Serializable
    private data class StateDto(
        val checkedAtMs: Long? = null,
        val outcome: String? = null,
        val pointLat: Double? = null,
        val pointLon: Double? = null,
        val alerts: List<AlertDto> = emptyList(),
        val notified: Map<String, String> = emptyMap(),
        val dismissed: Map<String, String> = emptyMap(),
    ) {
        fun toDomain() = WeatherAlertsState(
            checkedAt = checkedAtMs?.let(Instant::ofEpochMilli),
            outcome = AlertsCheckOutcome.entries.firstOrNull { it.name == outcome } ?: AlertsCheckOutcome.NEVER,
            point = if (pointLat != null && pointLon != null) GeoPoint(pointLat, pointLon) else null,
            alerts = alerts.mapNotNull { it.toDomain() },
            notified = notified.mapNotNull { (id, level) ->
                AlertLevel.entries.firstOrNull { it.name == level }?.let { id to it }
            }.toMap(),
            dismissed = dismissed,
        )
    }

    @Serializable
    private data class AlertDto(
        val id: String,
        val issuerTitle: String = "",
        val eventType: String? = null,
        val severity: String? = null,
        val areaName: String? = null,
        val description: String? = null,
        val instruction: String? = null,
        val startMs: Long? = null,
        val endMs: Long? = null,
        val issuer: String? = null,
        val issuerUrl: String? = null,
        val issuedMs: Long? = null,
        val attributionName: String? = null,
        val attributionUrl: String? = null,
        val notifiable: Boolean = true,
    ) {
        fun toDomain(): RelevantAlert? {
            if (id.isBlank()) return null
            return RelevantAlert(
                alert = WeatherAlert(
                    id = id,
                    issuerTitle = issuerTitle,
                    eventType = eventType,
                    severity = AlertSeverity.parse(severity),
                    areaName = areaName,
                    description = description,
                    instruction = instruction,
                    startTime = startMs?.let(Instant::ofEpochMilli),
                    endTime = endMs?.let(Instant::ofEpochMilli),
                    issuer = issuer,
                    issuerUrl = issuerUrl,
                    issuedAt = issuedMs?.let(Instant::ofEpochMilli),
                    attribution = if (attributionName != null && attributionUrl != null) {
                        AlertAttribution(attributionName, attributionUrl)
                    } else {
                        null
                    },
                    // The area has done its job once the alert is known to
                    // apply here; it isn't kept (a traced region can run to
                    // thousands of points).
                    area = null,
                ),
                notifiable = notifiable,
            )
        }
    }

    private fun WeatherAlertsState.toDto() = StateDto(
        checkedAtMs = checkedAt?.toEpochMilli(),
        outcome = outcome.name,
        pointLat = point?.latitude,
        pointLon = point?.longitude,
        alerts = alerts.map { relevant ->
            val a = relevant.alert
            AlertDto(
                id = a.id,
                issuerTitle = a.issuerTitle,
                eventType = a.eventType,
                severity = a.severity.name,
                areaName = a.areaName,
                description = a.description,
                instruction = a.instruction,
                startMs = a.startTime?.toEpochMilli(),
                endMs = a.endTime?.toEpochMilli(),
                issuer = a.issuer,
                issuerUrl = a.issuerUrl,
                issuedMs = a.issuedAt?.toEpochMilli(),
                attributionName = a.attribution?.name,
                attributionUrl = a.attribution?.url,
                notifiable = relevant.notifiable,
            )
        },
        notified = notified.mapValues { it.value.name },
        dismissed = dismissed,
    )

    companion object {
        private val STATE_KEY = stringPreferencesKey("weather_alerts_state_v1")

        fun create(context: Context): WeatherAlertsStore = WeatherAlertsStore(context.weatherAlertsDataStore)
    }
}

/**
 * A digest of everything the app shows of an alert (its title, the level it's
 * drawn at, hazard, area, the issuer's text and link, its times, its credit),
 * so a dismissed card returns when any of it changes. The drawn level covers
 * an area narrowed from too broad to notify (neutral) to the alert's own. The
 * traced area itself is left out: it isn't stored, and it isn't shown.
 */
fun RelevantAlert.dismissKey(): String {
    val fields = listOf(
        alert.issuerTitle,
        alert.eventType,
        alert.severity.name,
        // As drawn: neutral while the area is too broad.
        level.name,
        alert.areaName,
        alert.description,
        alert.instruction,
        alert.startTime?.toEpochMilli()?.toString(),
        alert.endTime?.toEpochMilli()?.toString(),
        alert.issuer,
        alert.issuerUrl,
    ) + listOfNotNull(
        // Only when present, so a key made before these existed (an alert
        // without them) still matches.
        alert.issuedAt?.toEpochMilli()?.toString(),
        alert.attribution?.let { "${it.name}\u0000${it.url}" },
    )
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(fields.joinToString("\u0000") { it ?: "\u0001" }.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
}

private val Context.weatherAlertsDataStore: DataStore<Preferences> by preferencesDataStore(name = "weather_alerts")
