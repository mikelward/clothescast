/**
 * CAP 1.2 (Common Alerting Protocol) documents and the Atom feeds that list
 * them, parsed into plain objects (docs/ALERTS.md → One integration for many
 * countries). Pure: no fetching, no Firebase, so it's unit testable.
 *
 * Only the fields the app shows or the ingester needs are read; everything
 * the issuer wrote stays verbatim.
 */
import { XMLParser } from "fast-xml-parser";

/** A point as CAP writes it: latitude first. */
export type LatLon = [number, number];

export interface CapArea {
  areaDesc: string | null;
  /** Each `<polygon>`, plus each `<circle>` drawn as one, as closed rings. */
  rings: LatLon[][];
  /**
   * Parts of the area left out, as closed rings: DWD's `EXCLUDE_POLYGON`
   * geocodes (islands, lakes), holes in the polygon around them.
   */
  holes: LatLon[][];
  /** `<geocode>` values by name, in document order (MeteoAlarm's `EMMA_ID`). */
  geocodes: Record<string, string[]>;
}

export interface CapInfo {
  language: string;
  event: string | null;
  headline: string | null;
  description: string | null;
  instruction: string | null;
  web: string | null;
  senderName: string | null;
  severity: string | null;
  certainty: string | null;
  urgency: string | null;
  effective: string | null;
  onset: string | null;
  expires: string | null;
  /** `<parameter>` values by name, in document order. */
  parameters: Record<string, string[]>;
  /** `<eventCode>` values by name, in document order (DWD's `GROUP`, `II`). */
  eventCodes: Record<string, string[]>;
  areas: CapArea[];
}

/** A message an update or cancellation replaces, as CAP's `<references>` names it. */
export interface CapReference {
  sender: string | null;
  identifier: string;
  sent: string | null;
}

export interface CapAlert {
  identifier: string;
  sender: string | null;
  sent: string | null;
  status: string | null;
  msgType: string | null;
  scope: string | null;
  /** The earlier messages this one updates or cancels. */
  references: CapReference[];
  infos: CapInfo[];
}

/** One entry of a CAP Atom feed: an alert in force and where its CAP document is. */
export interface FeedEntry {
  identifier: string;
  /** The entry's `<updated>`: a new value means the document changed. */
  updated: string | null;
  capUrl: string;
}

const ARRAYS = new Set(["info", "area", "polygon", "circle", "geocode", "parameter", "eventCode", "entry", "link"]);

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: "@",
  removeNSPrefix: true,
  parseTagValue: false,
  parseAttributeValue: false,
  trimValues: true,
  processEntities: true,
  isArray: (name) => ARRAYS.has(name),
});

type Node = Record<string, unknown>;

function text(value: unknown): string | null {
  if (typeof value === "string") return value === "" ? null : value;
  if (typeof value === "number") return String(value);
  if (value && typeof value === "object" && "#text" in value) return text((value as Node)["#text"]);
  return null;
}

function nodes(value: unknown): Node[] {
  return Array.isArray(value) ? value.filter((v): v is Node => !!v && typeof v === "object") : [];
}

function strings(value: unknown): string[] {
  return (Array.isArray(value) ? value : [value]).map(text).filter((v): v is string => v !== null);
}

/**
 * A CAP polygon: space-separated "lat,lon" pairs. Null when any pair is
 * malformed or out of range, or when fewer than three distinct points remain;
 * a ring that isn't closed is closed.
 */
