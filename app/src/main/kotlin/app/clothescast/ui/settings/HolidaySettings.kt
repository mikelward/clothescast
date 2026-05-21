package app.clothescast.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.clothescast.R
import app.clothescast.calendar.CalendarPermission
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayCountrySelection
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.ui.EdgeFadeOverlay
import java.text.Collator
import java.time.LocalDate
import java.util.Locale

/**
 * A top-of-page card with the country-source toggles (Home / Current
 * location / Global / Funny / All), followed by a Global collapsible
 * listing the four universal holidays (Christmas, NYE, Halloween,
 * Valentine's), a Funny collapsible listing the playful observances
 * (Talk Like a Pirate Day), then one collapsible per ISO country
 * listing that country's holidays, and finally an "All" collapsible
 * showing every holiday in the catalogue flat for power-user search.
 *
 * Both section headers and individual holiday rows carry a tri-state
 * dropdown (`Auto` / `On` / `Off`). The `Auto` label includes the
 * current resolution ("Auto (on)" / "Auto (off)") so the user can see
 * what the country picker (or the country override) is doing without
 * flipping it in their head.
 *
 * Section headers also show an `(n/m)` summary — holidays currently
 * active / total for that bucket.
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
    themeFromCalendarHolidays: Boolean,
    themeFromCalendarBirthdays: Boolean,
    padding: PaddingValues,
    onSetCountryHome: (Boolean) -> Unit,
    onSetCountryCurrent: (Boolean) -> Unit,
    onSetCountryGlobal: (Boolean) -> Unit,
    onSetCountryFunny: (Boolean) -> Unit,
    onSetCountryAll: (Boolean) -> Unit,
    onSetCountryOverride: (String, HolidayOverride) -> Unit,
    onSetHolidayOverride: (HolidayId, HolidayOverride) -> Unit,
    onSetThemeFromCalendarHolidays: (Boolean) -> Unit,
    onSetThemeFromCalendarBirthdays: (Boolean) -> Unit,
    onCalendarPermissionRechecked: () -> Unit,
    onNavigateToRegionSettings: () -> Unit,
    onNavigateToLocationSettings: () -> Unit,
    onNavigateToCalendarSettings: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val uiLocale = remember(context.resources.configuration) {
        context.resources.configuration.locales.get(0) ?: Locale.getDefault()
    }

    // Sort holidays chronologically within each bucket. Movable holidays
    // (NthWeekday / LastWeekday) are materialised against [sortYear] —
    // today's year — and the comparator reads only (month, dayOfMonth)
    // so the relative ordering is effectively year-agnostic. We re-derive
    // when the year ticks over (a midnight-on-Dec-31 edge case in
    // practice, but cheap to key on).
    val sortYear = remember { LocalDate.now().year }
    val sortedCatalog = remember(sortYear) {
        HolidayCatalog.all.sortedWith(
            compareBy(
                { (date, _) -> date.dateIn(sortYear).monthValue },
                { (date, _) -> date.dateIn(sortYear).dayOfMonth },
            ),
        )
    }
    // Globals (countries = {GLOBAL_COUNTRY}) live in their own collapsible
    // above the ISO list, no longer folded into every country's bucket.
    val globalThemes = remember(sortedCatalog) {
        sortedCatalog
            .map { it.second }
            .filter { HolidayCatalog.GLOBAL_COUNTRY in it.countries }
    }
    // Funny (countries = {FUNNY}) lives in its own collapsible like Global,
    // separate from the ISO country list below.
    val funnyThemes = remember(sortedCatalog) {
        sortedCatalog
            .map { it.second }
            .filter { HolidayCatalog.FUNNY in it.countries }
    }
    val isoCountries = remember(uiLocale) {
        HolidayCatalog.allCountries
            .filter { it != HolidayCatalog.GLOBAL_COUNTRY && it != HolidayCatalog.FUNNY }
            .sortedForDisplay(context, uiLocale)
    }
    val themesByCountry = remember(sortedCatalog) {
        isoCountries.associateWith { code ->
            sortedCatalog
                .map { it.second }
                .filter { code in it.countries }
        }
    }
    val allThemes = remember(sortedCatalog) {
        sortedCatalog.map { it.second }
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
            // Sources card — five holiday-source toggles, each in its own
            // row. Region / Location / Calendar Holidays / Calendar Birthdays
            // carry a deep-link to the corresponding settings page. Global
            // is the universal-holiday bucket (Christmas, NYE, Halloween,
            // Valentine's) with no dedicated settings target.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SourceRow(
                        label = stringResource(
                            R.string.settings_holidays_source_region,
                            localeCountry?.let { resolveCountryDisplayName(context, uiLocale, it) }
                                ?: stringResource(R.string.settings_holiday_country_unknown),
                        ),
                        checked = holidayCountrySelection.home,
                        onCheckedChange = onSetCountryHome,
                        linkLabel = stringResource(R.string.settings_holidays_link_settings),
                        linkContentDescription = stringResource(R.string.settings_holidays_link_region_settings_a11y),
                        onLinkClick = onNavigateToRegionSettings,
                    )
                    SourceRow(
                        label = stringResource(
                            R.string.settings_holidays_source_location,
                            weatherLocationCountry?.let { resolveCountryDisplayName(context, uiLocale, it) }
                                ?: stringResource(R.string.settings_holiday_country_unknown),
                        ),
                        checked = holidayCountrySelection.current,
                        onCheckedChange = onSetCountryCurrent,
                        linkLabel = stringResource(R.string.settings_holidays_link_settings),
                        linkContentDescription = stringResource(R.string.settings_holidays_link_location_settings_a11y),
                        onLinkClick = onNavigateToLocationSettings,
                    )
                    SourceRow(
                        label = stringResource(R.string.settings_holidays_source_global),
                        checked = holidayCountrySelection.global,
                        onCheckedChange = onSetCountryGlobal,
                    )
                    SourceRow(
                        label = stringResource(R.string.settings_holidays_source_funny),
                        checked = holidayCountrySelection.funny,
                        onCheckedChange = onSetCountryFunny,
                    )
                    CalendarSourceRow(
                        label = stringResource(R.string.settings_holidays_source_calendar_holidays),
                        checked = themeFromCalendarHolidays,
                        onSetChecked = onSetThemeFromCalendarHolidays,
                        onPermissionRechecked = onCalendarPermissionRechecked,
                        linkLabel = stringResource(R.string.settings_holidays_link_settings),
                        linkContentDescription = stringResource(R.string.settings_holidays_link_calendar_settings_a11y),
                        onLinkClick = onNavigateToCalendarSettings,
                    )
                    CalendarSourceRow(
                        label = stringResource(R.string.settings_holidays_source_calendar_birthdays),
                        checked = themeFromCalendarBirthdays,
                        onSetChecked = onSetThemeFromCalendarBirthdays,
                        onPermissionRechecked = onCalendarPermissionRechecked,
                        linkLabel = stringResource(R.string.settings_holidays_link_settings),
                        linkContentDescription = stringResource(R.string.settings_holidays_link_calendar_settings_a11y),
                        onLinkClick = onNavigateToCalendarSettings,
                    )
                    // Legacy "All countries" toggle, conditionally rendered so
                    // users who'd enabled the previous UI's All checkbox can
                    // turn it off from here. With `all=true` the resolver
                    // short-circuits every country on, making Region/Location/
                    // Global toggles below visually-meaningless — surface a
                    // clear opt-out instead of forcing app-data wipe.
                    if (holidayCountrySelection.all) {
                        SourceRow(
                            label = stringResource(R.string.settings_holidays_source_all),
                            checked = true,
                            onCheckedChange = onSetCountryAll,
                        )
                    }
                }
            }

            // Calendar Holidays / Birthdays placeholder collapsibles.
            // First-pass stubs — the real per-event listing + overrides
            // land in a follow-up PR (see TODO at the end of this file).
            CalendarSectionPlaceholder(
                title = stringResource(R.string.settings_holidays_source_calendar_holidays),
                checked = themeFromCalendarHolidays,
                rememberKey = "holidays-calendar-holidays-section",
            )
            CalendarSectionPlaceholder(
                title = stringResource(R.string.settings_holidays_source_calendar_birthdays),
                checked = themeFromCalendarBirthdays,
                rememberKey = "holidays-calendar-birthdays-section",
            )

            val globalActiveCount = globalThemes.count { theme ->
                theme.isActive(holidayOverrides, effectiveEnabledHolidayCountries)
            }
            val globalOverride = holidayCountrySelection.countryOverrides[HolidayCatalog.GLOBAL_COUNTRY]
                ?: HolidayOverride.AUTO
            val globalAutoOn = holidayCountrySelection.countryAutoEffective(
                HolidayCatalog.GLOBAL_COUNTRY,
                localeCountry,
                weatherLocationCountry,
            )
            CollapsibleSection(
                title = stringResource(R.string.settings_holiday_country_global_label),
                summary = "$globalActiveCount/${globalThemes.size}",
                rememberKey = "holidays-country-${HolidayCatalog.GLOBAL_COUNTRY}",
                trailing = {
                    OverrideDropdown(
                        current = globalOverride,
                        autoOn = globalAutoOn,
                        onChange = { newState ->
                            onSetCountryOverride(HolidayCatalog.GLOBAL_COUNTRY, newState)
                        },
                    )
                },
            ) {
                globalThemes.forEach { theme ->
                    HolidayOverrideRow(
                        theme = theme,
                        override = holidayOverrides[theme.id] ?: HolidayOverride.AUTO,
                        autoOn = theme.countries.any { it in effectiveEnabledHolidayCountries },
                        onChange = { newState -> onSetHolidayOverride(theme.id, newState) },
                    )
                }
            }

            val funnyActiveCount = funnyThemes.count { theme ->
                theme.isActive(holidayOverrides, effectiveEnabledHolidayCountries)
            }
            val funnyOverride = holidayCountrySelection.countryOverrides[HolidayCatalog.FUNNY]
                ?: HolidayOverride.AUTO
            val funnyAutoOn = holidayCountrySelection.countryAutoEffective(
                HolidayCatalog.FUNNY,
                localeCountry,
                weatherLocationCountry,
            )
            CollapsibleSection(
                title = stringResource(R.string.settings_holiday_country_funny_label),
                summary = "$funnyActiveCount/${funnyThemes.size}",
                rememberKey = "holidays-country-${HolidayCatalog.FUNNY}",
                trailing = {
                    OverrideDropdown(
                        current = funnyOverride,
                        autoOn = funnyAutoOn,
                        onChange = { newState ->
                            onSetCountryOverride(HolidayCatalog.FUNNY, newState)
                        },
                    )
                },
            ) {
                funnyThemes.forEach { theme ->
                    HolidayOverrideRow(
                        theme = theme,
                        override = holidayOverrides[theme.id] ?: HolidayOverride.AUTO,
                        autoOn = theme.countries.any { it in effectiveEnabledHolidayCountries },
                        onChange = { newState -> onSetHolidayOverride(theme.id, newState) },
                    )
                }
            }

            // Pull the Region and Location countries (if any) to the top of
            // the per-country list — the user's "own" countries first, then
            // the rest of the catalog alphabetically.
            val pinnedCountries = listOfNotNull(localeCountry?.uppercase(), weatherLocationCountry?.uppercase())
                .filter { it in isoCountries }
                .distinct()
            val orderedCountries = pinnedCountries + isoCountries.filterNot { it in pinnedCountries }

            orderedCountries.forEach { code ->
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

            // TODO(celebrations-v2): when the "My calendar holidays" /
            // "My calendar birthdays" toggles are on, add two extra
            // collapsibles at the bottom listing the actual events
            // detected in the user's synced calendars for the next
            // ~30 days, each with a per-event override dropdown (same
            // pattern as the country sections above). Lets the user
            // mute a specific event ("Boxing day" they don't celebrate;
            // a noisy birthday import) without disabling the whole
            // toggle. Needs a CalendarEvent → HolidayId stable-key
            // scheme that survives event-recurrence renames.

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

/**
 * Top-section toggle row: a checkbox + label, with an optional trailing
 * deep-link button right-aligned on the same row. Used for the Region /
 * Location / Global rows in the sources card. Calendar-sourced rows use
 * [CalendarSourceRow] instead since they need permission-prompt +
 * revocation-warning behaviour.
 *
 * The label takes `weight(1f)` so on narrow displays / long country
 * names it ellipsises before the trailing button gets squeezed off
 * (e.g. "Region (United Kingdom)" → "Region (United Kingd…)").
 */
