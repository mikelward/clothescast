"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");

const { bundledRegion } = require("../lib/alerts/bundled-regions");

// Ray casting on the outer rings, enough to tell which county a city is in.
function contains(polygons, [lat, lon]) {
  return polygons.some(([outer]) => {
    let inside = false;
    for (let i = 0, j = outer.length - 1; i < outer.length; j = i++) {
      const [yi, xi] = outer[i];
      const [yj, xj] = outer[j];
      if ((yi > lat) !== (yj > lat) && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside;
    }
    return inside;
  });
}

test("Croatia's county codes resolve to the right counties, by EMMA_ID and NUTS3 alike", () => {
  const cities = {
    "EMMA_ID:HR031": [45.81, 15.98], // Grad Zagreb: Zagreb
    "EMMA_ID:HR021": [45.34, 17.68], // Požeško-slavonska: Požega (mislabeled in Natural Earth)
    "EMMA_ID:HR022": [45.16, 18.02], // Brodsko-posavska: Slavonski Brod
    "EMMA_ID:HR015": [46.3, 16.34], // Varaždinska: Varaždin
    "EMMA_ID:HR013": [45.48, 16.37], // Sisačko-moslavačka: Sisak
    "EMMA_ID:HR014": [45.49, 15.55], // Karlovačka: Karlovac
    "NUTS3:HR036": [44.87, 13.85], // Istarska: Pula
    "NUTS3:HR025": [45.55, 18.69], // Osječko-baranjska: Osijek
  };
  for (const [key, city] of Object.entries(cities)) {
    const shape = bundledRegion(key);
    assert.ok(shape, `${key} has a shape`);
    assert.ok(contains(shape, city), `${key} contains its city`);
  }
  assert.deepEqual(bundledRegion("NUTS3:HR050"), bundledRegion("EMMA_ID:HR031"));
  for (let n = 11; n <= 31; n++) assert.ok(bundledRegion(`EMMA_ID:HR0${n}`), `EMMA_ID:HR0${n}`);
  assert.equal(bundledRegion("EMMA_ID:HR001"), undefined);
});