export function parsePolygon(raw: string): LatLon[] | null {
  const points: LatLon[] = [];
  for (const pair of raw.trim().split(/\s+/)) {
    const [lat, lon, ...rest] = pair.split(",").map(Number);
    if (rest.length > 0 || !Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
    points.push([lat, lon]);
  }
  if (points.length < 3) return null;
  const [first, last] = [points[0], points[points.length - 1]];
  if (first[0] !== last[0] || first[1] !== last[1]) points.push([first[0], first[1]]);
  return points.length >= 4 ? points : null;
}

const KM_PER_DEGREE = 111.32;
const CIRCLE_POINTS = 36;

/** A CAP circle ("lat,lon radiusKm") as a closed ring, or null when malformed. */
export function parseCircle(raw: string): LatLon[] | null {
  const match = raw.trim().match(/^(\S+),(\S+)\s+(\S+)$/);
  if (!match) return null;
  const [lat, lon, radiusKm] = match.slice(1).map(Number);
  if (![lat, lon, radiusKm].every(Number.isFinite) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  if (radiusKm <= 0) return null;
  const dLat = radiusKm / KM_PER_DEGREE;
  const dLon = radiusKm / (KM_PER_DEGREE * Math.max(Math.cos((lat * Math.PI) / 180), 0.01));
  const ring: LatLon[] = [];
  for (let i = 0; i < CIRCLE_POINTS; i++) {
    const angle = (2 * Math.PI * i) / CIRCLE_POINTS;
    ring.push([round(lat + dLat * Math.sin(angle)), round(lon + dLon * Math.cos(angle))]);
  }
  ring.push(ring[0]);
  return ring;
}

function round(x: number): number {
  return Math.round(x * 1e4) / 1e4;
}

function parseArea(node: Node): CapArea {
  const rings: LatLon[][] = [];
  for (const raw of strings(node.polygon ?? [])) {
    const ring = parsePolygon(raw);
    if (ring) rings.push(ring);
  }
  for (const raw of strings(node.circle ?? [])) {
    const ring = parseCircle(raw);
    if (ring) rings.push(ring);
  }
  const geocodes = valuesByName(nodes(node.geocode));
  const holes: LatLon[][] = [];
  for (const raw of geocodes.EXCLUDE_POLYGON ?? []) {
    const ring = parsePolygon(raw);
    if (ring) holes.push(ring);
  }
  delete geocodes.EXCLUDE_POLYGON;
  return { areaDesc: text(node.areaDesc), rings, holes, geocodes };
}

function valuesByName(list: Node[]): Record<string, string[]> {
  const values: Record<string, string[]> = {};
  for (const item of list) {
    const name = text(item.valueName);
    const value = text(item.value);
    if (name && value) (values[name] ??= []).push(value);
  }
  return values;
}

function parseInfo(node: Node): CapInfo {
  return {
    language: text(node.language) ?? "en-US",
    event: text(node.event),
    headline: text(node.headline),
    description: text(node.description),
    instruction: text(node.instruction),
    web: text(node.web),
    senderName: text(node.senderName),
    severity: text(node.severity),
    certainty: text(node.certainty),
    urgency: text(node.urgency),
    effective: text(node.effective),
    onset: text(node.onset),
    expires: text(node.expires),
    parameters: valuesByName(nodes(node.parameter)),
    eventCodes: valuesByName(nodes(node.eventCode)),
    areas: nodes(node.area).map(parseArea),
  };
}

/**
 * CAP's `<references>`: space-separated "sender,identifier,sent" triples.
 * A sender may itself hold commas, so the last two fields are taken from
 * the end; a malformed triple is skipped.
 */
export function parseReferences(raw: string | null): CapReference[] {
  if (!raw) return [];
  return raw.trim().split(/\s+/).flatMap((triple) => {
    const fields = triple.split(",");
    if (fields.length < 3) return [];
    const sent = fields.pop() ?? null;
    const identifier = fields.pop();
    return identifier ? [{ sender: fields.join(",") || null, identifier, sent: sent || null }] : [];
  });
}

/** Parses a CAP 1.2 `<alert>`; throws when the XML isn't one. */
export function parseCapAlert(xml: string): CapAlert {
  const root = parser.parse(xml) as Node;
  const alert = root.alert as Node | undefined;
  const identifier = alert ? text(alert.identifier) : null;
  if (!alert || !identifier) throw new Error("not a CAP alert");
  return {
    identifier,
    sender: text(alert.sender),
    sent: text(alert.sent),
    status: text(alert.status),
    msgType: text(alert.msgType),
    scope: text(alert.scope),
    references: parseReferences(text(alert.references)),
    infos: nodes(alert.info).map(parseInfo),
  };
}

/**
 * The alerts a CAP Atom feed lists (MeteoAlarm's legacy feeds and most of
 * the WMO directory's): each one's identifier, `<updated>` and the link to
 * its CAP document. The identifier is the entry's `cap:identifier`, else its
 * `<id>` (MetService's is the CAP identifier). MeteoAlarm repeats an alert
 * once per language, area and polygon, so entries are merged by identifier,
 * keeping the latest `<updated>`. Entries without an identifier or CAP link
 * are skipped. Throws when the XML isn't an Atom feed.
 */
export function parseCapFeed(xml: string): FeedEntry[] {
  const root = parser.parse(xml) as Node;
  const feed = root.feed as Node | undefined;
  if (!feed) throw new Error("not an Atom feed");
  const byId = new Map<string, FeedEntry>();
  for (const entry of nodes(feed.entry)) {
    const identifier = text(entry.identifier) ?? text(entry.id);
    const capLink = nodes(entry.link).find((l) => {
      const type = text(l["@type"]);
      return type === "application/cap+xml" || type === "application/xml" || type === "text/xml";
    });
    const capUrl = capLink ? text(capLink["@href"]) : null;
    if (!identifier || !capUrl) continue;
    const updated = text(entry.updated);
    const seen = byId.get(identifier);
    if (!seen || (updated && (!seen.updated || updated > seen.updated))) {
      byId.set(identifier, { identifier, updated: updated ?? seen?.updated ?? null, capUrl });
    }
  }
  return [...byId.values()];
}
