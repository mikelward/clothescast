"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");

const { parseEcccAlerts } = require("../lib/alerts/eccc");
const { INDEX_PATH, ingest, tilePath } = require("../lib/alerts/ingest");
const { fakeStore } = require("./support/fake-store");

// Seven regions from ECCC's weather-alerts collection, each drawn as a 1°
// square, texts cut to their first and last paragraphs: a wind warning
// over two regions; a watch whose text names a different storm-report
// address in each of its two provinces; a region whose part of a
// statement is over; a blizzard warning made to have ended; and a
// statement's region with no shape.
const FEED = readFileSync(path.join(__dirname, "fixtures", "eccc-alerts.json"), "utf8");
const NOW = new Date("2026-10-23T12:00:00Z");
const WIND = "191756675439027464202610220501";
const WATCH = "200601528818093584202610220501";

test("an ECCC response yields an alert per alert and province, its regions' shapes together", () => {
  const { alerts, skipped, missing } = parseEcccAlerts(FEED, "eccc", NOW);
  assert.deepEqual(skipped, { ended: 1, expired: 1, no_area: 1 });
  assert.equal(missing, 0);
  // The wind warning's regions are in Alberta and on the Saskatchewan border (Lloydminster).
  assert.deepEqual(alerts.map((a) => a.id), [
    `eccc:${WIND}:AB`, `eccc:${WIND}:AB-SK`, `eccc:${WATCH}:NU`, `eccc:${WATCH}:MB`,
  ]);

  const [wind, , watchNu, watchMb] = alerts;
  assert.equal(wind.polygons.length, 1);
  assert.deepEqual(wind.polygons[0][0][0], [50, -114]);
  assert.equal(wind.severity, "MODERATE");
  assert.equal(wind.eventType, "WIND_WARNING");
  assert.equal(wind.issuer, "Environment and Climate Change Canada");
  assert.equal(wind.sent, "2026-10-23T11:05:38.625Z");
  assert.equal(wind.onset, "2026-10-23T15:00:00.000Z");
  assert.equal(wind.expires, "2026-10-24T04:00:00.000Z", "when the hazard ends, not the message");
  assert.deepEqual(wind.texts.map((t) => [t.language, t.title]), [
    ["en-CA", "Wind warning"],
    ["fr-CA", "Avertissement de vent"],
  ]);
  assert.match(wind.texts[0].description, /^Damaging winds are expected\.\n\nPlease continue/);
  assert.match(watchNu.texts[0].description, /NUstorm@ec\.gc\.ca/);
  assert.match(watchMb.texts[0].description, /MBstorm@ec\.gc\.ca/);
  assert.deepEqual([watchNu.texts[0].areaName, watchMb.texts[0].areaName], ["Arviat", "Churchill"]);
});

test("an alert's ID stays the same when its text changes or another region's part ends", () => {
  const feed = JSON.parse(FEED);
  const ids = (features) =>
    parseEcccAlerts(JSON.stringify({ ...feed, features }), "eccc", NOW).alerts.map((a) => a.id);
  const [beaver, lloydminster, arviat, churchill] = feed.features;
  // Lloydminster's part, on the border, ends: Beaver County's alert keeps its ID.
  const borderEnded = { ...lloydminster, properties: { ...lloydminster.properties, status_en: "ended" } };
  assert.deepEqual(ids([beaver, lloydminster]), [`eccc:${WIND}:AB`, `eccc:${WIND}:AB-SK`]);
  assert.deepEqual(ids([beaver, borderEnded]), [`eccc:${WIND}:AB`]);
  // Arviat's part ends: Churchill's alert keeps its ID.
  const ended = { ...arviat, properties: { ...arviat.properties, status_en: "ended" } };
  assert.deepEqual(ids([ended, churchill]), [`eccc:${WATCH}:MB`]);
  // Churchill's text is updated: same ID.
  const updated = { ...churchill, properties: { ...churchill.properties, alert_text_en: "Updated." } };
  assert.deepEqual(ids([arviat, updated]), [`eccc:${WATCH}:NU`, `eccc:${WATCH}:MB`]);
  // Two texts over one province (an older version still listed): one alert, the newer text.
  const older = {
    ...churchill,
    id: `${WATCH}_fea1-9999`,
    properties: { ...churchill.properties, alert_text_en: "Older.", publication_datetime: "2026-10-22T08:00:00Z" },
  };
  const [merged] = parseEcccAlerts(JSON.stringify({ ...feed, features: [older, churchill] }), "eccc", NOW).alerts;
  assert.equal(merged.id, `eccc:${WATCH}:MB`);
  assert.equal(merged.polygons.length, 2);
  assert.match(merged.texts[0].description, /MBstorm/);
});

