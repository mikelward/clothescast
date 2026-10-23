"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");

const { parseCapAlert, parseCapFeed, parseCircle, parsePolygon } = require("../lib/alerts/cap");
const { serve } = require("../lib/alerts/served");
const { MAX_TILES_PER_ALERT, tileKey, tilesFor } = require("../lib/alerts/tiles");
const { METEOALARM_DISCLAIMER, SOURCES } = require("../lib/alerts/sources");
const { INDEX_PATH, USER_AGENT, ingest, tilePath } = require("../lib/alerts/ingest");
const { fakeStore: makeStore } = require("./support/fake-store");

const fixture = (name) => readFileSync(path.join(__dirname, "fixtures", name), "utf8");
const FEED = fixture("meteoalarm-feed.atom");
const ALERT = fixture("meteoalarm-alert.xml");
const NOW = new Date("2026-10-23T12:00:00Z");

test("a CAP polygon is read latitude first and closed", () => {
  assert.deepEqual(parsePolygon("1,2 1,3 2,3"), [[1, 2], [1, 3], [2, 3], [1, 2]]);
  assert.deepEqual(parsePolygon("1,2 1,3 2,3 1,2"), [[1, 2], [1, 3], [2, 3], [1, 2]]);
});

test("a malformed or degenerate polygon is dropped, not guessed", () => {
  for (const raw of ["1,2 1,3", "1,2 1,x 2,3", "1,2,0 1,3 2,3", "91,2 1,3 2,3", "1,2 1,2 1,2"]) {
    assert.equal(parsePolygon(raw), null, raw);
  }
});

test("a CAP circle becomes a closed ring around its center", () => {
  const ring = parseCircle("10,20 11.132");
  assert.equal(ring.length, 37);
  assert.deepEqual(ring[0], ring[ring.length - 1]);
  for (const [lat] of ring) assert.ok(Math.abs(lat - 10) <= 0.1 + 1e-9);
  assert.equal(parseCircle("10,20"), null);
  assert.equal(parseCircle("10,20 -1"), null);
});

test("a CAP document keeps the issuer's text, parameters and area", () => {
  const cap = parseCapAlert(ALERT);
  assert.equal(cap.identifier, "2.49.0.0.826.0.GB_261023090239_34b39a5a.v1.0.W");
  assert.equal(cap.status, "Actual");
  assert.equal(cap.msgType, "Alert");
  const [info] = cap.infos;
  assert.equal(info.language, "en-GB");
  assert.equal(info.event, "Amber wind warning");
  assert.equal(info.senderName, "UK Met Office");
  assert.match(info.description, /^Very strong westerly winds are expected & gusts/);
  assert.match(info.description, /\n\nFor further details/);
  assert.deepEqual(info.parameters.impacts, ["Flying debris is likely", "Some roads and bridges likely to close"]);
  assert.equal(info.areas[0].areaDesc, "Strathclyde | SW Scotland, Lothian Borders");
  assert.equal(info.areas[0].rings[0].length, 5);
});

