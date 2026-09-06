"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { headerValue, errorCode } = require("../lib/narrow");

test("headerValue: trims a present header", () => {
  assert.equal(headerValue("abc"), "abc");
  assert.equal(headerValue("  abc  "), "abc");
});

test("headerValue: blank and absent both read as absent", () => {
  // The handler branches on null to answer 401 rather than proceeding with an
  // empty identity, so whitespace must not count as a value.
  for (const raw of ["", "   ", "\t\n", undefined, null, 42, {}, []]) {
    assert.equal(headerValue(raw), null, JSON.stringify(raw) + " should be null");
  }
});

test("headerValue: takes the first entry of a repeated header", () => {
  assert.equal(headerValue([" first ", "second"]), "first");
  assert.equal(headerValue([""]), null);
  assert.equal(headerValue([7]), null);
});

test("errorCode: prefers a code over the error name", () => {
  assert.equal(errorCode({ code: "auth/id-token-expired" }), "auth/id-token-expired");
  const withCode = new Error("boom");
  withCode.code = 17;
  assert.equal(errorCode(withCode), "17");
});

test("errorCode: falls back to the error name", () => {
  assert.equal(errorCode(new TypeError("boom")), "TypeError");
  assert.equal(errorCode(new Error("boom")), "Error");
});

test("errorCode: never returns the message", () => {
  // A thrown error from the proxy path can quote what it was given, and
  // PRIVACY.md keeps that out of Cloud Logging.
  const secret = "Morning standup with Alice";
  assert.ok(!errorCode(new Error(secret)).includes("Alice"));
  assert.ok(!errorCode({ code: "x", message: secret }).includes("Alice"));
});

test("errorCode: survives values that are not errors at all", () => {
  for (const raw of [undefined, null, "string", 0, []]) {
    assert.equal(typeof errorCode(raw), "string");
  }
  assert.equal(errorCode(undefined), "unknown");
});
