package app.clothescast.core.domain.model

/**
 * The flag emoji for an ISO 3166-1 alpha-2 [countryCode] ("US" → 🇺🇸), built
 * from regional indicator symbols, or null for anything that isn't two
 * letters (the catalog's pseudo-countries, like [HolidayCatalog.GLOBAL_COUNTRY]).
 */
fun flagEmoji(countryCode: String): String? {
    val code = countryCode.uppercase()
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return null
    return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/** Whether [code] names a real country rather than a catalog pseudo-country. */
internal fun isRealCountry(code: String): Boolean = flagEmoji(code) != null

// Google's regional holiday calendars are keyed `<language>.<region>#holiday@…`.
// Most regions are named ("usa", "uk", "australian"); newer ones use the ISO
// code ("be", "ar"), taken as is.
// An unlisted region gets no flag rather than a guess.
private val GOOGLE_HOLIDAY_REGIONS = mapOf(
    "usa" to "US", "uk" to "GB", "australian" to "AU", "canadian" to "CA",
    "irish" to "IE", "new_zealand" to "NZ", "indian" to "IN", "sa" to "ZA",
    "singapore" to "SG", "philippines" to "PH", "malaysia" to "MY",
    "hong_kong" to "HK", "china" to "CN", "japanese" to "JP", "south_korea" to "KR",
    "taiwan" to "TW", "indonesian" to "ID", "vietnamese" to "VN", "thai" to "TH",
    "german" to "DE", "french" to "FR", "spain" to "ES", "italian" to "IT",
    "dutch" to "NL", "austrian" to "AT", "swiss" to "CH",
    "danish" to "DK", "swedish" to "SE", "norwegian" to "NO", "finnish" to "FI",
    "polish" to "PL", "portuguese" to "PT", "greek" to "GR", "turkish" to "TR",
    "russian" to "RU", "ukrainian" to "UA", "croatian" to "HR", "czech" to "CZ",
    "hungarian" to "HU", "romanian" to "RO", "brazilian" to "BR", "mexican" to "MX",
    "saudiarabian" to "SA",
)

private val GOOGLE_HOLIDAY_CALENDAR_REGEX = Regex(
    "^[a-z]{2}(?:[-_][a-z]{2})?\\.([a-z_]+)(?:\\.[a-z_]+)?#holiday@group\\.v\\.calendar\\.google\\.com$",
    RegexOption.IGNORE_CASE,
)

/**
 * The country a Google regional holiday calendar covers, from its id or owner
 * account (`en.usa#holiday@group.v.calendar.google.com` → "US"), or null for
 * any other calendar, a religion calendar (`en.judaism#holiday@…`), or a
 * region not in the table.
 */
fun holidayCalendarCountry(calendarId: String?): String? {
    if (calendarId == null) return null
    // A device calendar id is `account/syncId`; the sync id is what's keyed.
    val region = GOOGLE_HOLIDAY_CALENDAR_REGEX.find(calendarId.trim().substringAfterLast('/'))?.groupValues?.get(1)?.lowercase()
        ?: return null
    return GOOGLE_HOLIDAY_REGIONS[region]
        ?: region.uppercase().takeIf { it.length == 2 && flagEmoji(it) != null }
}
