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

/**
 * How far past its tile an alert's area is kept: more than the app's
 * containment slack, so the edge a cut draws stays farther than that slack
 * from every point in the tile, and the app answers as for the whole area.
 */
export const CLIP_MARGIN_KM = 2 * TILE_REACH_KM;

/**
 * An alert wider than this keeps its whole area in every tile. The app
 * won't notify of an area over 1,000 km across
 * (`WeatherAlertRules.MAX_NOTIFY_SPAN_KM`), measuring the area it's sent,
 * and a tile's piece is always narrower; under this, both are notified alike.
 */
export const MAX_CLIPPED_SPAN_KM = 900;

const KM_PER_DEGREE = 111.2;

/**
 * Coordinates a cut adds are rounded to 1e-4°, as CAP polygons are
 * (`cap.ts`): an edge the cut shortens moves by at most about 5 m.
 */
const COORDINATE_SCALE = 1e4;

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

/**
 * The longer side of the polygons' bounding box in km, as the app measures
 * an area (`AlertArea.spanKm`): longitudes taken as they are, so an area
 * across the antimeridian measures the long way round, as it does there.
 */
export function spanKm(polygons: LatLon[][][]): number {
  const points = polygons.flat(2);
  if (points.length === 0) return 0;
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
  const midLat = (minLat + maxLat) / 2;
  const height = (maxLat - minLat) * KM_PER_DEGREE;
  const width = (maxLon - minLon) * KM_PER_DEGREE * Math.cos((midLat * Math.PI) / 180);
  return Math.max(height, Math.abs(width));
}

interface Box {
  south: number;
  north: number;
  west: number;
  east: number;
}

/**
 * [polygons] cut to the tile [key] and [CLIP_MARGIN_KM] around it, so a tile
 * file carries only its part of each area. Within the tile the app finds a
 * point inside, outside, or within its slack of the edge just as for the
 * whole area: the cut keeps every edge near the tile and draws new ones only
 * along the margin's outer line. Each ring is cut on its own
 * (Sutherland–Hodgman), as the app's even-odd test reads rings; a ring left
 * with no area, and a polygon left with no rings, are dropped.
 */
export function clipToTile(polygons: LatLon[][][], key: string): LatLon[][][] {
  const [row, col] = key.split("/").map(Number);
  const latMargin = CLIP_MARGIN_KM / KM_PER_DEGREE;
  // Wide enough at the tile's poleward edge, so wider everywhere else.
  const poleward = Math.min(Math.max(Math.abs(row), Math.abs(row + TILE_DEGREES)) + latMargin, 90);
  const cos = Math.cos((poleward * Math.PI) / 180);
  const lonMargin = cos > 1e-9 ? latMargin / cos : Infinity;
  // Rounded outward to the coordinate step, so the margin only grows.
  const box: Box = {
    south: floorTo(row - latMargin),
    north: ceilTo(row + TILE_DEGREES + latMargin),
    west: floorTo(col - lonMargin),
    east: ceilTo(col + TILE_DEGREES + lonMargin),
  };
  return polygons
    .map((rings) => rings.map((ring) => clipRing(ring, box)).filter((ring) => ring.length > 0))
    .filter((rings) => rings.length > 0);
}

/** [ring] cut to [box], closed, or empty when no area is left. */
function clipRing(ring: LatLon[], box: Box): LatLon[] {
  const closed = ring.length > 1 && ring[0][0] === ring[ring.length - 1][0] && ring[0][1] === ring[ring.length - 1][1];
  let points = closed ? ring.slice(0, -1) : ring;
  const edges: [axis: 0 | 1, bound: number, keep: (v: number) => boolean][] = [
    [0, box.south, (lat) => lat >= box.south],
    [0, box.north, (lat) => lat <= box.north],
    [1, box.west, (lon) => lon >= box.west],
    [1, box.east, (lon) => lon <= box.east],
  ];
  for (const [axis, bound, keep] of edges) {
    if (!Number.isFinite(bound)) continue;
    const out: LatLon[] = [];
    points.forEach((cur, i) => {
      const prev = points[(i + points.length - 1) % points.length];
      if (keep(cur[axis]) !== keep(prev[axis])) out.push(crossing(prev, cur, axis, bound));
      if (keep(cur[axis])) out.push(cur);
    });
    points = out;
  }
  // Left only along the margin's line: a ring that was all outside.
  const flat = points.every((p) => onBox(p, box)) && Math.abs(area(points)) < COORDINATE_SCALE ** -2;
  if (points.length < 3 || flat) return [];
  return [...points, points[0]];
}

/** Where segment [a]→[b] crosses the line [axis] = [bound]. */
function crossing(a: LatLon, b: LatLon, axis: 0 | 1, bound: number): LatLon {
  const other = axis === 0 ? 1 : 0;
  const t = (bound - a[axis]) / (b[axis] - a[axis]);
  const point: LatLon = [0, 0];
  point[axis] = bound;
  point[other] = roundTo(a[other] + t * (b[other] - a[other]));
  return point;
}

function onBox([lat, lon]: LatLon, box: Box): boolean {
  return lat === box.south || lat === box.north || lon === box.west || lon === box.east;
}

/** Twice the ring's signed area, in square degrees (shoelace). */
function area(points: LatLon[]): number {
  let sum = 0;
  points.forEach(([lat, lon], i) => {
    const [nextLat, nextLon] = points[(i + 1) % points.length];
    sum += lon * nextLat - nextLon * lat;
  });
  return sum;
}

function roundTo(v: number): number {
  return Math.round(v * COORDINATE_SCALE) / COORDINATE_SCALE;
}

function floorTo(v: number): number {
  return Math.floor(v * COORDINATE_SCALE) / COORDINATE_SCALE;
}

function ceilTo(v: number): number {
  return Math.ceil(v * COORDINATE_SCALE) / COORDINATE_SCALE;
}
