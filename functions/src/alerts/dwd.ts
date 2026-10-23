/**
 * Germany's Deutscher Wetterdienst (DWD), read from its open-data CAP:
 * a zip of every warning in force (docs/ALERTS.md → Sources). Pure.
 *
 * DWD grades one step lower than CAP's usual meaning: its yellow
 * "Wetterwarnung" is `Minor`, orange `Moderate`, red `Severe` and violet
 * `Extreme`, so they're read up a step to match the colors it shows.
 * The hazard comes from the `GROUP` event code.
 */
import type { CapInfo } from "./cap";
import type { CapProfile, Severity } from "./served";

const SEVERITIES: Record<string, Severity> = {
  Minor: "MODERATE",
  Moderate: "SEVERE",
  Severe: "EXTREME",
  Extreme: "EXTREME",
};

// DWD's warning groups as the app's hazard keywords; the rest (UV, power
// line vibration) keep their own name.
const GROUPS: Record<string, string> = {
  THUNDERSTORM: "THUNDERSTORM",
  WIND: "WIND",
  TORNADO: "TORNADO",
  RAIN: "RAIN",
  HAIL: "HAIL",
  SNOWFALL: "SNOW_AND_ICE",
  SNOWDRIFT: "SNOW_AND_ICE",
  GLAZE: "SNOW_AND_ICE",
  FOG: "FOG",
  FROST: "COLD",
  THAW: "FLOOD",
  HEAT: "HEAT",
};

/** The drawn warning area's name in DWD's commune-level files, not a place. */
const EVENT_AREA = "polygonal event area";

/** Beyond this many places, the list is left out: the polygon is the area. */
const MAX_AREA_NAMES = 5;

export const DWD_PROFILE: CapProfile = {
  severity: (info: CapInfo) => (info.severity ? SEVERITIES[info.severity.trim()] ?? null : null),
  eventType: (info: CapInfo) => {
    const group = info.eventCodes.GROUP?.[0]?.trim().toUpperCase();
    return group ? GROUPS[group] ?? group : null;
  },
  // The commune-level files list every commune warned, often thousands.
  areaName: (info: CapInfo) => {
    const names = info.areas.map((a) => a.areaDesc).filter((n): n is string => !!n && n !== EVENT_AREA);
    return names.length > 0 && names.length <= MAX_AREA_NAMES ? names.join("; ") : null;
  },
};
