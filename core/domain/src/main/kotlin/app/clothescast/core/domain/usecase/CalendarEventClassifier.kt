package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.EventKind

/**
 * Classifies a calendar event row as a birthday, a public holiday, or a normal
 * event. Pure-Kotlin so the same logic is exercised in unit tests and in the
 * Android reader.
 *
 * Priority (highest to lowest):
 * 1. Holiday-calendar owner-account match → PUBLIC_HOLIDAY. Google's holiday
 *    calendars are read-only and only contain holiday events — anything sourced
 *    there is a holiday by definition, including titles that happen to look
 *    birthday-shaped ("King's Birthday" on Australia's calendar).
 * 2. `eventType == EVENT_TYPE_BIRTHDAY` → BIRTHDAY. This is the authoritative,
 *    locale-independent signal Google Calendar writes when you create an event
 *    with the explicit "Birthday" type. Constant isn't on every SDK level yet,
 *    so the reader passes `null` when it can't read the column.
 * 3. Birthday-shaped title (English) → BIRTHDAY. Fallback that catches both
 *    user-entered "<name>'s birthday" events on older Androids and Google's
 *    auto-Birthdays-calendar entries (which use the same title shape).
 * 4. Default → NORMAL.
 *
 * Locale caveat: the birthday-title regex is English-only in v1. Non-English
 * variants ("Anniversaire de X", "Geburtstag von X", "Xの誕生日") fall through
 * to NORMAL — but `eventType` above closes that gap on devices that expose it.
 */
object CalendarEventClassifier {

    /**
     * Constant value Google Calendar writes into `eventType` for explicit
     * birthday-typed events. The constant isn't exposed in `EventsColumns`
     * until a future compileSdk, so we hard-code the integer here; reader
     * graceful-fallbacks to `null` when the column doesn't exist on the
     * device. Verified against AOSP source; if a device emits a different
     * value the title-regex path still catches English-titled rows.
     */
    const val EVENT_TYPE_BIRTHDAY: Int = 1

    private val HOLIDAY_OWNER_REGEX = Regex(
        ".*(@holiday\\.calendar\\.google\\.com|#holiday@group\\.v\\.calendar\\.google\\.com)$",
        RegexOption.IGNORE_CASE,
    )

    // Trailing punctuation ("Eva's birthday!", "Eva's birthday...") is common
    // in user-entered titles, so allow it after the match.
    private val BIRTHDAY_TITLE_REGEX = Regex(
        "^(?:.+['’]s birthday|birthday\\s*[:\\-]\\s*.+)[\\s!?.]*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Why a given row received its [EventKind]. Surfaced via [Result.reason]
     * so the reader can log the deduction path — useful when a user reports
     * a birthday they expected to theme that didn't, or vice versa.
     */
    enum class Reason {
        HOLIDAY_CALENDAR_OWNER,
        EVENT_TYPE_BIRTHDAY,
        BIRTHDAY_TITLE_REGEX,
        DEFAULT_NORMAL,
    }

    data class Result(val kind: EventKind, val reason: Reason)

    fun classify(title: String, ownerAccount: String?, eventType: Int? = null): Result {
        if (ownerAccount != null && HOLIDAY_OWNER_REGEX.matches(ownerAccount)) {
            return Result(EventKind.PUBLIC_HOLIDAY, Reason.HOLIDAY_CALENDAR_OWNER)
        }
        if (eventType != null && eventType == EVENT_TYPE_BIRTHDAY) {
            return Result(EventKind.BIRTHDAY, Reason.EVENT_TYPE_BIRTHDAY)
        }
        if (BIRTHDAY_TITLE_REGEX.matches(title.trim())) {
            return Result(EventKind.BIRTHDAY, Reason.BIRTHDAY_TITLE_REGEX)
        }
        return Result(EventKind.NORMAL, Reason.DEFAULT_NORMAL)
    }
}
