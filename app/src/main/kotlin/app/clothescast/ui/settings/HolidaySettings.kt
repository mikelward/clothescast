package app.clothescast.ui.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayCountrySelection
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.ui.EdgeFadeOverlay
import java.text.Collator
import java.util.Locale

/**
 * A top-of-page card with the country-source toggles (Home / Current
 * location / All), followed by one collapsible per country listing that
 * country's holidays (plus the universal globals — Christmas, NYE,
 * Halloween, Valentine's — which apply everywhere) and finally an "All"
 * collapsible showing every holiday in the catalogue flat for power-user
 * search.
 *
 * Both country headers and individual holiday rows carry a tri-state
 * dropdown (`Auto` / `On` / `Off`). The `Auto` label includes the
 * current resolution ("Auto (on)" / "Auto (off)") so the user can see
 * what the country picker (or the country override) is doing without
 * flipping it in their head.
 *
 * Country headers also show an `(n/m)` summary — holidays currently
 * active / total for that country (including globals).
 *
 * Resource IDs are looked up via [LocalContext]'s `getIdentifier` so the
 * theme catalogue can live in `:core:domain` without depending on `R`.
 * Missing translations fall back to the raw enum name / ISO code.
 */
@Composable
internal fun HolidaysContent(
    holidayCountrySelection: HolidayCountrySelection,
    holidayOverrides: Map<HolidayId, HolidayOverride>,
    effectiveEnabledHolidayCountries: Set<String>,
    localeCountry: String?,
    weatherLocationCountry: String?,
    padding: PaddingValues,
    onSetCountryHome: (Boolean) -> Unit,
    onSetCountryCurrent: (Boolean) -> Unit,
    onSetCountryAll: (Boolean) -> Unit,
    onSetCountryOverride: (String, HolidayOverride) -> Unit,
    onSetHolidayOverride: (HolidayId, HolidayOverride) -> Unit,
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val uiLocale = remember(context.resources.configuration) {
        context.resources.configuration.locales.get(0) ?: Locale.getDefault()
    }

    // Holidays grouped by their owning country. Globals (countries =
    // {GLOBAL_COUNTRY}) are duplicated into every ISO country's bucket so
    // expanding a country shows both its country-specific holidays and the
    // four universal ones.
    val globalThemes = remember {
        HolidayCatalog.all
            .map { it.second }
            .filter { HolidayCatalog.GLOBAL_COUNTRY in it.countries }
    }
    val isoCountries = remember(uiLocale) {
        HolidayCatalog.allCountries
            .filter { it != HolidayCatalog.GLOBAL_COUNTRY }
            .sortedForDisplay(context, uiLocale)
    }
    val themesByCountry = remember(globalThemes) {
        isoCountries.associateWith { code ->
            val countrySpecific = HolidayCatalog.all
                .map { it.second }
                .filter { code in it.countries }
            (countrySpecific + globalThemes).distinctBy { it.id }
        }
    }
    val allThemes = remember {
        HolidayCatalog.all.map { it.second }
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CheckboxRow(
                        label = stringResource(
                            R.string.settings_holiday_country_home,
                            localeCountry?.let { resolveCountryDisplayName(context, uiLocale, it) }
                                ?: stringResource(R.string.settings_holiday_country_unknown),
                        ),
                        checked = holidayCountrySelection.home,
                        enabled = !holidayCountrySelection.all,
                        onCheckedChange = onSetCountryHome,
                    )
                    CheckboxRow(
                        label = stringResource(
                            R.string.settings_holiday_country_current,
                            weatherLocationCountry?.let { resolveCountryDisplayName(context, uiLocale, it) }
                                ?: stringResource(R.string.settings_holiday_country_unknown),
                        ),
                        checked = holidayCountrySelection.current,
                        enabled = !holidayCountrySelection.all,
                        onCheckedChange = onSetCountryCurrent,
                    )
                    CheckboxRow(
                        label = stringResource(R.string.settings_holiday_country_all_label),
                        checked = holidayCountrySelection.all,
                        onCheckedChange = onSetCountryAll,
                    )
                }
            }

            isoCountries.forEach { code ->
                val themes = themesByCountry[code].orEmpty()
                val activeCount = themes.count { theme -> theme.isActive(holidayOverrides, effectiveEnabledHolidayCountries) }
                val countryOverride = holidayCountrySelection.countryOverrides[code] ?: HolidayOverride.AUTO
                val countryAutoOn = holidayCountrySelection.countryAutoEffective(
                    code,
                    localeCountry,
                    weatherLocationCountry,
                )
                CollapsibleSection(
                    title = resolveCountryDisplayName(context, uiLocale, code),
                    summary = "$activeCount/${themes.size}",
                    rememberKey = "holidays-country-$code",
                    trailing = {
                        OverrideDropdown(
                            current = countryOverride,
                            autoOn = countryAutoOn,
                            onChange = { newState -> onSetCountryOverride(code, newState) },
                        )
                    },
                ) {
                    themes.forEach { theme ->
                        HolidayOverrideRow(
                            theme = theme,
                            override = holidayOverrides[theme.id] ?: HolidayOverride.AUTO,
                            autoOn = theme.countries.any { it in effectiveEnabledHolidayCountries },
                            onChange = { newState -> onSetHolidayOverride(theme.id, newState) },
                        )
                    }
                }
            }

            val allActiveCount = allThemes.count { theme -> theme.isActive(holidayOverrides, effectiveEnabledHolidayCountries) }
            CollapsibleSection(
                title = stringResource(R.string.settings_holiday_all_section_title),
                summary = "$allActiveCount/${allThemes.size}",
                rememberKey = "holidays-all-section",
            ) {
                allThemes.forEach { theme ->
                    HolidayOverrideRow(
                        theme = theme,
                        override = holidayOverrides[theme.id] ?: HolidayOverride.AUTO,
                        autoOn = theme.countries.any { it in effectiveEnabledHolidayCountries },
                        onChange = { newState -> onSetHolidayOverride(theme.id, newState) },
                    )
                }
            }
        }
    }
}

