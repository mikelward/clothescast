package app.clothescast.ui.today

import android.content.Context
import app.clothescast.R
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.core.domain.model.bannerTextKeyFor

/**
 * The day's banner copy without its emoji: "Merry Christmas", "Alice's
 * birthday", or a composed "Merry Christmas and Alice's birthday". The one
 * place it is built, so the Today banner and the outfit card read the same.
 *
 * Calendar-sourced themes carry their event title verbatim — a birthday's
 * contact name included. Callers own where that text goes: it is shown on
 * screen and on the outfit card, and must never reach the spoken greeting
 * (see [app.clothescast.tts.holidayTtsGreeting]).
 *
 * [country] picks a per-country override (Remembrance Day → Veterans Day in
 * the US); [and] joins the pieces of a composed banner, and is a parameter so
 * a composable can read it observably rather than off its context.
 */
internal fun holidayBannerText(
    context: Context,
    theme: HolidayTheme,
    country: String,
    and: String = context.getString(R.string.holiday_banner_and),
): String {
    val segments = theme.bannerSegments
    if (!segments.isNullOrEmpty()) {
        return segments.joinToString(separator = " $and ") { segment ->
            segment.literalText ?: run {
                val key = segment.textKeyByCountry[country.uppercase()] ?: segment.textKey
                resolveBannerString(context, key, theme.id.name)
            }
        }
    }
    // Synthetic themes (calendar-sourced holidays/birthdays) carry the event
    // title as [HolidayTheme.displayTitleOverride]; bypass the resource lookup
    // because the key is a runtime string, not a `@string/...` id.
    return theme.displayTitleOverride
        ?: resolveBannerString(context, theme.bannerTextKeyFor(country), theme.id.name)
}

/**
 * Black or white ARGB for text drawn on [bannerArgb], picked by sRGB luminance
 * (0.299/0.587/0.114): bright banners (Australia Day gold, Brazil yellow) get
 * dark text; deep banners (Christmas red, St Andrew's blue) get white. The
 * 0.55 threshold is a visual compromise that keeps Halloween's medium-orange
 * on white text rather than flipping at the midpoint.
 */
internal fun holidayBannerContentArgb(bannerArgb: Long): Int {
    val argb = bannerArgb.toInt()
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val luminance = 0.299f * r + 0.587f * g + 0.114f * b
    return if (luminance > 0.55f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
}

/**
 * Resolves a `@string/<name>` banner key by name (the theme catalogue lives
 * in `:core:domain` and can't hold compile-time `R` ids). Falls back to
 * [fallback] (the holiday's enum name) when the key is null or unknown so a
 * missing translation degrades to something developer-readable rather than
 * blank.
 */
@android.annotation.SuppressLint("DiscouragedApi")
private fun resolveBannerString(
    context: Context,
    key: String?,
    fallback: String,
): String {
    if (key == null) return fallback
    // getIdentifier is required: the string name is a runtime banner key.
    val resId = context.resources.getIdentifier(key, "string", context.packageName)
    return if (resId == 0) fallback else context.getString(resId)
}

/** "{emoji}  {text}", or just [text] for a theme with no emoji. */
internal fun holidayBannerLabel(theme: HolidayTheme, text: String): String =
    if (theme.emoji.isEmpty()) text else "${theme.emoji}  $text"
