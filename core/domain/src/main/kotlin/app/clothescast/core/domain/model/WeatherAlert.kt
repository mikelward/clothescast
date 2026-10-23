package app.clothescast.core.domain.model

import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * An official weather alert for the user's location, as ClothesCast's alerts
 * ingester relays it from the issuing agency (docs/ALERTS.md). Everything the issuer
 * wrote — [issuerTitle], [description], [instruction] — is kept verbatim; the
 * app composes its own title only where [AlertTitles.colorGraded] says the
 * issuer grades by color.
 */
data class WeatherAlert(
    val id: String,
    /** The issuer's own title ("Yellow warning of wind", "High Wind Warning"); may be blank. */
    val issuerTitle: String,
    /** A hazard keyword ("WIND", "SNOW_AND_ICE") or the issuer's event in capitals; null when absent. */
    val eventType: String?,
    val severity: AlertSeverity,
    /** The issuer's name for the area ("Grampian; Highlands & Eilean Siar"). A region, so a coarse location. */
    val areaName: String?,
    val description: String?,
    val instruction: String?,
    /**
     * When the weather is due to arrive (CAP onset). The card still shows
     * only [endTime]; notifications wait for this.
     */
    val startTime: Instant?,
    val endTime: Instant?,
    /** The issuing agency's name ("Met Office"). */
    val issuer: String?,
    /** The issuing agency's page, linked from the expanded card and the notification. */
    val issuerUrl: String?,
    /** The alert's area, or null when the source sent none (such alerts are dropped). */
    val area: AlertArea?,
    /** When the issuer sent it, where the source says (CAP `sent`). */
    val issuedAt: Instant? = null,
    /** The credit the source's terms ask for beside the alert, if any ("Met Office via MeteoAlarm"). */
    val attribution: AlertAttribution? = null,
) {
    val level: AlertLevel get() = severity.level
    val hazard: AlertHazard? get() = AlertHazards.of(eventType)
}

/**
 * Who to credit for an alert, where the credit links, and the source's own
 * disclaimer, which the card links after ClothesCast's under its author's
 * name ("MeteoAlarm disclaimer"), with its wording where the source's terms
 * require it shown ([disclaimerText], verbatim).
 */
data class AlertAttribution(
    val name: String,
    val url: String,
    val disclaimerUrl: String? = null,
    val disclaimerName: String? = null,
    val disclaimerText: String? = null,
)

/** CAP severity, as the source reports it. */
enum class AlertSeverity {
    MINOR,
    MODERATE,
    SEVERE,
    EXTREME,
    UNKNOWN,
    ;

    /** The level the app draws: shape and tint of the severity icon. */
    val level: AlertLevel
        get() = when (this) {
            EXTREME -> AlertLevel.RED
            SEVERE -> AlertLevel.ORANGE
            MODERATE -> AlertLevel.YELLOW
            MINOR, UNKNOWN -> AlertLevel.INFO
        }

    companion object {
        /** Parses the severity's name ("SEVERE"); anything unrecognized is [UNKNOWN]. */
        fun parse(raw: String?): AlertSeverity =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: UNKNOWN
    }
}

/**
 * The app's level for an alert, ordered from least to most severe. Each has
 * its own icon shape (docs/ALERTS.md → What the user sees), so it reads
 * without color or text: an outlined circle, an outlined triangle, a filled
 * triangle, a filled octagon.
 */
enum class AlertLevel { INFO, YELLOW, ORANGE, RED }

/** Which levels post a notification (Settings → Alerts → "Notify me for"). */
enum class AlertNotifyLevel {
    RED_ONLY,
    ORANGE_AND_RED,
    ALL,
    NONE,
    ;

    /** Whether an alert at [level] notifies. [AlertLevel.INFO] (minor or unknown severity) never does. */
    fun notifies(level: AlertLevel): Boolean = when (this) {
        RED_ONLY -> level == AlertLevel.RED
        ORANGE_AND_RED -> level == AlertLevel.RED || level == AlertLevel.ORANGE
        ALL -> level != AlertLevel.INFO
        NONE -> false
    }

