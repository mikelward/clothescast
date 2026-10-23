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

import { parseCapAlert, parseCapFeed, type CapAlert, type CapReference, type LatLon } from "./cap";
import { bundledRegion } from "./bundled-regions";
import { DWD_PROFILE } from "./dwd";
import { parseEcccAlerts } from "./eccc";
import { geoJsonPolygons, simplifyRing } from "./geo";
import { parseNwsAlerts, type NwsAlert } from "./nws";
import { REGIONS_PAGE_URL, REGION_TYPES, parseRegionAliases, parseRegionShapes, regionLinks } from "./regions";
import { serve, type CapProfile, type ServedAlert, type Skip } from "./served";
import type { AlertSource } from "./sources";
import { threadIds } from "./threads";
import { TILE_DEGREES, tilesFor } from "./tiles";
import { unzip } from "./unzip";

const PROFILES: Record<NonNullable<AlertSource["profile"]>, CapProfile> = { dwd: DWD_PROFILE };

/** Bump when a file's shape changes incompatibly; it's in every path. */
export const FORMAT_VERSION = 1;

export const INDEX_PATH = `v${FORMAT_VERSION}/index.json`;
export const TILES_PREFIX = `v${FORMAT_VERSION}/tiles/`;
/** A tile's file: its key, then the first [HASH_LENGTH] hex digits of its contents' SHA-256. */
export const tilePath = (key: string, hash: string): string => `${TILES_PREFIX}${key}/${hash}.json`;
export const STATE_PATH = `ingest-state/v${FORMAT_VERSION}.json`;
export const HASH_LENGTH = 16;

// NWS zone shapes: fetched once from api.weather.gov, simplified, and kept in
// the bucket; refetched after a month, since zones change a few times a year.
const NWS_ZONE = /^https:\/\/api\.weather\.gov\/zones\/(forecast|fire|county)\/([A-Z]{2}[ZC]\d{3})$/;
export const zonePath = (type: string, id: string): string => `zones/v${FORMAT_VERSION}/${type}/${id}.json`;
const ZONE_MAX_AGE_MS = 30 * 24 * 60 * 60_000;
/**
 * How far a simplified zone outline may stray, in degrees (about 200 m),
 * well inside the app's 1 km containment slack.
 */
export const ZONE_TOLERANCE = 0.002;
const ZONE_CONCURRENCY = 4;

// MeteoAlarm's region shapes, for feeds that name regions instead of drawing
// them: the files its Redistribution Hub links (all regions' shapes, and the
// NUTS and FIPS aliases), simplified and split into the bucket by geocode
// type and code ("NUTS3:FR302"), refreshed after a month. They're downloaded
// at most once a day, so a region missing from them can't cause a download
// every run.
export const regionPath = (key: string): string => `regions/v${FORMAT_VERSION}/${key.replace(":", "/")}.json`;
const REGION_MAX_AGE_MS = ZONE_MAX_AGE_MS;
const REGION_REFETCH_MS = 24 * 60 * 60_000;
const REGION_FETCH_TIMEOUT_MS = 90_000;
const REGION_WRITE_CONCURRENCY = 16;

/**
 * How long a tile file is kept after the last index that named it: a phone
 * that read an older index still finds the tiles it names.
 */
export const TILE_RETENTION_MS = 60 * 60_000;

/** Identifies us to feed operators (NWS, ECCC and DWD require one). */
export const USER_AGENT = "ClothesCast alerts (+https://github.com/mikelward/clothescast)";

const FETCH_TIMEOUT_MS = 20_000;
/**
 * How long a run fetches before publishing what it has: inside the function's
 * 240-second timeout, with room to tile and write. A source still fetching
 * then has its requests aborted and fails this run, keeping its last alerts,
 * so one slow host can't stop every source's update.
 */
export const RUN_FETCH_BUDGET_MS = 180_000;
/**
 * Feeds read at once from each host. Hosts run side by side, so NWS and DWD
 * never wait behind MeteoAlarm's 32 country feeds, and MeteoAlarm still sees
 * no more feeds at once than before.
 */