test("ECCC's colors, alert types and long region lists are read its way", () => {
  const feature = (id, props, lat = 50) => ({
    type: "Feature",
    id,
    geometry: { type: "Polygon", coordinates: [[[-100, lat], [-99, lat], [-99, lat + 1], [-100, lat + 1], [-100, lat]]] },
    properties: {
      alert_name_en: "rainfall warning", alert_text_en: "Rain.", alert_type: "warning",
      publication_datetime: "2026-10-23T10:00:00Z", status_en: "issued", ...props,
    },
  });
  const severity = (props) =>
    parseEcccAlerts(JSON.stringify({ features: [feature("a_1", props)] }), "eccc", NOW).alerts[0].severity;
  assert.deepEqual(
    ["yellow", "orange", "red"].map((risk_colour_en) => severity({ risk_colour_en })),
    ["MODERATE", "SEVERE", "EXTREME"],
  );
  assert.equal(severity({ risk_colour_en: null }), "MODERATE");
  assert.equal(severity({ risk_colour_en: null, alert_type: "statement" }), "MINOR");

  const regions = Array.from({ length: 6 }, (_, i) => feature(`b_${i}`, { feature_name_en: `Region ${i}` }, 40 + i));
  const [many] = parseEcccAlerts(JSON.stringify({ features: regions }), "eccc", NOW).alerts;
  assert.equal(many.polygons.length, 6);
  assert.equal(many.texts[0].areaName, null);
  // The newest publication sets the times.
  regions[3].properties.publication_datetime = "2026-10-23T11:00:00Z";
  const [newest] = parseEcccAlerts(JSON.stringify({ features: regions }), "eccc", NOW).alerts;
  assert.equal(newest.sent, "2026-10-23T11:00:00Z");

  assert.throws(() => parseEcccAlerts("{}", "eccc", NOW), /not an ECCC alerts collection/);
});

// ---- The ECCC source in an ingester run ----

const SOURCE = {
  id: "eccc",
  kind: "eccc",
  url: "https://api.example.ca/collections/weather-alerts/items?f=json",
  countries: ["CA"],
  attribution: { name: "Data Source: Environment and Climate Change Canada", url: "https://weather.example.ca" },
  disclaimer: { name: "ECCC", url: "https://eccc.example.ca/licence" },
};
const quiet = { info() {}, warn() {}, error() {} };

const run = (store, body, now = NOW) =>
  ingest({
    sources: [SOURCE],
    store,
    fetch: async () => (body === null ? new Response("down", { status: 503 }) : new Response(body, { status: 200 })),
    now: () => now,
    log: quiet,
  });

test("ECCC's alerts are published by tile", async () => {
  const store = fakeStore(() => NOW);
  const summary = await run(store, FEED);
  assert.equal(summary.alerts, 4);
  assert.equal(summary.sourceErrors, 0);
  const index = store.files.get(INDEX_PATH);
  assert.deepEqual(index.sources[0].countries, ["CA"]);
  // The Alberta region's square, and the border one's within the app's 1 km slack.
  const tile = store.files.get(tilePath("50/-114", index.tiles["50/-114"]));
  assert.deepEqual(tile.alerts.map((a) => a.id), [`eccc:${WIND}:AB`, `eccc:${WIND}:AB-SK`]);
});

test("a response that won't load, or is cut short, keeps the last alerts and says so", async () => {
  const store = fakeStore(() => NOW);
  await run(store, FEED);
  const failed = await run(store, null, new Date(NOW.getTime() + 300_000));
  assert.equal(failed.alerts, 4);
  assert.equal(store.files.get(INDEX_PATH).sources[0].error, "HTTP 503");

  // Cut short after a complete run: the last alerts stay, all four, though
  // the response held only the wind warning.
  const feed = JSON.parse(FEED);
  const short = JSON.stringify({ ...feed, features: feed.features.slice(0, 2), numberMatched: 9 });
  const cut = await run(store, short, new Date(NOW.getTime() + 600_000));
  assert.equal(cut.sourceErrors, 1);
  assert.equal(cut.alerts, 4);
  assert.equal(store.files.get(INDEX_PATH).sources[0].error, "7 regions not returned");
});