@Composable
private fun SourceRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    linkLabel: String? = null,
    linkContentDescription: String? = null,
    onLinkClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (linkLabel != null && onLinkClick != null) {
            SettingsLinkButton(
                label = linkLabel,
                contentDescription = linkContentDescription,
                onClick = onLinkClick,
            )
        }
    }
}

/**
 * `TextButton` whose visible text is intentionally short (just "Settings")
 * but whose accessible name is overridden via [Modifier.semantics] when a
 * [contentDescription] is supplied. Lets TalkBack / Voice Access users
 * distinguish the four otherwise-identical Settings buttons in the
 * Celebrations sources card (Region / Location / Calendar Holidays /
 * Calendar Birthdays).
 */
@Composable
private fun SettingsLinkButton(
    label: String,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = if (contentDescription != null) {
            Modifier.semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            }
        } else {
            Modifier
        },
    ) { Text(label) }
}

/**
 * Top-section toggle row for one of the calendar-sourced theming options
 * (Calendar Holidays, Calendar Birthdays). Flips the pref through the
 * supplied callback when permission is already granted; otherwise fires
 * the `READ_CALENDAR` prompt and only flips on grant. Disabling leaves
 * permissions unchanged.
 *
 * Visual `checked` follows the stored pref directly (NOT `pref && permission`)
 * so a user who revoked READ_CALENDAR can still tap the visually-checked
 * box to disable the feature without re-granting. An inline warning row
 * surfaces the "pref says on but permission missing" state and offers a
 * re-grant.
 *
 * Revocation propagation: an ON_RESUME observer notices grant/revoke
 * transitions from system Settings and fires [onPermissionRechecked] so
 * `TodayViewModel`'s cached events list refreshes promptly instead of
 * lingering until midnight.
 */
