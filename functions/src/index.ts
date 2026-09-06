import { onRequest } from "firebase-functions/v2/https";
import { setGlobalOptions } from "firebase-functions/v2";
import { defineSecret } from "firebase-functions/params";
import { logger } from "firebase-functions";
import { initializeApp } from "firebase-admin/app";
import { getAppCheck } from "firebase-admin/app-check";
import { getAuth } from "firebase-admin/auth";
import {
  FieldValue,
  getFirestore,
  Timestamp,
} from "firebase-admin/firestore";

import { excerpt, responseHasAudio, structuralEnvelope } from "./envelope";
import { errorCode, headerValue } from "./narrow";
import { nextUtcMidnightIso, utcDayKey } from "./utc-day";

initializeApp();
setGlobalOptions({ region: "us-central1" });

const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");

// Allowlist of upstream Gemini models the proxy will forward to. Keep narrow
// so a compromised or modded client can't redirect billing onto an expensive
// model. Mirror `DEFAULT_GEMINI_TTS_MODEL` in the Android code.
const ALLOWED_MODELS = new Set<string>(["gemini-2.5-flash-preview-tts"]);

const GEMINI_HOST = "generativelanguage.googleapis.com";
const GEMINI_API_VERSION = "v1beta";

const QUOTA_COLLECTION = "quota";

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

    // Identity for the per-install daily quota. Prefer the Firebase
    // Authentication uid from a verified anonymous ID token — it's minted
    // and signed by Firebase Auth, so a modded client can't choose its own
    // quota bucket. During the client rollout we still accept the legacy
    // client-chosen `X-Install-Id` header so app versions built before the
    // switch keep working; that header is spoofable, so it's a transitional
    // fallback only — remove it (and stop the client sending it) once old
    // app versions have aged out. App Check above already proves the caller
    // is a genuine app build; enforcing App Check on Authentication
    // (Security → App Check → Authentication → Enforce) stops scripted
    // anonymous-account farming to rotate the uid.
    let quotaKey: string;
    const authHeader = headerValue(req.header("Authorization"));
    const idToken = authHeader?.startsWith("Bearer ")
      ? authHeader.slice("Bearer ".length).trim()
      : null;
    if (idToken) {
      try {
        quotaKey = (await getAuth().verifyIdToken(idToken)).uid;
      } catch (err) {
        logger.warn("ID token verification failed", { code: errorCode(err) });
        res.status(401).json({ error: "invalid_auth_token" });
        return;
      }
    } else {
      const installId = headerValue(req.header("X-Install-Id"));
      if (!installId || !/^[A-Za-z0-9_-]{1,128}$/.test(installId)) {
        res.status(401).json({ error: "missing_identity" });
        return;
      }
      quotaKey = installId;
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
      reservation = await reserveDailySlot(quotaKey, today);
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
          await releaseDailySlot(quotaKey, today);
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
          await releaseDailySlot(quotaKey, today);
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
      // Only log structural fields from the envelope — never the body
      // bytes. A text-only candidate (the no-`inlineData` case this
      // branch handles) can echo user-prompt-derived content from
      // GeminiTtsClient: forecast prose, calendar event titles, etc.
      // CLAUDE.md / PRIVACY.md disallow shipping that off-device into
      // logs. `blockReason` / `finishReason` / candidate count are
      // safe.
      logger.warn("Gemini returned 200 but no inline audio", structuralEnvelope(upstreamBody));
      if (reservation.reserved) {
        try {
          await releaseDailySlot(quotaKey, today);
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
          await releaseDailySlot(quotaKey, today);
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
  quotaKey: string,
  today: string,
): Promise<Reservation> {
  const docRef = getFirestore().collection(QUOTA_COLLECTION).doc(quotaKey);
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
  quotaKey: string,
  today: string,
): Promise<void> {
  const docRef = getFirestore().collection(QUOTA_COLLECTION).doc(quotaKey);
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
