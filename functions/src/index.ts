import { onRequest } from "firebase-functions/v2/https";
import { setGlobalOptions } from "firebase-functions/v2";
import { defineSecret } from "firebase-functions/params";
import { logger } from "firebase-functions";
import { initializeApp } from "firebase-admin/app";
import { getAppCheck } from "firebase-admin/app-check";
import {
  FieldValue,
  getFirestore,
  Timestamp,
} from "firebase-admin/firestore";

initializeApp();
setGlobalOptions({ region: "us-central1" });

const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");

// Allowlist of upstream Gemini models the proxy will forward to. Keep narrow
// so a compromised or modded client can't redirect billing onto an expensive
// model. Mirror `DEFAULT_GEMINI_TTS_MODEL` in the Android code.
const ALLOWED_MODELS = new Set<string>(["gemini-2.5-flash-preview-tts"]);

const GEMINI_HOST = "generativelanguage.googleapis.com";
const GEMINI_API_VERSION = "v1beta";

const INSTALLS_COLLECTION = "installs";

// Per-install daily quota for the shared-key path. Counted by UTC calendar
// day so the reset moment is global and unambiguous; the Android client's
// nudge copy ("resets at midnight UTC") matches. Bump when we have a clearer
// signal of real-world legitimate use — five successful syntheses covers
// two morning + two evening insights + one preview tap per day.
const DAILY_LIMIT = 5;