    companion object {
        val DEFAULT = ORANGE_AND_RED

        fun parse(raw: String?): AlertNotifyLevel = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/** A point on the map, in degrees. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * An alert's area: the union of its polygons, each its outer boundary then
 * any holes. Within a polygon containment uses the even-odd rule, so holes
 * need no special case; a point inside any polygon is inside the area, so
 * shapes that overlap (CAP allows it) don't cancel out.
 */
data class AlertArea(val polygons: List<List<List<GeoPoint>>>) {

    private val rings: List<List<GeoPoint>> get() = polygons.flatten()

    /** Whether [point] lies inside the area. */
    fun contains(point: GeoPoint): Boolean = polygons.any { insideEvenOdd(it, point) }

    /**
     * The shortest distance from [point] to the area's boundary, in km, on a
     * local flat projection: plenty for the ~1 km tolerance it serves.
     */
    fun distanceToBoundaryKm(point: GeoPoint): Double {
        val kmPerDegLon = KM_PER_DEGREE * cos(point.latitude * PI / 180)
        var best = Double.MAX_VALUE
        for (ring in rings) {
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                val ax = (a.longitude - point.longitude) * kmPerDegLon
                val ay = (a.latitude - point.latitude) * KM_PER_DEGREE
                val bx = (b.longitude - point.longitude) * kmPerDegLon
                val by = (b.latitude - point.latitude) * KM_PER_DEGREE
                best = minOf(best, distanceToSegment(ax, ay, bx, by))
            }
        }
        return best
    }

    /**
     * The longer side of the area's bounding box, in km: how far across the
     * area is. A region-sized or continent-sized area can't be about one place.
     */
    fun spanKm(): Double {
        val points = rings.flatten()
        if (points.isEmpty()) return 0.0
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val midLat = (minLat + maxLat) / 2
        val height = (maxLat - minLat) * KM_PER_DEGREE
        val width = (maxLon - minLon) * KM_PER_DEGREE * cos(midLat * PI / 180)
        return max(height, abs(width))
    }

    private companion object {
        const val KM_PER_DEGREE = 111.2

        /** Whether [point] is inside [rings] by the even-odd rule. */
        fun insideEvenOdd(rings: List<List<GeoPoint>>, point: GeoPoint): Boolean {
            var inside = false
            for (ring in rings) {
                val n = ring.size
                if (n < 3) continue
                var j = n - 1
                for (i in 0 until n) {
                    val a = ring[i]
                    val b = ring[j]
                    if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                        point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) /
                        (b.latitude - a.latitude) + a.longitude
                    ) {
                        inside = !inside
                    }
                    j = i
                }
            }
            return inside
        }

        /** Distance from the origin to segment a→b, all in km. */
        fun distanceToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / lengthSquared
            val clamped = t.coerceIn(0.0, 1.0)
            return hypot(ax + clamped * dx, ay + clamped * dy)
        }
    }
}

/** The hazard an alert is about, for the composed title ("Amber wind warning"). */
enum class AlertHazard {
    WIND,
    RAIN,
    SNOW_AND_ICE,
    THUNDERSTORM,
    FOG,
    HEAT,
    COLD,
    COASTAL,
    FLOOD,
    WILDFIRE,
    AVALANCHE,
    STORM,
    TORNADO,
    HAIL,
    DUST,
}

/**
 * Maps Google's event types onto hazards and screens out alerts that aren't
 * about the weather. Google's full list isn't published in a form we could
 * check, so matching is by keyword on the type name ("FREEZING_RAIN_EVENT",
 * "HIGH_WIND"), most specific first.
 */
