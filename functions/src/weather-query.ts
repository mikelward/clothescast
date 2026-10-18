/**
 * Which hourly-forecast requests the `weather` proxy will forward to Google,
 * and in what shape. Split out of `index.ts` to be unit testable — that file
 * initializes Firebase at module scope.
 *
 * The proxy spends the developer's Maps Platform key, so it forwards only
 * what the app's `GoogleWeatherModelClient` sends: a coordinate, the 7-day
 * horizon, one 24-hour page at a time, metric units, and the next-page
 * token. Anything else is refused rather than passed through.
 */

/** The longest horizon the app asks for (7 days). */
export const MAX_HOURS = 168;
/** Google's cap on one hourly page. */
export const MAX_PAGE_SIZE = 24;
/** Page tokens are opaque; this bounds what we're willing to forward. */
const MAX_PAGE_TOKEN_LENGTH = 2048;

/** The query string to send upstream, or why the request is refused. */
export type WeatherQuery =
  | { ok: true; params: URLSearchParams }
  | { ok: false; error: string };

function single(raw: unknown): string | null {
  if (typeof raw === "string") return raw;
  if (Array.isArray(raw) && raw.length === 1 && typeof raw[0] === "string") return raw[0];
  return null;
}

function number(raw: unknown, min: number, max: number): number | null {
  const s = single(raw);
  if (s === null || s.trim() === "") return null;
  const n = Number(s);
  return Number.isFinite(n) && n >= min && n <= max ? n : null;
}

function integer(raw: unknown, min: number, max: number): number | null {
  const n = number(raw, min, max);
  return n !== null && Number.isInteger(n) ? n : null;
}

/** Validates the client's query and rebuilds it from known fields only. */
export function weatherQuery(query: Record<string, unknown>): WeatherQuery {
  const latitude = number(query["location.latitude"], -90, 90);
  const longitude = number(query["location.longitude"], -180, 180);
  if (latitude === null || longitude === null) return { ok: false, error: "invalid_location" };

  const hours = integer(query.hours, 1, MAX_HOURS);
  if (hours === null) return { ok: false, error: "invalid_hours" };
  const pageSize = integer(query.pageSize, 1, MAX_PAGE_SIZE);
  if (pageSize === null) return { ok: false, error: "invalid_page_size" };
  if (single(query.unitsSystem) !== "METRIC") return { ok: false, error: "invalid_units" };

  const params = new URLSearchParams({
    "location.latitude": String(latitude),
    "location.longitude": String(longitude),
    hours: String(hours),
    pageSize: String(pageSize),
    unitsSystem: "METRIC",
  });
  if (query.pageToken !== undefined) {
    const token = single(query.pageToken);
    if (token === null || token === "" || token.length > MAX_PAGE_TOKEN_LENGTH) {
      return { ok: false, error: "invalid_page_token" };
    }
    params.set("pageToken", token);
  }
  return { ok: true, params };
}
