package app.clothescast.core.domain.model

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * An additional morning ClothesCast beyond the primary [UserPreferences.schedule] —
 * lets a user split the week so, e.g., weekday mornings fire at 07:00 and weekend
 * mornings at 09:00. Each entry delivers the same morning ([ForecastPeriod.TODAY])
 * insight as the primary morning cast; only the wall-clock [time] and the [days]
 * it covers differ. Delivery mode and smart-home destinations are shared with the
 * primary morning cast — per-entry delivery is intentionally out of scope for now.
 *
 * [id] is a stable, per-entry identifier used to derive the AlarmManager request
 * code so a single entry's alarm can be armed / re-armed / cancelled independently
 * of the others. It is allocated monotonically (see [MorningSchedules.nextId]) and
 * never reused within a preferences snapshot. The primary morning cast occupies
 * the reserved id [MorningSchedules.PRIMARY_ID]; additional entries are always > 0.
 *
 * Invariant across every morning cast (primary + each entry): the day sets are
 * pairwise disjoint — a weekday is owned by at most one cast, so a day never fires
 * two morning casts. [MorningSchedules] owns the normalization that keeps that true.
 */
data class MorningScheduleEntry(
    val id: Int,
    val time: LocalTime,
    val days: Set<DayOfWeek>,
)
