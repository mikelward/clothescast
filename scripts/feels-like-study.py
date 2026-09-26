#!/usr/bin/env python3
"""Does Google's feels-like track sun and cloud, and if so which metric?

Two subcommands, standard library only:

  log      Fetch Google's hourly forecast and Open-Meteo's for the same place,
           join them hour by hour (UTC), and append the rows to a CSV.
           Run it a few times a day for a week or two to cover varied weather.

  analyze  Read the CSV and report how well each sun / cloud metric explains
           the gap between Google's feels-like and its air temperature, after
           wind and humidity have already had their say.

Usage:
  export GOOGLE_WEATHER_API_KEY=...        # key with the Weather API enabled
  ./feels-like-study.py log --lat <lat> --lon <lon> --csv feels-like-study.csv
  ./feels-like-study.py analyze --csv feels-like-study.csv [--max-lead 48] [--day-only]

The CSV holds forecasts for your location, so keep it on your machine. Only the
fitted summary that `analyze` prints is meant to be shared.

Cost and reliability of `log`:
  - Each run makes 10 Google Weather API requests (240 hours in 24-hour
    pages) and 1 Open-Meteo request, one after another, taking a few seconds.
    Four runs a day is about 1,200 Google requests and 120 Open-Meteo requests
    a month.
  - Google bills those requests to your own key's Cloud project. Check the
    Weather API's current price and free monthly allowance on its Google Cloud
    pricing page before scheduling runs, and set a daily quota cap on the API
    in the Cloud console so a runaway schedule can't cost more than you chose.
  - Open-Meteo is free and keyless; its free tier is soft-capped at 10,000
    requests a day, far above this usage.
  - Either service failing (an HTTP error, a quota or rate-limit rejection, a
    timeout after 30 s) stops the run with the service's host and status
    before anything is written, so a failed run leaves the CSV untouched and
    the next scheduled run simply tries again.
"""

import argparse
import csv
import json
import math
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

GOOGLE_URL = "https://weather.googleapis.com/v1/forecast/hours:lookup"
OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"

# Open-Meteo hourly variables. The *_instant radiation values are the reading
# at the timestamp; the plain ones average the preceding hour. Google's hour
# starts at its timestamp, so instant is the closer match — both are kept so
# the analysis can say which lines up better.
OM_VARS = [
    "temperature_2m",
    "apparent_temperature",
    "relative_humidity_2m",
    "wind_speed_10m",
    "shortwave_radiation",
    "shortwave_radiation_instant",
    "direct_radiation_instant",
    "diffuse_radiation_instant",
    "direct_normal_irradiance_instant",
    "sunshine_duration",
    "uv_index",
    "cloud_cover",
    "cloud_cover_low",
    "cloud_cover_mid",
    "cloud_cover_high",
    "is_day",
]

G_FIELDS = [
    "g_air", "g_feels", "g_heat_index", "g_wind_chill", "g_dew_point",
    "g_humidity", "g_wind_kmh", "g_cloud", "g_uv", "g_is_day",
]
CSV_FIELDS = ["fetched_at", "hour_utc", "lead_h"] + G_FIELDS + ["om_" + v for v in OM_VARS]

# Candidate explanations for Google's feels-like offset, tested one at a time
# after the baseline below has explained what it can.
CANDIDATES = [
    "om_shortwave_radiation_instant",
    "om_shortwave_radiation",
    "om_direct_radiation_instant",
    "om_diffuse_radiation_instant",
    "om_direct_normal_irradiance_instant",
    "om_sunshine_duration",
    "om_uv_index",
    "g_uv",
    "om_cloud_cover",
    "om_cloud_cover_low",
    "om_cloud_cover_mid",
    "om_cloud_cover_high",
    "g_cloud",
]


