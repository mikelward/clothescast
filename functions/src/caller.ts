/**
 * Who is calling, and what they get, from a verified Firebase ID token.
 *
 * Split out of `index.ts` to be unit testable — that file initializes
 * Firebase at module scope. See SPEC.md → "Accounts and the
 * developer-funded paths" for the design.
 */
import { createHmac } from "node:crypto";

/** The free daily cap for anyone without a `ttsDailyLimit` claim. */
export const DEFAULT_DAILY_LIMIT = 5;

/** A daily cap, or no per-account count at all. */
export type TtsLimit = number | "unlimited";

export interface Caller {
  /** Firestore doc id under `quota/`. */
  quotaKey: string;
  /** True for a Google sign-in, false for an anonymous one. */
  signedIn: boolean;
  ttsLimit: TtsLimit;
}

/** The parts of a decoded ID token this module reads. */
export interface TokenLike {
  uid: string;
  firebase?: { sign_in_provider?: unknown; identities?: unknown };
  [claim: string]: unknown;
}

const GOOGLE_PROVIDER = "google.com";

/**
 * The Google account's stable subject ID, or null when the token isn't a
 * Google sign-in or doesn't carry one.
 */
export function googleSubject(token: TokenLike): string | null {
  if (token.firebase?.sign_in_provider !== GOOGLE_PROVIDER) return null;
  const identities = token.firebase.identities;
  if (!identities || typeof identities !== "object") return null;
  const ids = (identities as Record<string, unknown>)[GOOGLE_PROVIDER];
  if (!Array.isArray(ids) || typeof ids[0] !== "string" || ids[0] === "") {
    return null;
  }
  return ids[0];
}

/**
 * Reads the `ttsDailyLimit` custom claim. Absent or malformed means the
 * default cap: a bad claim is the admin script's bug, and the default is
 * the conservative reading of it.
 */
export function ttsLimitFromClaim(raw: unknown): TtsLimit {
  if (raw === "unlimited") return "unlimited";
  if (typeof raw === "number" && Number.isInteger(raw) && raw >= 0) return raw;
  return DEFAULT_DAILY_LIMIT;
}

/**
 * The quota bucket for a Google account: a keyed hash of its subject ID,
 * not the Firebase uid, so deleting the account and signing in again (a
 * new uid) lands on the same day's count. Prefixed so it can never collide
 * with an anonymous uid's doc.
 */
export function googleQuotaKey(subject: string, hashKey: string): string {
  return "g_" + createHmac("sha256", hashKey).update(subject).digest("hex");
}

/**
 * The caller for a verified token, or null for a Google sign-in whose
 * token lacks the account ID (fail closed rather than guess a bucket).
 * Anonymous callers keep today's behavior: the uid is the bucket and
 * claims don't apply.
 */
export function callerFromToken(token: TokenLike, hashKey: string): Caller | null {
  if (token.firebase?.sign_in_provider === GOOGLE_PROVIDER) {
    const subject = googleSubject(token);
    if (!subject) return null;
    return {
      quotaKey: googleQuotaKey(subject, hashKey),
      signedIn: true,
      ttsLimit: ttsLimitFromClaim(token.ttsDailyLimit),
    };
  }
  return { quotaKey: token.uid, signedIn: false, ttsLimit: DEFAULT_DAILY_LIMIT };
}
