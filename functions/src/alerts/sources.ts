/**
 * The feeds the alerts ingester reads, each vetted for terms and area
 * precision before it's listed (docs/ALERTS.md → One integration for many
 * countries). A source covers whole countries: the app trusts the server's
 * answer for a country only when a listed source covers it.
 */

export interface AlertSource {
  /** Stable ID: part of every served alert's ID, so never reuse one. */
  id: string;
  /** A CAP Atom feed (an entry per alert in force, linking its CAP document). */
  kind: "cap-atom";
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

export const SOURCES: AlertSource[] = [
  meteoalarm("meteoalarm-gb", "united-kingdom", "GB", "Met Office"),
  meteoalarm("meteoalarm-no", "norway", "NO", "MET Norway"),
  meteoalarm("meteoalarm-se", "sweden", "SE", "SMHI"),
  meteoalarm("meteoalarm-ch", "switzerland", "CH", "MeteoSwiss"),
  meteoalarm("meteoalarm-si", "slovenia", "SI", "ARSO"),
  meteoalarm("meteoalarm-ua", "ukraine", "UA", "Ukrhydrometcenter"),
];
