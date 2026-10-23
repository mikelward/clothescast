#!/usr/bin/env node
// Builds src/alerts/croatia-regions.json, the county shapes behind the
// region codes Croatia's MeteoAlarm feed names but MeteoAlarm's own shapes
// lack (docs/ALERTS.md → MeteoAlarm regions), from Natural Earth's public
// domain admin-1 boundaries. Run from functions/ after `npm run build`:
//
//   curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_1_states_provinces.geojson
//   node scripts/make-croatia-regions.js ne_10m_admin_1_states_provinces.geojson
"use strict";
const { readFileSync, writeFileSync } = require("node:fs");
const path = require("node:path");
const { geoJsonPolygons, simplifyRing } = require("../lib/alerts/geo");
const { ZONE_TOLERANCE } = require("../lib/alerts/ingest");

// ISO 3166-2:HR county number → the feed's NUTS 2021 code. Its EMMA_ID is
// "HR0" + (10 + the number): HR-05 Varaždinska is EMMA_ID HR015.
const NUTS3 = {
  1: "HR065", 2: "HR064", 3: "HR028", 4: "HR027", 5: "HR062", 6: "HR063", 7: "HR021",
  8: "HR031", 9: "HR032", 10: "HR022", 11: "HR023", 12: "HR024", 13: "HR033", 14: "HR025",
  15: "HR034", 16: "HR026", 17: "HR035", 18: "HR036", 19: "HR037", 20: "HR061", 21: "HR050",
};

const source = process.argv[2];
if (!source) throw new Error("usage: make-croatia-regions.js <ne_10m_admin_1_states_provinces.geojson>");
const features = JSON.parse(readFileSync(source, "utf8")).features
  .filter((f) => f.properties.adm0_a3 === "HRV");

const counties = {};
for (const feature of features) {
  let iso = feature.properties.iso_3166_2;
  const polygons = geoJsonPolygons(feature.geometry);
  // Natural Earth labels Požega-Slavonia (HR-11) as a second Brod-Posavina
  // (HR-12); the two border each other, Požega's to the north.
  if (iso === "HR-12" && Math.max(...polygons[0][0].map(([lat]) => lat)) > 45.6) iso = "HR-11";
  if (counties[iso]) throw new Error(`two shapes for ${iso}`);
  counties[iso] = polygons.map((rings) => rings.map((ring) => simplifyRing(ring, ZONE_TOLERANCE)));
}
const keys = {};
for (const [number, nuts] of Object.entries(NUTS3)) {
  const iso = `HR-${String(number).padStart(2, "0")}`;
  if (!counties[iso]) throw new Error(`no shape for ${iso}`);
  keys[`EMMA_ID:HR0${10 + Number(number)}`] = iso;
  keys[`NUTS3:${nuts}`] = iso;
}
if (Object.keys(counties).length !== 21) throw new Error(`expected 21 counties, got ${Object.keys(counties).length}`);

const round = (polygons) => polygons.map((rings) => rings.map((ring) => ring.map(([lat, lon]) => [+lat.toFixed(4), +lon.toFixed(4)])));
const out = {
  source: "Natural Earth 1:10m admin-1 states and provinces (public domain)",
  keys,
  counties: Object.fromEntries(Object.entries(counties).sort().map(([iso, p]) => [iso, round(p)])),
};
const target = path.join(__dirname, "../src/alerts/croatia-regions.json");
writeFileSync(target, JSON.stringify(out) + "\n");
console.log(`wrote ${target}: ${Object.keys(keys).length} keys, ${Object.keys(counties).length} counties`);
