/**
 * The UTC calendar-day window the daily quota counts in.
 *
 * Counted by UTC day so the reset moment is global and unambiguous, and
 * so the Android client's nudge copy ("resets at midnight UTC") is true
 * wherever the user is. Split out of `index.ts` to be unit testable —
 * that file initializes Firebase at module scope.
 */

/** `YYYY-MM-DD` for the UTC day `date` falls in. The Firestore `dayKey`. */
export function utcDayKey(date: Date): string {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, "0");
  const d = String(date.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

/** ISO-8601 instant of the next UTC midnight after `date` — the quota reset. */
export function nextUtcMidnightIso(date: Date): string {
  const next = new Date(
    Date.UTC(
      date.getUTCFullYear(),
      date.getUTCMonth(),
      date.getUTCDate() + 1,
      0,
      0,
      0,
      0,
    ),
  );
  return next.toISOString();
}