export const tts = onRequest(
  {
    secrets: [GEMINI_API_KEY],
    cors: false,
    invoker: "public",
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "method_not_allowed" });
      return;
    }

    const appCheckToken = headerValue(req.header("X-Firebase-AppCheck"));
    if (!appCheckToken) {
      res.status(401).json({ error: "missing_app_check_token" });
      return;
    }
    try {
      await getAppCheck().verifyToken(appCheckToken);
    } catch (err) {
      logger.warn("App Check verification failed", { code: errorCode(err) });
      res.status(401).json({ error: "invalid_app_check_token" });
      return;
    }

    const installId = headerValue(req.header("X-Install-Id"));
    if (!installId || !/^[A-Za-z0-9_-]{1,128}$/.test(installId)) {
      res.status(400).json({ error: "missing_or_invalid_install_id" });
      return;
    }

    const body = req.body;
    if (!body || typeof body !== "object") {
      res.status(400).json({ error: "invalid_body" });
      return;
    }

    const modelHeader = headerValue(req.header("X-Gemini-Model"));
    if (!modelHeader || !ALLOWED_MODELS.has(modelHeader)) {
      res.status(400).json({ error: "model_not_allowed" });
      return;
    }

    // Reserve a quota slot transactionally before calling Gemini. A failed
    // upstream call rolls the slot back below so a Gemini hiccup doesn't
    // burn a user's daily allowance. Firestore outages fail open
    // (`reservation.reserved === false` with a logged warning) — better to
    // serve audio than to block on our own bookkeeping when the upstream
    // path is the expensive bit.
    const now = new Date();
    const today = utcDayKey(now);
    let reservation: Reservation;
    try {
      reservation = await reserveDailySlot(installId, today);
    } catch (err) {
      logger.warn("Quota reservation failed; failing open", {
        code: errorCode(err),
      });
      reservation = { reserved: false, remaining: null };
    }
    if (reservation.exhausted) {
      res.setHeader("X-Daily-Quota-Limit", String(DAILY_LIMIT));
      res.setHeader("X-Daily-Quota-Remaining", "0");
      res.status(429).json({
        error: "daily_quota_exhausted",
        limit: DAILY_LIMIT,
        resetAtUtc: nextUtcMidnightIso(now),
      });
      return;
    }

    const upstreamUrl =
      `https://${GEMINI_HOST}/${GEMINI_API_VERSION}` +
      `/models/${encodeURIComponent(modelHeader)}:generateContent`;

    let upstream: Response;
    try {
      upstream = await fetch(upstreamUrl, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-goog-api-key": GEMINI_API_KEY.value(),
        },
        body: JSON.stringify(body),
      });
    } catch (err) {
      logger.error("Upstream Gemini fetch failed", { code: errorCode(err) });
      // Await the rollback before responding: Cloud Functions for
      // Firebase v2 doesn't guarantee work continues after res.send(),
      // and a missed rollback here burns one of the user's daily
      // slots on a network blip. Only roll back when we actually
      // reserved a slot — when reserveDailySlot failed open
      // (reservation.reserved === false), no counter was incremented
      // and a decrement would silently steal from a future slot once
      // Firestore recovers.
      if (reservation.reserved) {
        try {
          await releaseDailySlot(installId, today);
        } catch (rbErr) {
          logger.warn("Quota rollback after fetch failure failed", {
            code: errorCode(rbErr),
          });
        }
      }
      res.status(502).json({ error: "upstream_unreachable" });
      return;
    }

    // The fetch() resolves once headers are in; the body read can
    // still throw if the upstream connection drops between header and
    // body. Treat that like a fetch-throw — release the slot before
    // responding so a transient network blip doesn't burn one of the
    // user's daily slots.
    let upstreamBody: ArrayBuffer;
    try {
      upstreamBody = await upstream.arrayBuffer();
    } catch (err) {
      logger.error("Upstream Gemini body read failed", { code: errorCode(err) });
      if (reservation.reserved) {
        try {
          await releaseDailySlot(installId, today);
        } catch (rbErr) {
          logger.warn("Quota rollback after body read failure failed", {
            code: errorCode(rbErr),
          });
        }
      }
      res.status(502).json({ error: "upstream_unreachable" });
      return;
    }

    // Even a 200 from Gemini can carry no audio: the prompt can land on
    // a safety filter (`promptFeedback.blockReason`) or the candidate
    // can come back without an `inlineData` part. The Android client
    // (`GeminiTtsClient.synthesize`) treats both as synthesis failures
    // and falls back to device TTS — so we should treat them the same
    // way for quota purposes and refund the slot, otherwise a string
    // of safety-blocked prompts could burn the user's daily allowance
    // while delivering no audio.
    const successHasAudio = upstream.ok && responseHasAudio(upstreamBody);

    if (successHasAudio) {
      if (reservation.reserved) {
        res.setHeader("X-Daily-Quota-Limit", String(DAILY_LIMIT));
        res.setHeader("X-Daily-Quota-Remaining", String(reservation.remaining));
      }
    } else if (upstream.ok) {
      logger.warn("Gemini returned 200 but no inline audio", {
        bodyExcerpt: excerpt(upstreamBody),
      });
      if (reservation.reserved) {
        try {
          await releaseDailySlot(installId, today);
        } catch (err) {
          logger.warn("Quota rollback after no-audio 200 failed", {
            code: errorCode(err),
          });
        }
      }
    } else {
      logger.warn("Gemini returned non-success", {
        status: upstream.status,
        bodyExcerpt: excerpt(upstreamBody),
      });
      // Only successful syntheses should burn quota. Roll back the
      // reservation; clients can retry within the same day without
      // penalty. Awaited (not fire-and-forget) so the work doesn't
      // get cut off by the response below — see the fetch-throw
      // path above for the rationale. Gated on `reservation.reserved`
      // for the same reason: a failed-open reservation has nothing
      // to undo.
      if (reservation.reserved) {
        try {
          await releaseDailySlot(installId, today);
        } catch (err) {
          logger.warn("Quota rollback after upstream non-success failed", {
            code: errorCode(err),
          });
        }
      }
    }

    res.status(upstream.status);
    const upstreamContentType = upstream.headers.get("content-type");
    if (upstreamContentType) {
      res.setHeader("content-type", upstreamContentType);
    }
    res.send(Buffer.from(upstreamBody));
  },
);

interface Reservation {
  /** True when we successfully reserved a slot (and so should rollback on failure). */
  reserved: boolean;
  /** True when the install already exhausted today's allowance. */
  exhausted?: boolean;
  /** Remaining slots for today after this call, or null when we failed open. */
  remaining: number | null;
}

