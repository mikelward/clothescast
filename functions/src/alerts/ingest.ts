/**
 * One run of the alerts ingester (docs/ALERTS.md → Architecture → B): read
 * every source's feed, fetch the CAP documents that are new or changed,
 * and publish the alerts in force as one file per 1° tile plus an index.
 * The phone downloads its own tile and checks containment itself.
 *
 * Tile files are named by their contents' hash, so they never change once
 * written; the index, which maps each tile to its file, is the only file a
 * run replaces, and only if no other run published since this one began.
 * Two runs that overlap can't mix their views: the later index is refused,
 * and the files it alone named are cleared away later as unreferenced.
 *
 * Everything that touches the network or the bucket comes in through
 * [IngestDeps], so a run is unit testable.
 */
import { createHash } from "node:crypto";

import { parseCapAlert, parseCapFeed } from "./cap";
import { serve, type ServedAlert, type Skip } from "./served";
import type { AlertSource } from "./sources";
import { TILE_DEGREES, tilesFor } from "./tiles";

/** Bump when a file's shape changes incompatibly; it's in every path. */
export const FORMAT_VERSION = 1;

export const INDEX_PATH = `v${FORMAT_VERSION}/index.json`;
export const TILES_PREFIX = `v${FORMAT_VERSION}/tiles/`;
/** A tile's file: its key, then the first [HASH_LENGTH] hex digits of its contents' SHA-256. */
export const tilePath = (key: string, hash: string): string => `${TILES_PREFIX}${key}/${hash}.json`;
export const STATE_PATH = `ingest-state/v${FORMAT_VERSION}.json`;
export const HASH_LENGTH = 16;

/**
 * How long a tile file is kept after the last index that named it: a phone
 * that read an older index still finds the tiles it names.
 */
export const TILE_RETENTION_MS = 60 * 60_000;

/** Identifies us to feed operators (NWS, ECCC and DWD require one). */
export const USER_AGENT = "ClothesCast alerts (+https://github.com/mikelward/clothescast)";

const FETCH_TIMEOUT_MS = 20_000;
const FEED_CONCURRENCY = 4;
const DOCUMENT_CONCURRENCY = 8;

interface CachedEntry {
  updated: string | null;
  /** The alert served for this document, or why it isn't. */
  result: ServedAlert | Skip;
}

interface SourceState {
  checkedAt: string;
  lastSuccessAt: string | null;
  error: string | null;
  entries: Record<string, CachedEntry>;
}

/** Saved between runs. */
export interface IngestState {
  v: number;
  /** What each feed listed, so unchanged documents aren't fetched again. */
  sources: Record<string, SourceState>;
  /** Each tile file's path → when a published index last named it. */
  named?: Record<string, string>;
}

export interface IndexSource {
  id: string;
  countries: string[];
  attribution: { name: string; url: string };
  /** When the feed and every document it listed last loaded; null if never. */
  lastSuccessAt: string | null;
  /**
   * Why the latest check was incomplete (the feed failed, or some of its
   * documents did), or null when it was complete.
   */
  error: string | null;
}

export interface IndexFile {
  v: number;
  generatedAt: string;
  tileDegrees: number;
  sources: IndexSource[];
  /**
   * Each tile with alerts ("51/-1") and its file's hash ([tilePath]); a tile
   * not listed has none.
   */
  tiles: Record<string, string>;
}

export interface TileFile {
  v: number;
  tile: string;
  alerts: ServedAlert[];
}

export interface Logger {
  info(message: string, fields?: Record<string, unknown>): void;
  warn(message: string, fields?: Record<string, unknown>): void;
  error(message: string, fields?: Record<string, unknown>): void;
}

/** A published tile file, as the bucket lists it. */
export interface StoredFile {
  path: string;
  createdAt: Date;
}

