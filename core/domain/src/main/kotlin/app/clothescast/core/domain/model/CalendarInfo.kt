package app.clothescast.core.domain.model

/**
 * A calendar present on the device, surfaced to the per-calendar
 * enable/disable setting so the user can choose which calendars feed
 * ClothesCast (theming, evening extras, the celebration listings).
 *
 * [id] is the calendar's **stable provider identity, scoped to its account** —
 * `account/syncId`, where syncId is the sync adapter's server-side calendar id
 * (e.g. `en.uk#holiday@group.v.calendar.google.com`), falling back to
 * `account/name` for purely-local calendars that have no sync id.
 * Account-scoped so the same holiday calendar synced into two accounts stays
 * independently toggleable. It is deliberately *not* the local `_ID` row index,
 * which is
 * regenerated whenever an account is removed and re-added (so an override keyed
 * on it would silently reset). [visible] mirrors the calendar's visibility
 * toggle in the host calendar app and is the default the per-calendar override
 * falls back to.
 *
 * Device-local like the rest of the calendar surface: [id] / [displayName] /
 * [accountName] are shown only in-app and must never leave the device.
 */
data class CalendarInfo(
    val id: String,
    val displayName: String,
    val accountName: String,
    val visible: Boolean,
    /**
     * Raw Android account type (e.g. `com.google`, `com.android.exchange`).
     * Surfaced only to disambiguate same-name/same-account calendars from
     * different providers in the settings list; null when unknown.
     */
    val accountType: String? = null,
)
