"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { alertsQuery, MAX_PAGE_SIZE } = require("../lib/alerts-query");

const good = {
  "location.latitude": "51.5",
  "location.longitude": "-0.12",
  languageCode: "en-GB",
  pageSize: "20",
};

test("a request shaped like the app's is rebuilt from known fields only", () => {
  const q = alertsQuery({ ...good, key: "attacker-key", extra: "x" });
  assert.equal(q.ok, true);
  assert.deepEqual(Object.fromEntries(q.params), good);
});

test("only the coordinate is required", () => {
  const q = alertsQuery({ "location.latitude": "0", "location.longitude": "0" });
  assert.equal(q.ok, true);
  assert.deepEqual(Object.fromEntries(q.params), {
    "location.latitude": "0",
    "location.longitude": "0",
  });
});

test("a next-page token is forwarded", () => {
  const q = alertsQuery({ ...good, pageToken: "abc123" });
  assert.equal(q.ok, true);
  assert.equal(q.params.get("pageToken"), "abc123");
});

test("the coordinate must be present and in range", () => {
  for (const [lat, lon] of [["", "0"], ["91", "0"], ["0", "-181"], ["north", "0"], [undefined, "0"]]) {
    const q = alertsQuery({ ...good, "location.latitude": lat, "location.longitude": lon });
    assert.deepEqual(q, { ok: false, error: "invalid_location" }, `${lat},${lon}`);
  }
});

test("the language code must look like a BCP 47 tag", () => {
  for (const code of ["en", "en-GB", "pt-BR", "zh-Hant-TW", "fil"]) {
    assert.equal(alertsQuery({ ...good, languageCode: code }).ok, true, code);
  }
  for (const code of ["", "e", "english", "en_GB", "en-GB&key=x", "en-GB-x-y-z-w"]) {
    assert.equal(alertsQuery({ ...good, languageCode: code }).error, "invalid_language_code", code);
  }
});

test("the page size can't exceed one page", () => {
  assert.equal(alertsQuery({ ...good, pageSize: String(MAX_PAGE_SIZE + 1) }).error, "invalid_page_size");
  assert.equal(alertsQuery({ ...good, pageSize: "0" }).error, "invalid_page_size");
  assert.equal(alertsQuery({ ...good, pageSize: "2.5" }).error, "invalid_page_size");
});

test("a repeated or oversized parameter is refused, not guessed", () => {
  assert.equal(alertsQuery({ ...good, languageCode: ["en", "fr"] }).error, "invalid_language_code");
  assert.equal(alertsQuery({ ...good, pageToken: "" }).error, "invalid_page_token");
  assert.equal(alertsQuery({ ...good, pageToken: "x".repeat(2049) }).error, "invalid_page_token");
});
