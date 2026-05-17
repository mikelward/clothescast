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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayCountryMode
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.ui.EdgeFadeOverlay
import java.text.Collator
import java.util.Locale

/**
 * Inline country-filter picker + per-holiday toggles.
 *
 * Three sections, top to bottom:
 *  1. **Country filter** — Auto / All / Custom radio picker. The Auto
 *     label appends the resolved country list in parentheses
 *     ("Auto (Australia, United Kingdom, Global)") mirroring the
 *     temperature-unit "Auto (°C)" pattern in [RegionContent].
 *  2. **Country toggles** — visible only in Custom mode. One row per
 *     entry in [HolidayCatalog.allCountries] (Global pinned first,
 *     rest sorted alphabetically by their localised display name).
 *  3. **Per-holiday list** — same shape as before but filtered to entries
 *     whose [HolidayTheme.countries] intersect
 *     [SettingsState.effectiveEnabledHolidayCountries]. Per-holiday toggle
 *     state persists for hidden holidays, so re-enabling a country
 *     restores the user's prior picks.
 *
 * Calendar order in [HolidayCatalog.all] — a user scanning the list sees
 * New Year's at the top and Christmas at the bottom, the same shape as a
 * wall calendar. Resource IDs are looked up via [LocalContext]'s
 * `getIdentifier` so the theme catalogue can live in `:core:domain`
 * without depending on `R`. String values not present in `strings.xml`
 * fall back to the holiday's enum name (in upper case) so missing
 * translations are visible in the UI rather than crashing the page.
 */
@Composable
internal fun HolidaysContent(
    enabledHolidays: Set<HolidayId>,
    holidayCountryMode: HolidayCountryMode,
    enabledHolidayCountries: Set<String>,
    autoEnabledHolidayCountries: Set<String>,
    effectiveEnabledHolidayCountries: Set<String>,
    padding: PaddingValues,
    onSetEnabled: (HolidayId, Boolean) -> Unit,
    onSetCountryMode: (HolidayCountryMode) -> Unit,
    onSetCountryEnabled: (String, Boolean) -> Unit,
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val uiLocale = remember(context.resources.configuration) {
        context.resources.configuration.locales.get(0) ?: Locale.getDefault()
    }
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
            SectionCard(title = stringResource(R.string.settings_holiday_countries_title)) {
                Text(
                    text = stringResource(R.string.settings_holiday_countries_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val autoDetail = remember(autoEnabledHolidayCountries, uiLocale) {
                    autoEnabledHolidayCountries.toSortedDisplayList(context, uiLocale)
                }
                HolidayCountryMode.entries.forEach { mode ->
                    val label = when (mode) {
                        HolidayCountryMode.AUTO -> stringResource(
                            R.string.settings_holiday_country_mode_auto_format,
                            autoDetail,
                        )
                        HolidayCountryMode.ALL -> stringResource(R.string.settings_holiday_country_mode_all)
                        HolidayCountryMode.CUSTOM -> stringResource(R.string.settings_holiday_country_mode_custom)
                    }
                    RadioRow(
                        label = label,
                        selected = mode == holidayCountryMode,
                        onSelect = { onSetCountryMode(mode) },
                    )
                }
            }

            if (holidayCountryMode == HolidayCountryMode.CUSTOM) {
                SectionCard(title = stringResource(R.string.settings_holiday_countries_pick_title)) {
                    val sortedCountries = remember(uiLocale) {
                        HolidayCatalog.allCountries.sortedForDisplay(context, uiLocale)
                    }
                    sortedCountries.forEach { code ->
                        CountryRow(
                            code = code,
                            checked = code in enabledHolidayCountries,
                            onCheckedChange = { wantsOn -> onSetCountryEnabled(code, wantsOn) },
                        )
                    }
                }
            }

            SectionCard(title = stringResource(R.string.settings_holidays_title)) {
                Text(
                    text = stringResource(R.string.settings_holidays_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val visible = HolidayCatalog.all
                    .filter { (_, theme) -> theme.countries.any { it in effectiveEnabledHolidayCountries } }
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_holidays_empty_for_country_filter),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    visible.forEach { (_, theme) ->
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

@Composable
private fun CountryRow(
    code: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val displayName = resolveCountryDisplayName(code)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displayName,
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

/**
 * Localised display name for a country code. `GLOBAL` resolves to the
 * "Global holidays" label; ISO codes resolve via the
 * `settings_holiday_country_<lower>` string family, falling back to
 * [Locale]'s display name (e.g. "United States") if the resource is
 * missing and finally to the raw code so the row is always readable.
 */
@Composable
private fun resolveCountryDisplayName(code: String): String {
    val context = LocalContext.current
    if (code.equals(HolidayCatalog.GLOBAL_COUNTRY, ignoreCase = true)) {
        return stringResource(R.string.settings_holiday_country_global)
    }
    val resName = "settings_holiday_country_${code.lowercase()}"
    val resId = context.resources.getIdentifier(resName, "string", context.packageName)
    if (resId != 0) return context.getString(resId)
    return Locale("", code).getDisplayCountry(
        context.resources.configuration.locales.get(0) ?: Locale.getDefault()
    ).ifBlank { code }
}

/** Renders a comma-separated, locale-sorted display list for the Auto subtitle. */
private fun Set<String>.toSortedDisplayList(
    context: android.content.Context,
    uiLocale: Locale,
): String {
    val labels = sortedForDisplay(context, uiLocale).map { code ->
        if (code.equals(HolidayCatalog.GLOBAL_COUNTRY, ignoreCase = true)) {
            context.getString(R.string.settings_holiday_country_global)
        } else {
            val resName = "settings_holiday_country_${code.lowercase()}"
            val resId = context.resources.getIdentifier(resName, "string", context.packageName)
            if (resId != 0) context.getString(resId)
            else Locale("", code).getDisplayCountry(uiLocale).ifBlank { code }
        }
    }
    return labels.joinToString(", ")
}

/**
 * Sorts a country-code collection for UI display: [HolidayCatalog.GLOBAL_COUNTRY]
 * pinned first (it's a bucket label, not a place — sorting it alphabetically
 * buries it), the rest alphabetised by their localised display name via a
 * locale-aware [Collator] so the order reads naturally in the user's UI
 * language.
 */
private fun Collection<String>.sortedForDisplay(
    context: android.content.Context,
    uiLocale: Locale,
): List<String> {
    val collator = Collator.getInstance(uiLocale)
    val (global, rest) = partition { it.equals(HolidayCatalog.GLOBAL_COUNTRY, ignoreCase = true) }
    val labelled = rest.map { code ->
        val resName = "settings_holiday_country_${code.lowercase()}"
        val resId = context.resources.getIdentifier(resName, "string", context.packageName)
        val label = if (resId != 0) context.getString(resId)
        else Locale("", code).getDisplayCountry(uiLocale).ifBlank { code }
        code to label
    }
    return global + labelled.sortedWith(compareBy(collator) { it.second }).map { it.first }
}
