package app.clothescast.data

/**
 * The telemetry preference and the deletion an opt-out still owes, as a pair
 * read from one DataStore snapshot — see [SettingsRepository.telemetryChoice].
 */
data class TelemetryChoice(
    val enabled: Boolean,
    val discardOwed: Boolean,
    /**
     * Which debt [discardOwed] refers to. Bumped by every crossing of the
     * consent line, and carried here so a purge can retire *the debt it
     * consumed* rather than whatever the flag says by the time it finishes.
     *
     * Without it a purge in flight across an off→on made during it would clear
     * the newer debt too: the flag reads `true` either way, so the clear
     * cannot tell the debt it started on from the one recorded since, and a
     * report captured in that opted-out gap would be released with the flags
     * turned back on (Codex, PR #1161).
     */
    val discardToken: Long = 0L,
)
