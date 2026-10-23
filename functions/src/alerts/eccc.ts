/**
 * Environment and Climate Change Canada's alerts in force, from its
 * `weather-alerts` collection on MSC GeoMet (`api.weather.gc.ca`, GeoJSON,
 * the ECCC End-use Licence), read into the ingester's alerts. Pure.
 *
 * The collection has a feature per forecast region an alert covers, each
 * with that region's polygon, its alert's English and French text and a
 * status; a region whose part of the alert is over stays listed as
 * "ended" for a while. Its ID is the alert's, then the region's
 * ("<alert>_<region>"). The regions of one alert in one province make one
 * served alert: the text can differ by province (each names its own
 * storm-report address).
 */
import type { LatLon } from "./cap";
import { geoJsonPolygons } from "./geo";
import type { ServedAlert, Severity } from "./served";

export type EcccSkip = "ended" | "expired" | "no_area";

/** ECCC's risk colors as the app's levels: its color is the warning itself. */
const COLORS: Record<string, Severity> = {
  yellow: "MODERATE",
  orange: "SEVERE",
  red: "EXTREME",
};

/** Statuses for a region whose part of the alert is over. */
const OVER = new Set(["ended", "cancelled", "canceled"]);

/** Beyond this many regions, the list is left out: the polygons are the area. */
const MAX_AREA_NAMES = 5;

const ISSUER = "Environment and Climate Change Canada";

type Props = Record<string, unknown>;

interface Region {
  p: Props;
  polygons: LatLon[][][];
}

function str(value: unknown): string | null {
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

function hasPassed(iso: string | null, now: Date): boolean {
  if (!iso) return false;
  const t = Date.parse(iso);
  return Number.isFinite(t) && t <= now.getTime();
}

/** ECCC's alert names are lower case ("wind warning"); a title starts with a capital. */
function title(name: string | null, language: string): string | null {
  return name && name.charAt(0).toLocaleUpperCase(language) + name.slice(1);
}

function areaName(regions: Region[], key: string): string | null {
  const names = [...new Set(regions.map((r) => str(r.p[key])).filter((n): n is string => n !== null))];
  return names.length > 0 && names.length <= MAX_AREA_NAMES ? names.join("; ") : null;
}

/** The latest of [regions]' publication times, as ECCC sends it. */
function latest(regions: Region[]): Region {
  return regions.reduce((a, b) =>
    Date.parse(str(b.p.publication_datetime) ?? "") > Date.parse(str(a.p.publication_datetime) ?? "") ? b : a,
  );
}

function servedAlert(source: string, id: string, regions: Region[]): ServedAlert {
  const { p } = latest(regions);
  const color = str(p.risk_colour_en)?.trim().toLowerCase();
  const name = str(p.alert_name_en);
  const text = (language: string, suffix: "en" | "fr") => ({
    language,
    title: title(str(p[`alert_name_${suffix}`]), language),
    headline: null,
    description: str(p[`alert_text_${suffix}`])?.replace(/\r\n?/g, "\n").replace(/[ \t]+\n/g, "\n").trim() ?? null,
    instruction: null,
    areaName: areaName(regions, `feature_name_${suffix}`),
    web: null,
  });
  return {
    id: `${source}:${id}`,
    source,
    sent: str(p.publication_datetime),
    onset: str(p.validity_datetime) ?? str(p.publication_datetime),
    // When the hazard ends where given, else when the message does.
    expires: str(p.event_end_datetime) ?? str(p.expiration_datetime),
    // A statement has no color; a warning without one reads yellow.
    severity: (color ? COLORS[color] : undefined) ?? (p.alert_type === "warning" ? "MODERATE" : "MINOR"),
    certainty: null,
    urgency: null,
    eventType: name ? name.toUpperCase().replace(/[^\p{L}\p{N}]+/gu, "_").replace(/^_|_$/g, "") : null,
    issuer: ISSUER,
    polygons: regions.flatMap((r) => r.polygons),
    texts: [text("en-CA", "en"), text("fr-CA", "fr")].filter((t) => t.description !== null || t.title !== null),
  };
}

/** A region's province as an ID part: "SK/AB" (a region on the border) is "AB-SK". */
function province(p: Props): string {
  const codes = (str(p.province) ?? "").split("/").map((c) => c.trim().toUpperCase());
  return [...new Set(codes.filter((c) => /^[A-Z]{2}$/.test(c)))].sort().join("-");
}

/**
 * The alerts in a `weather-alerts` response and, for each region left out,
 * why; [missing] counts the regions the response didn't return (it pages
 * past its `limit`).
 *
 * A served alert is one alert's regions in one province, its ID the
 * alert's, then the province ("eccc:<alert>:MB"): each region's own, so the
 * ID stays the same through updates to the text and whatever happens to
 * other regions, and the app keeps its dismissals and notifications. Two
 * texts in one province (an older version still listed) are served as
 * one, with the newest text.
 */
export function parseEcccAlerts(
  json: string,
  source: string,
  now: Date,
): { alerts: ServedAlert[]; skipped: Partial<Record<EcccSkip, number>>; missing: number } {
  const root = JSON.parse(json) as { features?: unknown; numberMatched?: unknown };
  if (!Array.isArray(root.features)) throw new Error("not an ECCC alerts collection");
  const skipped: Partial<Record<EcccSkip, number>> = {};
  const skip = (why: EcccSkip) => {
    skipped[why] = (skipped[why] ?? 0) + 1;
  };

  // By alert and province.
  const groups = new Map<string, Region[]>();
  for (const feature of root.features as { id?: unknown; properties?: Props; geometry?: unknown }[]) {
    const p = feature?.properties;
    const featureId = typeof feature?.id === "string" ? feature.id : null;
    if (!p || !featureId) continue;
    const alertId = featureId.includes("_") ? featureId.slice(0, featureId.lastIndexOf("_")) : featureId;
    const status = str(p.status_en)?.trim().toLowerCase();
    if (status && OVER.has(status)) { skip("ended"); continue; }
    if (hasPassed(str(p.event_end_datetime) ?? str(p.expiration_datetime), now)) { skip("expired"); continue; }
    const polygons = geoJsonPolygons(feature.geometry);
    if (polygons.length === 0) { skip("no_area"); continue; }
    const region = province(p);
    const id = region ? `${alertId}:${region}` : alertId;
    groups.set(id, [...(groups.get(id) ?? []), { p, polygons }]);
  }

  const alerts = [...groups].map(([id, regions]) => servedAlert(source, id, regions));
  const matched = typeof root.numberMatched === "number" ? root.numberMatched : root.features.length;
  return { alerts, skipped, missing: Math.max(0, matched - root.features.length) };
}
