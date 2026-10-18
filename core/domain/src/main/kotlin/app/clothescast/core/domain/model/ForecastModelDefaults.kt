package app.clothescast.core.domain.model

/**
 * Picks a sensible default set of [ForecastModel]s for [location] — the trio
 * the multi-model confidence fetcher consults when the user hasn't explicitly
 * customised their Forecasters selection (i.e. when
 * [UserPreferences.forecastModels] is null).
 *
 * The rule of thumb: the official national/regional meteorological service's
 * model is usually the most accurate over its own territory (UKMO over the
 * British Isles, JMA over Japan, GEM over Canada, etc.) because the agency
 * invests in local mesoscale modelling, fine-grained terrain handling, and
 * regional observation networks that a generic global model doesn't get. So
 * for each broad region we pair the local official with two strong global
 * majors as a divergence check: ECMWF — widely benchmarked as the world's
 * most accurate global model — plus a structurally different second one
 * (ICON) so the spread doesn't collapse when the regional model and ECMWF
 * happen to share a synoptic interpretation.
 *
 * Three models, no more (maintainer, 2026-10-18): the best few rather than a
 * wide blend, which dropped ECMWF's AI model AIFS everywhere and GEM over
 * East Asia. The cost is the second week:
 * the regional locals and ICON fall silent after ~day 7, so over most regions
 * ECMWF IFS (~day 15) carries days 8-14 alone, and North America keeps GEM
 * (~day 10) beside it. GFS (NOAA) stays out of every set; see the North
 * America branch for the rainfall record behind that, and
 * [ForecastModel.DEFAULTS] for why a systematically dry member is worse than
 * no member at all.
 *
 * With the Google forecaster on, it takes ICON's place as the structurally
 * different second opinion, so Auto keeps the local official and ECMWF (see
 * [autoModels]).
 *
 * The mapping is intentionally coarse — bounding boxes, not country
 * polygons — because the goal is "good defaults", not "perfect mapping".
 * Anyone unhappy with what auto picks for them can flip Auto off and pick
 * explicitly; a misclassified Strasbourg user (Western Europe vs. German
 * border, but still gets ECMWF + ICON + ARPEGE either way) won't notice.
 *
 * Overlap handling: the `when` cascade is ordered so British Isles wins over
 * Western Europe for the Channel area, and Nordics wins over Western Europe
 * for the southern Baltic. Inside any one branch the order also matters —
 * the picker's MIN_MODELS = 2 floor means dropping the first model leaves
 * a usable pair, so the "most local" model goes first.
 *
 * Returns the global default set (ECMWF / ICON / GEM) when [location]
 * is null (no location set yet) or falls outside every regional bounding box.
 * This matches the picker's pre-location-awareness behaviour and also acts
 * as a safe initial value for a fresh install before the first GPS fix
 * resolves.
 */