@Composable
private fun CalendarSourceRow(
    label: String,
    checked: Boolean,
    onSetChecked: (Boolean) -> Unit,
    onPermissionRechecked: () -> Unit,
    linkLabel: String,
    linkContentDescription: String,
    onLinkClick: () -> Unit,
) {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(CalendarPermission.isGranted(context)) }
    val currentChecked by rememberUpdatedState(checked)
    val currentOnPermissionRechecked by rememberUpdatedState(onPermissionRechecked)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val wasGranted = permissionGranted
                val nowGranted = CalendarPermission.isGranted(context)
                permissionGranted = nowGranted
                if (wasGranted != nowGranted && currentChecked) {
                    currentOnPermissionRechecked()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) onSetChecked(true)
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { wantsOn ->
                    if (!wantsOn) {
                        onSetChecked(false)
                    } else if (permissionGranted) {
                        onSetChecked(true)
                    } else {
                        launcher.launch(CalendarPermission.MANIFEST_PERMISSION)
                    }
                },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            SettingsLinkButton(
                label = linkLabel,
                contentDescription = linkContentDescription,
                onClick = onLinkClick,
            )
        }
        if (checked && !permissionGranted) {
            TextButton(
                onClick = { launcher.launch(CalendarPermission.MANIFEST_PERMISSION) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier.padding(start = 56.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_holidays_permission_revoked),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * First-pass placeholder for the Calendar Holidays / Calendar Birthdays
 * collapsibles in the lower list. Renders the same [CollapsibleSection]
 * chrome as the country sections so it visually fits, but the body is a
 * static message until follow-up work lands per-event listing + override
 * dropdowns. Summary is `—/—` until we have real event data.
 *
 * TODO(celebrations-v2): swap the placeholder body for a list of
 * detected events from the user's synced calendar (today plus a small
 * forward window), each with an On/Off override stored against a stable
 * per-event key. Requires a `CalendarEvent → HolidayOverride` storage
 * scheme that survives event-recurrence renames.
 */
@Composable
private fun CalendarSectionPlaceholder(
    title: String,
    checked: Boolean,
    rememberKey: String,
) {
    CollapsibleSection(
        title = title,
        summary = "—/—",
        rememberKey = rememberKey,
    ) {
        val message = if (checked) {
            stringResource(R.string.settings_holidays_calendar_section_placeholder_on)
        } else {
            stringResource(R.string.settings_holidays_calendar_section_placeholder_off)
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
