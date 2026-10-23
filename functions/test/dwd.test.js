"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");

const { parseCapAlert } = require("../lib/alerts/cap");
const { DWD_PROFILE } = require("../lib/alerts/dwd");
const { serve } = require("../lib/alerts/served");
const { unzip } = require("../lib/alerts/unzip");
const { INDEX_PATH, ingest, tilePath } = require("../lib/alerts/ingest");
const { fakeStore } = require("./support/fake-store");

// Two of DWD's warnings, trimmed (two languages, a square for the drawn
// area): a wind warning with an island left out, deflated, and a coastal
// one with no polygon, stored; plus a directory and a stray file.
const ZIP = readFileSync(path.join(__dirname, "fixtures", "dwd-warnings.zip"));
const WIND = "2.49.0.0.276.0.DWD.PVW.1792690260000.41ced8db-d84b-47ad-bb2d-9c6b87a53b25.MUL";
const COAST = "2.49.0.0.276.0.DWD.PVW.1792759080000.dca28f82-a5a8-4c3c-9e42-dc21f7d3750f.MUL";
const NOW = new Date("2026-10-23T12:00:00Z");

const document = (id) => unzip(ZIP).find((f) => f.name === `${id}.xml`).data.toString("utf8");

test("a zip's files come back stored or deflated, directories left out", () => {
  const files = unzip(ZIP);
  assert.deepEqual(files.map((f) => f.name), [`${WIND}.xml`, `${COAST}.xml`, "README.txt"]);
  assert.match(files[0].data.toString("utf8"), /<event>WINDBÖEN<\/event>/);
  assert.equal(files[2].data.toString("utf8"), "not a warning");
});

test("something that isn't a zip is refused", () => {
  assert.throws(() => unzip(Buffer.from("<html>Not found</html>")), /not a zip archive/);
  assert.throws(() => unzip(Buffer.alloc(0)), /not a zip archive/);
});

test("DWD's excluded parts and event codes are read", () => {
  const [info] = parseCapAlert(document(WIND)).infos;
  assert.deepEqual(info.eventCodes.GROUP, ["WIND"]);
  assert.deepEqual(info.eventCodes.II, ["51"]);
  const [event, communeA] = info.areas;
  assert.equal(event.rings.length, 1);
  assert.deepEqual(event.holes, [[[50.4, 8.4], [50.4, 8.6], [50.6, 8.6], [50.6, 8.4], [50.4, 8.4]]]);
  assert.deepEqual(communeA, { areaDesc: "Gemeinde A", rings: [], holes: [], geocodes: { WARNCELLID: ["806411000"] } });
});

test("a DWD warning is served a grade up, with its hazard group and its hole", () => {
  const served = serve("dwd", parseCapAlert(document(WIND)), NOW, DWD_PROFILE);
  // "Level 1 of 4", yellow: CAP's Minor, read as the app's yellow.
  assert.equal(served.severity, "MODERATE");
  assert.equal(served.eventType, "WIND");
  assert.equal(served.issuer, "Deutscher Wetterdienst");
  assert.deepEqual(served.polygons.map((rings) => rings.length), [2]);
  assert.deepEqual(served.texts.map((t) => [t.language, t.title, t.areaName]), [
    ["de-DE", "WINDBÖEN", "Gemeinde A; Gemeinde B"],
    ["en", "wind gusts", "Gemeinde A; Gemeinde B"],
  ]);
  assert.equal(served.onset, "2026-10-23T12:00:00+02:00");
});

test("a coastal-waters warning, with no polygon, isn't served", () => {
  assert.equal(serve("dwd", parseCapAlert(document(COAST)), NOW, DWD_PROFILE), "no_area");
});

