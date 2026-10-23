"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");

const { parseCapAlert, parseCapFeed } = require("../lib/alerts/cap");
const { METSERVICE_PROFILE } = require("../lib/alerts/metservice");
const { serve } = require("../lib/alerts/served");
const { SOURCES } = require("../lib/alerts/sources");
const { INDEX_PATH, ingest, tilePath } = require("../lib/alerts/ingest");
const { fakeStore } = require("./support/fake-store");

// MetService's feed and two of its CAP documents from 2026-10-24, their
// polygons replaced by 0.5° squares and their signatures dropped: an orange
// heavy rain warning, and a yellow watch over two areas.
const fixture = (name) => readFileSync(path.join(__dirname, "fixtures", name), "utf8");
const FEED = fixture("metservice-feed.atom");
const WARNING = fixture("metservice-warning.xml");
const WATCH = fixture("metservice-watch.xml");
const WARNING_ID = "2.49.0.0.554.0.severeweather.nz.20261023211847263.7";
const WATCH_ID = "2.49.0.0.554.0.severeweather.nz.20261023211847263.3";
const NOW = new Date("2026-10-24T00:00:00Z");

test("MetService's feed entries are read by their <id>, linking each CAP document", () => {
  assert.deepEqual(parseCapFeed(FEED), [
    {
      identifier: WARNING_ID,
      updated: "2026-10-24T10:18:47+13:00",
      capUrl: `https://alerts.metservice.com/cap/alert?id=${WARNING_ID}`,
    },
    {
      identifier: WATCH_ID,
      updated: "2026-10-24T10:18:47+13:00",
      capUrl: `https://alerts.metservice.com/cap/alert?id=${WATCH_ID}`,
    },
  ]);
});

test("a MetService alert is graded by its color and titled by its headline", () => {
  const warning = serve("metservice", parseCapAlert(WARNING), NOW, METSERVICE_PROFILE);
  assert.equal(warning.id, `metservice:${WARNING_ID}`);
  assert.equal(warning.severity, "SEVERE", "orange, though its CAP severity is Moderate");
  assert.equal(warning.eventType, "RAIN");
  assert.equal(warning.issuer, "Meteorological Service of New Zealand Limited");
  assert.equal(warning.onset, "2026-10-26T15:00:00+13:00");
  assert.equal(warning.expires, "2026-10-27T03:00:00+13:00");
  const [text] = warning.texts;
  assert.equal(text.title, "Heavy Rain Warning - Orange");
  assert.match(text.description, /^Expect 150 to 200 mm of rain about the main divide/);
  assert.equal(text.instruction, "Action: Clear your drains and gutters to prepare for heavy rain. Avoid low-lying areas and drive cautiously.");
  assert.equal(text.web, "https://metservice.com/warnings/home");

  const watch = serve("metservice", parseCapAlert(WATCH), NOW, METSERVICE_PROFILE);
  assert.equal(watch.severity, "MODERATE", "yellow, though its CAP severity is Minor");
  assert.equal(watch.texts[0].title, "Heavy Rain Watch");
  assert.equal(watch.polygons.length, 2);

  const colour = (value) => {
    const cap = parseCapAlert(WARNING.replace("<value>Orange</value>", value === null ? "" : `<value>${value}</value>`));
    return serve("metservice", cap, NOW, METSERVICE_PROFILE).severity;
  };
  assert.equal(colour("Red"), "EXTREME");
  assert.equal(colour("Purple"), "MODERATE", "an unknown color falls back to the CAP severity");
  assert.equal(colour(null), "MODERATE");
});

test("MetService's alerts are published by tile, credited under its licence", async () => {
  const source = SOURCES.find((s) => s.id === "metservice");
  const documents = {
    [source.url]: FEED,
    [`https://alerts.metservice.com/cap/alert?id=${WARNING_ID}`]: WARNING,
    [`https://alerts.metservice.com/cap/alert?id=${WATCH_ID}`]: WATCH,
  };
  const store = fakeStore(() => NOW);
  const summary = await ingest({
    sources: [source],
    store,
    fetch: async (url) => new Response(documents[url] ?? "not found", { status: url in documents ? 200 : 404 }),
    now: () => NOW,
    log: { info() {}, warn() {}, error() {} },
  });
  assert.equal(summary.alerts, 2);
  assert.equal(summary.sourceErrors, 0);
  const index = store.files.get(INDEX_PATH);
  assert.deepEqual(index.sources[0].countries, ["NZ"]);
  assert.deepEqual(index.sources[0].attribution, { name: "MetService, CC BY 4.0", url: "https://metservice.com" });
  const tile = store.files.get(tilePath("-44/170", index.tiles["-44/170"]));
  assert.deepEqual(tile.alerts.map((a) => [a.id, a.severity, a.texts[0].title]), [
    [`metservice:${WARNING_ID}`, "SEVERE", "Heavy Rain Warning - Orange"],
  ]);
});
