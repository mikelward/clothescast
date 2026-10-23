/**
 * Geometry helpers for sources that send GeoJSON rather than CAP polygons
 * (the US National Weather Service). Pure, so they're unit testable.
 */
import type { LatLon } from "./cap";

type Position = number[];

function ringOf(positions: unknown): LatLon[] | null {
  if (!Array.isArray(positions)) return null;
  const ring: LatLon[] = [];
  for (const p of positions as Position[]) {
    if (!Array.isArray(p) || p.length < 2) return null;
    const [lon, lat] = p;
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
    ring.push([lat, lon]);
  }
  return ring.length >= 4 ? ring : null;
}

/** A GeoJSON polygon's rings, outer first, or null when its outer ring is malformed. */
function polygonOf(polygon: unknown): LatLon[][] | null {
  if (!Array.isArray(polygon) || polygon.length === 0) return null;
  const outer = ringOf(polygon[0]);
  if (!outer) return null;
  return [outer, ...polygon.slice(1).map(ringOf).filter((r): r is LatLon[] => r !== null)];
}

/**
 * The polygons of a GeoJSON Polygon, MultiPolygon or GeometryCollection, each
 * its outer ring then any holes, as `[lat, lon]` (GeoJSON is longitude
 * first). A polygon whose outer ring is malformed is skipped, and so is a
 * malformed hole; anything else gives none.
 */
export function geoJsonPolygons(geometry: unknown): LatLon[][][] {
  if (!geometry || typeof geometry !== "object") return [];
  const g = geometry as { type?: unknown; coordinates?: unknown; geometries?: unknown };
  const some = (polygons: unknown[]): LatLon[][][] =>
    polygons.map(polygonOf).filter((p): p is LatLon[][] => p !== null);
  switch (g.type) {
    case "Polygon":
      return some([g.coordinates]);
    case "MultiPolygon":
      return Array.isArray(g.coordinates) ? some(g.coordinates) : [];
    case "GeometryCollection":
      return Array.isArray(g.geometries) ? g.geometries.flatMap(geoJsonPolygons) : [];
    default:
      return [];
  }
}

function distanceToSegment(p: LatLon, a: LatLon, b: LatLon): number {
  const [x, y] = p;
  const [x1, y1] = a;
  const dx = b[0] - x1;
  const dy = b[1] - y1;
  if (dx === 0 && dy === 0) return Math.hypot(x - x1, y - y1);
  const t = Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)));
  return Math.hypot(x - (x1 + t * dx), y - (y1 + t * dy));
}

function douglasPeucker(points: LatLon[], tolerance: number): LatLon[] {
  if (points.length < 3) return points;
  let worst = 0;
  let at = 0;
  for (let i = 1; i < points.length - 1; i++) {
    const d = distanceToSegment(points[i], points[0], points[points.length - 1]);
    if (d > worst) {
      worst = d;
      at = i;
    }
  }
  if (worst <= tolerance) return [points[0], points[points.length - 1]];
  const left = douglasPeucker(points.slice(0, at + 1), tolerance);
  return [...left.slice(0, -1), ...douglasPeucker(points.slice(at), tolerance)];
}

/**
 * [ring] with points dropped while the outline stays within [tolerance]
 * degrees of the original (Douglas–Peucker), still closed. A ring that would
 * collapse below a triangle is kept as it was.
 */
export function simplifyRing(ring: LatLon[], tolerance: number): LatLon[] {
  if (ring.length <= 4) return ring;
  // Split at the point farthest from the start, so the closed ring's two
  // halves simplify as open lines.
  let far = 1;
  let farthest = 0;
  for (let i = 1; i < ring.length - 1; i++) {
    const d = Math.hypot(ring[i][0] - ring[0][0], ring[i][1] - ring[0][1]);
    if (d > farthest) {
      farthest = d;
      far = i;
    }
  }
  const first = douglasPeucker(ring.slice(0, far + 1), tolerance);
  const second = douglasPeucker(ring.slice(far), tolerance);
  const simplified = [...first.slice(0, -1), ...second];
  return simplified.length >= 4 ? simplified : ring;
}
