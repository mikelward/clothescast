"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");

const {
  REGIONS_PAGE_URL, driveDownloadUrl, parseRegionAliases, parseRegionShapes, regionLinks,
} = require("../lib/alerts/regions");
const { INDEX_PATH, ZONE_TOLERANCE, ingest, regionPath, tilePath } = require("../lib/alerts/ingest");
const { fakeStore } = require("./support/fake-store");

const fixture = (name) => readFileSync(path.join(__dirname, "fixtures", name), "utf8");
// The Redistribution Hub page as MeteoAlarm's CMS serves it, trimmed to
// its links; two made-up regions, one with a hole; and aliases for them.
const HUB = fixture("meteoalarm-hub.json");
const REGIONS = fixture("meteoalarm-regions.json");
const ALIASES = fixture("meteoalarm-aliases.csv");
const FEED = fixture("meteoalarm-feed.atom");
// The UK warning with its polygon swapped for a region code, as most
// MeteoAlarm countries send them.
const ALERT = fixture("meteoalarm-alert.xml");
const byRegion = (type, code) =>
  ALERT.replace(/<polygon>[^<]*<\/polygon>/, `<geocode><valueName>${type}</valueName><value>${code}</value></geocode>`);
const NOW = new Date("2026-10-23T12:00:00Z");
const DAY = 24 * 60 * 60_000;

const GEOCODES_URL = "https://gitlab.example/documents/MeteoAlarm_Geocodes_2026_07_31.json?inline=false";
const ALIASES_URL = "https://drive.usercontent.google.com/download?id=ALIASES_ID&export=download&confirm=t";

test("the Redistribution Hub's current files are found by their link text", () => {
  assert.deepEqual(regionLinks(HUB), { geocodes: GEOCODES_URL, aliases: ALIASES_URL });
  assert.deepEqual(regionLinks('{"page": {"content": "<p>Moved</p>"}}'), { geocodes: null, aliases: null });
  assert.equal(driveDownloadUrl("https://example.com/aliases.csv"), "https://example.com/aliases.csv");
});

test("region shapes come back latitude first, holes kept, other geocode types left out", () => {
  const shapes = parseRegionShapes(REGIONS, ZONE_TOLERANCE);
  assert.deepEqual([...shapes.keys()], ["XX001", "XX002"]);
  assert.deepEqual(shapes.get("XX001")[0][0][0], [40.2, 10.2]);
  assert.deepEqual(shapes.get("XX002").map((rings) => rings.length), [2, 1]);
  assert.throws(() => parseRegionShapes("[]", ZONE_TOLERANCE), /not a GeoJSON feature collection/);
});

test("aliases map NUTS and FIPS codes to regions, one alias to several where it spans them", () => {
  const aliases = parseRegionAliases(ALIASES);
  assert.deepEqual(Object.fromEntries(aliases), {
    "NUTS3:XX101": ["XX001"],
    "NUTS2:XX1": ["XX001", "XX002"],
    "FIPS:XY14": ["XX002"],
  });
  assert.throws(() => parseRegionAliases("code;alias\n"), /not a geocode aliases table/);
});

// ---- Region-coded warnings in an ingester run ----

const SOURCE = {
  id: "meteoalarm-xx",
  kind: "cap-atom",
  url: "https://feeds.example.org/feeds/united-kingdom",
  countries: ["XX"],
  attribution: { name: "Service via MeteoAlarm", url: "https://meteoalarm.org" },
  disclaimer: { name: "MeteoAlarm", url: "https://meteoalarm.example/terms" },
};
const CAP_URL = "https://feeds.example.org/api/v1/warnings/feeds-united-kingdom/amber-wind";
const DOWNLOADS = { [REGIONS_PAGE_URL]: HUB, [GEOCODES_URL]: REGIONS, [ALIASES_URL]: ALIASES };
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

const run = (store, fetch, now = NOW) => ingest({ sources: [SOURCE], store, fetch, now: () => now, log: quiet });