const FEED_CONCURRENCY = 4;
const DOCUMENT_CONCURRENCY = 8;

interface CachedEntry {
  updated: string | null;
  /** The alert served for this document, or why it isn't. */
  result: ServedAlert | Skip;
  /** The document's CAP identifier and the messages it updates; absent in state saved before them. */
  identifier?: string;
  references?: CapReference[];
  /** Another current message updates or cancels this one, so it isn't served (threads.ts). */
  superseded?: boolean;
}

interface SourceState {
  checkedAt: string;
  lastSuccessAt: string | null;
  error: string | null;
  /**
   * Whether [entries] are still exactly the alerts of the check at
   * [lastSuccessAt]: true after a complete check, and kept through feed
   * failures that serve the last run's entries unchanged; false once a
   * check was incomplete document by document. Absent in state saved
   * before it existed.
   */
  servingLastSuccess?: boolean;
  entries: Record<string, CachedEntry>;
}

/** Saved between runs. */
export interface IngestState {
  v: number;
  /** What each feed listed, so unchanged documents aren't fetched again. */
  sources: Record<string, SourceState>;
  /** Each tile file's path → when a published index last named it. */
  named?: Record<string, string>;
  /** When MeteoAlarm's region shapes were last downloaded. */
  regionsFetchedAt?: string;
}

