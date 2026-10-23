/**
 * What the alerts ingester serves: one alert per CAP document in force, in
 * the shape the app reads from the tile files (docs/ALERTS.md → Architecture
 * → B). Pure, so it's unit testable.
 */
import type { CapAlert, CapArea, CapInfo, LatLon } from "./cap";
import { ringContains } from "./geo";

export type Severity = "MINOR" | "MODERATE" | "SEVERE" | "EXTREME" | "UNKNOWN";

/** The issuer's text in one language, verbatim. */
export interface ServedText {
  language: string;
  /** CAP `<event>`: the issuer's own title ("Amber wind warning", "High Wind Warning"). */
  title: string | null;
  headline: string | null;
  description: string | null;
  instruction: string | null;
  areaName: string | null;
  web: string | null;
}

export interface ServedAlert {
  /** `<source id>:<CAP identifier>`, unique across sources. */
  id: string;
  source: string;
  sent: string | null;
  onset: string | null;
  expires: string | null;
  severity: Severity;
  certainty: string | null;
  urgency: string | null;
  /**
   * A hazard keyword in the vocabulary the app's `AlertHazards` reads
   * ("WIND", "SNOW_AND_ICE"), else the issuer's event in capitals.
   */
  eventType: string | null;
  issuer: string | null;
  /**
   * The area: the union of these polygons, each its closed outer ring then
   * any holes. CAP shapes may overlap, and a point inside any is inside.
   */
  polygons: LatLon[][][];
  texts: ServedText[];
}

const SEVERITIES = new Set<Severity>(["MINOR", "MODERATE", "SEVERE", "EXTREME"]);

// MeteoAlarm's `awareness_level` ("3; orange; Severe"): its color grade is
// the warning itself, so it wins over the CAP severity when present.
const AWARENESS_LEVELS: Record<string, Severity> = {
  "1": "MINOR",
  "2": "MODERATE",
  "3": "SEVERE",
  "4": "EXTREME",
};

// MeteoAlarm's `awareness_type` ("1; Wind") as the app's hazard keywords.
const AWARENESS_TYPES: Record<string, string> = {
  "1": "WIND",
  "2": "SNOW_AND_ICE",
  "3": "THUNDERSTORM",
  "4": "FOG",
  "5": "HEAT",
  "6": "COLD",
  "7": "COASTAL",
  "8": "FIRE_WEATHER",
  "9": "AVALANCHE",
  "10": "RAIN",
  "12": "FLOOD",
  "13": "FLOOD",
};

function leadingNumber(value: string | undefined): string | null {
  const match = value?.match(/^\s*(\d+)\s*;/);
  return match ? match[1] : null;
}

function severityOf(info: CapInfo): Severity {
  const level = leadingNumber(info.parameters.awareness_level?.[0]);
  if (level && AWARENESS_LEVELS[level]) return AWARENESS_LEVELS[level];
  const cap = info.severity?.trim().toUpperCase() as Severity | undefined;
  return cap && SEVERITIES.has(cap) ? cap : "UNKNOWN";
}

function eventTypeOf(info: CapInfo): string | null {
  const type = leadingNumber(info.parameters.awareness_type?.[0]);
  if (type && AWARENESS_TYPES[type]) return AWARENESS_TYPES[type];
  const event = info.event?.trim();
  return event ? event.toUpperCase().replace(/[^\p{L}\p{N}]+/gu, "_").replace(/^_|_$/g, "") : null;
}

function areaNameOf(info: CapInfo): string | null {
  const names = info.areas.map((a) => a.areaDesc).filter((n): n is string => !!n);
  return names.length > 0 ? names.join("; ") : null;
}

/** Whether an ISO time has passed; an unparseable or missing one hasn't. */
function hasPassed(iso: string | null, now: Date): boolean {
  if (!iso) return false;
  const t = Date.parse(iso);
  return Number.isFinite(t) && t <= now.getTime();
}

export type Skip = "not_actual" | "cancel" | "not_public" | "no_info" | "expired" | "no_area";

/**
 * How a source's CAP differs from the norm, for the fields the app grades
 * and names by. [severity] and [eventType] return null to fall back to the
 * standard reading; [areaName], when given, replaces it.
 */
export interface CapProfile {
  severity?: (info: CapInfo) => Severity | null;
  eventType?: (info: CapInfo) => string | null;
  areaName?: (info: CapInfo) => string | null;
}

/**
 * An area's polygons, each with the holes inside it: a hole goes to the
 * first polygon holding its first point, and one inside none is dropped.
 */
function areaPolygons(area: CapArea): LatLon[][][] {
  const polygons = area.rings.map((ring) => [ring]);
  for (const hole of area.holes) polygons.find(([outer]) => ringContains(outer, hole[0]))?.push(hole);
  return polygons;
}

/**
 * The alert to serve for [cap] from [source], or why it isn't served: only
 * real, public, unexpired alerts with an area. The first `<info>` sets the
 * times, severity and area; every `<info>` contributes its language's text.
 */
export function serve(source: string, cap: CapAlert, now: Date, profile: CapProfile = {}): ServedAlert | Skip {
  if (cap.status !== "Actual") return "not_actual";
  if (cap.msgType === "Cancel") return "cancel";
  if (cap.scope && cap.scope !== "Public") return "not_public";
  const primary = cap.infos[0];
  if (!primary) return "no_info";
  if (hasPassed(primary.expires, now)) return "expired";
  // Each CAP polygon or circle is a polygon of its own; CAP itself has no
  // holes, only DWD's excluded parts.
  const polygons = primary.areas.flatMap(areaPolygons);
  if (polygons.length === 0) return "no_area";
  return {
    id: `${source}:${cap.identifier}`,
    source,
    sent: cap.sent,
    onset: primary.onset ?? primary.effective,
    expires: primary.expires,
    severity: profile.severity?.(primary) ?? severityOf(primary),
    certainty: primary.certainty,
    urgency: primary.urgency,
    eventType: profile.eventType?.(primary) ?? eventTypeOf(primary),
    issuer: primary.senderName,
    polygons,
    texts: cap.infos.map((info) => ({
      language: info.language,
      title: info.event,
      headline: info.headline,
      description: info.description,
      instruction: info.instruction,
      areaName: profile.areaName ? profile.areaName(info) : areaNameOf(info),
      web: info.web,
    })),
  };
}
