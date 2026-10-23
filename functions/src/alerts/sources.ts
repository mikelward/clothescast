/**
 * The feeds the alerts ingester reads, each vetted for terms and area
 * precision before it's listed (docs/ALERTS.md → One integration for many
 * countries). A source covers whole countries: the app trusts the server's
 * answer for a country only when a listed source covers it.
 */

export interface AlertSource {
  /** Stable ID: part of every served alert's ID, so never reuse one. */
  id: string;
  /**
   * `cap-atom`: an Atom feed with an entry per alert in force, linking its
   * CAP document. `nws`: the NWS active-alerts GeoJSON, with zone shapes
   * fetched from `api.weather.gov/zones` for alerts without a polygon.
   */
  kind: "cap-atom" | "nws";
  url: string;
  /** ISO 3166-1 alpha-2 codes of the countries the feed fully covers. */
  countries: string[];
  /** The credit and link the app shows beside each alert (the feed's terms). */
  attribution: { name: string; url: string };
}

// MeteoAlarm's terms (docs/ALERTS.md → Sources): credit the national
// service for one country's warnings and link to meteoalarm.org. Only the
// countries whose feeds carry polygons are listed; the rest name EMMA
// regions, which need boundary data first.
function meteoalarm(id: string, slug: string, country: string, issuer: string): AlertSource {
  return {
    id,
    kind: "cap-atom",
    url: `https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-${slug}`,
    countries: [country],
    attribution: { name: `${issuer} via MeteoAlarm`, url: "https://meteoalarm.org" },
  };
}

// Public domain, but don't imply endorsement or present altered text as
// official (docs/ALERTS.md → Sources); credited and linked anyway. Only
// actual alerts and their updates; marine-only ones are dropped when read.
const NWS: AlertSource = {
  id: "nws",
  kind: "nws",
  url: "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update",
  // The 50 states and DC, and the territories NWS forecasts for.
  countries: ["US", "PR", "VI", "GU", "AS", "MP"],
  attribution: { name: "US National Weather Service", url: "https://www.weather.gov" },
};

export const SOURCES: AlertSource[] = [
  meteoalarm("meteoalarm-gb", "united-kingdom", "GB", "Met Office"),
  meteoalarm("meteoalarm-no", "norway", "NO", "MET Norway"),
  meteoalarm("meteoalarm-se", "sweden", "SE", "SMHI"),
  meteoalarm("meteoalarm-ch", "switzerland", "CH", "MeteoSwiss"),
  meteoalarm("meteoalarm-si", "slovenia", "SI", "ARSO"),
  meteoalarm("meteoalarm-ua", "ukraine", "UA", "Ukrhydrometcenter"),
  NWS,
];
