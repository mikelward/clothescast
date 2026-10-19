"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { isAnonymousUser, isLegacyQuotaDoc, parsePurgeArgs } = require("../lib/legacy");

test("a user with no sign-in provider is anonymous", () => {
  assert.equal(isAnonymousUser({ uid: "anon-1", providerData: [] }), true);
});

test("a Google sign-in is never anonymous", () => {
  assert.equal(
    isAnonymousUser({ uid: "g-1", providerData: [{ providerId: "google.com" }] }),
    false,
  );
});

test("a quota doc without expireAt is legacy", () => {
  assert.equal(
    isLegacyQuotaDoc({ firstUseAt: 1, lastUseAt: 2, count: 3, dayKey: "2026-10-19", dayCount: 1 }),
    true,
  );
});

test("a signed-in quota doc with expireAt is kept for the TTL", () => {
  assert.equal(isLegacyQuotaDoc({ dayKey: "2026-10-19", dayCount: 1, expireAt: 0 }), false);
});

test("a missing doc is not legacy", () => {
  assert.equal(isLegacyQuotaDoc(undefined), false);
});

test("--project is required", () => {
  assert.throws(() => parsePurgeArgs(["--apply"]), /--project is required/);
});

test("--project takes a separate or = value, and a dry run is the default", () => {
  assert.deepEqual(parsePurgeArgs(["--project", "example-app"]), { projectId: "example-app", apply: false });
  assert.deepEqual(parsePurgeArgs(["--project=example-app", "--apply"]), { projectId: "example-app", apply: true });
});

test("a missing or malformed project is rejected", () => {
  assert.throws(() => parsePurgeArgs(["--project"]), /needs a value/);
  assert.throws(() => parsePurgeArgs(["--project", "Not_A_Project"]), /not a project ID/);
});

test("an unknown argument is rejected", () => {
  assert.throws(() => parsePurgeArgs(["--project", "example-app", "--force"]), /unknown argument/);
});