private fun HolidayTheme.isActive(
    overrides: Map<HolidayId, HolidayOverride>,
    effectiveCountries: Set<String>,
): Boolean = when (overrides[id] ?: HolidayOverride.AUTO) {
    HolidayOverride.ON -> true
    HolidayOverride.OFF -> false
    HolidayOverride.AUTO -> countries.any { it in effectiveCountries }
}

/**
 * Card with a tap-to-expand header. The summary text (e.g. `3/15`) sits
 * to the right of the title and the expand/collapse chevron is at the
 * far right. An optional [trailing] slot (e.g. the country override
 * dropdown) sits between summary and chevron. `rememberKey` scopes the
 * expansion state via [rememberSaveable] so it survives config changes.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    summary: String,
    rememberKey: String,
    initiallyExpanded: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(rememberKey) { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                if (trailing != null) {
                    trailing()
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.settings_holidays_collapse
                            else R.string.settings_holidays_expand
                        ),
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = { content() },
                )
            }
        }
    }
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun HolidayOverrideRow(
    theme: HolidayTheme,
    override: HolidayOverride,
    autoOn: Boolean,
    onChange: (HolidayOverride) -> Unit,
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
        OverrideDropdown(
            current = override,
            autoOn = autoOn,
            onChange = onChange,
        )
    }
}

/**
 * Three-option dropdown for the per-holiday override. The visible trigger
 * label matches whichever option is currently selected; for [HolidayOverride.AUTO]
 * the label appends the current resolution in parentheses ("Auto (on)" /
 * "Auto (off)").
 */
@Composable
private fun OverrideDropdown(
    current: HolidayOverride,
    autoOn: Boolean,
    onChange: (HolidayOverride) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val autoLabel = stringResource(
        if (autoOn) R.string.settings_holiday_override_auto_on
        else R.string.settings_holiday_override_auto_off
    )
    val onLabel = stringResource(R.string.settings_holiday_override_on)
    val offLabel = stringResource(R.string.settings_holiday_override_off)
    val triggerLabel = when (current) {
        HolidayOverride.AUTO -> autoLabel
        HolidayOverride.ON -> onLabel
        HolidayOverride.OFF -> offLabel
    }
    TextButton(onClick = { expanded = true }) {
        Text(triggerLabel)
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(autoLabel) },
                onClick = {
                    expanded = false
                    onChange(HolidayOverride.AUTO)
                },
            )
            DropdownMenuItem(
                text = { Text(onLabel) },
                onClick = {
                    expanded = false
                    onChange(HolidayOverride.ON)
                },
            )
            DropdownMenuItem(
                text = { Text(offLabel) },
                onClick = {
                    expanded = false
                    onChange(HolidayOverride.OFF)
                },
            )
        }
    }
}

/**
 * Resolves a string by name without holding a compile-time R reference,
 * so `:core:domain` doesn't have to know about Android resource IDs.
 * Returns null when the name is unknown (typo / not localised yet) so
 * callers can fall back to a developer-visible default rather than
 * crashing.
 */
@Composable
private fun resolveHolidayString(name: String): String? {
    val context = LocalContext.current
    val resId = context.resources.getIdentifier(name, "string", context.packageName)
    return if (resId == 0) null else context.getString(resId)
}

/**
 * Localised display name for a country code. ISO codes resolve via the
 * `settings_holiday_country_<lower>` string family, falling back to
 * [Locale]'s display name (e.g. "United States") if the resource is
 * missing and finally to the raw code so the row is always readable.
 */
private fun resolveCountryDisplayName(
    context: android.content.Context,
    uiLocale: Locale,
    code: String,
): String {
    val resName = "settings_holiday_country_${code.lowercase()}"
    val resId = context.resources.getIdentifier(resName, "string", context.packageName)
    if (resId != 0) return context.getString(resId)
    return Locale("", code).getDisplayCountry(uiLocale).ifBlank { code }
}

/**
 * Sorts a country-code collection for UI display alphabetised by their
 * localised display name via a locale-aware [Collator] so the order
 * reads naturally in the user's UI language.
 */
private fun Collection<String>.sortedForDisplay(
    context: android.content.Context,
    uiLocale: Locale,
): List<String> {
    val collator = Collator.getInstance(uiLocale)
    val labelled = map { code -> code to resolveCountryDisplayName(context, uiLocale, code) }
    return labelled.sortedWith(compareBy(collator) { it.second }).map { it.first }
}
