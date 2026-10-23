"use strict";
// An in-memory stand-in for the alerts bucket (src/alerts/bucket.ts), with
// the same generation preconditions on the state and the index.
const { INDEX_PATH } = require("../../lib/alerts/ingest");

exports.fakeStore = function fakeStore(clock) {
  const files = new Map();
  const created = new Map();
  const zones = new Map();
  let state = null;
  let stateGeneration = 0;
  let indexGeneration = 0;
  return {
    files,
    zones,
    get state() { return state; },
    async readState() { return { state, generation: state ? String(stateGeneration) : null }; },
    async writeState(next, expected) {
      if ((state ? String(stateGeneration) : null) !== expected) throw new Error("precondition failed");
      state = JSON.parse(JSON.stringify(next));
      stateGeneration++;
    },
    async indexGeneration() { return files.has(INDEX_PATH) ? String(indexGeneration) : null; },
    async writeIndex(index, expected) {
      if ((files.has(INDEX_PATH) ? String(indexGeneration) : null) !== expected) throw new Error("precondition failed");
      files.set(INDEX_PATH, JSON.parse(JSON.stringify(index)));
      indexGeneration++;
    },
    async listTiles() {
      return [...files.keys()].filter((p) => p !== INDEX_PATH).map((p) => ({ path: p, createdAt: created.get(p) }));
    },
    async writeTile(p, body) {
      files.set(p, JSON.parse(JSON.stringify(body)));
      created.set(p, clock());
    },
    async delete(p) { files.delete(p); created.delete(p); },
    async readZone(p) { return zones.has(p) ? JSON.parse(JSON.stringify(zones.get(p))) : null; },
    async writeZone(p, zone) { zones.set(p, JSON.parse(JSON.stringify(zone))); },
  };
};