test("a warning naming a region is placed from its shape, the files downloaded once and kept", async () => {
  const store = fakeStore(() => NOW);
  const summary = await run(store, fakeFetch({ [SOURCE.url]: FEED, [CAP_URL]: byRegion("EMMA_ID", "XX002"), ...DOWNLOADS }));
  assert.equal(summary.alerts, 1);
  assert.equal(summary.sourceErrors, 0);
  assert.deepEqual([...store.zones.keys()].sort(), [
    regionPath("EMMA_ID:XX001"), regionPath("EMMA_ID:XX002"),
    regionPath("FIPS:XY14"), regionPath("NUTS2:XX1"), regionPath("NUTS3:XX101"),
  ].sort());
  assert.equal(store.state.regionsFetchedAt, NOW.toISOString());

  const index = store.files.get(INDEX_PATH);
  const tile = store.files.get(tilePath("41/10", index.tiles["41/10"]));
  // Region B: its main part with its hole, and its second part.
  assert.deepEqual(tile.alerts[0].polygons.map((rings) => rings.length), [2, 1]);
});

test("a warning naming a NUTS code is placed from the regions it stands for", async () => {
  const store = fakeStore(() => NOW);
  const summary = await run(store, fakeFetch({ [SOURCE.url]: FEED, [CAP_URL]: byRegion("NUTS2", "XX1"), ...DOWNLOADS }));
  assert.equal(summary.sourceErrors, 0);
  const index = store.files.get(INDEX_PATH);
  assert.ok("40/10" in index.tiles && "41/10" in index.tiles && "42/10" in index.tiles);
});

test("cached region shapes are used without downloading the files again", async () => {
  const store = fakeStore(() => NOW);
  store.zones.set(regionPath("EMMA_ID:XX001"), {
    v: 1,
    fetchedAt: new Date(NOW.getTime() - DAY).toISOString(),
    polygons: [[[[40.2, 10.2], [40.2, 10.8], [40.8, 10.8], [40.8, 10.2], [40.2, 10.2]]]],
  });
  const fetch = fakeFetch({ [SOURCE.url]: FEED, [CAP_URL]: byRegion("EMMA_ID", "XX001") });
  const summary = await run(store, fetch);
  assert.equal(summary.sourceErrors, 0);
  assert.deepEqual(fetch.calls, [SOURCE.url, CAP_URL]);
});

test("an unknown region marks the check incomplete, and the files aren't downloaded again that day", async () => {
  const store = fakeStore(() => NOW);
  const routes = { [SOURCE.url]: FEED, [CAP_URL]: byRegion("EMMA_ID", "XX999"), ...DOWNLOADS };
  const first = await run(store, fakeFetch(routes));
  assert.equal(first.sourceErrors, 1);
  assert.equal(store.files.get(INDEX_PATH).sources[0].error, "1 of 1 documents name unknown regions");

  // The document is read again next run, but the files aren't.
  const fetch = fakeFetch(routes);
  await run(store, fetch, new Date(NOW.getTime() + 300_000));
  assert.deepEqual(fetch.calls, [SOURCE.url, CAP_URL]);

  // A day on, they are.
  const later = fakeFetch(routes);
  await run(store, later, new Date(NOW.getTime() + DAY + 300_000));
  assert.ok(later.calls.includes(REGIONS_PAGE_URL));
});

test("without the aliases, regions still place; without the shapes, nothing does until tomorrow", async () => {
  const noAliases = fakeStore(() => NOW);
  const routes = { [SOURCE.url]: FEED, [CAP_URL]: byRegion("EMMA_ID", "XX001"), ...DOWNLOADS, [ALIASES_URL]: undefined };
  assert.equal((await run(noAliases, fakeFetch(routes))).sourceErrors, 0);

  const nothing = fakeStore(() => NOW);
  const summary = await run(nothing, fakeFetch({ [SOURCE.url]: FEED, [CAP_URL]: byRegion("EMMA_ID", "XX001") }));
  assert.equal(summary.alerts, 0);
  assert.equal(summary.sourceErrors, 1);
  assert.equal(nothing.state.regionsFetchedAt, NOW.toISOString());
});
