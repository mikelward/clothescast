/**
 * New Zealand's MetService, read from its CAP feed (docs/ALERTS.md →
 * Sources): an Atom entry per watch and warning in force, each linking its
 * CAP document, with a drawn polygon. Pure.
 *
 * MetService grades by color and sends it as the `ColourCode` parameter,
 * while its CAP severity sits a step below the color it shows (a yellow
 * watch is `Minor`, an orange warning `Moderate`), so the color is read
 * instead, as MeteoAlarm's is. Its `<event>` is the bare hazard ("rain"),
 * so the title is its headline ("Heavy Rain Warning - Orange").
 */
import type { CapInfo } from "./cap";
import type { CapProfile, Severity } from "./served";

const COLOURS: Record<string, Severity> = {
  yellow: "MODERATE",
  orange: "SEVERE",
  red: "EXTREME",
};

export const METSERVICE_PROFILE: CapProfile = {
  severity: (info: CapInfo) => {
    const colour = info.parameters.ColourCode?.[0]?.trim().toLowerCase();
    return colour ? COLOURS[colour] ?? null : null;
  },
  title: (info: CapInfo) => info.headline,
};
