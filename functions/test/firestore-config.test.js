"use strict";
// The signed-in quota docs rely on a Firestore TTL policy to be deleted after
// their UTC day (SPEC.md, PRIVACY.md); without it they would persist forever.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const config = require(path.join(__dirname, "..", "..", "firestore.indexes.json"));

test("quota.expireAt has a TTL policy", () => {
  assert.ok(Array.isArray(config.fieldOverrides) && config.fieldOverrides.length > 0);
  const ttl = config.fieldOverrides.find(
    (o) => o.collectionGroup === "quota" && o.fieldPath === "expireAt",
  );
  assert.ok(ttl, "no fieldOverride for quota.expireAt");
  assert.equal(ttl.ttl, true);
});
