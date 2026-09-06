"use strict";
// Run against the COMPILED output in lib/, so these test what deploys rather
// than a separately-transpiled copy of it. `npm test` builds first.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const {
  responseHasAudio,
  structuralEnvelope,
  excerpt,
} = require("../lib/envelope");

/** A Gemini envelope as an ArrayBuffer, the shape the handler passes in. */
const body = (value) => {
  const json = typeof value === "string" ? value : JSON.stringify(value);
  return new TextEncoder().encode(json).buffer;
};

const audioEnvelope = {
  candidates: [
    { content: { parts: [{ inlineData: { mimeType: "audio/L16", data: "UklGRg==" } }] } },
  ],
};

test("responseHasAudio: a candidate carrying inlineData is audio", () => {
  assert.equal(responseHasAudio(body(audioEnvelope)), true);
});

test("responseHasAudio: a safety block is not audio, so the slot is refunded", () => {
  // The client falls back to device TTS here, so charging a quota slot for it
  // would burn the user's daily allowance on prompts that produced nothing.
  assert.equal(
    responseHasAudio(body({ promptFeedback: { blockReason: "SAFETY" }, ...audioEnvelope })),
    false,
  );
});

test("responseHasAudio: a text-only candidate is not audio", () => {
  assert.equal(
    responseHasAudio(body({ candidates: [{ content: { parts: [{ text: "sorry" }] } }] })),
    false,
  );
});

test("responseHasAudio: no candidates at all is not audio", () => {
  assert.equal(responseHasAudio(body({ candidates: [] })), false);
  assert.equal(responseHasAudio(body({})), false);
});

test("responseHasAudio: empty inlineData.data is not audio", () => {
  assert.equal(
    responseHasAudio(body({ candidates: [{ content: { parts: [{ inlineData: { data: "" } }] } }] })),
    false,
  );
});

test("responseHasAudio: an unreadable body credits the user rather than refunding", () => {
  // Deliberate: over-refunding steals a slot the user paid nothing for, while
  // crediting them costs at most one call. Gemini is the only producer, so an
  // unparseable 200 is vanishingly rare.
  assert.equal(responseHasAudio(body("not json at all")), true);
  assert.equal(responseHasAudio(body("null")), true);
  assert.equal(responseHasAudio(body("[1,2,3]")), false); // an array IS an object; no candidates
});

test("responseHasAudio: tolerates junk in the candidate shape", () => {
  for (const envelope of [
    { candidates: [null] },
    { candidates: [{ content: null }] },
    { candidates: [{ content: { parts: "nope" } }] },
    { candidates: [{ content: { parts: [null, 7, "x"] } }] },
    { candidates: [{ content: { parts: [{ inlineData: { data: 42 } }] } }] },
  ]) {
    assert.equal(responseHasAudio(body(envelope)), false, JSON.stringify(envelope));
  }
});

test("responseHasAudio: finds audio past a junk candidate", () => {
  assert.equal(
    responseHasAudio(body({ candidates: [{ content: { parts: [{ text: "hi" }] } }, ...audioEnvelope.candidates] })),
    true,
  );
});

// PRIVACY. CLAUDE.md and PRIVACY.md keep prompt-derived content — forecast
// prose, calendar event titles — out of Cloud Logging. `structuralEnvelope`
// exists so the no-audio 200 case can be logged without it, and this is the
// test that keeps it honest.
test("structuralEnvelope: returns only structural fields, never candidate text", () => {
  const secret = "Morning standup with Alice at the office";
  const out = structuralEnvelope(
    body({
      promptFeedback: { blockReason: "SAFETY" },
      candidates: [
        { finishReason: "STOP", content: { parts: [{ text: secret }, { inlineData: { data: "QUJD" } }] } },
      ],
    }),
  );
  assert.deepEqual(out, { blockReason: "SAFETY", finishReason: "STOP", candidateCount: 1 });
  assert.ok(!JSON.stringify(out).includes(secret), "candidate text must not be logged");
  assert.ok(!JSON.stringify(out).includes("QUJD"), "inlineData must not be logged");
});

test("structuralEnvelope: nulls the fields it cannot find rather than omitting them", () => {
  assert.deepEqual(structuralEnvelope(body({ candidates: [{}] })), {
    blockReason: null,
    finishReason: null,
    candidateCount: 1,
  });
});

test("structuralEnvelope: says so when the body is not a JSON object", () => {
  assert.deepEqual(structuralEnvelope(body("<html>502</html>")), { parseable: false });
  assert.deepEqual(structuralEnvelope(body("null")), { parseable: false });
});

test("excerpt: caps at 200 characters", () => {
  assert.equal(excerpt(body("x".repeat(500))).length, 200);
  assert.equal(excerpt(body("short")), "short");
});
