/**
 * Which public-alerts requests the `weatherAlerts` proxy will forward to
 * Google, and in what shape. Split out of `index.ts` to be unit testable —
 * that file initializes Firebase at module scope.
 *
 * The proxy spends the developer's Maps Platform key, so it forwards only
 * what the app's alerts client sends: a coordinate, a language for the
 * alert titles, a page size and the next-page token. Anything else is
 * refused rather than passed through.
 */

/** Google's cap on one page of alerts; the app asks for one page. */
export const MAX_PAGE_SIZE = 20;
/** Page tokens are opaque; this bounds what we're willing to forward. */
const MAX_PAGE_TOKEN_LENGTH = 2048;
/**
 * A BCP 47 tag as the app sends it (`en`, `en-GB`, `pt-BR`, `zh-Hant-TW`):
 * a language, then up to three subtags. Bounded so a crafted value can't
 * smuggle anything into the upstream query.
 */
const LANGUAGE_CODE = /^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}$/;

/** The query string to send upstream, or why the request is refused. */
export type AlertsQuery =
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
export function alertsQuery(query: Record<string, unknown>): AlertsQuery {
  const latitude = number(query["location.latitude"], -90, 90);
  const longitude = number(query["location.longitude"], -180, 180);
  if (latitude === null || longitude === null) return { ok: false, error: "invalid_location" };

  const params = new URLSearchParams({
    "location.latitude": String(latitude),
    "location.longitude": String(longitude),
  });

  if (query.languageCode !== undefined) {
    const code = single(query.languageCode);
    if (code === null || !LANGUAGE_CODE.test(code)) {
      return { ok: false, error: "invalid_language_code" };
    }
    params.set("languageCode", code);
  }
  if (query.pageSize !== undefined) {
    const pageSize = integer(query.pageSize, 1, MAX_PAGE_SIZE);
    if (pageSize === null) return { ok: false, error: "invalid_page_size" };
    params.set("pageSize", String(pageSize));
  }
  if (query.pageToken !== undefined) {
    const token = single(query.pageToken);
    if (token === null || token === "" || token.length > MAX_PAGE_TOKEN_LENGTH) {
      return { ok: false, error: "invalid_page_token" };
    }
    params.set("pageToken", token);
  }
  return { ok: true, params };
}
