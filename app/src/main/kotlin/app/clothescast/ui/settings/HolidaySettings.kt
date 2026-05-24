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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.clothescast.R
import app.clothescast.calendar.CalendarPermission
import app.clothescast.diag.findActivity
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayCountrySelection
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.core.domain.model.UpcomingCalendarEvent
import app.clothescast.ui.EdgeFadeOverlay
import kotlinx.coroutines.launch
import java.text.Collator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The Calendar settings page. Top to bottom: a "Use my calendar" permission
 * master toggle, a "What to use it for" card with the three calendar-sourced
 * feature toggles (Evening tie-ins, Birthdays, Public holidays), the
 * country-source toggles (Region / Location / Global / Funny / All), the two
 * upcoming-celebration listings (Calendar Holidays / Birthdays), and finally
 * the curated holiday catalogue: a Global collapsible listing the four
 * universal holidays (Christmas, NYE, Halloween, Valentine's), a Funny
 * collapsible listing the playful observances (Talk Like a Pirate Day), one
 * collapsible per ISO country, and an "All" collapsible showing every holiday
 * flat for power-user search.
 *
 * The calendar permission toggle gates only the calendar-sourced features and
 * the upcoming-event listings; the curated catalogue (country sources +
 * collapsibles) is independent of it and stays usable without calendar access.
 *
 * Catalogue section headers and individual holiday rows carry a tri-state
 * dropdown (`Auto` / `On` / `Off`). The `Auto` label includes the current
 * resolution ("Auto (on)" / "Auto (off)") so the user can see what the country
 * picker (or the country override) is doing without flipping it in their head.
 * Section headers also show an `(n/m)` summary — holidays currently active /
 * total for that bucket.
 *
 * Resource IDs are looked up via [LocalContext]'s `getIdentifier` so the
 * theme catalogue can live in `:core:domain` without depending on `R`.
 * Missing translations fall back to the raw enum name / ISO code.
 */
@Composable
internal fun CalendarContent(
    holidayCountrySelection: HolidayCountrySelection,
    holidayOverrides: Map<HolidayId, HolidayOverride>,
    effectiveEnabledHolidayCountries: Set<String>,
    localeCountry: String?,
    weatherLocationCountry: String?,
    calendarEnabled: Boolean,
    useCalendarEvents: Boolean,
    themeFromCalendarHolidays: Boolean,
    themeFromCalendarBirthdays: Boolean,
    calendarCelebrations: List<UpcomingCalendarEvent>?,
    padding: PaddingValues,
    onSetCalendarEnabled: (Boolean) -> Unit,
    onSetUseCalendarEvents: (Boolean) -> Unit,
    onSetCountryHome: (Boolean) -> Unit,
    onSetCountryCurrent: (Boolean) -> Unit,
    onSetCountryGlobal: (Boolean) -> Unit,
    onSetCountryFunny: (Boolean) -> Unit,
    onSetCountryChristian: (Boolean) -> Unit,
    onSetCountryOrthodox: (Boolean) -> Unit,
    onSetCountryAll: (Boolean) -> Unit,
    onSetCountryOverride: (String, HolidayOverride) -> Unit,
    onSetHolidayOverride: (HolidayId, HolidayOverride) -> Unit,
    onSetThemeFromCalendarHolidays: (Boolean) -> Unit,
    onSetThemeFromCalendarBirthdays: (Boolean) -> Unit,
    onCalendarPermissionRechecked: () -> Unit,
    onLoadCalendarCelebrations: () -> Unit,
    onNavigateToRegionSettings: () -> Unit,
    onNavigateToLocationSettings: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val uiLocale = remember(context.resources.configuration) {
        context.resources.configuration.locales.get(0) ?: Locale.getDefault()
    }

    // One source of permission truth for the whole page — the master toggle, the
    // three feature toggles, and the two upcoming-celebration listings all read
    // it. Re-checked on resume so a grant/revoke from system Settings is
    // reflected without leaving the page; a revoke flips the master switch off
    // (and pings consumers to refresh) so we don't keep claiming calendar access
    // we no longer have. The curated holiday catalogue below is deliberately NOT
    // gated on this — built-in holidays work without calendar permission.
    var permissionGranted by remember { mutableStateOf(CalendarPermission.isGranted(context)) }
    // True once a permission request comes back denied *and* the system won't
    // show the rationale dialog again ("don't ask again" / permanently denied).
    // In that state tapping a toggle does nothing — the OS suppresses the prompt
    // — so the only way back is the app's system-settings screen, which is the
    // sole reason we surface a button at all.
    var permanentlyDenied by remember { mutableStateOf(false) }
    val currentCalendarEnabled by rememberUpdatedState(calendarEnabled)
    val currentOnSetCalendarEnabled by rememberUpdatedState(onSetCalendarEnabled)
    val currentOnRechecked by rememberUpdatedState(onCalendarPermissionRechecked)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = CalendarPermission.isGranted(context)
                permissionGranted = granted
                if (granted) {
                    permanentlyDenied = false
                } else if (currentCalendarEnabled) {
                    currentOnSetCalendarEnabled(false)
                    currentOnRechecked()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Single permission launcher with a pending action: whichever toggle asked
    // to turn on runs its enable callback once permission is granted. The VM
    // flips the master switch on when any sub-feature is enabled, so enabling a
    // sub-feature from scratch (master off) prompts once and lights up both.
    var pendingEnable by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            permanentlyDenied = false
            pendingEnable?.invoke()
            currentOnRechecked()
        } else {
            // Denied. If the system would still show the rationale dialog the
            // user can just try the toggle again; if not, they're permanently
            // denied and we point them at system settings.
            val activity = context.findActivity()
            permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    CalendarPermission.MANIFEST_PERMISSION,
                )
        }
        pendingEnable = null
    }
    val requestThenEnable: (() -> Unit) -> Unit = { action ->
        if (permissionGranted) action() else {
            pendingEnable = action
            launcher.launch(CalendarPermission.MANIFEST_PERMISSION)
        }
    }
    // The listings' in-place "grant permission" prompt requests access without a
    // pending feature enable — granting just lets the read run.
    val requestPermissionForListing: () -> Unit = {
        if (!permissionGranted) launcher.launch(CalendarPermission.MANIFEST_PERMISSION)
    }
    // Master off → the listing prompt scrolls back up to the toggle (same page)
    // rather than deep-linking elsewhere.
    val scrollToTop: () -> Unit = { coroutineScope.launch { scrollState.animateScrollTo(0) } }

    LaunchedEffect(calendarEnabled, permissionGranted) {
        if (calendarEnabled && permissionGranted) onLoadCalendarCelebrations()
    }
    val calendarHolidays = calendarCelebrations?.filter { it.kind == EventKind.PUBLIC_HOLIDAY }
    val calendarBirthdays = calendarCelebrations?.filter { it.kind == EventKind.BIRTHDAY }

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
    // Christian and Orthodox religious buckets — peers of Global / Funny.
    // Each holiday is also tagged with its observing countries so it still
    // resolves via the per-country path when the religious bucket is off.
    val christianThemes = remember(sortedCatalog) {
        sortedCatalog
            .map { it.second }
            .filter { HolidayCatalog.CHRISTIAN in it.countries }
    }
    val orthodoxThemes = remember(sortedCatalog) {
        sortedCatalog
            .map { it.second }
            .filter { HolidayCatalog.ORTHODOX in it.countries }
    }
    val isoCountries = remember(uiLocale) {
        HolidayCatalog.allCountries
            .filter {
                it != HolidayCatalog.GLOBAL_COUNTRY &&
                    it != HolidayCatalog.FUNNY &&
                    it != HolidayCatalog.CHRISTIAN &&
                    it != HolidayCatalog.ORTHODOX
            }
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

    // Tucks the per-country / Global / Funny / All collapsibles behind a
    // single expand row at the bottom of the Built-in holidays card.
    // Collapsed by default so the page top stays scannable; expansion state
    // is saved across config changes for the power-user case.
    var browseCountriesExpanded by rememberSaveable { mutableStateOf(false) }

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
            SectionCard(title = stringResource(R.string.settings_calendar_features_title)) {
                // Master toggle for calendar access. Gating: when this is off,
                // the three feature rows below render as off (their `checked`
                // gates on `calendarEnabled && permissionGranted`) and a
                // request-permission tap on any of them re-enables the master
                // before flipping the feature pref. On: the toggle itself is
                // the permission prompt — no separate "grant" button. Off: we
                // just stop reading in-app (the *Active gates); we deliberately
                // don't relinquish the OS permission — there's no immediate
                // API for it, and `revokeSelfPermissionsOnKill` is API 33+
                // (minSdk is 31) and only takes effect after the process is
                // killed, so it'd add a version-gated, surprising code path
                // for no real benefit until we bump minSdk to Android 13+.
                CalendarFeatureRow(
                    label = stringResource(R.string.settings_calendar_master),
                    description = stringResource(R.string.settings_calendar_master_description),
                    checked = calendarEnabled && permissionGranted,
                    onToggle = { wantsOn ->
                        if (wantsOn) requestThenEnable { onSetCalendarEnabled(true) }
                        else onSetCalendarEnabled(false)
                    },
                )
                if (permanentlyDenied && !permissionGranted) {
                    Text(
                        text = stringResource(R.string.settings_calendar_open_settings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = { openAppDetails(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_calendar_open_system_settings)) }
                }
                CalendarFeatureRow(
                    label = stringResource(R.string.settings_calendar_evening_tie_ins),
                    description = stringResource(R.string.settings_calendar_evening_tie_ins_description),
                    checked = calendarEnabled && useCalendarEvents && permissionGranted,
                    onToggle = { wantsOn ->
                        if (wantsOn) requestThenEnable { onSetUseCalendarEvents(true) }
                        else onSetUseCalendarEvents(false)
                    },
                )
                CalendarFeatureRow(
                    label = stringResource(R.string.settings_calendar_birthdays),
                    description = stringResource(R.string.settings_calendar_birthdays_description),
                    checked = calendarEnabled && themeFromCalendarBirthdays && permissionGranted,
                    onToggle = { wantsOn ->
                        if (wantsOn) requestThenEnable { onSetThemeFromCalendarBirthdays(true) }
                        else onSetThemeFromCalendarBirthdays(false)
                    },
                )
                CalendarFeatureRow(
                    label = stringResource(R.string.settings_calendar_public_holidays),
                    description = stringResource(R.string.settings_calendar_public_holidays_description),
                    checked = calendarEnabled && themeFromCalendarHolidays && permissionGranted,
                    onToggle = { wantsOn ->
                        if (wantsOn) requestThenEnable { onSetThemeFromCalendarHolidays(true) }
                        else onSetThemeFromCalendarHolidays(false)
                    },
                )
            }

            // Curated celebration sources — country buckets that drive the
            // built-in holiday catalogue below. Independent of calendar
            // permission. Region / Location carry a deep-link to the
            // corresponding settings page; Global / Funny have no dedicated
            // target.
            SectionCard(title = stringResource(R.string.settings_holidays_sources_title)) {
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
                SourceRow(
                    label = stringResource(R.string.settings_holidays_source_christian),
                    checked = holidayCountrySelection.christian,
                    onCheckedChange = onSetCountryChristian,
                )
                SourceRow(
                    label = stringResource(R.string.settings_holidays_source_orthodox),
                    checked = holidayCountrySelection.orthodox,
                    onCheckedChange = onSetCountryOrthodox,
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
                // Expand row that reveals the per-country / Global / Funny /
                // All catalogue below the card. Off-page by default so the
                // top of Calendar stays scannable.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_holidays_browse_by_country),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { browseCountriesExpanded = !browseCountriesExpanded }) {
                        Icon(
                            imageVector = if (browseCountriesExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(
                                if (browseCountriesExpanded) R.string.settings_holidays_collapse
                                else R.string.settings_holidays_expand
                            ),
                        )
                    }
                }
            }

            // Per-country / Global / Funny / All catalogue — drill-down for
            // the four source-row toggles above. Off-page by default and
            // revealed via the "Browse by country" expand row on the
            // Built-in holidays card. Each section's expansion state is
            // independently saved via [CollapsibleSection]'s rememberKey, so
            // toggling Browse-by-country off and on returns the user to the
            // same sub-section they were drilling into.
            if (browseCountriesExpanded) {
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

                val christianActiveCount = christianThemes.count { theme ->
                    theme.isActive(holidayOverrides, effectiveEnabledHolidayCountries)
                }
                val christianOverride = holidayCountrySelection.countryOverrides[HolidayCatalog.CHRISTIAN]
                    ?: HolidayOverride.AUTO
                val christianAutoOn = holidayCountrySelection.countryAutoEffective(
                    HolidayCatalog.CHRISTIAN,
                    localeCountry,
                    weatherLocationCountry,
                )
                CollapsibleSection(
                    title = stringResource(R.string.settings_holiday_country_christian_label),
                    summary = "$christianActiveCount/${christianThemes.size}",
                    rememberKey = "holidays-country-${HolidayCatalog.CHRISTIAN}",
                    trailing = {
                        OverrideDropdown(
                            current = christianOverride,
                            autoOn = christianAutoOn,
                            onChange = { newState ->
                                onSetCountryOverride(HolidayCatalog.CHRISTIAN, newState)
                            },
                        )
                    },
                ) {
                    christianThemes.forEach { theme ->
                        HolidayOverrideRow(
                            theme = theme,
                            override = holidayOverrides[theme.id] ?: HolidayOverride.AUTO,
                            autoOn = theme.countries.any { it in effectiveEnabledHolidayCountries },
                            onChange = { newState -> onSetHolidayOverride(theme.id, newState) },
                        )
                    }
                }

                val orthodoxActiveCount = orthodoxThemes.count { theme ->
                    theme.isActive(holidayOverrides, effectiveEnabledHolidayCountries)
                }
                val orthodoxOverride = holidayCountrySelection.countryOverrides[HolidayCatalog.ORTHODOX]
                    ?: HolidayOverride.AUTO
                val orthodoxAutoOn = holidayCountrySelection.countryAutoEffective(
                    HolidayCatalog.ORTHODOX,
                    localeCountry,
                    weatherLocationCountry,
                )
                CollapsibleSection(
                    title = stringResource(R.string.settings_holiday_country_orthodox_label),
                    summary = "$orthodoxActiveCount/${orthodoxThemes.size}",
                    rememberKey = "holidays-country-${HolidayCatalog.ORTHODOX}",
                    trailing = {
                        OverrideDropdown(
                            current = orthodoxOverride,
                            autoOn = orthodoxAutoOn,
                            onChange = { newState ->
                                onSetCountryOverride(HolidayCatalog.ORTHODOX, newState)
                            },
                        )
                    },
                ) {
                    orthodoxThemes.forEach { theme ->
                        HolidayOverrideRow(
                            theme = theme,
                            override = holidayOverrides[theme.id] ?: HolidayOverride.AUTO,
                            autoOn = theme.countries.any { it in effectiveEnabledHolidayCountries },
                            onChange = { newState -> onSetHolidayOverride(theme.id, newState) },
                        )
                    }
                }

                // Pull the Region and Location countries (if any) to the top
                // of the per-country list — the user's "own" countries first,
                // then the rest of the catalog alphabetically.
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

            // TODO(celebrations-v2): give each row in the Calendar Holidays /
            // Birthdays listings below a per-event override dropdown (same
            // pattern as the country sections) so the user can mute a specific
            // event ("Boxing Day" they don't celebrate; a noisy birthday import)
            // without disabling the whole source. Needs a CalendarEvent → stable
            // key scheme that survives event-recurrence renames, persisted
            // alongside holidayOverrides.

            // Calendar Holidays / Birthdays listings — the next year of detected
            // events from the user's synced calendars, collapsed by default and
            // gated on READ_CALENDAR. When permission is missing each section
            // offers an in-place grant prompt instead of a list.
            CalendarCelebrationsSection(
                title = stringResource(R.string.settings_holidays_source_calendar_holidays),
                rememberKey = "holidays-calendar-holidays-section",
                calendarEnabled = calendarEnabled,
                permissionGranted = permissionGranted,
                events = calendarHolidays,
                emptyMessage = stringResource(R.string.settings_holidays_calendar_no_holidays),
                uiLocale = uiLocale,
                onRequestPermission = requestPermissionForListing,
                onEnableCalendar = scrollToTop,
            )
            CalendarCelebrationsSection(
                title = stringResource(R.string.settings_holidays_source_calendar_birthdays),
                rememberKey = "holidays-calendar-birthdays-section",
                calendarEnabled = calendarEnabled,
                permissionGranted = permissionGranted,
                events = calendarBirthdays,
                emptyMessage = stringResource(R.string.settings_holidays_calendar_no_birthdays),
                uiLocale = uiLocale,
                onRequestPermission = requestPermissionForListing,
                onEnableCalendar = scrollToTop,
            )
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
 * Location / Global / Funny rows in the celebration sources card.
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
 * distinguish the otherwise-identical Settings buttons in the celebration
 * sources card (Region / Location).
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

/** A sub-feature row: label + supporting description on the left, switch right. */
@Composable
private fun CalendarFeatureRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/**
 * One of the two calendar-sourced listing collapsibles (Holidays / Birthdays).
 * Renders the same [CollapsibleSection] chrome as the country sections so it
 * visually fits, with a forward-year list of detected [events] inside —
 * collapsed by default. Gated on the master [calendarEnabled] switch and
 * READ_CALENDAR, in that order:
 *
 *  - master off → a tappable prompt that scrolls up to the master toggle via
 *    [onEnableCalendar];
 *  - master on, permission missing → a "grant permission" prompt that fires the
 *    system dialog via [onRequestPermission];
 *  - active but [events] still `null` → a brief "checking…" line while the
 *    first read runs;
 *  - active, list empty → [emptyMessage];
 *  - otherwise → one row per event (title + localised date).
 *
 * [events] is pre-filtered by the caller to this section's [EventKind]; the
 * header summary shows the count once known (`—` while gated / loading). Event
 * titles are device-local and never leave the device.
 */
@Composable
internal fun CalendarCelebrationsSection(
    title: String,
    rememberKey: String,
    calendarEnabled: Boolean,
    permissionGranted: Boolean,
    events: List<UpcomingCalendarEvent>?,
    emptyMessage: String,
    uiLocale: Locale,
    onRequestPermission: () -> Unit,
    onEnableCalendar: () -> Unit,
    initiallyExpanded: Boolean = false,
) {
    val active = calendarEnabled && permissionGranted
    val summary = if (active && events != null) events.size.toString() else "—"
    CollapsibleSection(
        title = title,
        summary = summary,
        rememberKey = rememberKey,
        initiallyExpanded = initiallyExpanded,
    ) {
        when {
            !calendarEnabled -> {
                TextButton(
                    onClick = onEnableCalendar,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_holidays_calendar_enable_master),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            !permissionGranted -> {
                TextButton(
                    onClick = onRequestPermission,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_holidays_calendar_grant_permission),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            events == null -> {
                Text(
                    text = stringResource(R.string.settings_holidays_calendar_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            events.isEmpty() -> {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            else -> {
                val dateFormatter = remember(uiLocale) {
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(uiLocale)
                }
                events.forEach { event ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = event.date.format(dateFormatter),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
