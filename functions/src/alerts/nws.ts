/**
 * The US National Weather Service's active alerts (`api.weather.gov/alerts/
 * active`, GeoJSON, public domain), read into the ingester's alerts. Pure:
 * zone shapes come in through the caller (docs/ALERTS.md → Sources).
 *
 * Storm-based warnings carry a polygon; the rest name forecast or fire
 * zones, whose shapes the caller resolves. Marine zones are left out (open
 * water, not where anyone gets dressed), and so is an alert naming only
 * them.
 */
import type { LatLon } from "./cap";
import { geoJsonPolygons } from "./geo";
import type { ServedAlert, Severity } from "./served";

/** A marine forecast zone: a water-body prefix, then Z and three digits ("ANZ530", "PZZ110"). */
const MARINE_ZONE = /^(AN|AM|GM|LC|LE|LH|LM|LO|LS|PH|PK|PM|PS|PZ|SL)Z\d{3}$/;

export function isMarineZone(zoneUrl: string): boolean {
  return MARINE_ZONE.test(zoneUrl.slice(zoneUrl.lastIndexOf("/") + 1));
}

/** An NWS alert, its area to come from [zones] when it carries no polygon. */
export interface NwsAlert {
  alert: ServedAlert;
  /** Land zone URLs whose shapes make up the area; empty when [alert] has a polygon. */
  zones: string[];
}

export type NwsSkip = "not_actual" | "cancel" | "expired" | "superseded" | "marine" | "no_area";

const SEVERITIES = new Set<Severity>(["MINOR", "MODERATE", "SEVERE", "EXTREME"]);

type Props = Record<string, unknown>;

function str(value: unknown): string | null {
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

/**
 * NWS text with its fixed-width line wrapping undone, so it flows on a phone:
 * a line break inside a paragraph becomes a space, unless the next line
 * starts a list item ("- ", "* ") or the line ends a list entry ("...").
 * Paragraph breaks stay.
 */
export function unwrapNwsText(text: string | null): string | null {
  if (text === null) return null;
  return text
    .replace(/\r\n?/g, "\n")
    .replace(/[ \t]+\n/g, "\n")
    .split(/\n{2,}/)
    .map((paragraph) => paragraph.replace(/(?<!\.\.\.)\n(?![-*] )/g, " "))
    .join("\n\n")
    .trim();
}

function hasPassed(iso: string | null, now: Date): boolean {
  if (!iso) return false;
  const t = Date.parse(iso);
  return Number.isFinite(t) && t <= now.getTime();
}

/**
 * The alerts in an active-alerts response and, for each left out, why. An
 * alert another one in the response updates is left out as superseded. The
 * end is `ends` (when the hazard ends) where given, else `expires`.
 */
export function parseNwsAlerts(
  json: string,
  source: string,
  now: Date,
): { alerts: NwsAlert[]; skipped: Partial<Record<NwsSkip, number>> } {
  const root = JSON.parse(json) as { features?: unknown };
  if (!Array.isArray(root.features)) throw new Error("not an NWS alerts collection");
  const features = root.features.filter(
    (f): f is { properties: Props; geometry?: unknown } =>
      !!f && typeof f === "object" && !!(f as { properties?: unknown }).properties,
  );
  const superseded = new Set<string>();
  for (const f of features) {
    const refs = f.properties.references;
    if (!Array.isArray(refs)) continue;
    for (const r of refs) {
      const id = str((r as Props | null)?.identifier);
      if (id) superseded.add(id);
    }
  }

  const alerts: NwsAlert[] = [];
  const skipped: Partial<Record<NwsSkip, number>> = {};
  const skip = (why: NwsSkip) => {
    skipped[why] = (skipped[why] ?? 0) + 1;
  };
  for (const { properties: p, geometry } of features) {
    const id = str(p.id);
    if (!id) continue;
    if (p.status !== "Actual") { skip("not_actual"); continue; }
    if (p.messageType === "Cancel") { skip("cancel"); continue; }
    if (superseded.has(id)) { skip("superseded"); continue; }
    const ends = str(p.ends) ?? str(p.expires);
    if (hasPassed(ends, now)) { skip("expired"); continue; }

    const polygons: LatLon[][][] = geoJsonPolygons(geometry);
    const allZones = Array.isArray(p.affectedZones) ? p.affectedZones.filter((z): z is string => typeof z === "string") : [];
    const zones = polygons.length > 0 ? [] : allZones.filter((z) => !isMarineZone(z));
    if (polygons.length === 0 && zones.length === 0) {
      skip(allZones.length > 0 ? "marine" : "no_area");
      continue;
    }
    const severity = str(p.severity)?.toUpperCase() as Severity | undefined;
    const event = str(p.event);
    alerts.push({
      zones,
      alert: {
        id: `${source}:${id}`,
        source,
        sent: str(p.sent),
        onset: str(p.onset) ?? str(p.effective),
        expires: ends,
        severity: severity && SEVERITIES.has(severity) ? severity : "UNKNOWN",
        certainty: str(p.certainty),
        urgency: str(p.urgency),
        eventType: event ? event.toUpperCase().replace(/[^\p{L}\p{N}]+/gu, "_").replace(/^_|_$/g, "") : null,
        issuer: str(p.senderName),
        polygons,
        texts: [{
          language: "en-US",
          title: event,
          headline: str(p.headline),
          description: unwrapNwsText(str(p.description)),
          instruction: unwrapNwsText(str(p.instruction)),
          areaName: str(p.areaDesc),
          web: str(p.web),
        }],
      },
    });
  }
  return { alerts, skipped };
}