fun ForecastModel.Companion.defaultsFor(location: Location?): Set<ForecastModel> {
    if (location == null) return DEFAULTS
    val lat = location.latitude
    val lon = location.longitude
    return when {
        // British Isles + Ireland: roughly Land's End to the Shetlands, with
        // a generous easterly slop that catches the Channel coast of France
        // too. UKMO over Calais is still a sensible pick — the Channel is
        // UKMO's home synoptic region. Tested by the London / Dublin /
        // Edinburgh entries in ForecastModelDefaultsTest.
        lat in 49.5..61.0 && lon in -10.5..2.0 ->
            setOf(
                ForecastModel.UKMO_SEAMLESS,
                ForecastModel.ECMWF_IFS025,
                ForecastModel.ICON_SEAMLESS,
            )

        // Iceland: handled before the Nordic branch because it sits west of
        // 0° longitude. Same trio as Nordics (ECMWF + ICON + UKMO) — UKMO
        // covers the North Atlantic well, no native Icelandic model on
        // Open-Meteo.
        lat in 63.0..67.0 && lon in -25.0..-13.0 ->
            setOf(
                ForecastModel.ECMWF_IFS025,
                ForecastModel.ICON_SEAMLESS,
                ForecastModel.UKMO_SEAMLESS,
            )

        // Nordics (Norway / Sweden / Finland / Denmark, plus a Baltic slop
        // that catches Tallinn / Riga). UKMO is the local-ish official —
        // MET Norway's flagship MetCoOp Nordic model isn't on Open-Meteo
        // as a per-model series.
        lat in 55.0..72.0 && lon in 4.0..32.0 ->
            setOf(
                ForecastModel.ECMWF_IFS025,
                ForecastModel.ICON_SEAMLESS,
                ForecastModel.UKMO_SEAMLESS,
            )

        // Western + Central Europe (Iberia / France / Benelux / Germany /
        // Italy / Switzerland / Austria / Poland / Czechia). Three European
        // mesoscale models: ECMWF (Reading), ICON (DWD), ARPEGE
        // (Météo-France). British Isles + Iceland + Nordics already took
        // their slice above, so this catches the rest.
        lat in 36.0..58.0 && lon in -10.0..28.0 ->
            setOf(
                ForecastModel.ECMWF_IFS025,
                ForecastModel.ICON_SEAMLESS,
                ForecastModel.METEOFRANCE_SEAMLESS,
            )

        // Japan: JMA is the local official, ECMWF + ICON as the global
        // cross-checks. Tightish lat/lon — overlapping the Korean peninsula
        // is fine, the next branch will catch it.
        lat in 30.0..46.0 && lon in 128.0..146.0 ->
            setOf(
                ForecastModel.JMA_SEAMLESS,
                ForecastModel.ECMWF_IFS025,
                ForecastModel.ICON_SEAMLESS,
            )

        // Korean peninsula: KMA isn't on Open-Meteo, so JMA is the closest
        // regional model with native East Asia coverage. ECMWF + ICON
        // round out the trio.
        lat in 33.0..43.0 && lon in 124.0..131.0 ->
            setOf(
                ForecastModel.JMA_SEAMLESS,
                ForecastModel.ECMWF_IFS025,
                ForecastModel.ICON_SEAMLESS,
            )

        // North America: US (lower 48 + Alaska) + Canada + Mexico. GEM
        // (Environment Canada) is the local official; ECMWF is the global
        // cross-check still considered the most accurate over North America
        // even by NOAA's own metrics; ICON is the structurally different
        // second global.
        //
        // GFS (NCEP) used to lead this set as the American local. It was
        // swapped for ICON because it is markedly the weakest of the
        // candidates on rainfall: verified against ERA5 over 85 days at five
        // locations (including two North American ones), GFS came last on
        // both mean absolute error and equitable threat score for daily
        // totals, and detected barely half of wet days — POD 0.49, against
        // 0.76 for ICON and 0.86 for ECMWF. The misses run systematically
        // dry, which is the damaging direction here: a missed shower sends
        // the user out without an umbrella, while a false alarm only costs
        // them carrying one.
        //
        // This de-weights GFS rather than silencing it. Open-Meteo's
        // `best_match` overlay resolves to GFS at many North American points
        // and votes in the consensus blend as a regular model, so GFS keeps
        // one voice in the average instead of the two it held while it was
        // also listed here.
        //
        // Second-week coverage: ICON stops at ~day 7, but ECMWF IFS reaches
        // ~day 15 and GEM ~day 10.
        lat in 15.0..72.0 && lon in -170.0..-50.0 ->
            setOf(
                ForecastModel.GEM_SEAMLESS,
                ForecastModel.ECMWF_IFS025,
                ForecastModel.ICON_SEAMLESS,
            )

        // Australia + New Zealand: BOM (Australian Bureau of Meteorology)
        // would be the obvious local pick but Open-Meteo currently has
        // BOM open-data delivery suspended (see Settings.kt for the link),
        // so we fall back to the global set. Re-add BOM to this branch
        // when the enum entry comes back in Settings.kt.
        lat in -47.0..-10.0 && lon in 110.0..180.0 -> DEFAULTS

        // Default: everywhere else (South America, Africa, Middle East,
        // South + Southeast Asia, polar regions) falls back to the global
        // trio. The big regional models on Open-Meteo are all Northern-
        // Hemisphere-and-Europe-leaning, and we'd be picking arbitrarily
        // for these continents.
        else -> DEFAULTS
    }
}

/**
 * Auto's set for [location]: the regional three, or with [google] on, Google in
 * ICON's place (every regional set carries ICON as its independent second
 * opinion, and the maintainer rates it below the local official and ECMWF).
 */
fun ForecastModel.Companion.autoModels(location: Location?, google: Boolean): Set<ForecastModel> {
    val regional = defaultsFor(location)
    if (!google) return regional
    return regional - ForecastModel.ICON_SEAMLESS + ForecastModel.GOOGLE_WEATHER
}

/**
 * [models]' Open-Meteo forecasters plus Google when [google]. A set already at
 * [ForecastModel.MAX_MODELS] gives up its last model to make room; the picker
 * never lets a custom pick reach the cap with Google on.
 */
fun ForecastModel.Companion.withGoogle(models: Set<ForecastModel>, google: Boolean): Set<ForecastModel> {
    val openMeteo = models - ForecastModel.GOOGLE_WEATHER
    if (!google) return openMeteo
    return openMeteo.take(ForecastModel.MAX_MODELS - 1).toSet() + ForecastModel.GOOGLE_WEATHER
}

/**
 * The custom pick if it still makes a consensus, else null (Auto). A pick
 * needs two Open-Meteo models, or one beside Google: an older build's
 * "Google plus one" carries over as one pick plus the Google switch, and
 * turning Google off must not leave a single model, which has no spread.
 */
fun ForecastModel.Companion.usablePick(selection: Set<ForecastModel>?, google: Boolean): Set<ForecastModel>? =
    selection?.takeIf { (it - ForecastModel.GOOGLE_WEATHER).size >= if (google) 1 else 2 }

/** The forecasters a fetch uses: a [usablePick] plus Google, else Auto ([autoModels]). */
fun ForecastModel.Companion.effective(
    selection: Set<ForecastModel>?,
    location: Location?,
    google: Boolean,
): Set<ForecastModel> =
    usablePick(selection, google)?.let { withGoogle(it, google) } ?: autoModels(location, google)
