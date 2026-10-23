/**
 * Region shapes ClothesCast carries itself, for codes a feed names that
 * MeteoAlarm's own shapes and aliases lack: Croatia's counties (EMMA_ID
 * HR011–HR031 and their NUTS3 codes), from Natural Earth's public-domain
 * boundaries. Rebuilt by `scripts/make-croatia-regions.js`.
 */
import type { LatLon } from "./cap";
import croatia from "./croatia-regions.json";

const counties = croatia.counties as unknown as Record<string, LatLon[][][]>;
const keys = croatia.keys as Record<string, string>;

/** The bundled shape for a region key ("EMMA_ID:HR015"), or undefined. */
export function bundledRegion(key: string): LatLon[][][] | undefined {
  const county = keys[key];
  return county === undefined ? undefined : counties[county];
}
