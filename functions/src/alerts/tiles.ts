/**
 * Which grid squares ("tiles") an alert's area touches. The app downloads
 * the tile holding its point and checks containment itself, so no
 * coordinate leaves the device (docs/ALERTS.md → Architecture → B).
 */
import type { LatLon } from "./cap";

/** Tile size in degrees. Changing it changes every file name: bump the format version. */
export const TILE_DEGREES = 1;

/**
 * An alert touching more tiles than this is left out and logged: at 1° it's
 * a box over 3,000 km across, too broad to mean anything at a point.
 */
export const MAX_TILES_PER_ALERT = 1000;

/**
 * How far past its edge an alert's tiles reach: the app's containment slack
 * (`WeatherAlertRules.CONTAINMENT_TOLERANCE_KM`). A phone that close outside
 * an area still matches it, so it must find the alert in its own tile.
 */
export const TILE_REACH_KM = 1;

const KM_PER_DEGREE = 111.2;

/** A tile's key: the floor of its south-west corner, "lat/lon" ("51/-1"). */
export function tileKey(lat: number, lon: number): string {
  const row = Math.floor(lat / TILE_DEGREES) * TILE_DEGREES;
  const col = Math.floor(normalizeLon(lon) / TILE_DEGREES) * TILE_DEGREES;
  return `${row}/${col}`;
}

function normalizeLon(lon: number): number {
  const wrapped = ((((lon + 180) % 360) + 360) % 360) - 180;
  return wrapped;
}

/**
 * Every tile the polygons' bounding box covers, widened by [TILE_REACH_KM],
 * or null when there are more than [MAX_TILES_PER_ALERT]. A box more than
 * 180° wide is taken to cross the antimeridian (a Pacific alert), not to
 * span the globe.
 */
export function tilesFor(polygons: LatLon[][][]): string[] | null {
  const points = polygons.flat(2);
  if (points.length === 0) return [];
  let minLat = Infinity;
  let maxLat = -Infinity;
  let minLon = Infinity;
  let maxLon = -Infinity;
  for (const [lat, lon] of points) {
    minLat = Math.min(minLat, lat);
    maxLat = Math.max(maxLat, lat);
    minLon = Math.min(minLon, lon);
    maxLon = Math.max(maxLon, lon);
  }
  if (maxLon - minLon > 180) {
    // Shift the western hemisphere east by 360° so the box is contiguous.
    minLon = Infinity;
    maxLon = -Infinity;
    for (const [, lon] of points) {
      const shifted = lon < 0 ? lon + 360 : lon;
      minLon = Math.min(minLon, shifted);
      maxLon = Math.max(maxLon, shifted);
    }
  }
  const latReach = TILE_REACH_KM / KM_PER_DEGREE;
  const widest = Math.min(Math.max(Math.abs(minLat), Math.abs(maxLat)) + latReach, 89);
  const lonReach = latReach / Math.cos((widest * Math.PI) / 180);
  minLat = Math.max(minLat - latReach, -90);
  maxLat = Math.min(maxLat + latReach, 90);
  minLon -= lonReach;
  maxLon += lonReach;
  const rowStart = Math.floor(minLat / TILE_DEGREES);
  const rowEnd = Math.floor(maxLat / TILE_DEGREES);
  const colStart = Math.floor(minLon / TILE_DEGREES);
  const colEnd = Math.floor(maxLon / TILE_DEGREES);
  const count = (rowEnd - rowStart + 1) * (colEnd - colStart + 1);
  if (count > MAX_TILES_PER_ALERT) return null;
  const keys = new Set<string>();
  for (let row = rowStart; row <= rowEnd; row++) {
    for (let col = colStart; col <= colEnd; col++) {
      keys.add(tileKey(row * TILE_DEGREES, col * TILE_DEGREES));
    }
  }
  return [...keys];
}
