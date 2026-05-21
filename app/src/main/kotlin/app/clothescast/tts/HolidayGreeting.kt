package app.clothescast.tts

import android.content.Context
import android.content.res.Configuration
import app.clothescast.R
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.core.domain.model.bannerTextKeyFor
import java.util.Locale

/**
 * The spoken greeting prepended to the TTS briefing on a themed day. String
 * language follows [locale] (the voice locale, not necessarily the app region —
 * so a de-AT voice greets in German), while the per-country holiday-name
 * override is keyed off [country] (the app/effective region country) so the
 * spoken greeting mirrors the Today banner: a US-region user with an en-GB
 * voice still hears "Veterans Day", not "Remembrance Day".
 *
 * Privacy: a synthetic theme's title is calendar-sourced and must never cross
 * the device boundary into TTS (the prose is sent to Gemini over the BYOK key —
 * see PRIVACY.md). So birthdays collapse to a generic "Happy birthday!" that
 * drops the name the banner shows, calendar public holidays are skipped
 * entirely, and composed multi-celebration banners speak only their catalog
 * pieces. Returns null when there's nothing safe to greet, leaving the plain
 * forecast unchanged.
 */
internal fun holidayTtsGreeting(
    context: Context,
    theme: HolidayTheme?,
    locale: Locale,
    country: String,
): String? {
    if (theme == null) return null
    val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
    val localized = context.createConfigurationContext(config)

    val segments = theme.bannerSegments
    val core: String? = when {
        !segments.isNullOrEmpty() -> {
            val and = localized.getString(R.string.holiday_banner_and)
            segments
                // Skip synthetic literal segments (calendar titles); speak only
                // the catalog/Funny pieces, which resolve from string resources.
                .mapNotNull { segment ->
                    val key = segment.textKeyByCountry[country.uppercase()] ?: segment.textKey
                    key?.let { localized.resolveStringByName(it) }
                }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = " $and ")
        }
        theme.isSynthetic ->
            if (theme.id == HolidayId.BIRTHDAY) {
                localized.getString(R.string.tts_greeting_birthday)
            } else {
                null
            }
        else -> localized.resolveStringByName(theme.bannerTextKeyFor(country))
    }

    val trimmed = core?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.last() in TERMINAL_PUNCTUATION) return trimmed
    // Solemn remembrance days take a flat full stop; festive days an
    // exclamation, matching their banner copy's tone ("Merry Christmas!").
    return trimmed + if (theme.solemn) "." else "!"
}

private val TERMINAL_PUNCTUATION = setOf('.', '!', '?')

private fun Context.resolveStringByName(name: String): String? {
    val resId = resources.getIdentifier(name, "string", packageName)
    return if (resId == 0) null else getString(resId)
}
