"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");

const { parseCapAlert, parseReferences } = require("../lib/alerts/cap");
const { threadIds } = require("../lib/alerts/threads");
const { INDEX_PATH, ingest, tilePath } = require("../lib/alerts/ingest");
const { fakeStore } = require("./support/fake-store");

const fixture = (name) => readFileSync(path.join(__dirname, "fixtures", name), "utf8");

test("references are read as sender, identifier and sent, a URL sender included", () => {
  assert.deepEqual(parseReferences(
    "https://www.metoffice.gov.uk/warnings,GB_1.v1,2026-10-21T09:07:06+00:00 " +
      "cap@zamg.ac.at,AT_2,2026-10-22T08:32:22+02:00 malformed",
  ), [
    { sender: "https://www.metoffice.gov.uk/warnings", identifier: "GB_1.v1", sent: "2026-10-21T09:07:06+00:00" },
    { sender: "cap@zamg.ac.at", identifier: "AT_2", sent: "2026-10-22T08:32:22+02:00" },
  ]);
  assert.deepEqual(parseReferences(null), []);
  assert.deepEqual(parseCapAlert(fixture("meteoalarm-alert.xml")).references, []);
});

// ---- threadIds ----

const alert = (id) => ({ id });
const ref = (identifier, sent) => ({ sender: null, identifier, sent });
const t = (identifier, references = [], fields = {}) =>
  ({ identifier, references, result: alert(`x:${identifier}`), kept: false, ...fields });

test("a message with no references is served under its own identifier", () => {
  assert.deepEqual(threadIds("x", [t("A")], new Map()), ["x:A"]);
});

test("an update takes the ID its predecessor was served under", () => {
  const ids = threadIds("x", [t("C", [ref("B", "2026-10-23T10:00:00Z")])], new Map([["B", "x:A"]]));
  assert.deepEqual(ids, ["x:A"]);
});

test("an update whose predecessor wasn't seen takes the oldest message it names", () => {
  const ids = threadIds("x", [t("C", [ref("B", "2026-10-23T10:00:00Z"), ref("A", "2026-10-22T10:00:00Z")])], new Map());
  assert.deepEqual(ids, ["x:A"]);
});

test("a message another current one names is superseded, and a skipped one keeps its own ID", () => {
  const cancel = t("C", [ref("A", null)], { result: "cancel" });
  assert.deepEqual(threadIds("x", [t("A"), cancel], new Map()), [null, "x:C"]);
});

test("a message fetched again keeps the ID it was served under", () => {
  // B inherited A's ID; A has left the feed; B is retried to place its area.
  const ids = threadIds("x", [t("B", [ref("A", null)])], new Map([["B", "x:original"]]));
  assert.deepEqual(ids, ["x:original"]);
});

test("two updates between runs: the newer inherits through the one it replaces", () => {
  const ids = threadIds("x", [t("B", [ref("A", null)]), t("C", [ref("B", null)])], new Map([["A", "x:A"]]));
  assert.deepEqual(ids, [null, "x:A"]);
  // A first run: the oldest message the chain names.
  assert.deepEqual(threadIds("x", [t("B", [ref("A", null)]), t("C", [ref("B", null)])], new Map()), [null, "x:A"]);
  // A cycle in references ends rather than looping.
  assert.deepEqual(threadIds("x", [t("D", [ref("E", null)]), t("E", [ref("D", null)])], new Map()), [null, null]);
});

test("an update takes the ID of the predecessor served last run, not of a superseded ancestor", () => {
  // A split into C1 (served as x:A) and C2 (served as x:C2); D2 updates C2, naming A too.
  const last = new Map([["A", "x:A"], ["C1", "x:A"], ["C2", "x:C2"]]);
  const served = new Set(["C1", "C2"]);
  const threads = [
    t("A", [], { kept: true, result: alert("x:A") }),
    t("C1", [ref("A", "2026-10-22T10:00:00Z")], { kept: true, result: alert("x:A") }),
    t("C2", [ref("A", "2026-10-22T10:00:00Z")], { kept: true, result: alert("x:C2") }),
    t("D2", [ref("A", "2026-10-22T10:00:00Z"), ref("C2", "2026-10-23T10:00:00Z")]),
  ];
  assert.deepEqual(threadIds("x", threads, last, served), [null, "x:A", null, "x:C2"]);
});

test("a kept alert keeps its ID", () => {
  const kept = t("B", [], { result: alert("x:A"), kept: true });
  assert.deepEqual(threadIds("x", [kept], new Map()), ["x:A"]);
});

test("a message split into two updates: the first by identifier keeps the ID, unless the other was kept", () => {
  const last = new Map([["A", "x:A"]]);
  assert.deepEqual(threadIds("x", [t("C2", [ref("A", null)]), t("C1", [ref("A", null)])], last), ["x:C2", "x:A"]);
  const kept = t("C2", [ref("A", null)], { result: alert("x:A"), kept: true });
  assert.deepEqual(threadIds("x", [kept, t("C1", [ref("A", null)])], last), ["x:A", "x:C1"]);
  // C2 already holds the ID and is fetched again (not kept) beside a new C1.
  const held = new Map([["C2", "x:A"]]);
  const servedC2 = new Set(["C2"]);
  assert.deepEqual(
    threadIds("x", [t("C2", [ref("A", null)]), t("C1", [ref("A", null)])], held, servedC2),
    ["x:A", "x:C1"],
  );
  // C2, the holder, is updated by D2 as a new C1 arrives: D2 continues it.
  assert.deepEqual(
    threadIds("x", [t("D2", [ref("C2", null)]), t("C1", [ref("A", null)])], new Map([...held, ["A", "x:A"]]), servedC2),
    ["x:A", "x:C1"],
  );
});

