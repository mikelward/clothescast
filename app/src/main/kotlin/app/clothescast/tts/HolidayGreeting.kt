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
 * entirely, and composed multi-celebration banners speak their catalog pieces
 * plus that same generic birthday stand-in — never a calendar title. Returns
 * null when there's nothing safe to greet, leaving the plain forecast
 * unchanged.
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
                .mapNotNull { segment ->
                    val key = segment.textKeyByCountry[country.uppercase()] ?: segment.textKey
                    // Catalog / Funny pieces resolve from string resources.
                    // A literal segment is a calendar-sourced title and must
                    // not be spoken — but a birthday still has a safe generic
                    // stand-in that names nobody, the same one a lone birthday
                    // theme speaks below. Without it a day whose only
                    // celebrations are birthdays (two people sharing a date, or
                    // a birthday alongside a calendar public holiday) fell
                    // through to an empty list and spoke no greeting at all.
                    // Calendar public holidays have no safe stand-in, so they
                    // stay skipped.
                    key?.let { localized.resolveStringByName(it) }
                        ?: R.string.tts_greeting_birthday
                            .takeIf { segment.holidayId == HolidayId.BIRTHDAY }
                            ?.let { localized.getString(it) }
                }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                // Two people sharing a birthday contribute one greeting, not
                // "Happy birthday and Happy birthday".
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.joinGreetings(and)
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

/**
 * Joins the day's greeting pieces with [and] — except after a piece that
 * already closed its own sentence, which starts a new one instead.
 *
 * Several banner strings ship their punctuation as part of the copy
 * ("¡Feliz Cinco de Mayo!", "Ahoy, matey!"), so gluing the next piece on with
 * "and" reads as one broken sentence: "¡Feliz Cinco de Mayo! and Happy
 * birthday!". Stripping the trailing "!" instead would orphan the opening "¡"
 * that Spanish pairs it with, so the fix is a sentence break, not a trim —
 * "¡Feliz Cinco de Mayo! Happy birthday!".
 *
 * Receiver must be non-empty and carry no blank entries; the caller filters.
 * The terminal punctuation on the *final* piece is left to
 * [holidayTtsGreeting], which adds one when the copy doesn't bring its own.
 */
private fun List<String>.joinGreetings(and: String): String =
    reduce { sentence, piece ->
        if (sentence.last() in TERMINAL_PUNCTUATION) "$sentence $piece" else "$sentence $and $piece"
    }

// getIdentifier is required: the string name is computed at runtime (holiday
// key), so a compile-time R.string reference isn't possible.
@android.annotation.SuppressLint("DiscouragedApi")
private fun Context.resolveStringByName(name: String): String? {
    val resId = resources.getIdentifier(name, "string", packageName)
    return if (resId == 0) null else getString(resId)
}