export interface IndexSource {
  id: string;
  countries: string[];
  attribution: { name: string; url: string };
  /** The source's own disclaimer, linked after ClothesCast's under its author's name. */
  disclaimer: { name: string; url: string; text?: string };
  /** When the feed and every document it listed last loaded; null if never. */
  lastSuccessAt: string | null;
  /**
   * Why the latest check was incomplete (the feed failed, or some of its
   * documents did), or null when it was complete.
   */
  error: string | null;
  /**
   * Whether the source's served alerts are still exactly those of the check
   * at [lastSuccessAt], as when only the feed has failed since: the app can
   * then show them for a while instead of reading the source as unknown.
   */
  servingLastSuccess: boolean;
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

/** A zone's simplified shape, as kept in the bucket. */
export interface ZoneFile {
  v: number;
  fetchedAt: string;
  polygons: LatLon[][][];
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
  /** A cached zone shape, or null when there's none. */
  readZone(path: string): Promise<ZoneFile | null>;
  writeZone(path: string, zone: ZoneFile): Promise<void>;
}

export interface IngestDeps {
  sources: AlertSource[];
  store: AlertStore;
  fetch: typeof fetch;
  now: () => Date;
  log: Logger;
  /** Settles when fetching must stop; by default after [RUN_FETCH_BUDGET_MS]. */
  fetchDeadline?: Promise<void>;
}

export interface IngestSummary {
  alerts: number;
  tilesWritten: number;
  tilesDeleted: number;
  sourceErrors: number;
}

async function fetchText(deps: IngestDeps, url: string, timeoutMs = FETCH_TIMEOUT_MS): Promise<string> {
  // No Accept header: MeteoAlarm answers 406 to any XML type and serves
  // only fetch's default `*/*`.
  const response = await deps.fetch(url, {
    headers: { "User-Agent": USER_AGENT },
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return await response.text();
}

async function fetchBytes(deps: IngestDeps, url: string): Promise<Buffer> {
  const response = await deps.fetch(url, {
    headers: { "User-Agent": USER_AGENT },
    signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return Buffer.from(await response.arrayBuffer());
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
  if (err.name === "AbortError") return "run out of time";
  // fetch() reports network failures as "fetch failed" with the reason in `cause`.
  const code = ((err as { cause?: { code?: unknown } }).cause)?.code;
  return typeof code === "string" ? `${err.message}: ${code}` : err.message;
}

/** MeteoAlarm's region shapes for one run, downloaded at most once. */
interface Regions {
  /** The shapes of those of [keys] ("EMMA_ID:FR069") it knows. */
  shapes(keys: string[]): Promise<Map<string, LatLon[][][]>>;
  /** When the shapes were last downloaded, for the run's state. */
  fetchedAt: string | null;
}

function regionResolver(deps: IngestDeps, fetchedAt: string | null, now: Date): Regions {
  const cached = new Map<string, Promise<{ fetchedAt: string; polygons: LatLon[][][] } | null>>();
  let downloaded: Promise<Map<string, LatLon[][][]> | null> | null = null;
  const regions: Regions = {
    fetchedAt,
    async shapes(keys) {
      const found = new Map<string, LatLon[][][]>();
      let stale = false;
      for (const key of keys) {
        const bundled = bundledRegion(key);
        if (bundled) {
          found.set(key, bundled);
          continue;
        }
        if (!cached.has(key)) cached.set(key, deps.store.readZone(regionPath(key)));
        const file = await cached.get(key);
        if (file) found.set(key, file.polygons);
        if (!file || now.getTime() - Date.parse(file.fetchedAt) >= REGION_MAX_AGE_MS) stale = true;
      }
      if (!stale) return found;
      downloaded ??= download();
      const fresh = await downloaded;
      for (const key of keys) {
        const polygons = fresh?.get(key);
        if (polygons) found.set(key, polygons);
      }
      return found;
    },
  };

  async function download(): Promise<Map<string, LatLon[][][]> | null> {
    if (regions.fetchedAt && now.getTime() - Date.parse(regions.fetchedAt) < REGION_REFETCH_MS) return null;
    try {
      const links = regionLinks(await fetchText(deps, REGIONS_PAGE_URL));
      if (!links.geocodes) throw new Error("no geocode file linked");
      const byCode = parseRegionShapes(await fetchText(deps, links.geocodes, REGION_FETCH_TIMEOUT_MS), ZONE_TOLERANCE);
      const shapes = new Map<string, LatLon[][][]>([...byCode].map(([code, p]) => [`EMMA_ID:${code}`, p]));
      // An alias stands for one or more regions: their shapes together.
      try {
        if (!links.aliases) throw new Error("no aliases file linked");
        for (const [key, codes] of parseRegionAliases(await fetchText(deps, links.aliases, REGION_FETCH_TIMEOUT_MS))) {
          const polygons = codes.flatMap((code) => byCode.get(code) ?? []);
          if (polygons.length > 0) shapes.set(key, polygons);
        }
      } catch (err) {
        deps.log.warn("MeteoAlarm region aliases download failed", { error: describe(err) });
      }
      const fetchedIso = now.toISOString();
      await pool([...shapes], REGION_WRITE_CONCURRENCY, ([key, polygons]) =>
        deps.store.writeZone(regionPath(key), { v: FORMAT_VERSION, fetchedAt: fetchedIso, polygons }),
      );
      regions.fetchedAt = fetchedIso;
      deps.log.info("MeteoAlarm regions downloaded", { file: links.geocodes, regions: byCode.size, keys: shapes.size });
      return shapes;
    } catch (err) {
      // Stale shapes stand in where there are any; tried again tomorrow.
      regions.fetchedAt = now.toISOString();
      deps.log.warn("MeteoAlarm regions download failed", { error: describe(err) });
      return null;
    }
  }
  return regions;
}

/** An area's region keys ("NUTS3:FR302"), by the most precise geocode type it names. */
function regionKeys(geocodes: Record<string, string[]>): string[] {
  const type = REGION_TYPES.find((t) => geocodes[t]?.length);
  return type ? geocodes[type].map((code) => `${type}:${code}`) : [];
}

/**
 * Fills the first `<info>`'s areas that name MeteoAlarm regions instead of
 * drawing them with those regions' shapes; returns how many of the regions
 * named are unknown.
 */
async function placeRegions(cap: CapAlert, regions: Regions): Promise<number> {
  const pending = (cap.infos[0]?.areas ?? []).filter((a) => a.rings.length === 0 && regionKeys(a.geocodes).length);
  if (pending.length === 0) return 0;
  const shapes = await regions.shapes([...new Set(pending.flatMap((a) => regionKeys(a.geocodes)))]);
  let unknown = 0;
  for (const area of pending) {
    for (const key of regionKeys(area.geocodes)) {
      const polygons = shapes.get(key);
      if (!polygons) {
        unknown++;
        continue;
      }
      for (const [outer, ...holes] of polygons) {
        area.rings.push(outer);
        area.holes.push(...holes);
      }
    }
  }
  return unknown;
}

/** One source's entries this run; on a feed failure, the last run's. */
async function refreshSource(
  deps: IngestDeps,
  source: AlertSource,
  previous: SourceState | undefined,
  now: Date,
  regions: Regions,
): Promise<SourceState> {
  let state: SourceState;
  switch (source.kind) {
    case "nws":
      state = await refreshNwsSource(deps, source, previous, now);
      break;
    case "cap-zip":
      state = await refreshZipSource(deps, source, previous, now);
      break;
    case "eccc":
      state = await refreshEcccSource(deps, source, previous, now);
      break;
    default:
      state = await refreshCapSource(deps, source, previous, now, regions);
  }
  return { ...state, entries: threaded(source, state.entries, previous) };
}

/**
 * [entries] with each alert served under the ID it had before its latest
 * update, and the messages another current one replaces marked superseded
 * (threads.ts). An entry kept from [previous] keeps its ID.
 */
function threaded(
  source: AlertSource,
  entries: Record<string, CachedEntry>,
  previous: SourceState | undefined,
): Record<string, CachedEntry> {
  // State saved before identifiers were kept: the served ID still held one.
  const identifierOf = (key: string, entry: CachedEntry) =>
    entry.identifier ?? (typeof entry.result === "string" ? key : entry.result.id.slice(source.id.length + 1));
  const lastIds = new Map<string, string>();
  const lastServed = new Set<string>();
  for (const [key, entry] of Object.entries(previous?.entries ?? {})) {
    if (typeof entry.result === "string") continue;
    lastIds.set(identifierOf(key, entry), entry.result.id);
    if (!entry.superseded) lastServed.add(identifierOf(key, entry));
  }
  const keys = Object.keys(entries);
  const ids = threadIds(source.id, keys.map((key) => ({
    identifier: identifierOf(key, entries[key]),
    references: entries[key].references ?? [],
    result: entries[key].result,
    kept: previous?.entries[key] === entries[key],
  })), lastIds, lastServed);
  return Object.fromEntries(keys.map((key, i) => {
    const entry = entries[key];
    const id = ids[i];
    const { superseded: _, ...rest } = entry;
    if (id === null) return [key, { ...rest, superseded: true }];
    if (typeof entry.result === "string" || entry.result.id === id) return [key, rest];
    return [key, { ...rest, result: { ...entry.result, id } }];
  }));
}

// Keep serving what the feed last listed until those alerts expire; the index
// reports the failure so the app can tell stale from quiet.
function feedFailed(deps: IngestDeps, source: AlertSource, previous: SourceState | undefined, now: Date, err: unknown) {
  deps.log.warn("Alert feed failed; keeping its last alerts", { source: source.id, error: describe(err) });
  return {
    checkedAt: now.toISOString(),
    lastSuccessAt: previous?.lastSuccessAt ?? null,
    error: describe(err),
    // The last run's entries, unchanged: still the last complete check's if they were.
    servingLastSuccess: previous ? (previous.servingLastSuccess ?? previous.error === null) : false,
    entries: previous?.entries ?? {},
  };
}

async function refreshCapSource(
  deps: IngestDeps,
  source: AlertSource,
  previous: SourceState | undefined,
  now: Date,
  regions: Regions,
): Promise<SourceState> {
  const checkedAt = now.toISOString();
  let feed;
  try {
    feed = parseCapFeed(await fetchText(deps, source.url));
  } catch (err) {
    return feedFailed(deps, source, previous, now, err);
  }

  const entries: Record<string, CachedEntry> = {};
  let documentErrors = 0;
  let regionErrors = 0;
  await pool(feed, DOCUMENT_CONCURRENCY, async (entry) => {
    const cached = previous?.entries[entry.identifier];
    if (cached && cached.updated === entry.updated) {
      entries[entry.identifier] = cached;
      return;
    }
    try {
      const cap = parseCapAlert(await fetchText(deps, entry.capUrl));
      const unknownRegions = await placeRegions(cap, regions);
      // A region not yet known is placed again next run.
      if (unknownRegions > 0) {
        regionErrors++;
        deps.log.warn("Alert names unknown regions", { source: source.id, count: unknownRegions });
      }
      entries[entry.identifier] = {
        updated: unknownRegions > 0 ? null : entry.updated,
        result: serve(source.id, cap, now),
        identifier: cap.identifier,
        references: cap.references,
      };
    } catch (err) {
      documentErrors++;
      // A document that won't load keeps its last version, if any, and is
      // retried next run.
      if (cached) entries[entry.identifier] = cached;
      deps.log.warn("Alert document failed", { source: source.id, error: describe(err) });
    }
  });
  if (documentErrors > 0 || regionErrors > 0) {
    // The check is incomplete: say so, so a covered country with a missing
    // alert never reads as quiet, and keep the last time it was complete.
    deps.log.warn("Alert documents failed", { source: source.id, count: documentErrors, regions: regionErrors });
    const errors = [
      documentErrors > 0 ? `${documentErrors} of ${feed.length} documents failed` : null,
      regionErrors > 0 ? `${regionErrors} of ${feed.length} documents name unknown regions` : null,
    ];
    return {
      checkedAt,
      lastSuccessAt: previous?.lastSuccessAt ?? null,
      error: errors.filter((e) => e !== null).join("; "),
      servingLastSuccess: false,
      entries,
    };
  }
  return { checkedAt, lastSuccessAt: checkedAt, error: null, servingLastSuccess: true, entries };
}

/**
 * [result] with every ring simplified as zone shapes are: DWD draws its
 * warnings along commune boundaries, hundreds of points that would
 * otherwise dominate the tile files.
 */
function simplified(result: ServedAlert | Skip): ServedAlert | Skip {
  if (typeof result === "string") return result;
  return { ...result, polygons: result.polygons.map((rings) => rings.map((r) => simplifyRing(r, ZONE_TOLERANCE))) };
}

/**
 * A zip of every alert in force (DWD): each file is one CAP document named
 * by its identifier, which changes with every update, so a file seen before
 * keeps the alert served for it and one gone from the zip is over.
 */
async function refreshZipSource(
  deps: IngestDeps,
  source: AlertSource,
  previous: SourceState | undefined,
  now: Date,
): Promise<SourceState> {
  const checkedAt = now.toISOString();
  let files;
  try {
    files = unzip(await fetchBytes(deps, source.url)).filter((f) => f.name.endsWith(".xml"));
  } catch (err) {
    return feedFailed(deps, source, previous, now, err);
  }
  const profile = source.profile ? PROFILES[source.profile] : {};
  const entries: Record<string, CachedEntry> = {};
  let documentErrors = 0;
  for (const file of files) {
    const cached = previous?.entries[file.name];
    if (cached) {
      entries[file.name] = cached;
      continue;
    }
    try {
      const cap = parseCapAlert(file.data.toString("utf8"));
      entries[file.name] = {
        updated: cap.sent,
        result: simplified(serve(source.id, cap, now, profile)),
        identifier: cap.identifier,
        references: cap.references,
      };
    } catch (err) {
      // A document that won't parse is left out and tried again next run.
      documentErrors++;
      deps.log.warn("Alert document failed", { source: source.id, error: describe(err) });
    }
  }
  if (documentErrors > 0) {
    return {
      checkedAt,
      lastSuccessAt: previous?.lastSuccessAt ?? null,
      error: `${documentErrors} of ${files.length} documents failed`,
      servingLastSuccess: false,
      entries,
    };
  }
  return { checkedAt, lastSuccessAt: checkedAt, error: null, servingLastSuccess: true, entries };
}

/**
 * ECCC's alerts in force: one response carries every alert with its
 * regions' polygons and text, so nothing else is fetched. A response cut
 * short is treated as a failed one: what it lacks may be an alert still in
 * force, so the last complete run's alerts stay.
 */
async function refreshEcccSource(
  deps: IngestDeps,
  source: AlertSource,
  previous: SourceState | undefined,
  now: Date,
): Promise<SourceState> {
  const checkedAt = now.toISOString();
  let parsed;
  try {
    parsed = parseEcccAlerts(await fetchText(deps, source.url), source.id, now);
    if (parsed.missing > 0) throw new Error(`${parsed.missing} regions not returned`);
  } catch (err) {
    return feedFailed(deps, source, previous, now, err);
  }
  deps.log.info("ECCC alerts read", { source: source.id, alerts: parsed.alerts.length, skipped: parsed.skipped });
  const entries: Record<string, CachedEntry> = {};
  for (const alert of parsed.alerts) entries[alert.id] = { updated: alert.sent, result: simplified(alert) };
  return { checkedAt, lastSuccessAt: checkedAt, error: null, servingLastSuccess: true, entries };
}

/**
 * The NWS feed's alerts: each carries its text, so only the zones of an
 * alert without a polygon need fetching, and an alert seen before keeps the
 * area it was given then.
 */
async function refreshNwsSource(
  deps: IngestDeps,
  source: AlertSource,
  previous: SourceState | undefined,
  now: Date,
): Promise<SourceState> {
  const checkedAt = now.toISOString();
  let parsed;
  try {
    parsed = parseNwsAlerts(await fetchText(deps, source.url), source.id, now);
  } catch (err) {
    return feedFailed(deps, source, previous, now, err);
  }
  deps.log.info("NWS alerts read", { source: source.id, alerts: parsed.alerts.length, skipped: parsed.skipped });

  const entries: Record<string, CachedEntry> = {};
  // Superseded messages aren't served, but an update two steps on finds
  // its chain through them.
  for (const link of parsed.links) {
    entries[`${source.id}:${link.identifier}`] = {
      updated: null,
      result: "superseded",
      identifier: link.identifier,
      references: link.references,
    };
  }
  const pending: NwsAlert[] = [];
  for (const nws of parsed.alerts) {
    const cached = previous?.entries[nws.alert.id];
    if (cached && cached.updated !== null && cached.updated === nws.alert.sent) {
      entries[nws.alert.id] = cached;
    } else if (nws.zones.length === 0) {
      entries[nws.alert.id] = thread(nws, nws.alert.sent, nws.alert);
    } else {
      pending.push(nws);
    }
  }

  const needed = [...new Set(pending.flatMap((n) => n.zones))];
  const shapes = new Map<string, LatLon[][][]>();
  let zoneErrors = 0;
  await pool(needed, ZONE_CONCURRENCY, async (url) => {
    try {
      shapes.set(url, await zoneShape(deps, url, now));
    } catch (err) {
      zoneErrors++;
      deps.log.warn("NWS zone shape failed", { source: source.id, zone: url, error: describe(err) });
    }
  });
  for (const nws of pending) {
    const polygons = nws.zones.flatMap((z) => shapes.get(z) ?? []);
    const complete = nws.zones.every((z) => shapes.has(z));
    if (polygons.length === 0) {
      // Can't be placed this run: keep its last version, if any; retried next run.
      const cached = previous?.entries[nws.alert.id];
      if (cached) entries[nws.alert.id] = cached;
      continue;
    }
    // A partly placed alert is served, but marked to be placed again next run.
    entries[nws.alert.id] = thread(nws, complete ? nws.alert.sent : null, { ...nws.alert, polygons });
  }
  if (zoneErrors > 0) {
    return {
      checkedAt,
      lastSuccessAt: previous?.lastSuccessAt ?? null,
      error: `${zoneErrors} of ${needed.length} zone shapes failed`,
      servingLastSuccess: false,
      entries,
    };
  }
  return { checkedAt, lastSuccessAt: checkedAt, error: null, servingLastSuccess: true, entries };
}

function thread(nws: NwsAlert, updated: string | null, result: ServedAlert): CachedEntry {
  return { updated, result, identifier: nws.identifier, references: nws.references };
}

/**
 * A zone's simplified shape: from the bucket while under a month old, else
 * from api.weather.gov (kept for next time); a stale copy stands in when the
 * fetch fails. Only api.weather.gov zone addresses are followed.
 */
async function zoneShape(deps: IngestDeps, url: string, now: Date): Promise<LatLon[][][]> {
  const match = url.match(NWS_ZONE);
  if (!match) throw new Error("not an api.weather.gov zone");
  const path = zonePath(match[1], match[2]);
  const cached = await deps.store.readZone(path);
  if (cached && now.getTime() - Date.parse(cached.fetchedAt) < ZONE_MAX_AGE_MS) return cached.polygons;
  try {
    const feature = JSON.parse(await fetchText(deps, url)) as { geometry?: unknown };
    const polygons = geoJsonPolygons(feature.geometry).map((rings) =>
      rings.map((ring) => simplifyRing(ring, ZONE_TOLERANCE)),
    );
    if (polygons.length === 0) throw new Error("zone has no shape");
    await deps.store.writeZone(path, { v: FORMAT_VERSION, fetchedAt: now.toISOString(), polygons });
    return polygons;
  } catch (err) {
    if (!cached) throw err;
    deps.log.warn("NWS zone refresh failed; using the old shape", { zone: url, error: describe(err) });
    return cached.polygons;
  }
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
export async function ingest(runDeps: IngestDeps): Promise<IngestSummary> {
  const outOfTime = new AbortController();
  let timer: NodeJS.Timeout | undefined;
  const deadline = runDeps.fetchDeadline ??
    new Promise<void>((resolve) => { timer = setTimeout(resolve, RUN_FETCH_BUDGET_MS); });
  void deadline.then(() => outOfTime.abort());
  try {
    return await ingestWithin({
      ...runDeps,
      fetch: (url, init) => runDeps.fetch(url, {
        ...init,
        signal: init?.signal ? AbortSignal.any([init.signal, outOfTime.signal]) : outOfTime.signal,
      }),
    });
  } finally {
    clearTimeout(timer);
  }
}

async function ingestWithin(deps: IngestDeps): Promise<IngestSummary> {
  const now = deps.now();
  const { state: saved, generation } = await deps.store.readState();
  const previous = saved?.v === FORMAT_VERSION ? saved : null;
  const indexGeneration = await deps.store.indexGeneration();

  const regions = regionResolver(deps, previous?.regionsFetchedAt ?? null, now);
  const byHost = new Map<string, number[]>();
  deps.sources.forEach((source, i) => {
    const host = new URL(source.url).host;
    byHost.set(host, [...(byHost.get(host) ?? []), i]);
  });
  const sourceStates: SourceState[] = new Array(deps.sources.length);
  await Promise.all([...byHost.values()].map((indices) =>
    pool(indices, FEED_CONCURRENCY, async (i) => {
      const source = deps.sources[i];
      const started = Date.now();
      const state = await refreshSource(deps, source, previous?.sources[source.id], now, regions);
      sourceStates[i] = state;
      const entries = Object.values(state.entries);
      // One line per source per run, so a quiet feed reads as checked, not missing.
      deps.log.info("Alert source checked", {
        source: source.id,
        alerts: entries.filter(({ result, superseded }) => typeof result !== "string" && !superseded).length,
        skipped: entries.filter(({ result, superseded }) => typeof result === "string" || superseded).length,
        error: state.error,
        ms: Date.now() - started,
      });
    }),
  ));

  const byTile = new Map<string, ServedAlert[]>();
  let alertCount = 0;
  deps.sources.forEach((source, i) => {
    for (const { result, superseded } of Object.values(sourceStates[i].entries)) {
      if (typeof result === "string" || superseded || hasPassed(result.expires, now)) continue;
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
      disclaimer: source.disclaimer,
      lastSuccessAt: sourceStates[i].lastSuccessAt,
      error: sourceStates[i].error,
      servingLastSuccess: sourceStates[i].servingLastSuccess ?? false,
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
    ...(regions.fetchedAt ? { regionsFetchedAt: regions.fetchedAt } : {}),
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