// ---- An update in an ingester run ----

const NOW = new Date("2026-10-23T12:00:00Z");
const FEED = fixture("meteoalarm-feed.atom");
const ALERT = fixture("meteoalarm-alert.xml");
const ORIGINAL = "2.49.0.0.826.0.GB_261023090239_34b39a5a.v1.0.W";
const UPDATE = "2.49.0.0.826.0.GB_261023110000_34b39a5a.v2.0.W";
const CAP_URL = "https://feeds.example.org/api/v1/warnings/feeds-united-kingdom/amber-wind";
const UPDATE_URL = `${CAP_URL}-v2`;
const SOURCE = {
  id: "meteoalarm-gb",
  kind: "cap-atom",
  url: "https://feeds.example.org/feeds/united-kingdom",
  countries: ["GB"],
  attribution: { name: "Met Office via MeteoAlarm", url: "https://meteoalarm.org" },
  disclaimer: { name: "MeteoAlarm", url: "https://meteoalarm.example/terms" },
};
const quiet = { info() {}, warn() {}, error() {} };

// The same warning, updated: a new identifier naming the original.
const updatedFeed = FEED.replaceAll(ORIGINAL, UPDATE)
  .replaceAll("2026-10-23T09:02:39Z", "2026-10-23T11:00:00Z")
  .replaceAll(`href="${CAP_URL}"`, `href="${UPDATE_URL}"`);
const updatedAlert = ALERT.replace(ORIGINAL, UPDATE)
  .replace("<msgType>Alert</msgType>", "<msgType>Update</msgType>")
  .replace("</scope>", `</scope>\n  <references>https://www.metoffice.gov.uk,${ORIGINAL},2026-10-23T09:02:39+00:00</references>`);

const DOCUMENTS = { [CAP_URL]: ALERT, [UPDATE_URL]: updatedAlert };
const run = (store, feed, now) => ingest({
  sources: [SOURCE],
  store,
  fetch: async (url) => new Response(url === SOURCE.url ? feed : DOCUMENTS[url], { status: 200 }),
  now: () => now,
  log: quiet,
});

const servedIds = (store) => {
  const index = store.files.get(INDEX_PATH);
  return Object.entries(index.tiles).flatMap(([key, hash]) => store.files.get(tilePath(key, hash)).alerts.map((a) => a.id));
};

test("an updated warning is served under its first ID, run after run", async () => {
  const store = fakeStore(() => NOW);
  await run(store, FEED, NOW);
  assert.deepEqual([...new Set(servedIds(store))], [`meteoalarm-gb:${ORIGINAL}`]);

  await run(store, updatedFeed, new Date(NOW.getTime() + 300_000));
  assert.deepEqual([...new Set(servedIds(store))], [`meteoalarm-gb:${ORIGINAL}`]);
  assert.equal(store.state.sources["meteoalarm-gb"].entries[UPDATE].identifier, UPDATE);

  // A run later, kept unchanged: the same ID.
  await run(store, updatedFeed, new Date(NOW.getTime() + 600_000));
  assert.deepEqual([...new Set(servedIds(store))], [`meteoalarm-gb:${ORIGINAL}`]);
});

test("a warning still listed beside its update is served once, under its first ID", async () => {
  const entries = (feed) => feed.slice(feed.indexOf("<entry>"), feed.indexOf("</feed>"));
  const both = FEED.replace("</feed>", `${entries(updatedFeed)}</feed>`);
  const store = fakeStore(() => NOW);
  await run(store, both, NOW);
  assert.deepEqual([...new Set(servedIds(store))], [`meteoalarm-gb:${ORIGINAL}`]);
  const kept = store.state.sources["meteoalarm-gb"].entries;
  assert.equal(kept[ORIGINAL].superseded, true);
  assert.equal(kept[UPDATE].superseded, undefined);
});

// ---- NWS: two updates between runs ----

const NWS_SOURCE = {
  id: "nws",
  kind: "nws",
  url: "https://api.example.gov/alerts/active",
  countries: ["US"],
  attribution: { name: "US National Weather Service", url: "https://www.weather.gov" },
  disclaimer: { name: "NWS", url: "https://www.weather.gov/disclaimer" },
};
const nwsAlert = (id, references) => ({
  type: "Feature",
  geometry: { type: "Polygon", coordinates: [[[-111.5, 35], [-111, 35], [-111, 35.5], [-111.5, 35.5], [-111.5, 35]]] },
  properties: {
    id, references: references.map((identifier) => ({ identifier, sent: "2026-10-23T04:00:00-07:00" })),
    sent: "2026-10-23T04:39:00-07:00", expires: "2026-10-23T07:45:00-07:00", ends: "2026-10-23T09:45:00-07:00",
    status: "Actual", messageType: references.length > 0 ? "Update" : "Alert",
    severity: "Severe", event: "Flood Warning", areaDesc: "Coconino, AZ",
  },
});
const nwsRun = (store, features, now) => ingest({
  sources: [NWS_SOURCE],
  store,
  fetch: async () => new Response(JSON.stringify({ features }), { status: 200 }),
  now: () => now,
  log: quiet,
});

test("an NWS update two steps on keeps the first ID, found through the superseded one between", async () => {
  const store = fakeStore(() => NOW);
  await nwsRun(store, [nwsAlert("A", [])], NOW);
  assert.deepEqual([...new Set(servedIds(store))], ["nws:A"]);
  // B updated A and C updated B, both before the next run; C names only B.
  await nwsRun(store, [nwsAlert("B", ["A"]), nwsAlert("C", ["B"])], new Date(NOW.getTime() + 300_000));
  assert.deepEqual([...new Set(servedIds(store))], ["nws:A"]);
});
