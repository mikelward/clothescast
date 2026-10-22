#!/usr/bin/env bash
# Asks Google's Weather API for the public alerts active at a list of test
# cities, using the weather proxy's Maps Platform key, and prints what comes
# back: the region, then per alert its severity, event type, issuing agency,
# title, validity, area name, and its polygon's size, point count and whether
# it contains the test city (the app must keep only alerts that do), then
# every field the alert carries and the start of its description, to find
# when a warning takes effect (Google's startTime looks like the issue time).
# See docs/ALERTS.md → Coverage and → Location.
#
# An empty answer means no active alert *or* no coverage there. Run it while a
# national warning map shows something at a test point to tell them apart.
#
# Usage: functions/scripts/check-alert-coverage.sh <projectId> [outDir]
# With outDir, each raw response is also saved as <outDir>/<city>.json.
# Needs gcloud (with access to the GOOGLE_WEATHER_API_KEY secret), curl and
# jq; Cloud Shell has all three. About 30 calls, well inside the free tier.
set -euo pipefail

project="${1:?usage: $0 <projectId> [outDir]}"
out_dir="${2:-}"
[[ -n "$out_dir" ]] && mkdir -p "$out_dir"

key="$(gcloud secrets versions access latest --secret=GOOGLE_WEATHER_API_KEY --project "$project")"

# Header from a file descriptor, so the key never appears in the process list.
lookup() {
  curl -sS -G "https://weather.googleapis.com/v1/publicAlerts:lookup" \
    -H @<(printf 'X-Goog-Api-Key: %s\n' "$key") \
    --data-urlencode "location.latitude=$1" \
    --data-urlencode "location.longitude=$2" \
    --data-urlencode "languageCode=en"
}

while IFS=, read -r city lat lon; do
  body="$(lookup "$lat" "$lon")" || { echo "$city: request failed"; continue; }
  [[ -n "$out_dir" ]] && printf '%s\n' "$body" > "$out_dir/${city// /_}.json"
  printf '%s\n' "$body" | jq -r --arg city "$city" --argjson lat "$lat" --argjson lon "$lon" '
    # Every ring (outer boundary or hole) in the GeoJSON, as [lon, lat] pairs.
    def rings: [.. | arrays
      | select(length > 0 and (.[0] | type) == "array" and (.[0][0] | type) == "number")];
    # Even-odd ray cast across all rings, so holes and multipolygon parts
    # need no special case.
    def covers($x; $y):
      reduce rings[] as $r (false;
        ($r | length) as $n
        | reduce range(0; $n) as $i (.;
            $r[$i] as $a | $r[($i + $n - 1) % $n] as $b
            | if (($a[1] > $y) != ($b[1] > $y))
                 and ($x < ($b[0] - $a[0]) * ($y - $a[1]) / ($b[1] - $a[1]) + $a[0])
              then not else . end));
    # The alert area: "no polygon", "unparsed" (not GeoJSON), or the bounding
    # box in km, the point count (a hand-drawn warning has tens of points; a
    # region traced along coastlines and borders has thousands) and whether
    # it contains the test point.
    def area:
      (.polygon // "") as $p
      | ($p | try fromjson catch null) as $g
      | if $p == "" then "no polygon"
        elif $g == null then "polygon unparsed (\($p | length) chars)"
        else [$g | .. | arrays | select(length == 2 and (.[0] | type) == "number")] as $pts
          | if ($pts | length) == 0 then "polygon empty"
            else ($pts | map(.[1])) as $lats | ($pts | map(.[0])) as $lons
              | ((($lats | max) - ($lats | min)) * 111 | round) as $h
              | ((($lons | max) - ($lons | min)) * 111
                  * (($lats | add / length) * 3.14159 / 180 | cos) | round) as $w
              | "polygon ~\($w)×\($h) km, \($pts | length) points, "
                + (if ($g | covers($lon; $lat)) then "contains the city"
                   else "does NOT contain the city" end)
            end
        end;
    def short: if length > 60 then .[0:57] + "..." else . end;
    if .error then "\($city): error \(.error.code) \(.error.message)"
    else
      (.weatherAlerts // []) as $alerts
      | "\($city) [\(.regionCode // "?")]: \($alerts | length) alert(s)",
        ($alerts[] | "  \(.severity // "?") \(.eventType // "?") · "
          + "\(.dataSource.name // .dataSource.publisher // "?") · "
          + "\(.alertTitle.text // "?")\n"
          + "    \(.startTime // "?") to \(.expirationTime // "?") (UTC)\n"
          + "    area: \(.areaName // "?" | short) · \(area)\n"
          + "    fields: \(keys | join(", "))\n"
          + "    description: \(.description // "" | tostring | gsub("\\s+"; " ") | .[0:200])")
    end'
done <<'EOF'
Oklahoma City US,35.47,-97.52
Miami US,25.76,-80.19
Toronto CA,43.65,-79.38
Birmingham GB,52.48,-1.90
Manchester GB,53.48,-2.24
Skegness GB,53.14,0.34
Cardiff GB,51.48,-3.18
St Davids GB,51.88,-5.27
Glasgow GB,55.86,-4.25
Dublin IE,53.35,-6.26
Berlin DE,52.52,13.40
Paris FR,48.86,2.35
Madrid ES,40.42,-3.70
Rome IT,41.90,12.50
Amsterdam NL,52.37,4.90
Oslo NO,59.91,10.75
Warsaw PL,52.23,21.01
Athens GR,37.98,23.73
Zurich CH,47.37,8.54
Sydney AU,-33.87,151.21
Melbourne AU,-37.81,144.96
Auckland NZ,-36.85,174.76
Tokyo JP,35.68,139.69
Seoul KR,37.57,126.98
Mumbai IN,19.08,72.88
Manila PH,14.60,120.98
Sao Paulo BR,-23.55,-46.63
Mexico City MX,19.43,-99.13
Johannesburg ZA,-26.20,28.05
Istanbul TR,41.01,28.98
EOF