test("every source has a unique id, countries, and a named https credit and disclaimer", () => {
  assert.equal(new Set(SOURCES.map((s) => s.id)).size, SOURCES.length);
  for (const source of SOURCES) {
    assert.ok(source.countries.length > 0, source.id);
    assert.ok(source.attribution.name, source.id);
    assert.match(source.attribution.url, /^https:\/\//, source.id);
    assert.ok(source.disclaimer.name, source.id);
    assert.match(source.disclaimer.url, /^https:\/\//, source.id);
  }
  // MeteoAlarm's terms (§5) require its wording, not just a link.
  for (const source of SOURCES.filter((s) => s.id.startsWith("meteoalarm-"))) {
    assert.equal(source.disclaimer.text, METEOALARM_DISCLAIMER, source.id);
  }
});

test("XML that isn't a CAP alert or an Atom feed is refused", () => {
  assert.throws(() => parseCapAlert("<feed/>"), /not a CAP alert/);
  assert.throws(() => parseCapFeed("<alert/>"), /not an Atom feed/);
});

test("a feed lists each alert once, with its CAP document", () => {
  assert.deepEqual(parseCapFeed(FEED), [
    {
      identifier: "2.49.0.0.826.0.GB_261023090239_34b39a5a.v1.0.W",
      updated: "2026-10-23T09:02:39Z",
      capUrl: "https://feeds.example.org/api/v1/warnings/feeds-united-kingdom/amber-wind",
    },
  ]);
});

test("MeteoAlarm's own grade and hazard set the severity and event type", () => {
  const served = serve("meteoalarm-gb", parseCapAlert(ALERT), NOW);
  assert.equal(served.id, "meteoalarm-gb:2.49.0.0.826.0.GB_261023090239_34b39a5a.v1.0.W");
  assert.equal(served.severity, "SEVERE");
  assert.equal(served.eventType, "WIND");
  assert.equal(served.onset, "2026-10-24T20:00:00+00:00");
  assert.equal(served.expires, "2026-10-25T21:00:00+00:00");
  assert.equal(served.issuer, "UK Met Office");
  assert.equal(served.polygons.length, 1);
  assert.equal(served.polygons[0].length, 1, "one ring: CAP has no holes");
  assert.deepEqual(served.texts.map((t) => [t.language, t.title, t.areaName]), [
    ["en-GB", "Amber wind warning", "Strathclyde | SW Scotland, Lothian Borders"],
  ]);
});

test("without MeteoAlarm's parameters, CAP's severity and event are used", () => {
  const xml = ALERT.replace(/<parameter>[\s\S]*?<\/parameter>/g, "")
    .replace("<event>Amber wind warning</event>", "<event>High Wind Warning</event>")
    .replace("<severity>Severe</severity>", "<severity>Moderate</severity>");
  const served = serve("x", parseCapAlert(xml), NOW);
  assert.equal(served.severity, "MODERATE");
  assert.equal(served.eventType, "HIGH_WIND_WARNING");
});

test("only real, public, unexpired alerts with an area are served", () => {
  const variant = (from, to) => parseCapAlert(ALERT.replace(from, to));
  assert.equal(serve("x", variant("<status>Actual</status>", "<status>Exercise</status>"), NOW), "not_actual");
  assert.equal(serve("x", variant("<msgType>Alert</msgType>", "<msgType>Cancel</msgType>"), NOW), "cancel");
  assert.equal(serve("x", variant("<scope>Public</scope>", "<scope>Restricted</scope>"), NOW), "not_public");
  assert.equal(serve("x", parseCapAlert(ALERT), new Date("2026-10-26T00:00:00Z")), "expired");
  assert.equal(serve("x", variant(/<polygon>[^<]*<\/polygon>/, ""), NOW), "no_area");
});

test("tiles are named by their south-west corner", () => {
  assert.equal(tileKey(51.5, -0.12), "51/-1");
  assert.equal(tileKey(-37.8, 144.9), "-38/144");
  assert.equal(tileKey(0, 0), "0/0");
  assert.equal(tileKey(10, 180), "10/-180");
});

test("an alert lands in every tile its box touches", () => {
  assert.deepEqual(tilesFor([[[[55.2, -4.5], [56.5, -2.5], [55.2, -2.5], [55.2, -4.5]]]]).sort(), [
    "55/-3", "55/-4", "55/-5", "56/-3", "56/-4", "56/-5",
  ]);
});

test("an alert's tiles reach the app's 1 km slack past its edge", () => {
  // Ends 111 m short of 56°N and of 4°W: a phone just over either line
  // still matches it, so it must find the alert in its own tile.
  const square = [[55.5, -4.5], [55.999, -4.5], [55.999, -4.0016], [55.5, -4.0016], [55.5, -4.5]];
  assert.deepEqual(tilesFor([[square]]).sort(), ["55/-4", "55/-5", "56/-4", "56/-5"]);
  // At 70°N a degree of longitude is a third as long, so the reach is wider.
  const north = [[70.5, 10.02], [70.6, 10.02], [70.6, 10.5], [70.5, 10.02]];
  assert.deepEqual(tilesFor([[north]]).sort(), ["70/10", "70/9"]);
});

test("an alert across the antimeridian tiles both sides, not the whole world", () => {
  const keys = tilesFor([[[[-17.5, 179.5], [-17.5, -179.5], [-16.5, -179.5], [-17.5, 179.5]]]]).sort();
  assert.deepEqual(keys, ["-17/-180", "-17/179", "-18/-180", "-18/179"]);
});

test("an alert too broad to mean anything at a point isn't tiled", () => {
  const side = Math.ceil(Math.sqrt(MAX_TILES_PER_ALERT)) + 1;
  assert.equal(tilesFor([[[[0, 0], [side, 0], [side, side], [0, 0]]]]), null);
});

// ---- One ingester run, against a fake network and bucket ----

const fakeStore = (clock = () => NOW) => makeStore(clock);

function fakeFetch(routes) {
  const calls = [];
  const fn = async (url, init) => {
    calls.push({ url, userAgent: init.headers["User-Agent"] });
    const route = routes[url];
    if (route === undefined) return new Response("not found", { status: 404 });
    if (route instanceof Error) throw route;
    return new Response(route, { status: 200 });
  };
  fn.calls = calls;
  return fn;
}

const SOURCE = {
  id: "meteoalarm-gb",
  kind: "cap-atom",
  url: "https://feeds.example.org/feeds/united-kingdom",
  countries: ["GB"],
  attribution: { name: "Met Office via MeteoAlarm", url: "https://meteoalarm.org" },
  disclaimer: { name: "MeteoAlarm", url: "https://meteoalarm.example/terms" },
};
const CAP_URL = "https://feeds.example.org/api/v1/warnings/feeds-united-kingdom/amber-wind";
const quiet = { info() {}, warn() {}, error() {} };
// The fixture's square starts on 55°N, so its tiles reach into row 54.
const TILES = ["54/-3", "54/-4", "54/-5", "55/-3", "55/-4", "55/-5", "56/-3", "56/-4", "56/-5"];
const run = (store, routes, now = NOW) =>
  ingest({ sources: [SOURCE], store, fetch: fakeFetch(routes), now: () => now, log: quiet });
const minutes = (n) => new Date(NOW.getTime() + n * 60_000);

test("a run publishes each tile, then an index naming them", async () => {
  const store = fakeStore();
  const fetch = fakeFetch({ [SOURCE.url]: FEED, [CAP_URL]: ALERT });
  const summary = await ingest({ sources: [SOURCE], store, fetch, now: () => NOW, log: quiet });

  assert.deepEqual(summary, { alerts: 1, tilesWritten: TILES.length, tilesDeleted: 0, sourceErrors: 0 });
  const index = store.files.get(INDEX_PATH);
  assert.equal(index.generatedAt, NOW.toISOString());
  assert.equal(index.tileDegrees, 1);
  assert.deepEqual(Object.keys(index.tiles), TILES);
  assert.deepEqual(index.sources, [{
    id: "meteoalarm-gb",
    countries: ["GB"],
    attribution: SOURCE.attribution,
    disclaimer: SOURCE.disclaimer,
    lastSuccessAt: NOW.toISOString(),
    error: null,
  }]);
  const tile = store.files.get(tilePath("55/-5", index.tiles["55/-5"]));
  assert.equal(tile.tile, "55/-5");
  assert.equal(tile.alerts[0].texts[0].title, "Amber wind warning");
  assert.match(index.tiles["55/-5"], /^[0-9a-f]{16}$/);
  assert.ok(fetch.calls.every((c) => c.userAgent === USER_AGENT));
});

test("each host's feeds are read side by side with other hosts'", async () => {
  // Five feeds on one host fill its pool; the other host's feed must still
  // start before any of them finishes, not queue behind them.
  const busy = Array.from({ length: 5 }, (_, i) =>
    ({ ...SOURCE, id: `busy-${i}`, url: `https://busy.example.org/feed-${i}` }));
  const other = { ...SOURCE, id: "other", url: "https://other.example.org/feed" };
  let busyDone = 0;
  let busyDoneWhenOtherStarted = null;
  const fetch = async (url) => {
    if (url === other.url) {
      busyDoneWhenOtherStarted = busyDone;
    } else {
      // Yield a macrotask so every pool gets to start its first feed.
      await new Promise((resolve) => setImmediate(resolve));
      busyDone++;
    }
    return new Response(FEED, { status: 200 });
  };
  await ingest({ sources: [...busy, other], store: fakeStore(), fetch, now: () => NOW, log: quiet });

  assert.equal(busyDoneWhenOtherStarted, 0);
});

test("a source still fetching at the deadline keeps its last alerts, and the rest publish", async () => {
  const store = fakeStore();
  await run(store, { [SOURCE.url]: FEED, [CAP_URL]: ALERT });
  const other = { ...SOURCE, id: "other", url: "https://other.example.org/feed" };
  let stop;
  const fetchDeadline = new Promise((resolve) => { stop = resolve; });
  // The first source's feed hangs until its request is aborted; the deadline
  // passes once the other source has fetched its alert.
  const fetch = (url, init) => {
    if (init.signal.aborted) return Promise.reject(init.signal.reason);
    if (url === other.url) return Promise.resolve(new Response(FEED, { status: 200 }));
    if (url === CAP_URL) {
      stop();
      return Promise.resolve(new Response(ALERT, { status: 200 }));
    }
    return new Promise((_, reject) => init.signal.addEventListener("abort", () => reject(init.signal.reason)));
  };
  const summary = await ingest({ sources: [SOURCE, other], store, fetch, now: () => minutes(5), log: quiet, fetchDeadline });

  assert.equal(summary.sourceErrors, 1);
  const index = store.files.get(INDEX_PATH);
  assert.equal(index.generatedAt, minutes(5).toISOString());
  const [late, onTime] = index.sources;
  assert.equal(late.error, "run out of time");
  assert.equal(late.lastSuccessAt, NOW.toISOString());
  assert.equal(onTime.error, null);
  assert.equal(summary.alerts, 2);
});

test("an unchanged feed rewrites only the index and refetches no documents", async () => {
  const store = fakeStore();
  await run(store, { [SOURCE.url]: FEED, [CAP_URL]: ALERT });
  const fetch = fakeFetch({ [SOURCE.url]: FEED });
  const summary = await ingest({ sources: [SOURCE], store, fetch, now: () => minutes(5), log: quiet });

  assert.equal(summary.tilesWritten, 0);
  assert.deepEqual(fetch.calls.map((c) => c.url), [SOURCE.url]);
  assert.equal(store.files.get(INDEX_PATH).generatedAt, minutes(5).toISOString());
});

test("a failing feed keeps its last alerts and says so in the index", async () => {
  const store = fakeStore();
  await run(store, { [SOURCE.url]: FEED, [CAP_URL]: ALERT });
  const summary = await run(store, {}, minutes(5));

  assert.equal(summary.sourceErrors, 1);
  assert.equal(summary.alerts, 1);
  const index = store.files.get(INDEX_PATH);
  assert.equal(index.sources[0].error, "HTTP 404");
  assert.equal(index.sources[0].lastSuccessAt, NOW.toISOString());
  assert.equal(Object.keys(index.tiles).length, TILES.length);
});

test("a document that won't load marks the check incomplete and is retried", async () => {
  const store = fakeStore();
  const first = await run(store, { [SOURCE.url]: FEED });
  assert.equal(first.alerts, 0);
  assert.equal(first.sourceErrors, 1);
  const [source] = store.files.get(INDEX_PATH).sources;
  assert.equal(source.error, "1 of 1 documents failed");
  assert.equal(source.lastSuccessAt, null);

  const fetch = fakeFetch({ [SOURCE.url]: FEED, [CAP_URL]: ALERT });
  const second = await ingest({ sources: [SOURCE], store, fetch, now: () => minutes(5), log: quiet });
  assert.equal(second.alerts, 1);
  assert.ok(fetch.calls.some((c) => c.url === CAP_URL));
  assert.equal(store.files.get(INDEX_PATH).sources[0].error, null);
});

test("an alert gone from its feed, or past its end, leaves the index at once and its files after an hour", async () => {
  const emptyFeed = FEED.replace(/<entry>[\s\S]*<\/entry>/, "");
  const store = fakeStore(() => NOW);
  await run(store, { [SOURCE.url]: FEED, [CAP_URL]: ALERT });
  const gone = await run(store, { [SOURCE.url]: emptyFeed }, minutes(5));
  assert.deepEqual(gone, { alerts: 0, tilesWritten: 0, tilesDeleted: 0, sourceErrors: 0 });
  assert.deepEqual(store.files.get(INDEX_PATH).tiles, {});
  // A phone holding the last index still finds its tiles for an hour.
  assert.equal(store.files.size, TILES.length + 1);
  const later = await run(store, { [SOURCE.url]: emptyFeed }, minutes(61));
  assert.equal(later.tilesDeleted, TILES.length);
  assert.deepEqual([...store.files.keys()], [INDEX_PATH]);

  const expiring = fakeStore();
  await run(expiring, { [SOURCE.url]: FEED, [CAP_URL]: ALERT });
  const afterEnd = new Date("2026-10-25T21:00:01Z");
  const late = await run(expiring, { [SOURCE.url]: FEED }, afterEnd);
  assert.equal(late.alerts, 0);
  assert.equal(late.tilesDeleted, TILES.length);
});

test("a tile file is kept for an hour after the index stops naming it, however old it is", async () => {
  const emptyFeed = FEED.replace(/<entry>[\s\S]*<\/entry>/, "");
  const store = fakeStore(() => NOW);
  await run(store, { [SOURCE.url]: FEED, [CAP_URL]: ALERT });
  await run(store, { [SOURCE.url]: FEED }, minutes(65));
  // Removed at minute 70: phones holding the minute-65 index still need its tiles.
  const removed = await run(store, { [SOURCE.url]: emptyFeed }, minutes(70));
  assert.equal(removed.tilesDeleted, 0);
  assert.equal(store.files.size, TILES.length + 1);
  assert.equal((await run(store, { [SOURCE.url]: emptyFeed }, minutes(124))).tilesDeleted, 0);
  assert.equal((await run(store, { [SOURCE.url]: emptyFeed }, minutes(126))).tilesDeleted, TILES.length);
});

test("a run that overlaps a newer one can't replace its index or delete its tiles", async () => {
  const emptyFeed = FEED.replace(/<entry>[\s\S]*<\/entry>/, "");
  const store = fakeStore();
  // An old run starts on a quiet feed; before it publishes, a newer run
  // publishes the alert.
  const original = store.listTiles.bind(store);
  store.listTiles = async () => {
    store.listTiles = original;
    await run(store, { [SOURCE.url]: FEED, [CAP_URL]: ALERT }, minutes(61));
    return original();
  };
  await assert.rejects(run(store, { [SOURCE.url]: emptyFeed }, minutes(61)), /precondition failed/);

  const index = store.files.get(INDEX_PATH);
  assert.deepEqual(Object.keys(index.tiles), TILES);
  for (const [key, hash] of Object.entries(index.tiles)) {
    assert.ok(store.files.has(tilePath(key, hash)), key);
  }
});

test("a run that overlaps another fails at the state save instead of overwriting it", async () => {
  const store = fakeStore();
  const original = store.readState.bind(store);
  store.readState = async () => {
    const read = await original();
    await store.writeState({ v: 1, sources: {} }, read.generation);
    return read;
  };
  await assert.rejects(run(store, { [SOURCE.url]: FEED, [CAP_URL]: ALERT }), /precondition failed/);
});