object AlertHazards {
    private val HAZARD_KEYWORDS: List<Pair<String, AlertHazard>> = listOf(
        "FREEZING" to AlertHazard.SNOW_AND_ICE,
        "BLIZZARD" to AlertHazard.SNOW_AND_ICE,
        "SNOW" to AlertHazard.SNOW_AND_ICE,
        "ICE" to AlertHazard.SNOW_AND_ICE,
        "WINTER" to AlertHazard.SNOW_AND_ICE,
        "THUNDER" to AlertHazard.THUNDERSTORM,
        "LIGHTNING" to AlertHazard.THUNDERSTORM,
        "TORNADO" to AlertHazard.TORNADO,
        "HAIL" to AlertHazard.HAIL,
        "COASTAL" to AlertHazard.COASTAL,
        "SURGE" to AlertHazard.COASTAL,
        "SURF" to AlertHazard.COASTAL,
        "WAVE" to AlertHazard.COASTAL,
        "FLOOD" to AlertHazard.FLOOD,
        "AVALANCHE" to AlertHazard.AVALANCHE,
        "FIRE" to AlertHazard.WILDFIRE,
        "RED_FLAG" to AlertHazard.WILDFIRE,
        "HURRICANE" to AlertHazard.STORM,
        "TYPHOON" to AlertHazard.STORM,
        "CYCLONE" to AlertHazard.STORM,
        "TROPICAL" to AlertHazard.STORM,
        "STORM" to AlertHazard.STORM,
        "DUST" to AlertHazard.DUST,
        "SAND" to AlertHazard.DUST,
        "FOG" to AlertHazard.FOG,
        "HEAT" to AlertHazard.HEAT,
        "HOT" to AlertHazard.HEAT,
        "CHILL" to AlertHazard.COLD,
        "COLD" to AlertHazard.COLD,
        "FROST" to AlertHazard.COLD,
        "FREEZE" to AlertHazard.COLD,
        "WIND" to AlertHazard.WIND,
        "GALE" to AlertHazard.WIND,
        "RAIN" to AlertHazard.RAIN,
    )

    // Public alerts also carry earthquakes, tsunamis, air quality and AMBER
    // child-abduction alerts (Google's US feed). Weather alerts only
    // (maintainer, 2026-10-22).
    private val EXCLUDED_KEYWORDS: List<String> = listOf(
        "EARTHQUAKE", "TSUNAMI", "VOLCAN", "LANDSLIDE", "AIR_QUALITY", "POLLUTION",
        "SMOKE", "CHILD", "ABDUCTION", "MISSING", "CIVIL", "TERROR", "SHOOTER",
        "HAZMAT", "HAZARDOUS", "CHEMICAL", "BIOLOGICAL", "NUCLEAR", "RADIOLOG",
        "LAW_ENFORCEMENT", "POLICE", "EVACUATION", "SHELTER", "OUTAGE", "TELEPHONE",
        "911", "TEST", "ANIMAL",
    )

    /** The hazard for [eventType], or null when it names none we know. */
    fun of(eventType: String?): AlertHazard? {
        val type = eventType?.uppercase() ?: return null
        return HAZARD_KEYWORDS.firstOrNull { (keyword, _) -> keyword in type }?.second
    }

    /** True for alerts that aren't about the weather (earthquakes, AMBER alerts, air quality, …). */
    fun isExcluded(eventType: String?): Boolean {
        val type = eventType?.uppercase() ?: return false
        return EXCLUDED_KEYWORDS.any { it in type }
    }
}

/** Composes "{color} {hazard} warning" titles where the issuer grades by color. */
object AlertTitles {
    /** The color words issuers lead their titles with, as the app names them. */
    enum class Color { YELLOW, AMBER, ORANGE, RED }

    private val WORD = Regex("""^\s*(\p{L}+)""")

    /**
     * The color [issuerTitle] leads with when the issuer grades by color —
     * "Yellow warning of wind" (Met Office), "Orange wind warning" (MeteoAlarm)
     * — else null. NWS's "Red Flag Warning" is a product name, not a color
     * grade, so a title mentioning a flag never counts. Matches English titles
     * only; any other language keeps the issuer's title.
     */
    fun colorGraded(issuerTitle: String): Color? {
        val lower = issuerTitle.lowercase()
        if ("warning" !in lower || "flag" in lower) return null
        val first = WORD.find(issuerTitle)?.groupValues?.get(1)?.lowercase() ?: return null
        return Color.entries.firstOrNull { it.name.lowercase() == first }
    }
}
