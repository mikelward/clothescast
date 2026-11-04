"use strict";
// Run against the COMPILED output in lib/, like the other tests here.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { ALLOWED_MODELS, CURRENT_TTS_MODELS, LEGACY_TTS_MODEL } = require("../lib/models");

/** Every TTS model the Android app can send, read from its source rather than retyped. */
function androidModels() {
  const source = fs.readFileSync(
    path.join(
      __dirname,
      "../../core/data/src/main/kotlin/app/clothescast/core/data/tts/GeminiTtsClient.kt",
    ),
    "utf8",
  );
  const models = [
    ...source.matchAll(/^const val (?:DEFAULT_GEMINI_TTS_MODEL|GEMINI_TTS_FULL_MODEL): String = "([^"]+)"/gm),
  ].map((m) => m[1]);
  assert.equal(models.length, 2, "expected both TTS model constants in GeminiTtsClient.kt");
  return models;
}

test("the proxy forwards every model the app sends", () => {
  const models = androidModels();
  assert.deepEqual([...CURRENT_TTS_MODELS].sort(), [...models].sort());
  for (const model of models) assert.ok(ALLOWED_MODELS.has(model), model);
});

test("installs from before the 3.8 move keep their voice until Google retires it", () => {
  assert.ok(ALLOWED_MODELS.has(LEGACY_TTS_MODEL));
});

test("the proxy forwards nothing else, so a modded client can't pick another model", () => {
  assert.deepEqual(
    [...ALLOWED_MODELS].sort(),
    [...CURRENT_TTS_MODELS, LEGACY_TTS_MODEL].sort(),
  );
  assert.equal(ALLOWED_MODELS.has("gemini-3.8-flash"), false);
  assert.equal(ALLOWED_MODELS.has("gemini-2.5-pro-preview-tts"), false);
});
