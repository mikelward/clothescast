package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.ui.EdgeFadeOverlay

/**
 * Per-holiday opt-in toggles. Order matches [HolidayCatalog.all] — calendar
 * order across the year — so a user scanning the list sees New Year's at the
 * top and Christmas at the bottom, the same shape as a wall calendar.
 *
 * The leading emoji + display name come from each [HolidayTheme]; the resolved
 * resource IDs are looked up via [LocalContext]'s `getIdentifier` so the
 * theme catalogue can live in `:core:domain` without depending on `R`.
 * String values not present in `strings.xml` fall back to the holiday's enum
 * name (in upper case) so missing translations are visible in the UI rather
 * than crashing the page.
 */
@Composable
internal fun HolidaysContent(
    enabledHolidays: Set<HolidayId>,
    padding: PaddingValues,
    onSetEnabled: (HolidayId, Boolean) -> Unit,
) {
    val scrollState = rememberScrollState()
    EdgeFadeOverlay(
        scrollState = scrollState,
        modifier = Modifier.padding(padding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = stringResource(R.string.settings_holidays_title)) {
                Text(
                    text = stringResource(R.string.settings_holidays_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HolidayCatalog.all.forEach { (_, theme) ->
                    HolidayRow(
                        theme = theme,
                        checked = theme.id in enabledHolidays,
                        onCheckedChange = { wantsOn -> onSetEnabled(theme.id, wantsOn) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HolidayRow(
    theme: HolidayTheme,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val displayName = resolveHolidayString(theme.displayNameKey) ?: theme.id.name
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${theme.emoji}  $displayName",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Resolves a string by name without holding a compile-time R reference, so
 * `:core:domain` doesn't have to know about Android resource IDs. Returns
 * null when the name is unknown (typo / not localised yet) so callers can
 * fall back to a developer-visible default rather than crashing.
 */
@Composable
private fun resolveHolidayString(name: String): String? {
    val context = LocalContext.current
    val resId = context.resources.getIdentifier(name, "string", context.packageName)
    return if (resId == 0) null else context.getString(resId)
}
