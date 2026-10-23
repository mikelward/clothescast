"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");

const { geoJsonPolygons, simplifyRing } = require("../lib/alerts/geo");
const { isMarineZone, parseNwsAlerts, unwrapNwsText } = require("../lib/alerts/nws");
const { INDEX_PATH, ZONE_TOLERANCE, ingest, tilePath, zonePath } = require("../lib/alerts/ingest");
const { fakeStore } = require("./support/fake-store");

const fixture = (name) => readFileSync(path.join(__dirname, "fixtures", name), "utf8");
const FEED = fixture("nws-active.json");
const ZONE_060 = fixture("nws-zone-MNZ060.json");
const ZONE_061 = fixture("nws-zone-MNZ061.json");
const NOW = new Date("2026-10-23T12:00:00Z");

test("marine zones are told apart from land ones by their prefix", () => {
  assert.equal(isMarineZone("https://api.weather.gov/zones/forecast/ANZ530"), true);
  assert.equal(isMarineZone("https://api.weather.gov/zones/forecast/PZZ110"), true);
  assert.equal(isMarineZone("https://api.weather.gov/zones/forecast/LSZ162"), true);
  assert.equal(isMarineZone("https://api.weather.gov/zones/forecast/MNZ060"), false);
  assert.equal(isMarineZone("https://api.weather.gov/zones/fire/CAZ241"), false);
  assert.equal(isMarineZone("https://api.weather.gov/zones/county/AZC005"), false);
});

test("GeoJSON polygons come back latitude first, holes kept, malformed ones dropped", () => {
  const square = [[0, 1], [2, 1], [2, 3], [0, 3], [0, 1]];
  const hole = [[0.5, 1.5], [1, 1.5], [1, 2], [0.5, 1.5]];
  assert.deepEqual(geoJsonPolygons({ type: "Polygon", coordinates: [square] }), [[[[1, 0], [1, 2], [3, 2], [3, 0], [1, 0]]]]);
  assert.equal(geoJsonPolygons({ type: "Polygon", coordinates: [square, hole] })[0].length, 2);
  assert.equal(geoJsonPolygons({ type: "MultiPolygon", coordinates: [[square], [square]] }).length, 2);
  assert.equal(geoJsonPolygons({ type: "GeometryCollection", geometries: [{ type: "Polygon", coordinates: [square] }] }).length, 1);
  // A malformed outer ring drops its polygon, holes and all; a malformed hole drops alone.
  assert.deepEqual(geoJsonPolygons({ type: "Polygon", coordinates: [[[0, 1], [2, 1]], square] }), []);
  assert.equal(geoJsonPolygons({ type: "Polygon", coordinates: [square, [[0, 95], [1, 1], [2, 2], [0, 95]]] })[0].length, 1);
  assert.deepEqual(geoJsonPolygons(null), []);
  assert.deepEqual(geoJsonPolygons({ type: "Point", coordinates: [0, 0] }), []);
});

test("a zone outline is simplified within the tolerance, and stays closed", () => {
  const [[ring]] = geoJsonPolygons(JSON.parse(ZONE_060).geometry);
  const simplified = simplifyRing(ring, ZONE_TOLERANCE);
  assert.ok(simplified.length < ring.length / 10, `${simplified.length} of ${ring.length}`);
  assert.deepEqual(simplified[0], simplified[simplified.length - 1]);
  // Every original point stays near the simplified outline's corners: the
  // square's sides are straight but for wiggles smaller than the tolerance.
  for (const [lat] of ring) assert.ok(lat >= 44.499 && lat <= 45.5 + 1e-9);
  const tiny = [[0, 0], [0, 1], [1, 1], [0, 0]];
  assert.deepEqual(simplifyRing(tiny, 10), tiny);
});

test("an NWS response yields its current land alerts, not superseded, marine-only or test ones", () => {
  const { alerts, skipped } = parseNwsAlerts(FEED, "nws", NOW);
  assert.deepEqual(skipped, { superseded: 1, marine: 1, not_actual: 1 });
  assert.equal(alerts.length, 2);

  const [flood, wind] = alerts;
  assert.equal(flood.alert.id, "nws:urn:oid:2.49.0.1.840.0.polygon.001.1");
  assert.equal(flood.alert.severity, "SEVERE");
  assert.equal(flood.alert.eventType, "FLOOD_WARNING");
  assert.equal(flood.alert.expires, "2026-10-23T09:45:00-07:00", "ends, not the message's expiry");
  assert.equal(flood.alert.issuer, "NWS Flagstaff AZ");
  assert.equal(flood.alert.texts[0].title, "Flood Warning");
  assert.equal(flood.alert.texts[0].instruction, "Turn around, don't drown.");
  assert.deepEqual(flood.alert.polygons[0][0][0], [35.0, -111.5]);
  assert.deepEqual(flood.zones, []);

  assert.equal(wind.alert.polygons.length, 0);
  assert.deepEqual(wind.zones, [
    "https://api.weather.gov/zones/forecast/MNZ060",
    "https://api.weather.gov/zones/forecast/MNZ061",
  ]);
  assert.equal(wind.alert.onset, "2026-10-23T18:00:00-05:00");
});

