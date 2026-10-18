/**
 * Custom-claim editing for `scripts/set-tts-limit.js`, split out so the
 * claim shape is unit tested against `caller.ts`'s reader.
 */
import type { TtsLimit } from "./caller";

/** What the admin asked for: a cap, no cap, or back to the default. */
export type TtsSetting = TtsLimit | "default";

/** Parses the script's argument; throws on anything else. */
export function parseTtsSetting(arg: string | undefined): TtsSetting {
  if (arg === "default" || arg === "unlimited") return arg;
  if (arg !== undefined && /^\d{1,6}$/.test(arg)) return Number(arg);
  throw new Error(
    `expected a daily limit (a whole number), "unlimited" or "default", got ${JSON.stringify(arg)}`,
  );
}

/**
 * The claims to store: the existing ones with `ttsDailyLimit` set or
 * removed. Other claims are kept, since setCustomUserClaims replaces the
 * whole object.
 */
export function withTtsSetting(
  current: Record<string, unknown> | undefined,
  setting: TtsSetting,
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...(current ?? {}) };
  if (setting === "default") {
    delete next.ttsDailyLimit;
  } else {
    next.ttsDailyLimit = setting;
  }
  return next;
}
