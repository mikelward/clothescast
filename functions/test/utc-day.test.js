"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { utcDayKey, nextUtcMidnightIso } = require("../lib/utc-day");

test("utcDayKey: zero-pads month and day", () => {
  assert.equal(utcDayKey(new Date("2026-01-02T03:04:05Z")), "2026-01-02");
  assert.equal(utcDayKey(new Date("2026-12-31T23:59:59.999Z")), "2026-12-31");
});

test("utcDayKey: reads UTC, not the host's local day", () => {
  // The whole point of a UTC key: the client's copy says "resets at midnight
  // UTC", so a server in any zone has to agree. This instant is 2026-03-01
  // in UTC but still 2026-02-28 in the Americas.
  assert.equal(utcDayKey(new Date("2026-03-01T00:30:00Z")), "2026-03-01");
  // ...and this one is 2026-02-28 in UTC while already 2026-03-01 in Sydney.
  assert.equal(utcDayKey(new Date("2026-02-28T23:30:00Z")), "2026-02-28");
});

test("utcDayKey: keys compare equal within a day and differ across one", () => {
  // `releaseDailySlot` compares the stored dayKey to today's with `!==`, so
  // the format has to be stable for the same day and different across days.
  assert.equal(
    utcDayKey(new Date("2026-06-05T00:00:00Z")),
    utcDayKey(new Date("2026-06-05T23:59:59Z")),
  );
  assert.ok(
    utcDayKey(new Date("2026-06-05T00:00:00Z")) < utcDayKey(new Date("2026-06-06T00:00:00Z")),
  );
});

test("nextUtcMidnightIso: is the next midnight, never the current one", () => {
  assert.equal(nextUtcMidnightIso(new Date("2026-06-05T13:45:00Z")), "2026-06-06T00:00:00.000Z");
  // Exactly midnight still advances a whole day — a client told "resets now"
  // would retry straight into the same exhausted bucket.
  assert.equal(nextUtcMidnightIso(new Date("2026-06-05T00:00:00.000Z")), "2026-06-06T00:00:00.000Z");
});

test("nextUtcMidnightIso: rolls month, year and leap day", () => {
  assert.equal(nextUtcMidnightIso(new Date("2026-01-31T12:00:00Z")), "2026-02-01T00:00:00.000Z");
  assert.equal(nextUtcMidnightIso(new Date("2026-12-31T12:00:00Z")), "2027-01-01T00:00:00.000Z");
  assert.equal(nextUtcMidnightIso(new Date("2028-02-28T12:00:00Z")), "2028-02-29T00:00:00.000Z");
  assert.equal(nextUtcMidnightIso(new Date("2028-02-29T12:00:00Z")), "2028-03-01T00:00:00.000Z");
});

test("nextUtcMidnightIso: its day is the day after utcDayKey's", () => {
  const at = new Date("2026-06-05T23:59:59.999Z");
  assert.equal(utcDayKey(at), "2026-06-05");
  assert.ok(nextUtcMidnightIso(at).startsWith("2026-06-06"));
});
