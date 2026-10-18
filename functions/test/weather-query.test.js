"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { weatherQuery, MAX_HOURS, MAX_PAGE_SIZE } = require("../lib/weather-query");

const good = {
  "location.latitude": "51.5",
  "location.longitude": "-0.12",
  hours: "168",
  pageSize: "24",
  unitsSystem: "METRIC",
};

test("a request shaped like the app's is rebuilt from known fields only", () => {
  const q = weatherQuery({ ...good, key: "attacker-key", extra: "x" });
  assert.equal(q.ok, true);
  assert.deepEqual(Object.fromEntries(q.params), good);
});

test("a next-page token is forwarded", () => {
  const q = weatherQuery({ ...good, pageToken: "abc123" });
  assert.equal(q.ok, true);
  assert.equal(q.params.get("pageToken"), "abc123");
});

test("the coordinate must be present and in range", () => {
  for (const [lat, lon] of [["", "0"], ["91", "0"], ["0", "-181"], ["north", "0"], [undefined, "0"]]) {
    const q = weatherQuery({ ...good, "location.latitude": lat, "location.longitude": lon });
    assert.deepEqual(q, { ok: false, error: "invalid_location" }, `${lat},${lon}`);
  }
});

test("the horizon and page size can't exceed what the app asks for", () => {
  assert.equal(weatherQuery({ ...good, hours: String(MAX_HOURS + 1) }).error, "invalid_hours");
  assert.equal(weatherQuery({ ...good, hours: "2.5" }).error, "invalid_hours");
  assert.equal(weatherQuery({ ...good, pageSize: String(MAX_PAGE_SIZE + 1) }).error, "invalid_page_size");
  assert.equal(weatherQuery({ ...good, unitsSystem: "IMPERIAL" }).error, "invalid_units");
});

test("a repeated or oversized parameter is refused, not guessed", () => {
  assert.equal(weatherQuery({ ...good, hours: ["24", "168"] }).error, "invalid_hours");
  assert.equal(weatherQuery({ ...good, pageToken: "" }).error, "invalid_page_token");
  assert.equal(weatherQuery({ ...good, pageToken: "x".repeat(2049) }).error, "invalid_page_token");
});
