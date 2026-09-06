"use strict";
// The HTTP contract, exercised against the REAL built handler mounted in the
// REAL express — the shape the Functions Framework uses. So `req.method`,
// `req.header` and `res.status().json()` actually run, rather than being
// asserted against a mock that cannot disagree with them. This is what makes
// an express major bump reviewable.
//
// Only the paths that short-circuit BEFORE any Firebase call are asserted
// here: the method guard and the missing-App-Check guard. Everything past
// them (a real App Check verdict, the quota transactions, the Gemini proxy)
// needs credentials and an emulator, and asserting it here would pass for
// the wrong reason — a 401 because no credentials exist looks identical to a
// 401 because the token was bad.
const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const path = require("node:path");

process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "demo-clothescast";
process.env.FIREBASE_CONFIG =
  process.env.FIREBASE_CONFIG || JSON.stringify({ projectId: "demo-clothescast" });

const express = require(path.join(__dirname, "..", "node_modules", "express"));
const { tts } = require("../lib/index");

let server;
let base;

before(async () => {
  const app = express();
  app.use(express.json()); // the Functions Framework parses the body too
  app.use(tts);
  server = http.createServer(app);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${server.address().port}/`;
});

after(() => server && server.close());

const post = (headers, body) =>
  fetch(base, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: body === undefined ? JSON.stringify({ contents: [] }) : body,
  });

test("rejects every method but POST", async () => {
  for (const method of ["GET", "PUT", "DELETE", "PATCH"]) {
    const res = await fetch(base, { method });
    assert.equal(res.status, 405, `${method} should be 405`);
    assert.deepEqual(await res.json(), { error: "method_not_allowed" });
  }
});

test("requires an App Check token before anything else", async () => {
  const res = await post();
  assert.equal(res.status, 401);
  assert.deepEqual(await res.json(), { error: "missing_app_check_token" });
});

test("a blank App Check header counts as missing, not present", async () => {
  // `headerValue` trims to null; the guard must not let "   " through as a
  // token and fall into verification with an empty string.
  const res = await post({ "X-Firebase-AppCheck": "   " });
  assert.equal(res.status, 401);
  assert.deepEqual(await res.json(), { error: "missing_app_check_token" });
});

test("the App Check guard runs before the body is inspected", async () => {
  // Ordering matters: an unauthenticated caller must not be able to probe
  // body-validation behavior. A bodyless POST still answers on App Check.
  const res = await fetch(base, { method: "POST" });
  assert.equal(res.status, 401);
  assert.deepEqual(await res.json(), { error: "missing_app_check_token" });
});

test("answers JSON, so the client can read the error code", async () => {
  const res = await fetch(base, { method: "GET" });
  assert.match(res.headers.get("content-type") || "", /application\/json/);
});
