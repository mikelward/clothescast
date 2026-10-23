/**
 * Alert IDs that last through an issuer's updates. Every update is a new
 * CAP message with a new identifier, naming the messages it replaces in
 * `<references>`; the app keys its dismissals and notifications by the
 * served ID, so an ID that changed with each update would sound a new
 * notification and bring back a dismissed card every time (maintainer,
 * 2026-10-23: stable IDs for every source). Pure, so it's unit testable.
 *
 * A message keeps the ID it was served under on the last run, even when
 * fetched again (a retry to place its area). An update takes the ID of the
 * nearest message it updates that was served last run, the holder,
 * reaching through updates still listed (two updates between runs); failing
 * that, of the nearest one the last run knew; failing both (a first run, or
 * a predecessor never seen), the ID of the oldest message its chain names. A message another current one names
 * is superseded and not served. Where one message is split into several
 * updates (one per area), the one continuing the message served under the
 * ID last run keeps it (that message itself, fetched again or kept, or an
 * update of it), else the first by identifier; the others take their own.
 */
import type { CapReference } from "./cap";
import type { ServedAlert, Skip } from "./served";

/** One current message of a source, with its served alert or why it isn't served. */
export interface Thread {
  /** The CAP identifier. */
  identifier: string;
  /** The messages it updates or cancels. */
  references: CapReference[];
  result: ServedAlert | Skip;
  /** Kept from the last run unchanged, so it keeps the ID it had. */
  kept: boolean;
}

/**
 * Each thread's served ID, or null when it's superseded; in [threads]'
 * order. [lastIds] maps each identifier of the last run to the ID it had;
 * [lastServed] holds those that were served under it, not superseded.
 */
export function threadIds(
  source: string,
  threads: Thread[],
  lastIds: Map<string, string>,
  lastServed: Set<string> = new Set(),
): (string | null)[] {
  const named = new Set(threads.flatMap((t) => t.references.map((r) => r.identifier)));
  const current = new Map(threads.map((t) => [t.identifier, t]));
  const own = (t: Thread) => `${source}:${t.identifier}`;

  // The ID a message continues: its own from the last run; else that of
  // the nearest message it updates (through current ones) that was served
  // last run, the holder, newest first among equals; else that of the
  // nearest one the last run knew at all (a superseded link). Undefined when
  // nothing in its chain is known.
  const continued = (identifier: string): string | undefined => {
    const t = current.get(identifier);
    if (t?.kept && typeof t.result !== "string") return t.result.id;
    const mine = lastIds.get(identifier);
    if (mine !== undefined) return mine;
    const seen = new Set([identifier]);
    let level = newestFirst(t?.references ?? []);
    let nearestKnown: string | undefined;
    while (level.length > 0) {
      const fresh = level.filter((r) => !seen.has(r.identifier));
      fresh.forEach((r) => seen.add(r.identifier));
      const holder = fresh.find((r) => lastServed.has(r.identifier) && lastIds.has(r.identifier));
      if (holder) return lastIds.get(holder.identifier);
      nearestKnown ??= fresh.map((r) => lastIds.get(r.identifier)).find((id) => id !== undefined);
      level = newestFirst(fresh.flatMap((r) => current.get(r.identifier)?.references ?? []));
    }
    return nearestKnown;
  };

  // The oldest message a chain names, through current messages.
  const oldest = (identifier: string, seen: Set<string>): string => {
    const [first] = oldestFirst(current.get(identifier)?.references ?? []);
    if (!first || seen.has(identifier)) return identifier;
    seen.add(identifier);
    return oldest(first.identifier, seen);
  };

  const ids = threads.map((t): string | null => {
    if (named.has(t.identifier)) return null;
    if (typeof t.result === "string") return own(t);
    return continued(t.identifier) ?? `${source}:${oldest(t.identifier, new Set())}`;
  });

  // Whether a message's chain reaches the one served under [id] last run:
  // that message itself (kept or fetched again), or one it updates.
  const reachesHolder = (identifier: string, id: string, seen: Set<string>): boolean => {
    if (seen.has(identifier)) return false;
    seen.add(identifier);
    if (lastServed.has(identifier) && lastIds.get(identifier) === id) return true;
    const t = current.get(identifier);
    if (t?.kept && typeof t.result !== "string" && t.result.id === id) return true;
    return (t?.references ?? []).some((r) => reachesHolder(r.identifier, id, seen));
  };

  // A split: the claimant continuing the ID's holder, else the first by
  // identifier, keeps it.
  const claims = new Map<string, number[]>();
  ids.forEach((id, i) => {
    if (id !== null && typeof threads[i].result !== "string") claims.set(id, [...(claims.get(id) ?? []), i]);
  });
  for (const [id, indices] of claims) {
    if (indices.length < 2) continue;
    const held = (i: number) => Number(reachesHolder(threads[i].identifier, id, new Set()));
    const ranked = [...indices].sort((a, b) =>
      held(b) - held(a) || threads[a].identifier.localeCompare(threads[b].identifier),
    );
    for (const i of ranked.slice(1)) ids[i] = own(threads[i]);
  }
  return ids;
}

function oldestFirst(references: CapReference[]): CapReference[] {
  return [...references].sort((a, b) => sentAt(a) - sentAt(b));
}

function newestFirst(references: CapReference[]): CapReference[] {
  return oldestFirst(references).reverse();
}

function sentAt(reference: CapReference): number {
  const t = reference.sent ? Date.parse(reference.sent) : NaN;
  return Number.isFinite(t) ? t : Number.MAX_SAFE_INTEGER;
}
