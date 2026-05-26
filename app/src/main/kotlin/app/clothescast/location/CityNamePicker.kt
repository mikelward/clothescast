package app.clothescast.location

/** Picks a user-facing city name from the structured fields of an Android `Address`. */
internal fun pickCityName(
    locality: String?,
    subLocality: String?,
    subAdminArea: String?,
    adminArea: String?,
    countryCode: String?,
    countryName: String?,
    postalCode: String?,
    addressLines: List<String>,
): String? {
    val cc = countryCode?.trim()?.uppercase()

    // UK: Google's Geocoder fills `locality` with the hamlet (Oakley Green) or leaves it blank
    // for inner-London residentials. The Royal Mail post town — what people actually call the
    // place — is the addressLine token containing the postcode.
    if (cc == "GB") {
        extractUkPostTown(postalCode, addressLines)?.let { return it }
    }

    // City-states / SARs: the country itself is the city. Geocoder typically leaves
    // `locality` empty (Hong Kong, Macau) so we'd otherwise fall through to a
    // sub-district. Honour `locality` when Geocoder did fill it (preserves the
    // device-locale form) and use the canonical English short form as a last-resort
    // fallback. Excludes Liechtenstein and Andorra: those have meaningful internal
    // municipalities / parishes (Vaduz, Andorra la Vella) that Geocoder fills into
    // `locality` correctly.
    if (cc in CITY_STATE_FALLBACK_NAMES) {
        locality?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return CITY_STATE_FALLBACK_NAMES[cc]
    }

    // Governorate-model countries: Geocoder fills `locality` with the neighbourhood
    // (e.g. Cairo's "Khairat") and the actual major city is in `adminArea` as
    // "<City> Governorate". Prefer the cleaned admin area over the locality.
    if (cc in GOVERNORATE_COUNTRIES) {
        cleanAdminArea(adminArea)?.let { return it }
    }

    // New York City: Geocoder fills `locality` with the borough (Brooklyn, Queens,
    // The Bronx, Staten Island) for non-Manhattan addresses, which surfaces as a
    // sub-city label on the Today header. Collapse to "New York" when the address
    // sits in any of the five boroughs — identified uniformly by the NYC county
    // names in `subAdminArea`.
    if (cc == "US" && subAdminArea?.trim() in NYC_COUNTIES) {
        return "New York"
    }

    return listOfNotNull(
        locality,
        subLocality,
        cleanAdminArea(subAdminArea),
        cleanAdminArea(adminArea),
        countryName,
    )
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
}

private val CITY_STATE_FALLBACK_NAMES = mapOf(
    "HK" to "Hong Kong",
    "MO" to "Macau",
    "SG" to "Singapore",
    "MC" to "Monaco",
    "VA" to "Vatican City",
    "GI" to "Gibraltar",
)

private val GOVERNORATE_COUNTRIES = setOf("EG")

// Counties that make up New York City's five boroughs. Manhattan is "New York
// County"; the others share names with their boroughs (Kings = Brooklyn, etc.).
private val NYC_COUNTIES = setOf(
    "New York County",   // Manhattan
    "Kings County",      // Brooklyn
    "Queens County",     // Queens
    "Bronx County",      // The Bronx
    "Richmond County",   // Staten Island
)

/**
 * Strips Irish "County "/"Co. " prefixes and " Governorate"/" Province"/" Prefecture"
 * suffixes so admin-area-style values can be used as a city label. Leaves
 * "X County" (US/UK suffix style, e.g. "Kings County") untouched so we don't
 * turn "Kings County" into "Kings".
 */
private fun cleanAdminArea(value: String?): String? {
    if (value.isNullOrBlank()) return null
    var s = value.trim()
    for (prefix in listOf("County ", "Co. ", "Co ")) {
        if (s.startsWith(prefix, ignoreCase = true)) {
            s = s.substring(prefix.length).trim()
            break
        }
    }
    for (suffix in listOf(" Governorate", " Province", " Prefecture")) {
        if (s.endsWith(suffix, ignoreCase = true)) {
            s = s.substring(0, s.length - suffix.length).trim()
            break
        }
    }
    return s.takeIf { it.isNotBlank() }
}

private fun extractUkPostTown(postalCode: String?, addressLines: List<String>): String? {
    if (postalCode.isNullOrBlank()) return null
    val pcPattern = postalCode.trim()
        .split("\\s+".toRegex())
        .filter { it.isNotEmpty() }
        .joinToString("\\s*") { Regex.escape(it) }
        .toRegex(RegexOption.IGNORE_CASE)
    for (line in addressLines) {
        for (rawToken in line.split(",")) {
            val token = rawToken.trim()
            if (token.isEmpty() || !pcPattern.containsMatchIn(token)) continue
            val cleaned = pcPattern.replace(token, " ")
                .replace("\\s+".toRegex(), " ")
                .trim()
                .trim(',')
                .trim()
            if (cleaned.isNotBlank()) return cleaned
        }
    }
    return null
}