test("DWD's grades, groups and long commune lists are read its way", () => {
  const info = (fields) => ({ severity: null, eventCodes: {}, areas: [], ...fields });
  assert.deepEqual(
    ["Minor", "Moderate", "Severe", "Extreme", "Unknown"].map((s) => DWD_PROFILE.severity(info({ severity: s }))),
    ["MODERATE", "SEVERE", "EXTREME", "EXTREME", null],
  );
  const group = (g) => DWD_PROFILE.eventType(info({ eventCodes: { GROUP: [g] } }));
  assert.deepEqual(["SNOWFALL", "GLAZE", "FROST", "THAW", "UV"].map(group), [
    "SNOW_AND_ICE", "SNOW_AND_ICE", "COLD", "FLOOD", "UV",
  ]);
  assert.equal(DWD_PROFILE.eventType(info({})), null);
  const areas = (n) => Array.from({ length: n }, (_, i) => ({ areaDesc: `Gemeinde ${i}`, rings: [], holes: [] }));
  assert.equal(DWD_PROFILE.areaName(info({ areas: areas(6) })), null);
  assert.equal(DWD_PROFILE.areaName(info({ areas: [{ areaDesc: "polygonal event area", rings: [], holes: [] }] })), null);
});

test("a hole outside every polygon is dropped", () => {
  const xml = document(WIND).replaceAll("50.4,8.4 50.4,8.6 50.6,8.6 50.6,8.4 50.4,8.4", "10,10 10,11 11,11 10,10");
  const served = serve("dwd", parseCapAlert(xml), NOW, DWD_PROFILE);
  assert.deepEqual(served.polygons.map((rings) => rings.length), [1]);
});

// ---- The DWD source in an ingester run ----

const SOURCE = {
  id: "dwd",
  kind: "cap-zip",
  profile: "dwd",
  url: "https://opendata.example.de/warnings.zip",
  countries: ["DE"],
  attribution: { name: "Deutscher Wetterdienst, © GeoBasis-DE / BKG", url: "https://www.dwd.de" },
  disclaimer: { name: "DWD", url: "https://www.dwd.de/legal" },
};
const quiet = { info() {}, warn() {}, error() {} };

function fakeFetch(routes) {
  const calls = [];
  const fn = async (url) => {
    calls.push(url);
    const body = routes[url];
    return body === undefined ? new Response("not found", { status: 404 }) : new Response(body, { status: 200 });
  };
  fn.calls = calls;
  return fn;
}

const run = (store, body, now = NOW) =>
  ingest({ sources: [SOURCE], store, fetch: fakeFetch(body ? { [SOURCE.url]: body } : {}), now: () => now, log: quiet });

test("a zip's warnings are published by tile, and a rerun reuses them", async () => {
  const store = fakeStore(() => NOW);
  const first = await run(store, ZIP);
  assert.equal(first.alerts, 1);
  assert.equal(first.sourceErrors, 0);
  const index = store.files.get(INDEX_PATH);
  assert.equal(index.sources[0].error, null);
  const tile = store.files.get(tilePath("50/8", index.tiles["50/8"]));
  assert.equal(tile.alerts[0].id, `dwd:${WIND}`);
  assert.equal(tile.alerts[0].polygons[0].length, 2, "the island stays a hole");
  assert.equal(store.state.sources.dwd.entries[`${COAST}.xml`].result, "no_area");

  const again = await run(store, ZIP, new Date(NOW.getTime() + 300_000));
  assert.equal(again.tilesWritten, 0);
});

test("a zip that won't load keeps the last warnings and says so", async () => {
  const store = fakeStore(() => NOW);
  await run(store, ZIP);
  const failed = await run(store, null, new Date(NOW.getTime() + 300_000));
  assert.equal(failed.alerts, 1);
  assert.equal(store.files.get(INDEX_PATH).sources[0].error, "HTTP 404");
});

test("a document that won't parse marks the check incomplete", async () => {
  const broken = Buffer.from(ZIP);
  // Corrupt the stored coastal document's opening tag.
  const at = broken.indexOf("<alert", broken.indexOf(COAST));
  broken.write("<blert", at);
  const store = fakeStore(() => NOW);
  const summary = await run(store, broken);
  assert.equal(summary.sourceErrors, 1);
  assert.equal(store.files.get(INDEX_PATH).sources[0].error, "1 of 2 documents failed");
});
