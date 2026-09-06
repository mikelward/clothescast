/**
 * Reading Gemini's response envelope.
 *
 * Split out of `index.ts` so it can be unit tested: importing `index.ts`
 * runs `initializeApp()` and `setGlobalOptions()` at module scope, which
 * needs credentials and a project, so nothing in that file is reachable
 * from a test. These functions are pure — an ArrayBuffer in, a verdict out.
 */

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
export function responseHasAudio(body: ArrayBuffer): boolean {
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

/**
 * Pulls non-payload structural fields out of a Gemini envelope for
 * safe logging — `blockReason`, `finishReason`, `candidates.length`.
 * Never returns the candidate text / inlineData. Used to log the
 * no-audio 200 case without persisting user-prompt-derived content
 * in Cloud Logging.
 */
export function structuralEnvelope(body: ArrayBuffer): Record<string, unknown> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(Buffer.from(body).toString("utf8"));
  } catch {
    return { parseable: false };
  }
  if (!parsed || typeof parsed !== "object") return { parseable: false };
  const envelope = parsed as Record<string, unknown>;
  const promptFeedback = envelope.promptFeedback as
    | { blockReason?: unknown }
    | undefined;
  const candidates = envelope.candidates;
  const candidateCount = Array.isArray(candidates) ? candidates.length : 0;
  const firstFinishReason = Array.isArray(candidates) && candidates.length > 0 &&
    candidates[0] && typeof candidates[0] === "object"
    ? (candidates[0] as Record<string, unknown>).finishReason
    : undefined;
  return {
    blockReason: promptFeedback?.blockReason ?? null,
    finishReason: firstFinishReason ?? null,
    candidateCount,
  };
}

/** First 200 characters of a body, for logging a non-success upstream status. */
export function excerpt(body: ArrayBuffer): string {
  const text = Buffer.from(body).toString("utf8");
  return text.slice(0, 200);
}
