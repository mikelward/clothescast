package app.clothescast.core.domain.model

/**
 * Speaker gender a [TtsStyle] persona implies, used to pick a matching Gemini
 * voice when a holiday auto-selects the persona. [NEUTRAL] means "no
 * preference" — keep whatever voice the user already has, since the register
 * reads fine in any voice.
 */
enum class VoiceGender { MALE, FEMALE, NEUTRAL }

/**
 * The seasonal persona a holiday should speak in, or null when the holiday
 * carries no special voice (the user's own style and voice stand).
 *
 * Only a handful of days have an obvious persona fit — the seasonal registers
 * called out in [TtsStyle]'s doc plus the two novelty days the registers were
 * built for (Talk Like a Pirate Day → [TtsStyle.PIRATE], Towel Day →
 * [TtsStyle.SCIFI_NARRATOR]). Everything else returns null so the everyday
 * forecaster voice (or the user's deliberate pick) keeps speaking.
 */
fun holidayTtsStyle(id: HolidayId): TtsStyle? = when (id) {
    HolidayId.NEW_YEARS_DAY -> TtsStyle.NEW_YEARS_HOST
    HolidayId.US_PRESIDENTS_DAY -> TtsStyle.PRESIDENT
    HolidayId.ST_PATRICKS_DAY -> TtsStyle.LEPRECHAUN
    HolidayId.TOWEL_DAY -> TtsStyle.SCIFI_NARRATOR
    HolidayId.TALK_LIKE_A_PIRATE_DAY -> TtsStyle.PIRATE
    HolidayId.UK_KINGS_BIRTHDAY -> TtsStyle.KING
    HolidayId.HALLOWEEN -> TtsStyle.SPOOKY_NARRATOR
    HolidayId.CHRISTMAS_DAY, HolidayId.BOXING_DAY -> TtsStyle.FATHER_CHRISTMAS
    else -> null
}

/**
 * Speaker gender a persona evokes. Drives the holiday voice switch: a male
 * persona on a female default voice (or vice versa) picks a matching-gender
 * voice so the register lands — a booming Father Christmas in a youthful
 * female voice reads wrong. Most personas are [VoiceGender.NEUTRAL] and keep
 * the user's voice untouched.
 */
val TtsStyle.suggestedVoiceGender: VoiceGender
    get() = when (this) {
        TtsStyle.PRESIDENT,
        TtsStyle.KING,
        TtsStyle.FATHER_CHRISTMAS,
        TtsStyle.SCIFI_NARRATOR,
        TtsStyle.PIRATE,
        TtsStyle.COWBOY,
        TtsStyle.LEPRECHAUN,
        -> VoiceGender.MALE
        TtsStyle.QUEEN -> VoiceGender.FEMALE
        else -> VoiceGender.NEUTRAL
    }
