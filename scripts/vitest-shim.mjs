// The slice of vitest's API the two JavaScript suites here use, over
// `node:test` and `node:assert`.
//
// Those suites -- the Codex verdict sweep and the unshallow script -- are kept
// byte-identical to gedmap's copies apart from this import, so a fix in one
// repo transplants into the others without a rewrite. gedmap runs them under
// vitest because it is already an npm project; mesh is a Rust workspace with
// no package.json, no lockfile and nothing to update weekly, and adding all of
// that to run two test files would cost far more than it buys. `node --test`
// is already on every machine that has node, so the only thing missing is the
// handful of matchers below.
//
// Its own failure mode is a false pass -- a matcher that asserts nothing looks
// exactly like one that passed -- so `vitest-shim.test.js` checks that each
// one rejects what it should.
import { describe, it as nodeIt, before, beforeEach, after, afterEach } from "node:test";
import assert from "node:assert/strict";
import { inspect } from "node:util";

export { describe, before, beforeEach, after, afterEach };

// vitest takes a per-test timeout as a third positional argument; node:test
// takes it in an options object between the name and the body.
const define = (options) => (name, fn, timeout) =>
  nodeIt(name, timeout === undefined ? options : { ...options, timeout }, fn);

export const it = Object.assign(define({}), {
  skip: define({ skip: true }),
  skipIf: (condition) => define(condition ? { skip: true } : {}),
  only: define({ only: true }),
});

const show = (value) => inspect(value, { depth: 3 });

const isRecord = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);

/**
 * vitest's asymmetric `expect.stringContaining`, honored inside `toEqual`.
 *
 * `resolveMatchers` walks the expectation and replaces each satisfied marker
 * with the actual value at that position, so the final deep-equal compares
 * like with like; an UNsatisfied marker is left in place, which makes the
 * comparison fail with the marker visible in the diff rather than passing
 * vacuously.
 */
const STRING_CONTAINING = Symbol("stringContaining");

const resolveMatchers = (actual, want) => {
  if (isRecord(want) && STRING_CONTAINING in want) {
    return typeof actual === "string" && actual.includes(want[STRING_CONTAINING])
      ? actual
      : want;
  }
  if (Array.isArray(want)) {
    if (!Array.isArray(actual)) return want;
    return want.map((entry, i) => resolveMatchers(actual[i], entry));
  }
  // Only rebuild PLAIN objects: a Set, Map, Date or class instance compares
  // by its own structure, and reconstructing it as `{...}` would replace the
  // expectation with an empty object and break the comparison.
  if (isRecord(want) && (Object.getPrototypeOf(want) === Object.prototype || Object.getPrototypeOf(want) === null)) {
    if (!isRecord(actual)) return want;
    const out = {};
    for (const key of Object.keys(want)) out[key] = resolveMatchers(actual[key], want[key]);
    return out;
  }
  return want;
};

/**
 * The part of `actual` that `want` names, so a deep-equal against `want` reads
 * as "matches these fields, ignore the rest" -- which is what `toMatchObject`
 * means. A key `actual` does not have picks up `undefined` and fails, rather
 * than being quietly dropped from both sides.
 */
const overlap = (actual, want) => {
  if (!isRecord(actual) || !isRecord(want)) return actual;
  const picked = {};
  for (const key of Object.keys(want)) picked[key] = overlap(actual[key], want[key]);
  return picked;
};

export function expect(actual, message) {
  // Accepts a promise or a function returning one, as vitest's `.rejects`
  // does. Resolving is itself the failure, so it is reported here rather than
  // falling through to a matcher with nothing to match.
  const rejection = async () => {
    try {
      await (typeof actual === "function" ? actual() : actual);
    } catch (err) {
      return err;
    }
    assert.fail(message ?? "expected a rejection, but it resolved");
  };

  return {
    toBe: (want) => assert.strictEqual(actual, want, message),
    toBeNull: () => assert.strictEqual(actual, null, message),
    toEqual: (want) => assert.deepStrictEqual(actual, resolveMatchers(actual, want), message),
    toMatch: (want) => assert.match(String(actual), want, message),
    toContain: (want) =>
      assert.ok(actual.includes(want), message ?? `expected ${show(actual)} to contain ${show(want)}`),
    toHaveLength: (want) => assert.strictEqual(actual?.length, want, message),
    toBeLessThan: (want) =>
      assert.ok(actual < want, message ?? `expected ${show(actual)} to be less than ${show(want)}`),
    toMatchObject: (want) => assert.deepStrictEqual(overlap(actual, want), want, message),
    not: {
      toMatch: (want) => assert.doesNotMatch(String(actual), want, message),
      toEqual: (want) => assert.notDeepStrictEqual(actual, resolveMatchers(actual, want), message),
    },
    rejects: {
      toThrow: async (want) => {
        const err = await rejection();
        const text = err instanceof Error ? err.message : String(err);
        if (want instanceof RegExp) {
          assert.match(text, want, message);
        } else if (want !== undefined) {
          assert.ok(
            text.includes(String(want)),
            message ?? `expected ${show(text)} to contain ${show(want)}`,
          );
        }
        return err;
      },
    },
  };
}

expect.stringContaining = (want) => ({ [STRING_CONTAINING]: String(want) });
