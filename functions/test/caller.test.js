"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const {
  callerFromToken,
  DEFAULT_DAILY_LIMIT,
  googleQuotaKey,
  googleSubject,
  ttsLimitFromClaim,
} = require("../lib/caller");
const { parseTtsSetting, withTtsSetting } = require("../lib/access");

const KEY = "test-hash-key";

const googleToken = (extra = {}) => ({
  uid: "firebase-uid-1",
  firebase: {
    sign_in_provider: "google.com",
    identities: { "google.com": ["1234567890"], email: ["a@example.com"] },
  },
  ...extra,
});

const anonymousToken = (extra = {}) => ({
  uid: "anon-uid-1",
  firebase: { sign_in_provider: "anonymous", identities: {} },
  ...extra,
});

test("an anonymous caller keeps its uid bucket and the default cap", () => {
  assert.deepEqual(callerFromToken(anonymousToken(), KEY), {
    quotaKey: "anon-uid-1",
    signedIn: false,
    ttsLimit: DEFAULT_DAILY_LIMIT,
  });
});

test("an anonymous caller ignores a limit claim", () => {
  // Claims are only ever set on Google accounts; an anonymous uid can't be
  // allowed to raise its own cap even if one somehow carried the claim.
  const caller = callerFromToken(anonymousToken({ ttsDailyLimit: "unlimited" }), KEY);
  assert.equal(caller.ttsLimit, DEFAULT_DAILY_LIMIT);
});

test("a Google caller is bucketed by account, not by Firebase uid", () => {
  const first = callerFromToken(googleToken(), KEY);
  // Deleting the account and signing in again mints a new uid.
  const recreated = callerFromToken(googleToken({ uid: "firebase-uid-2" }), KEY);
  assert.equal(first.signedIn, true);
  assert.equal(first.quotaKey, recreated.quotaKey);
  assert.equal(first.quotaKey, googleQuotaKey("1234567890", KEY));
});

test("the Google bucket is a keyed hash, prefixed apart from uids", () => {
  const key = googleQuotaKey("1234567890", KEY);
  assert.match(key, /^g_[0-9a-f]{64}$/);
  assert.ok(!key.includes("1234567890"), "the raw account ID must not appear");
  assert.notEqual(key, googleQuotaKey("1234567890", "another-key"));
  assert.notEqual(key, googleQuotaKey("1234567891", KEY));
});

test("a Google caller reads its limit from the claim", () => {
  assert.equal(callerFromToken(googleToken({ ttsDailyLimit: 50 }), KEY).ttsLimit, 50);
  assert.equal(callerFromToken(googleToken({ ttsDailyLimit: 0 }), KEY).ttsLimit, 0);
  assert.equal(
    callerFromToken(googleToken({ ttsDailyLimit: "unlimited" }), KEY).ttsLimit,
    "unlimited",
  );
  assert.equal(callerFromToken(googleToken(), KEY).ttsLimit, DEFAULT_DAILY_LIMIT);
});

test("a malformed limit claim falls back to the default cap", () => {
  for (const bad of [-1, 2.5, "50", "Unlimited", null, true, {}]) {
    assert.equal(ttsLimitFromClaim(bad), DEFAULT_DAILY_LIMIT, JSON.stringify(bad));
  }
});

test("a Google token without the account ID is refused, not guessed", () => {
  for (const identities of [undefined, {}, { "google.com": [] }, { "google.com": [""] }]) {
    const token = { uid: "u", firebase: { sign_in_provider: "google.com", identities } };
    assert.equal(googleSubject(token), null);
    assert.equal(callerFromToken(token, KEY), null);
  }
});

test("parseTtsSetting accepts a number, unlimited or default", () => {
  assert.equal(parseTtsSetting("50"), 50);
  assert.equal(parseTtsSetting("0"), 0);
  assert.equal(parseTtsSetting("unlimited"), "unlimited");
  assert.equal(parseTtsSetting("default"), "default");
  for (const bad of [undefined, "", "-1", "2.5", "lots", "1e3"]) {
    assert.throws(() => parseTtsSetting(bad), /expected a daily limit/, String(bad));
  }
});

test("withTtsSetting writes a claim the caller reads back", () => {
  for (const setting of [0, 50, "unlimited"]) {
    const claims = withTtsSetting({}, setting);
    assert.equal(callerFromToken(googleToken(claims), KEY).ttsLimit, setting);
  }
});

test("withTtsSetting keeps other claims and removes the limit on default", () => {
  const current = { admin: true, ttsDailyLimit: 50 };
  assert.deepEqual(withTtsSetting(current, "unlimited"), { admin: true, ttsDailyLimit: "unlimited" });
  assert.deepEqual(withTtsSetting(current, "default"), { admin: true });
  assert.deepEqual(withTtsSetting(undefined, 7), { ttsDailyLimit: 7 });
  assert.deepEqual(current, { admin: true, ttsDailyLimit: 50 }, "input must not be mutated");
});

const {
  limitForPurpose,
  PREVIEW_DAILY_LIMIT,
  quotaBucketKey,
  ttsPurpose,
} = require("../lib/caller");

test("only an exact preview header counts as a preview", () => {
  assert.equal(ttsPurpose("preview"), "preview");
  assert.equal(ttsPurpose(" Preview "), "preview");
  for (const raw of [null, undefined, "", "cast", "previews", "preview,cast"]) {
    assert.equal(ttsPurpose(raw), "cast", `${raw} should be a cast`);
  }
});

test("previews count in their own bucket; casts keep the caller's", () => {
  assert.equal(quotaBucketKey("g_abc", "cast"), "g_abc");
  assert.equal(quotaBucketKey("g_abc", "preview"), "g_abc__preview");
});

test("previews get their own cap, unlimited stays unlimited", () => {
  assert.equal(PREVIEW_DAILY_LIMIT, 10);
  assert.equal(limitForPurpose(DEFAULT_DAILY_LIMIT, "cast"), DEFAULT_DAILY_LIMIT);
  assert.equal(limitForPurpose(DEFAULT_DAILY_LIMIT, "preview"), PREVIEW_DAILY_LIMIT);
  assert.equal(limitForPurpose(50, "preview"), PREVIEW_DAILY_LIMIT);
  assert.equal(limitForPurpose("unlimited", "preview"), "unlimited");
  assert.equal(limitForPurpose("unlimited", "cast"), "unlimited");
});