def get_json(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        # Report the status and host only: the Google key travels in a header,
        # but the body can echo request details we don't want in a terminal log.
        sys.exit(f"{urllib.parse.urlsplit(url).netloc} returned HTTP {e.code}")
    except (urllib.error.URLError, TimeoutError) as e:
        reason = getattr(e, "reason", e)
        sys.exit(f"{urllib.parse.urlsplit(url).netloc} unreachable: {reason}")


def fetch_google(lat, lon, key):
    hours, token = [], None
    for _ in range(10):  # 10 pages x 24 h = the 240 h Google serves
        params = {
            "location.latitude": lat,
            "location.longitude": lon,
            "hours": 240,
            "pageSize": 24,
            "unitsSystem": "METRIC",
        }
        if token:
            params["pageToken"] = token
        data = get_json(GOOGLE_URL + "?" + urllib.parse.urlencode(params),
                        {"X-Goog-Api-Key": key})
        hours += data.get("forecastHours", [])
        token = data.get("nextPageToken")
        if not token:
            break
    out = {}
    for h in hours:
        start = (h.get("interval") or {}).get("startTime")
        if not start:
            continue
        t = datetime.fromisoformat(start.replace("Z", "+00:00")).astimezone(timezone.utc)
        deg = lambda k: (h.get(k) or {}).get("degrees")
        out[t.strftime("%Y-%m-%dT%H:00")] = {
            "g_air": deg("temperature"),
            "g_feels": deg("feelsLikeTemperature"),
            "g_heat_index": deg("heatIndex"),
            "g_wind_chill": deg("windChill"),
            "g_dew_point": deg("dewPoint"),
            "g_humidity": h.get("relativeHumidity"),
            "g_wind_kmh": ((h.get("wind") or {}).get("speed") or {}).get("value"),
            "g_cloud": h.get("cloudCover"),
            "g_uv": h.get("uvIndex"),
            "g_is_day": 1 if h.get("isDaytime") else 0,
        }
    return out


def fetch_open_meteo(lat, lon):
    params = {
        "latitude": lat,
        "longitude": lon,
        "hourly": ",".join(OM_VARS),
        "timezone": "GMT",
        "forecast_days": 10,
        "wind_speed_unit": "kmh",
    }
    data = get_json(OPEN_METEO_URL + "?" + urllib.parse.urlencode(params))
    hourly = data["hourly"]
    out = {}
    for i, t in enumerate(hourly["time"]):
        out[t] = {"om_" + v: hourly[v][i] for v in OM_VARS}
    return out


def cmd_log(args):
    key = os.environ.get("GOOGLE_WEATHER_API_KEY")
    if not key:
        sys.exit("Set GOOGLE_WEATHER_API_KEY first.")
    google = fetch_google(args.lat, args.lon, key)
    om = fetch_open_meteo(args.lat, args.lon)
    now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    rows = []
    for hour in sorted(set(google) & set(om)):
        lead = (datetime.fromisoformat(hour).replace(tzinfo=timezone.utc) - now).total_seconds() / 3600
        if lead < 0:
            continue
        rows.append({"fetched_at": now.strftime("%Y-%m-%dT%H:00"), "hour_utc": hour,
                     "lead_h": int(lead), **google[hour], **om[hour]})
    if not rows:
        sys.exit(f"No overlapping hours (Google {len(google)}, Open-Meteo {len(om)}).")
    # An existing but empty file (from mktemp, say) still needs the header.
    new_file = not os.path.exists(args.csv) or os.path.getsize(args.csv) == 0
    with open(args.csv, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        if new_file:
            w.writeheader()
        w.writerows(rows)
    print(f"Appended {len(rows)} hours to {args.csv}.")


# --- analysis -------------------------------------------------------------

def num(v):
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    return None if math.isnan(x) else x


def load(args):
    rows = []
    with open(args.csv, newline="") as f:
        for r in csv.DictReader(f):
            r = {k: num(v) if k not in ("fetched_at", "hour_utc") else v for k, v in r.items()}
            if r["g_air"] is None or r["g_feels"] is None:
                continue
            if args.max_lead is not None and (r["lead_h"] or 0) > args.max_lead:
                continue
            if args.day_only and not r.get("om_is_day"):
                continue
            rows.append(r)
    return rows


class Baseline:
    """The span of an intercept plus the baseline columns, for removing from
    any series what those columns can explain.

    Built by Gram-Schmidt, twice over each column for numerical stability.
    A column that adds nothing new (constant, like a heat-index offset that is
    zero in mild weather, or a combination of the others) is dropped rather
    than making the fit singular.
    """

    def __init__(self, X):
        n = len(X)
        self.basis = []
        for col in [[1.0] * n] + [list(c) for c in zip(*X)] if X and X[0] else [[1.0] * n]:
            norm0 = math.sqrt(sum(v * v for v in col))
            v = col
            for _ in range(2):
                for q in self.basis:
                    d = sum(a * b for a, b in zip(q, v))
                    v = [a - d * b for a, b in zip(v, q)]
            norm = math.sqrt(sum(a * a for a in v))
            if norm > 1e-9 * max(norm0, 1e-12):
                self.basis.append([a / norm for a in v])

    def residual(self, v):
        for _ in range(2):
            for q in self.basis:
                d = sum(a * b for a, b in zip(q, v))
                v = [a - d * b for a, b in zip(v, q)]
        return v


def r_squared(y, resid):
    mean = sum(y) / len(y)
    ss_tot = sum((v - mean) ** 2 for v in y)
    return 0.0 if ss_tot == 0 else 1 - sum(e * e for e in resid) / ss_tot


def partial_effect(base, x, y):
    """How [x] relates to [y] once [base] has explained what it can of both.

    Residualizing both sides (Frisch-Waugh-Lovell) gives the same slope as
    adding x to the baseline regression, plus a correlation that isn't
    diluted by the part of x the baseline already accounts for. Returns
    (R² gain, partial r, slope), or None when the baseline explains x
    entirely and there is nothing left to test.
    """
    rx, ry = base.residual(x), base.residual(y)
    sxx = sum(a * a for a in rx)
    syy = sum(b * b for b in ry)
    mean = sum(y) / len(y)
    ss_tot = sum((v - mean) ** 2 for v in y)
    if sxx <= 1e-12 * max(1.0, sum(a * a for a in x)) or ss_tot == 0:
        return None
    sxy = sum(a * b for a, b in zip(rx, ry))
    r = 0.0 if syy == 0 else sxy / math.sqrt(sxx * syy)
    return sxy * sxy / sxx / ss_tot, r, sxy / sxx


def conventional_offset(r):
    """Google's own heat index or wind chill minus its air temperature.

    Whichever of the two departs from air is the conventional feels-like for
    that hour; when neither does (mild weather, or the field is missing), the
    conventional offset is zero.
    """
    air, hi, wc = r["g_air"], r.get("g_heat_index"), r.get("g_wind_chill")
    if hi is not None and hi > air + 0.05:
        return hi - air
    if wc is not None and wc < air - 0.05:
        return wc - air
    return 0.0


def conventional_terms(air, wind, humidity):
    """Air, wind and humidity plus their pairwise products, so the baseline
    can absorb the curvature heat-index and wind-chill formulas have."""
    return [air, wind, humidity, air * wind, air * humidity, wind * humidity]


def google_baseline(r):
    """Everything a conventional feels-like formula could use, taken from
    Google's own values. Sunny hours are usually warmer, so leaving air
    temperature or the heat index out would let sunlight take credit for
    what is really plain heat-index maths."""
    if r.get("g_wind_kmh") is None or r.get("g_humidity") is None:
        return None
    return [conventional_offset(r)] + conventional_terms(r["g_air"], r["g_wind_kmh"], r["g_humidity"])


def open_meteo_baseline(r):
    if any(r.get(k) is None for k in ("om_temperature_2m", "om_wind_speed_10m", "om_relative_humidity_2m")):
        return None
    return conventional_terms(r["om_temperature_2m"], r["om_wind_speed_10m"], r["om_relative_humidity_2m"])


def cmd_analyze(args):
    rows = load(args)
    if len(rows) < 30:
        sys.exit(f"Only {len(rows)} usable hours; log more before reading anything into it.")
    days = len({r["hour_utc"][:10] for r in rows})
    print(f"{len(rows)} hours across {days} days"
          + (f", lead <= {args.max_lead} h" if args.max_lead is not None else "")
          + (", daytime only" if args.day_only else "") + ".\n")

    # 1. Is Google's feels-like just a switch between air, heat index and wind
    #    chill? If it matches one of those almost every hour, there is no room
    #    for a sun term and any correlation below is incidental.
    def close(a, b):
        return a is not None and b is not None and abs(a - b) < 0.05
    match = {"air": 0, "heat index": 0, "wind chill": 0, "none of these": 0}
    for r in rows:
        if close(r["g_feels"], r["g_air"]):
            match["air"] += 1
        elif close(r["g_feels"], r["g_heat_index"]):
            match["heat index"] += 1
        elif close(r["g_feels"], r["g_wind_chill"]):
            match["wind chill"] += 1
        else:
            match["none of these"] += 1
    print("Google feels-like equals (to 0.05 °C):")
    for k, v in match.items():
        print(f"  {k:<14} {100 * v / len(rows):5.1f}%")
    print()

    # 2. Target: how far Google's feels-like sits from its own air temperature.
    #    Remove what the conventional inputs explain, then see which sun /
    #    cloud metric explains what's left.
    base_rows = [r for r in rows if google_baseline(r) is not None]
    if len(base_rows) < 30:
        sys.exit(f"Only {len(base_rows)} hours carry Google's wind and humidity; log more first.")
    y = [r["g_feels"] - r["g_air"] for r in base_rows]
    base_r2 = r_squared(y, Baseline([google_baseline(r) for r in base_rows]).residual(y))
    print("Google's heat index / wind chill, air, wind and humidity explain "
          f"R² = {base_r2:.3f} of the feels-like offset.\n")

    results, skipped = [], []
    for c in CANDIDATES:
        sub = [r for r in base_rows if r.get(c) is not None]
        if len(sub) < 30:
            skipped.append(f"{c} (only {len(sub)} hours carry it)")
            continue
        effect = partial_effect(Baseline([google_baseline(r) for r in sub]),
                                [r[c] for r in sub], [r["g_feels"] - r["g_air"] for r in sub])
        if effect is None:
            skipped.append(f"{c} (fully explained by the baseline, so its effect can't be separated)")
            continue
        gain, r, slope = effect
        results.append((gain, c, r, slope, len(sub)))

    results.sort(reverse=True)
    print(f"{'metric':<38}{'R² gain':>8}{'r(resid)':>10}{'slope':>12}{'n':>7}")
    for gain, c, r, slope, n in results:
        if "radiation" in c or "irradiance" in c:
            unit, s = "°C per 100 W/m²", slope * 100
        elif "sunshine" in c:
            unit, s = "°C per sunny hour", slope * 3600
        elif "cloud" in c:
            unit, s = "°C per 100% cover", slope * 100
        else:
            unit, s = "°C per UV point", slope
        print(f"{c:<38}{gain:8.3f}{r:10.3f}{s:12.3f}{n:7d}  {unit}")
    for note in skipped:
        print(f"skipped: {note}")
    print("\nR² gain: extra share of the offset explained on top of the baseline.")
    print("r(resid): correlation between what the baseline leaves unexplained in the")
    print("          offset and in the metric itself.")
    print("Judge by r(resid) and slope: |r| under ~0.2 or an effect under ~0.2 °C at")
    print("typical values is noise. R² gain stays small whenever the heat index")
    print("dominates the offset, so a low gain alone doesn't rule a metric out.")

    # 3. Open-Meteo's own feels-like, for comparison: does it already carry
    #    the same sun effect? If its offset tracks the winning metric just as
    #    strongly, boosting it would double-count.
    if results:
        best = results[0][1]
        sub = [r for r in rows if r.get(best) is not None
               and r.get("om_apparent_temperature") is not None
               and open_meteo_baseline(r) is not None]
        if len(sub) >= 30:
            om_y = [r["om_apparent_temperature"] - r["om_temperature_2m"] for r in sub]
            effect = partial_effect(Baseline([open_meteo_baseline(r) for r in sub]),
                                    [r[best] for r in sub], om_y)
            if effect is not None:
                print(f"\nOpen-Meteo's own offset vs {best}: r(resid) = {effect[1]:.3f}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    lg = sub.add_parser("log")
    lg.add_argument("--lat", type=float, required=True)
    lg.add_argument("--lon", type=float, required=True)
    lg.add_argument("--csv", default="feels-like-study.csv")
    an = sub.add_parser("analyze")
    an.add_argument("--csv", default="feels-like-study.csv")
    an.add_argument("--max-lead", type=int, default=None,
                    help="ignore hours forecast more than this many hours ahead")
    an.add_argument("--day-only", action="store_true", help="daytime hours only")
    args = p.parse_args()
    {"log": cmd_log, "analyze": cmd_analyze}[args.cmd](args)


if __name__ == "__main__":
    main()
