package app.clothescast.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.formatHourMinute
import app.clothescast.core.domain.model.formatTopOfHour
import java.time.LocalTime

/**
 * Drives the user's clock convention (12h / 24h) for every chart subtitle,
 * scrub readout, schedule picker label, and other user-facing time label.
 * Provided near the screen roots by Today / Settings / onboarding flows from
 * the resolved [TimeFormat] preference; defaults to 24h so previews and tests
 * outside a real composition render deterministically.
 */
val LocalTimeFormat = compositionLocalOf { TimeFormat.TWENTY_FOUR_HOUR }

/**
 * Top-of-hour formatter ("14:00" / "2pm") shared by every chart subtitle
 * and the scrub readout. The indicator always snaps to a whole hour, so the
 * 12h English form drops the ":00" — "Peak 12 km/h at 2pm" reads as
 * naturally as the 24h "Peak 12 km/h at 14:00".
 */
@Composable
fun formatScrubHour(time: LocalTime): String {
    val format = LocalTimeFormat.current
    val locale = LocalConfiguration.current.locales[0]
    return format.formatTopOfHour(time, locale)
}

/**
 * Hour+minute formatter ("14:35" / "2:35pm") for sites that name an
 * arbitrary minute — the model-divergence peak time, the "generated at"
 * footer, the rationale "rain at HH:mm" prose, and the schedule pickers.
 */
@Composable
fun formatHourMinute(time: LocalTime): String {
    val format = LocalTimeFormat.current
    val locale = LocalConfiguration.current.locales[0]
    return format.formatHourMinute(time, locale)
}