async function reserveDailySlot(
  installId: string,
  today: string,
): Promise<Reservation> {
  const docRef = getFirestore().collection(INSTALLS_COLLECTION).doc(installId);
  return await getFirestore().runTransaction(async (tx) => {
    const snap = await tx.get(docRef);
    const now = Timestamp.now();

    const data = snap.exists ? (snap.data() ?? {}) : {};
    const sameDay = data.dayKey === today;
    const dayCount = sameDay && typeof data.dayCount === "number"
      ? data.dayCount
      : 0;

    if (dayCount >= DAILY_LIMIT) {
      return { reserved: false, exhausted: true, remaining: 0 };
    }

    const nextDayCount = dayCount + 1;
    const remaining = DAILY_LIMIT - nextDayCount;

    if (snap.exists) {
      tx.update(docRef, {
        count: FieldValue.increment(1),
        lastUseAt: now,
        dayKey: today,
        dayCount: nextDayCount,
      });
    } else {
      tx.set(docRef, {
        firstUseAt: now,
        lastUseAt: now,
        count: 1,
        dayKey: today,
        dayCount: 1,
      });
    }

    return { reserved: true, remaining };
  });
}

async function releaseDailySlot(
  installId: string,
  today: string,
): Promise<void> {
  const docRef = getFirestore().collection(INSTALLS_COLLECTION).doc(installId);
  await getFirestore().runTransaction(async (tx) => {
    const snap = await tx.get(docRef);
    if (!snap.exists) return;
    const data = snap.data() ?? {};
    // Day rolled between reserve and rollback: another day's counter would
    // be wrong to decrement. Lifetime `count` likewise stays as-is — at
    // most one extra count survives across the day boundary, which is
    // tolerable.
    if (data.dayKey !== today) return;
    const dayCount = typeof data.dayCount === "number" ? data.dayCount : 0;
    if (dayCount <= 0) return;
    tx.update(docRef, {
      count: FieldValue.increment(-1),
      dayCount: dayCount - 1,
    });
  });
}

function utcDayKey(date: Date): string {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, "0");
  const d = String(date.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function nextUtcMidnightIso(date: Date): string {
  const next = new Date(
    Date.UTC(
      date.getUTCFullYear(),
      date.getUTCMonth(),
      date.getUTCDate() + 1,
      0,
      0,
      0,
      0,
    ),
  );
  return next.toISOString();
}

function headerValue(raw: unknown): string | null {
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
 * Returns true when the upstream 200 response actually carries audio.
 *
 * Mirrors what `GeminiTtsClient.synthesize` checks for on the Android
 * client: presence of at least one `candidates[*].content.parts[*].inlineData`,
 * and no `promptFeedback.blockReason`. A 200 that fails either check
 * is treated by the client as a synthesis failure (falls back to device
 * TTS), so for quota purposes we treat it the same way and refund the
 * slot.
 *
 * Defaults to "looks like audio" when the body isn't parseable as the
 * expected JSON envelope — better to credit the user with a slot than
 * to over-rollback on a payload shape we don't recognise. Gemini is
 * the only producer here, so an unparseable 200 body is the
 * vanishingly-rare case in practice.
 */
function responseHasAudio(body: ArrayBuffer): boolean {
  let text: string;
  try {
    text = Buffer.from(body).toString("utf8");
  } catch {
    return true;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return true;
  }
  if (!parsed || typeof parsed !== "object") return true;
  const envelope = parsed as Record<string, unknown>;
  const promptFeedback = envelope.promptFeedback as
    | { blockReason?: unknown }
    | undefined;
  if (promptFeedback && promptFeedback.blockReason) return false;
  const candidates = envelope.candidates;
  if (!Array.isArray(candidates) || candidates.length === 0) return false;
  for (const candidate of candidates) {
    if (!candidate || typeof candidate !== "object") continue;
    const content = (candidate as Record<string, unknown>).content as
      | { parts?: unknown }
      | undefined;
    const parts = content?.parts;
    if (!Array.isArray(parts)) continue;
    for (const part of parts) {
      if (!part || typeof part !== "object") continue;
      const inlineData = (part as Record<string, unknown>).inlineData as
        | { data?: unknown }
        | undefined;
      if (inlineData && typeof inlineData.data === "string" && inlineData.data.length > 0) {
        return true;
      }
    }
  }
  return false;
}

function excerpt(body: ArrayBuffer): string {
  const text = Buffer.from(body).toString("utf8");
  return text.slice(0, 200);
}

function errorCode(err: unknown): string {
  if (err && typeof err === "object" && "code" in err) {
    return String((err as { code: unknown }).code);
  }
  if (err instanceof Error) return err.name;
  return "unknown";
}
