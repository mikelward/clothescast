/**
 * Narrowing values the handler does not control into something safe to use.
 *
 * A header can arrive as a string, as an array of strings, or not at all;
 * a thrown value may or may not carry a `code`. Both of these feed decisions
 * (which quota bucket, which model) or logs, so neither may assume a shape.
 * Split out of `index.ts` to be unit testable — that file initializes
 * Firebase at module scope.
 */

/**
 * The single trimmed value of a header, or null when it is absent, blank,
 * or a shape we do not recognise. Express hands back `string | string[] |
 * undefined`; an array takes its first entry.
 */
export function headerValue(raw: unknown): string | null {
  if (typeof raw === "string") {
    const trimmed = raw.trim();
    return trimmed.length === 0 ? null : trimmed;
  }
  if (Array.isArray(raw) && raw.length > 0 && typeof raw[0] === "string") {
    return headerValue(raw[0]);
  }
  return null;
}

/**
 * A short, non-sensitive identifier for a thrown value, for logging.
 *
 * Deliberately never the message: an error raised while proxying can quote
 * what it was given, and CLAUDE.md / PRIVACY.md keep prompt-derived content
 * out of Cloud Logging.
 */
export function errorCode(err: unknown): string {
  if (err && typeof err === "object" && "code" in err) {
    return String((err as { code: unknown }).code);
  }
  if (err instanceof Error) return err.name;
  return "unknown";
}