test("NWS text is unwrapped into paragraphs, keeping its lists", () => {
  const wrapped =
    "* WHAT...Flooding caused by excessive rainfall\ncontinues.\n\n" +
    "* WHERE...County A in State A...\nCounty B in State A...\n\n" +
    "* ADDITIONAL DETAILS...\n- At 556 AM, radar indicated heavy\nrain.\n- Locations include...\nTown A, Town B.\r\n\r\n\r\n" +
    "Turn around, don't drown. ";
  assert.equal(
    unwrapNwsText(wrapped),
    "* WHAT...Flooding caused by excessive rainfall continues.\n\n" +
      "* WHERE...County A in State A...\nCounty B in State A...\n\n" +
      "* ADDITIONAL DETAILS...\n- At 556 AM, radar indicated heavy rain.\n- Locations include...\nTown A, Town B.\n\n" +
      "Turn around, don't drown.",
  );
  assert.equal(unwrapNwsText(null), null);
});

test("a response that isn't an alerts collection is refused", () => {
  assert.throws(() => parseNwsAlerts("{}", "nws", NOW), /not an NWS alerts collection/);
});

// ---- The NWS source in an ingester run ----

const SOURCE = {
  id: "nws",
  kind: "nws",
  url: "https://api.weather.gov/alerts/active?status=actual",
  countries: ["US"],
  attribution: { name: "US National Weather Service", url: "https://www.weather.gov" },
};
const quiet = { info() {}, warn() {}, error() {} };
const ZONE_URL = (id) => `https://api.weather.gov/zones/forecast/${id}`;

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

const run = (store, routes, now = NOW) =>
  ingest({ sources: [SOURCE], store, fetch: fakeFetch(routes), now: () => now, log: quiet });

test("zoned alerts are placed from simplified zone shapes, fetched once and kept", async () => {
  const store = fakeStore(() => NOW);
  const routes = { [SOURCE.url]: FEED, [ZONE_URL("MNZ060")]: ZONE_060, [ZONE_URL("MNZ061")]: ZONE_061 };
  const first = await run(store, routes);
  assert.deepEqual(first, { alerts: 2, tilesWritten: first.tilesWritten, tilesDeleted: 0, sourceErrors: 0 });
  assert.ok(store.zones.has(zonePath("forecast", "MNZ060")));
  assert.ok(store.zones.has(zonePath("forecast", "MNZ061")));

  const index = store.files.get(INDEX_PATH);
  // The two zones span 94.5°W to 92.5°W at 44.5–45.5°N.
  for (const key of ["44/-95", "44/-94", "44/-93", "45/-95", "45/-93"]) assert.ok(key in index.tiles, key);
  const tile = store.files.get(tilePath("44/-94", index.tiles["44/-94"]));
  const wind = tile.alerts.find((a) => a.texts[0].title === "Wind Advisory");
  assert.equal(wind.polygons.length, 2);
  assert.ok(wind.polygons[0][0].length < 20);

  // Unchanged next run: nothing refetched but the feed.
  const fetch = fakeFetch({ [SOURCE.url]: FEED });
  await ingest({ sources: [SOURCE], store, fetch, now: () => new Date(NOW.getTime() + 300_000), log: quiet });
  assert.deepEqual(fetch.calls, [SOURCE.url]);
});

test("a zone that won't load marks the check incomplete, and the alert is placed again next run", async () => {
  const store = fakeStore(() => NOW);
  const partial = await run(store, { [SOURCE.url]: FEED, [ZONE_URL("MNZ060")]: ZONE_060 });
  assert.equal(partial.sourceErrors, 1);
  assert.equal(store.files.get(INDEX_PATH).sources[0].error, "1 of 2 zone shapes failed");
  const [partialWind] = Object.values(store.state.sources.nws.entries).filter((e) => e.result.texts?.[0].title === "Wind Advisory");
  assert.equal(partialWind.updated, null);
  assert.equal(partialWind.result.polygons.length, 1);

  const fetch = fakeFetch({ [SOURCE.url]: FEED, [ZONE_URL("MNZ061")]: ZONE_061 });
  const later = new Date(NOW.getTime() + 300_000);
  await ingest({ sources: [SOURCE], store, fetch, now: () => later, log: quiet });
  // MNZ060 came from the bucket this time; only MNZ061 was fetched.
  assert.deepEqual(fetch.calls, [SOURCE.url, ZONE_URL("MNZ061")]);
  assert.equal(store.files.get(INDEX_PATH).sources[0].error, null);
});

test("a zone address that isn't api.weather.gov is never fetched", async () => {
  const feed = FEED.replace("https://api.weather.gov/zones/forecast/MNZ061", "https://example.com/zones/forecast/MNZ061");
  const store = fakeStore(() => NOW);
  const fetch = fakeFetch({ [SOURCE.url]: feed, [ZONE_URL("MNZ060")]: ZONE_060 });
  const summary = await ingest({ sources: [SOURCE], store, fetch, now: () => NOW, log: quiet });
  assert.equal(summary.sourceErrors, 1);
  assert.ok(!fetch.calls.some((u) => u.startsWith("https://example.com")));
});
