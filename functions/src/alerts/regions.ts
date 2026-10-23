/**
 * MeteoAlarm's region shapes: the polygons behind the geocodes most of its
 * country feeds name instead of drawing an area. Its Redistribution Hub
 * page links the current files: the shapes, keyed by `EMMA_ID`, and an
 * aliases table mapping the NUTS and FIPS codes some feeds use to those
 * regions (docs/ALERTS.md → MeteoAlarm regions). Pure: fetching and caching
 * are the ingester's.
 */
import type { LatLon } from "./cap";
import { geoJsonPolygons, simplifyRing } from "./geo";

/** The Redistribution Hub page, as MeteoAlarm's own site loads it. */
export const REGIONS_PAGE_URL =
  "https://cms-visualization.meteoalarm.org/api/v1/content-pages/redistribution-hub?locale=en";

/** The geocode types a feed's areas name regions by, most precise first. */
export const REGION_TYPES = ["EMMA_ID", "NUTS3", "NUTS2", "FIPS"] as const;
export type RegionType = (typeof REGION_TYPES)[number];

export interface RegionLinks {
  /** The shapes file ("MeteoAlarm_Geocodes_<date>.json"). */
  geocodes: string | null;
  /** The aliases table, as a direct download. */
  aliases: string | null;
}

/** A Google Drive "view" link as a direct download, or the link as it was. */
export function driveDownloadUrl(url: string): string {
  const id = url.match(/^https:\/\/drive\.google\.com\/file\/d\/([\w-]+)/)?.[1];
  return id ? `https://drive.usercontent.google.com/download?id=${id}&export=download&confirm=t` : url;
}

/** The current files' links on the Redistribution Hub page, by their link text. */
export function regionLinks(pageJson: string): RegionLinks {
  const page = JSON.stringify(JSON.parse(pageJson));
  const link = (label: string): string | null => {
    const match = page.match(new RegExp(`href=\\\\"(https://[^"\\\\]+)\\\\"[^>]*>\\s*${label}\\s*</a>`));
    return match ? match[1] : null;
  };
  const aliases = link("Geocodes Aliases");
  return { geocodes: link("Geocodes"), aliases: aliases && driveDownloadUrl(aliases) };
}

/**
 * Every `EMMA_ID` region in a geocode file, each simplified to within
 * [tolerance] degrees: its polygons, each an outer ring then any holes.
 * Throws when the file isn't a GeoJSON feature collection.
 */
export function parseRegionShapes(json: string, tolerance: number): Map<string, LatLon[][][]> {
  const root = JSON.parse(json) as { features?: unknown };
  if (!Array.isArray(root.features)) throw new Error("not a GeoJSON feature collection");
  const shapes = new Map<string, LatLon[][][]>();
  for (const feature of root.features as { properties?: Record<string, unknown>; geometry?: unknown }[]) {
    const code = feature?.properties?.code;
    if (typeof code !== "string" || feature.properties?.type !== "EMMA_ID") continue;
    const polygons = geoJsonPolygons(feature.geometry).map((rings) => rings.map((r) => simplifyRing(r, tolerance)));
    if (polygons.length > 0) shapes.set(code, polygons);
  }
  return shapes;
}

/**
 * The aliases table (`CODE,ALIAS_CODE,ALIAS_TYPE`): for each alias of the
 * types feeds use, the `EMMA_ID` regions it stands for, keyed
 * "NUTS3:FR302". Throws when the header isn't the expected one.
 */
export function parseRegionAliases(csv: string): Map<string, string[]> {
  const [header, ...rows] = csv.trim().split(/\r?\n/);
  if (header?.trim() !== "CODE,ALIAS_CODE,ALIAS_TYPE") throw new Error("not a geocode aliases table");
  const aliases = new Map<string, string[]>();
  for (const row of rows) {
    const [code, alias, type] = row.split(",").map((field) => field.trim());
    if (!code || !alias || !(REGION_TYPES as readonly string[]).includes(type)) continue;
    const key = `${type}:${alias}`;
    aliases.set(key, [...(aliases.get(key) ?? []), code]);
  }
  return aliases;
}
