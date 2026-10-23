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
   * CAP document. `cap-zip`: a zip of every alert in force, one CAP document
   * per file, named by its identifier (DWD). `nws`: the NWS active-alerts
   * GeoJSON, with zone shapes fetched from `api.weather.gov/zones` for
   * alerts without a polygon.
   */
  kind: "cap-atom" | "cap-zip" | "nws";
  /** How the source's CAP differs from the norm (served.ts → CapProfile). */
  profile?: "dwd";
  url: string;
  /** ISO 3166-1 alpha-2 codes of the countries the feed fully covers. */
  countries: string[];
  /** The credit and link the app shows beside each alert (the feed's terms). */
  attribution: { name: string; url: string };
  /**
   * The source's own disclaimer, which the app links after ClothesCast's
   * (maintainer, 2026-10-23: every source gets both), named for whoever
   * wrote it ("MeteoAlarm disclaimer"), which isn't always the issuer.
   * [text], where the source's terms require its wording shown, is shown
   * verbatim above the link.
   */
  disclaimer: { name: string; url: string; text?: string };
}

/** MeteoAlarm's terms §5, read 2026-10-23: "All redistributors must publish the following disclaimer". */
export const METEOALARM_DISCLAIMER =
  "Time delays between this website and the www.meteoalarm.org website are possible. For the most " +
  "up-to-date awareness information as published by the participating National Meteorological and " +
  "Hydrological Services, please refer to www.meteoalarm.org.";

// MeteoAlarm's terms (docs/ALERTS.md → Sources): credit the national
// service for one country's warnings and link to meteoalarm.org. Listed are
// the countries whose feeds draw polygons or name regions whose shapes
// MeteoAlarm publishes, by EMMA, NUTS or FIPS code (regions.ts). Left out
// (TODO.md): Germany, which comes from DWD and its own drawn areas; Croatia,
// whose feed names county regions the shapes lack; Bosnia and Herzegovina,
// whose feed is one entity's service; and Andorra and Israel, which have no
// shapes.
function meteoalarm(id: string, slug: string, country: string, issuer: string): AlertSource {
  return {
    id,
    kind: "cap-atom",
    url: `https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-${slug}`,
    countries: [country],
    attribution: { name: `${issuer} via MeteoAlarm`, url: "https://meteoalarm.org" },
    // Its terms (§5) require every redistributor to publish this wording;
    // its own liability exclusions are in the same terms.
    disclaimer: {
      name: "MeteoAlarm",
      url: "https://www.meteoalarm.org/en/live/page/terms-and-conditions",
      text: METEOALARM_DISCLAIMER,
    },
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
  disclaimer: { name: "NWS", url: "https://www.weather.gov/disclaimer" },
};

// CC BY 4.0 with the source named (dwd.de legal notice); the warning areas
// are drawn on the federal mapping agency's data, which each document
// credits as "© GeoBasis-DE / BKG". The commune-level file carries the
// drawn warning polygons, with islands and lakes left out as holes, and
// every warning in German, English, French, Spanish, Arabic, Russian,
// Turkish and Polish.
const DWD: AlertSource = {
  id: "dwd",
  kind: "cap-zip",
  profile: "dwd",
  url:
    "https://opendata.dwd.de/weather/alerts/cap/COMMUNEUNION_DWD_STAT/" +
    "Z_CAP_C_EDZW_LATEST_PVW_STATUS_PREMIUMDWD_COMMUNEUNION_MUL.zip",
  countries: ["DE"],
  attribution: { name: "Deutscher Wetterdienst, © GeoBasis-DE / BKG", url: "https://www.dwd.de" },
  disclaimer: { name: "DWD", url: "https://www.dwd.de/EN/service/legal_notice/legal_notice_node.html" },
};

export const SOURCES: AlertSource[] = [
  meteoalarm("meteoalarm-gb", "united-kingdom", "GB", "Met Office"),
  meteoalarm("meteoalarm-no", "norway", "NO", "MET Norway"),
  meteoalarm("meteoalarm-se", "sweden", "SE", "SMHI"),
  meteoalarm("meteoalarm-ch", "switzerland", "CH", "MeteoSwiss"),
  meteoalarm("meteoalarm-si", "slovenia", "SI", "ARSO"),
  meteoalarm("meteoalarm-ua", "ukraine", "UA", "Ukrhydrometcenter"),
  meteoalarm("meteoalarm-at", "austria", "AT", "GeoSphere Austria"),
  meteoalarm("meteoalarm-ee", "estonia", "EE", "Estonian Environment Agency"),
  meteoalarm("meteoalarm-fi", "finland", "FI", "Finnish Meteorological Institute"),
  meteoalarm("meteoalarm-gr", "greece", "GR", "HNMS"),
  meteoalarm("meteoalarm-it", "italy", "IT", "Aeronautica Militare"),
  meteoalarm("meteoalarm-lv", "latvia", "LV", "LVĢMC"),
  meteoalarm("meteoalarm-lt", "lithuania", "LT", "Lithuanian Hydrometeorological Service"),
  meteoalarm("meteoalarm-lu", "luxembourg", "LU", "MeteoLux"),
  meteoalarm("meteoalarm-md", "moldova", "MD", "Meteorology and Environmental Monitoring Authority"),
  meteoalarm("meteoalarm-me", "montenegro", "ME", "Institute of Hydrometeorology and Seismology"),
  meteoalarm("meteoalarm-nl", "netherlands", "NL", "KNMI"),
  meteoalarm("meteoalarm-pt", "portugal", "PT", "IPMA"),
  meteoalarm("meteoalarm-rs", "serbia", "RS", "RHMSS"),
  meteoalarm("meteoalarm-es", "spain", "ES", "AEMET"),
  meteoalarm("meteoalarm-fr", "france", "FR", "Météo-France"),
  meteoalarm("meteoalarm-ie", "ireland", "IE", "Met Éireann"),
  meteoalarm("meteoalarm-be", "belgium", "BE", "RMI"),
  meteoalarm("meteoalarm-bg", "bulgaria", "BG", "NIMH"),
  meteoalarm("meteoalarm-hu", "hungary", "HU", "HungaroMet"),
  meteoalarm("meteoalarm-mk", "republic-of-north-macedonia", "MK", "National Hydrometeorological Service"),
  meteoalarm("meteoalarm-ro", "romania", "RO", "ANM"),
  meteoalarm("meteoalarm-cy", "cyprus", "CY", "Cyprus Department of Meteorology"),
  meteoalarm("meteoalarm-cz", "czechia", "CZ", "CHMI"),
  meteoalarm("meteoalarm-dk", "denmark", "DK", "DMI"),
  meteoalarm("meteoalarm-is", "iceland", "IS", "Icelandic Met Office"),
  meteoalarm("meteoalarm-mt", "malta", "MT", "Malta Met Office"),
  meteoalarm("meteoalarm-pl", "poland", "PL", "IMGW-PIB"),
  meteoalarm("meteoalarm-sk", "slovakia", "SK", "SHMU"),
  NWS,
  DWD,
];
