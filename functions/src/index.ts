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

    // Count usage (v1: count only, do not enforce). The enforcement step
    // — return 429 when count >= 30 or now - firstUseAt >= 30 days, and
    // emit `X-Trial-Remaining` on success — lands as a follow-up; see
    // docs/TODO.md "Enforce shared-key TTS trial limit".
    //
    // We do the bookkeeping AFTER a successful Gemini call so failed
    // attempts don't inflate the count (matches the eventual enforcement
    // semantics where the trial measures successful syntheses).

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
      res.status(502).json({ error: "upstream_unreachable" });
      return;
    }

    const upstreamBody = await upstream.arrayBuffer();

    if (upstream.ok) {
      // Fire-and-forget the count update so a Firestore hiccup doesn't
      // stall the audio response. We accept the small slop this
      // introduces (a missed write means an undercount, never a charged
      // user lockout); when enforcement lands the decision must be
      // synchronous and replace this block.
      recordCall(installId).catch((err) => {
        logger.warn("Firestore count update failed", { code: errorCode(err) });
      });
    } else {
      logger.warn("Gemini returned non-success", {
        status: upstream.status,
        bodyExcerpt: excerpt(upstreamBody),
      });
    }

    res.status(upstream.status);
    const upstreamContentType = upstream.headers.get("content-type");
    if (upstreamContentType) {
      res.setHeader("content-type", upstreamContentType);
    }
    res.send(Buffer.from(upstreamBody));
  },
);

async function recordCall(installId: string): Promise<void> {
  const docRef = getFirestore().collection(INSTALLS_COLLECTION).doc(installId);
  await getFirestore().runTransaction(async (tx) => {
    const snap = await tx.get(docRef);
    const now = Timestamp.now();
    if (snap.exists) {
      tx.update(docRef, {
        count: FieldValue.increment(1),
        lastUseAt: now,
      });
    } else {
      tx.set(docRef, {
        firstUseAt: now,
        count: 1,
        lastUseAt: now,
      });
    }
  });
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
