package app.clothescast.core.domain.model

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Pure partition logic for the set of morning ClothesCasts — the primary
 * [UserPreferences.schedule] plus every [UserPreferences.additionalMorningSchedules]
 * entry, viewed as one list. The week is *partitioned* across the casts: every
 * weekday is owned by at most one cast, so a day never fires two morning casts.
 *
 * The conventions every operation here preserves:
 *  - The primary cast occupies id [PRIMARY_ID] (always present, always element 0
 *    of a unified list) and can never be left with an empty day set — there has
 *    to be *a* morning cast for the [UserPreferences.schedule] slot.
 *  - Additional entries (id > [PRIMARY_ID]) may be dropped when they reach zero
 *    days — an entry that owns no days has nothing to fire.
 *  - Day sets stay pairwise disjoint: claiming a day for one cast strips it from
 *    every other cast.
 *
 * Operations take and return a *unified* list (primary first), so callers don't
 * special-case the primary in the day-assignment math — they split the result
 * back into primary + extras when persisting.
 */
object MorningSchedules {
    /** Reserved id of the primary morning cast within a unified entry list. */
    const val PRIMARY_ID: Int = 0

    private val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    /** The next free entry id given the [entries] already in play. */
    fun nextId(entries: List<MorningScheduleEntry>): Int =
        (entries.maxOfOrNull { it.id } ?: PRIMARY_ID) + 1

    /**
     * Toggle [day]'s membership in the cast with id [entryId].
     *
     *  - If the cast already owns [day], release it (the day becomes uncovered —
     *    no morning cast that day). Refused if it would empty the primary, or if
     *    it would empty an additional entry whose only day this is *and* — no:
     *    an emptied additional entry is simply dropped.
     *  - Otherwise claim [day] for the cast, stripping it from whichever cast
     *    held it. Refused if claiming it would empty the primary (the primary
     *    can't give away its last day).
     *
     * A no-op transform (refused, or [entryId] unknown) returns the list unchanged.
     */
    fun toggleDay(
        entries: List<MorningScheduleEntry>,
        entryId: Int,
        day: DayOfWeek,
    ): List<MorningScheduleEntry> {
        val target = entries.firstOrNull { it.id == entryId } ?: return entries
        val owns = day in target.days
        if (owns) {
            // Releasing the primary's last day would leave the primary slot with
            // no morning at all — keep at least one day.
            if (entryId == PRIMARY_ID && target.days.size == 1) return entries
            return entries.mapNotNull { e ->
                if (e.id != entryId) {
                    e
                } else {
                    val remaining = e.days - day
                    if (remaining.isEmpty() && e.id != PRIMARY_ID) null else e.copy(days = remaining)
                }
            }
        }
        // Claiming: the day has to leave its current owner. If that owner is the
        // primary and this was its last day, refuse — the primary can't empty.
        val primaryWouldEmpty = entryId != PRIMARY_ID &&
            entries.first { it.id == PRIMARY_ID }.days == setOf(day)
        if (primaryWouldEmpty) return entries
        return entries.mapNotNull { e ->
            when (e.id) {
                entryId -> e.copy(days = e.days + day)
                else -> {
                    val remaining = e.days - day
                    if (remaining.isEmpty() && e.id != PRIMARY_ID) null else e.copy(days = remaining)
                }
            }
        }
    }

    /** Replace the wall-clock time of the cast with id [entryId]. */
    fun setTime(
        entries: List<MorningScheduleEntry>,
        entryId: Int,
        time: LocalTime,
    ): List<MorningScheduleEntry> =
        entries.map { if (it.id == entryId) it.copy(time = time) else it }

    /**
     * Whether a new additional entry can be split off: there has to be a day on
     * the primary to seed it with that doesn't empty the primary, i.e. the
     * primary owns at least two days.
     */
    fun canAddEntry(entries: List<MorningScheduleEntry>): Boolean =
        (entries.firstOrNull { it.id == PRIMARY_ID }?.days?.size ?: 0) >= 2

    /**
     * Add a new additional entry at [time], seeded with days taken from the
     * primary so the week stays partitioned and the entry is immediately useful.
     * Prefers the weekend (the canonical "different time on weekends" case);
     * falls back to the latest single primary day. Never empties the primary —
     * returns the list unchanged when no day can be split off (see [canAddEntry]).
     */
    fun addEntry(
        entries: List<MorningScheduleEntry>,
        time: LocalTime,
    ): List<MorningScheduleEntry> {
        val primary = entries.firstOrNull { it.id == PRIMARY_ID } ?: return entries
        val seed = seedDays(primary.days)
        if (seed.isEmpty()) return entries
        val newEntry = MorningScheduleEntry(id = nextId(entries), time = time, days = seed)
        return entries.map { e ->
            if (e.id == PRIMARY_ID) e.copy(days = e.days - seed) else e
        } + newEntry
    }

    /**
     * Remove the additional entry with id [entryId]; its days return to the
     * primary so the week stays covered the way it was before the split. A no-op
     * for [PRIMARY_ID] or an unknown id.
     */
    fun removeEntry(
        entries: List<MorningScheduleEntry>,
        entryId: Int,
    ): List<MorningScheduleEntry> {
        if (entryId == PRIMARY_ID) return entries
        val removed = entries.firstOrNull { it.id == entryId } ?: return entries
        return entries
            .filter { it.id != entryId }
            .map { if (it.id == PRIMARY_ID) it.copy(days = it.days + removed.days) else it }
    }

    /** Days to seed a new entry with, drawn from the primary without emptying it. */
    private fun seedDays(primaryDays: Set<DayOfWeek>): Set<DayOfWeek> {
        if (primaryDays.size < 2) return emptySet()
        val weekendOnPrimary = primaryDays intersect WEEKEND
        val candidate = weekendOnPrimary.ifEmpty {
            // No weekend left on the primary — peel off its latest day instead.
            setOfNotNull(primaryDays.maxByOrNull { it.value })
        }
        // Guard the primary against emptying: if the candidate is the whole
        // primary, give one day back so the primary keeps at least one.
        return if ((primaryDays - candidate).isEmpty()) {
            candidate - candidate.minByOrNull { it.value }!!
        } else {
            candidate
        }
    }
}