export interface AlertStore {
  /** The saved state and its generation, or nulls before the first run. */
  readState(): Promise<{ state: IngestState | null; generation: string | null }>;
  /** Saves [state] only if it's still at [generation]; throws otherwise. */
  writeState(state: IngestState, generation: string | null): Promise<void>;
  /** The published index's generation, or null before the first publication. */
  indexGeneration(): Promise<string | null>;
  /** Publishes [index] only if the index is still at [generation]; throws otherwise. */
  writeIndex(index: IndexFile, generation: string | null): Promise<void>;
  /** Every tile file in the bucket. */
  listTiles(): Promise<StoredFile[]>;
  writeTile(path: string, body: TileFile): Promise<void>;
  delete(path: string): Promise<void>;
}

export interface IngestDeps {
  sources: AlertSource[];
  store: AlertStore;
  fetch: typeof fetch;
  now: () => Date;
  log: Logger;
}

export interface IngestSummary {
  alerts: number;
  tilesWritten: number;
  tilesDeleted: number;
  sourceErrors: number;
}

async function fetchText(deps: IngestDeps, url: string): Promise<string> {
  // No Accept header: MeteoAlarm answers 406 to any XML type and serves
  // only fetch's default `*/*`.
  const response = await deps.fetch(url, {
    headers: { "User-Agent": USER_AGENT },
    signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return await response.text();
}

/** Runs [work] over [items], at most [limit] at a time. */
async function pool<T, R>(items: T[], limit: number, work: (item: T) => Promise<R>): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      results[i] = await work(items[i]);
    }
  });
  await Promise.all(workers);
  return results;
}

function describe(err: unknown): string {
  if (!(err instanceof Error)) return String(err);
  if (err.name === "TimeoutError") return "timeout";
  // fetch() reports network failures as "fetch failed" with the reason in `cause`.
  const code = ((err as { cause?: { code?: unknown } }).cause)?.code;
  return typeof code === "string" ? `${err.message}: ${code}` : err.message;
}

/** One source's entries this run; on a feed failure, the last run's. */
async function refreshSource(
  deps: IngestDeps,
  source: AlertSource,
  previous: SourceState | undefined,
  now: Date,
): Promise<SourceState> {
  const checkedAt = now.toISOString();
  let feed;
  try {
    feed = parseCapFeed(await fetchText(deps, source.url));
  } catch (err) {
    // Keep serving what it last listed until those alerts expire; the index
    // reports the failure so the app can tell stale from quiet.
    deps.log.warn("Alert feed failed; keeping its last alerts", { source: source.id, error: describe(err) });
    return {
      checkedAt,
      lastSuccessAt: previous?.lastSuccessAt ?? null,
      error: describe(err),
      entries: previous?.entries ?? {},
    };
  }

  const entries: Record<string, CachedEntry> = {};
  let documentErrors = 0;
  await pool(feed, DOCUMENT_CONCURRENCY, async (entry) => {
    const cached = previous?.entries[entry.identifier];
    if (cached && cached.updated === entry.updated) {
      entries[entry.identifier] = cached;
      return;
    }
    try {
      const cap = parseCapAlert(await fetchText(deps, entry.capUrl));
      entries[entry.identifier] = { updated: entry.updated, result: serve(source.id, cap, now) };
    } catch (err) {
      documentErrors++;
      // A document that won't load keeps its last version, if any, and is
      // retried next run.
      if (cached) entries[entry.identifier] = cached;
      deps.log.warn("Alert document failed", { source: source.id, error: describe(err) });
    }
  });
  if (documentErrors > 0) {
    // The check is incomplete: say so, so a covered country with a missing
    // alert never reads as quiet, and keep the last time it was complete.
    deps.log.warn("Alert documents failed", { source: source.id, count: documentErrors });
    return {
      checkedAt,
      lastSuccessAt: previous?.lastSuccessAt ?? null,
      error: `${documentErrors} of ${feed.length} documents failed`,
      entries,
    };
  }
  return { checkedAt, lastSuccessAt: checkedAt, error: null, entries };
}

