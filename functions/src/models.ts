/**
 * The upstream Gemini models the TTS proxy will forward to.
 *
 * Kept narrow so a compromised or modded client can't redirect billing onto
 * an expensive model. Split out of `index.ts` so a test can check it against
 * the Android client (importing `index.ts` needs Firebase credentials).
 */

/**
 * What the current app sends: `DEFAULT_GEMINI_TTS_MODEL` for most languages
 * and `GEMINI_TTS_FULL_MODEL` for the few Flash-Lite doesn't cover
 * (GeminiTtsClient.kt). Both cost less per clip than the legacy model.
 */
export const CURRENT_TTS_MODELS: readonly string[] = [
  "gemini-3.8-flash-lite-tts",
  "gemini-3.8-flash-tts",
];

/**
 * What app versions before the 3.8 move send. Google shuts it down on
 * 2026-11-17; until then it keeps those installs on the Gemini voice. After
 * that Google rejects it, the proxy refunds the slot, and the app falls back
 * to the device voice, so the entry can go once that date has passed.
 */
export const LEGACY_TTS_MODEL = "gemini-2.5-flash-preview-tts";

export const ALLOWED_MODELS: ReadonlySet<string> = new Set([
  ...CURRENT_TTS_MODELS,
  LEGACY_TTS_MODEL,
]);