function hasPassed(iso: string | null, now: Date): boolean {
  if (!iso) return false;
  const t = Date.parse(iso);
  return Number.isFinite(t) && t <= now.getTime();
}

function sha256(body: string): string {
  return createHash("sha256").update(body).digest("hex");
}

/** Runs the ingester once and publishes what changed. */
export async function ingest(deps: IngestDeps): Promise<IngestSummary> {
  const now = deps.now();
  const { state: saved, generation } = await deps.store.readState();
  const previous = saved?.v === FORMAT_VERSION ? saved : null;
  const indexGeneration = await deps.store.indexGeneration();

  const sourceStates = await pool(deps.sources, FEED_CONCURRENCY, (source) =>
    refreshSource(deps, source, previous?.sources[source.id], now),
  );

  const byTile = new Map<string, ServedAlert[]>();
  let alertCount = 0;
  deps.sources.forEach((source, i) => {
    for (const { result } of Object.values(sourceStates[i].entries)) {
      if (typeof result === "string" || hasPassed(result.expires, now)) continue;
      const keys = tilesFor(result.polygons);
      if (keys === null) {
        deps.log.warn("Alert area too broad to tile; left out", { source: source.id, alert: result.id });
        continue;
      }
      alertCount++;
      for (const key of keys) {
        const list = byTile.get(key) ?? [];
        list.push(result);
        byTile.set(key, list);
      }
    }
  });

  // Tiles first, then the index that names them, then the old files: a
  // phone holding any index from the last hour finds every tile it names.
  const stored = await deps.store.listTiles();
  const present = new Set(stored.map((f) => f.path));
  const tiles: Record<string, string> = {};
  let tilesWritten = 0;
  for (const [key, alerts] of [...byTile.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    alerts.sort((a, b) => a.id.localeCompare(b.id));
    const body: TileFile = { v: FORMAT_VERSION, tile: key, alerts };
    const hash = sha256(JSON.stringify(body)).slice(0, HASH_LENGTH);
    tiles[key] = hash;
    const path = tilePath(key, hash);
    if (present.has(path)) continue;
    await deps.store.writeTile(path, body);
    tilesWritten++;
  }

  const index: IndexFile = {
    v: FORMAT_VERSION,
    generatedAt: now.toISOString(),
    tileDegrees: TILE_DEGREES,
    sources: deps.sources.map((source, i) => ({
      id: source.id,
      countries: source.countries,
      attribution: source.attribution,
      lastSuccessAt: sourceStates[i].lastSuccessAt,
      error: sourceStates[i].error,
    })),
    tiles,
  };
  // Refused if another run published since this one began: its view stands,
  // and this run stops before deleting anything or saving its state.
  await deps.store.writeIndex(index, indexGeneration);

  // A file goes an hour after the last index that named it, measured from
  // when it stopped being named, not from when it was written. One this run
  // knows nothing about (state lost, or written by a refused run) gets a
  // full hour from now.
  const nowIso = now.toISOString();
  const named: Record<string, string> = {};
  for (const [key, hash] of Object.entries(tiles)) named[tilePath(key, hash)] = nowIso;
  let tilesDeleted = 0;
  for (const file of stored) {
    if (file.path in named) continue;
    const lastNamed = previous?.named?.[file.path];
    if (lastNamed === undefined || now.getTime() - Date.parse(lastNamed) < TILE_RETENTION_MS) {
      named[file.path] = lastNamed ?? nowIso;
      continue;
    }
    await deps.store.delete(file.path);
    tilesDeleted++;
  }

  const state: IngestState = {
    v: FORMAT_VERSION,
    sources: Object.fromEntries(deps.sources.map((s, i) => [s.id, sourceStates[i]])),
    named,
  };
  await deps.store.writeState(state, generation);

  const summary = {
    alerts: alertCount,
    tilesWritten,
    tilesDeleted,
    sourceErrors: sourceStates.filter((s) => s.error !== null).length,
  };
  deps.log.info("Alerts ingested", { ...summary });
  return summary;
}
