package app.clothescast.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.clothescast.ui.AppMenuShape
import app.clothescast.R
import app.clothescast.calendar.CalendarPermission
import app.clothescast.diag.findActivity
import app.clothescast.core.domain.model.CalendarInfo
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
 * feature toggles (Evening tie-ins, Birthdays, Public holidays), the two
 * upcoming-celebration listings (Birthdays / Public holidays), the
 * country-source toggles (Region / Location / Global / Funny / All), the
 * currently-enabled country collapsibles surfaced at the top level, and
 * finally a "More countries" expand row revealing the rest of the curated
 * catalogue: any non-enabled Global / Funny / Christian / Orthodox /
 * per-country collapsibles, plus an "All" collapsible showing every
 * holiday flat for power-user search.
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
    availableCalendars: List<CalendarInfo>?,
    calendarOverrides: Map<String, Boolean>,
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
    onLoadAvailableCalendars: () -> Unit,
    onSetCalendarOverride: (String, Boolean) -> Unit,
    onNavigateToRegionSettings: () -> Unit,
    onNavigateToLocationSettings: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val uiLocale = remember(configuration) {
        configuration.locales.get(0) ?: Locale.getDefault()
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
        if (calendarEnabled && permissionGranted) {
            onLoadCalendarCelebrations()
            onLoadAvailableCalendars()
        }
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

    // Currently-enabled country collapsibles render at the top level (always
    // visible). Non-enabled countries — plus the flat "All" search — are
    // tucked behind a single "More countries" expand row below them.
    // Collapsed by default so the page stays scannable; expansion state is
    // saved across config changes for the power-user case.
    var moreCountriesExpanded by rememberSaveable { mutableStateOf(false) }

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
                // The master description deliberately stays short ("nothing
                // leaves your device by default"); the full who-gets-what —
                // including the calendar-derived has_events flag the Smart Home
                // bridge can publish — lives in the privacy policy.
                TextButton(
                    onClick = { openUrl(context, PRIVACY_POLICY_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_privacy_open_policy)) }
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
                    label = stringResource(R.string.settings_evening_add_ons),
                    description = stringResource(R.string.settings_evening_add_ons_description),
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

            // TODO(celebrations-v2): give each row in the Birthdays / Public
            // holidays listings below a per-event override dropdown (same
            // pattern as the country sections) so the user can mute a specific
            // event ("Boxing Day" they don't celebrate; a noisy birthday import)
            // without disabling the whole source. Needs a CalendarEvent → stable
            // key scheme that survives event-recurrence renames, persisted
            // alongside holidayOverrides.

            // Birthdays / Public holidays listings — the next year of detected
            // events from the user's synced calendars, collapsed by default and
            // gated on READ_CALENDAR. When permission is missing each section
            // offers an in-place grant prompt instead of a list. Ordered to
            // match the Birthdays-then-Public-holidays toggle order in the
            // Personal calendars card above.
            CalendarCelebrationsSection(
                title = stringResource(R.string.settings_calendar_birthdays),
                rememberKey = "holidays-calendar-birthdays-section",
                calendarEnabled = calendarEnabled,
                permissionGranted = permissionGranted,
                events = calendarBirthdays,
                emptyMessage = stringResource(R.string.settings_holidays_calendar_no_birthdays),
                uiLocale = uiLocale,
                onRequestPermission = requestPermissionForListing,
                onEnableCalendar = scrollToTop,
            )
            CalendarCelebrationsSection(
                title = stringResource(R.string.settings_calendar_public_holidays),
                rememberKey = "holidays-calendar-holidays-section",
                calendarEnabled = calendarEnabled,
                permissionGranted = permissionGranted,
                events = calendarHolidays,
                emptyMessage = stringResource(R.string.settings_holidays_calendar_no_holidays),
                uiLocale = uiLocale,
                onRequestPermission = requestPermissionForListing,
                onEnableCalendar = scrollToTop,
            )

            // Which device calendars feed ClothesCast. Defaults to each
            // calendar's visibility in the host calendar app; an explicit
            // toggle here overrides that.
            CalendarSelectionSection(
                calendarEnabled = calendarEnabled,
                permissionGranted = permissionGranted,
                calendars = availableCalendars,
                overrides = calendarOverrides,
                onSetCalendarOverride = onSetCalendarOverride,
                onRequestPermission = requestPermissionForListing,
                onEnableCalendar = scrollToTop,
            )

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
            }

            // Catalogue collapsibles — drill-down for the country-source
            // toggles above. The four special buckets (Global / Funny /
            // Christian / Orthodox) come first, then ISO countries with the
            // user's Region and Location countries pinned to the top. Each
            // section's expansion state is independently saved via
            // [CollapsibleSection]'s rememberKey.
            //
            // Sections corresponding to currently-enabled countries render
            // unconditionally at the top level so the user always sees what's
            // active. The rest tuck behind the "More countries" expand row
            // below, alongside the flat "All" search collapsible.
            val pinnedCountries = listOfNotNull(localeCountry?.uppercase(), weatherLocationCountry?.uppercase())
                .filter { it in isoCountries }
                .distinct()
            val orderedCountries = pinnedCountries + isoCountries.filterNot { it in pinnedCountries }
            val sectionEntries: List<Triple<String, String, List<HolidayTheme>>> = buildList {
                add(Triple(HolidayCatalog.GLOBAL_COUNTRY, stringResource(R.string.settings_holiday_country_global_label), globalThemes))
                add(Triple(HolidayCatalog.FUNNY, stringResource(R.string.settings_holiday_country_funny_label), funnyThemes))
                add(Triple(HolidayCatalog.CHRISTIAN, stringResource(R.string.settings_holiday_country_christian_label), christianThemes))
                add(Triple(HolidayCatalog.ORTHODOX, stringResource(R.string.settings_holiday_country_orthodox_label), orthodoxThemes))
                orderedCountries.forEach { code ->
                    add(Triple(code, resolveCountryDisplayName(context, uiLocale, code), themesByCountry[code].orEmpty()))
                }
            }

            sectionEntries
                .filter { (code, _, _) -> code in effectiveEnabledHolidayCountries }
                .forEach { (code, title, themes) ->
                    CountrySectionCard(
                        code = code,
                        title = title,
                        themes = themes,
                        holidayOverrides = holidayOverrides,
                        effectiveEnabledHolidayCountries = effectiveEnabledHolidayCountries,
                        countryOverride = holidayCountrySelection.countryOverrides[code] ?: HolidayOverride.AUTO,
                        autoOn = holidayCountrySelection.countryAutoEffective(code, localeCountry, weatherLocationCountry),
                        onSetCountryOverride = onSetCountryOverride,
                        onSetHolidayOverride = onSetHolidayOverride,
                    )
                }

            // "More countries" expand card, sitting below the enabled-country
            // collapsibles. Its own Card so it reads as a distinct affordance
            // alongside the country collapsibles above and below it.
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_holidays_more_countries),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { moreCountriesExpanded = !moreCountriesExpanded }) {
                        Icon(
                            imageVector = if (moreCountriesExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(
                                if (moreCountriesExpanded) R.string.settings_holidays_collapse
                                else R.string.settings_holidays_expand
                            ),
                        )
                    }
                }
            }

            if (moreCountriesExpanded) {
                sectionEntries
                    .filterNot { (code, _, _) -> code in effectiveEnabledHolidayCountries }
                    .forEach { (code, title, themes) ->
                        CountrySectionCard(
                            code = code,
                            title = title,
                            themes = themes,
                            holidayOverrides = holidayOverrides,
                            effectiveEnabledHolidayCountries = effectiveEnabledHolidayCountries,
                            countryOverride = holidayCountrySelection.countryOverrides[code] ?: HolidayOverride.AUTO,
                            autoOn = holidayCountrySelection.countryAutoEffective(code, localeCountry, weatherLocationCountry),
                            onSetCountryOverride = onSetCountryOverride,
                            onSetHolidayOverride = onSetHolidayOverride,
                        )
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
 * One catalogue collapsible — a [CollapsibleSection] populated with the
 * country's (or special bucket's) holiday themes, an active-count summary,
 * and a tri-state override dropdown in the trailing slot. Used for both
 * the four special buckets (Global / Funny / Christian / Orthodox) and the
 * per-ISO-country sections; the [code] doubles as the [rememberKey] scope
 * so each section keeps its own expansion state across config changes and
 * across moves between the "enabled" and "More countries" passes.
 */
@Composable
private fun CountrySectionCard(
    code: String,
    title: String,
    themes: List<HolidayTheme>,
    holidayOverrides: Map<HolidayId, HolidayOverride>,
    effectiveEnabledHolidayCountries: Set<String>,
    countryOverride: HolidayOverride,
    autoOn: Boolean,
    onSetCountryOverride: (String, HolidayOverride) -> Unit,
    onSetHolidayOverride: (HolidayId, HolidayOverride) -> Unit,
) {
    val activeCount = themes.count { it.isActive(holidayOverrides, effectiveEnabledHolidayCountries) }
    CollapsibleSection(
        title = title,
        summary = "$activeCount/${themes.size}",
        rememberKey = "holidays-country-$code",
        trailing = {
            OverrideDropdown(
                current = countryOverride,
                autoOn = autoOn,
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
        Text(triggerLabel, style = MaterialTheme.typography.bodyLarge)
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = AppMenuShape,
        ) {
            DropdownMenuItem(
                text = { Text(autoLabel, style = MaterialTheme.typography.bodyLarge) },
                onClick = {
                    expanded = false
                    onChange(HolidayOverride.AUTO)
                },
            )
            DropdownMenuItem(
                text = { Text(onLabel, style = MaterialTheme.typography.bodyLarge) },
                onClick = {
                    expanded = false
                    onChange(HolidayOverride.ON)
                },
            )
            DropdownMenuItem(
                text = { Text(offLabel, style = MaterialTheme.typography.bodyLarge) },
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
// The resource id is looked up dynamically by name (getIdentifier), so
// stringResource() can't be used here — the id isn't known at compile time, so
// getIdentifier is required (DiscouragedApi).
@Suppress("LocalContextGetResourceValueCall")
@android.annotation.SuppressLint("DiscouragedApi")
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
@android.annotation.SuppressLint("DiscouragedApi")
private fun resolveCountryDisplayName(
    context: android.content.Context,
    uiLocale: Locale,
    code: String,
): String {
    // getIdentifier is required: resName is built from a runtime country code.
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
                events.forEach { event -> CelebrationRow(event, dateFormatter) }
            }
        }
    }
}

/**
 * One detected-celebration row: title + date, tappable to expand and reveal the
 * source calendar (the synced account the event came from) so the user can see
 * *which* calendar is theming a day — e.g. tracking down a surprise "King's
 * Birthday" public holiday. The owner account is account/email-shaped and
 * device-local: shown on screen only, never copied off-device.
 */
@Composable
private fun CelebrationRow(
    event: UpcomingCalendarEvent,
    dateFormatter: DateTimeFormatter,
) {
    var expanded by rememberSaveable(event.date, event.title, event.kind) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.settings_holidays_collapse
                    else R.string.settings_holidays_expand
                ),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (expanded) {
            Text(
                text = stringResource(
                    R.string.settings_holidays_calendar_source,
                    event.ownerAccount ?: stringResource(R.string.settings_holiday_country_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Per-calendar enable/disable list. One switch per device calendar; the
 * effective state is the user's explicit override when set, otherwise the
 * calendar's visibility in the host calendar app (so a fresh install mirrors
 * Google Calendar). Disabling a calendar stops ClothesCast reading it for
 * theming, evening tie-ins, the listings, and the insight prose. Gated like
 * the celebration listings: master switch first, then READ_CALENDAR.
 */
@Composable
private fun CalendarSelectionSection(
    calendarEnabled: Boolean,
    permissionGranted: Boolean,
    calendars: List<CalendarInfo>?,
    overrides: Map<String, Boolean>,
    onSetCalendarOverride: (String, Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onEnableCalendar: () -> Unit,
) {
    val active = calendarEnabled && permissionGranted
    val summary = if (active && calendars != null) calendars.size.toString() else "—"
    CollapsibleSection(
        title = stringResource(R.string.settings_calendars_title),
        summary = summary,
        rememberKey = "holidays-calendar-selection-section",
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
            calendars == null -> {
                Text(
                    text = stringResource(R.string.settings_holidays_calendar_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            calendars.isEmpty() -> {
                Text(
                    text = stringResource(R.string.settings_calendars_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.settings_calendars_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // When two calendars would show the same name + account (e.g. the
                // same email added under two providers), surface the provider so
                // their switches are distinguishable.
                val ambiguous = calendars
                    .groupBy { it.displayName to it.accountName }
                    .filterValues { it.size > 1 }
                    .keys
                calendars.forEach { calendar ->
                    CalendarToggleRow(
                        calendar = calendar,
                        checked = overrides[calendar.id] ?: calendar.visible,
                        showProvider = (calendar.displayName to calendar.accountName) in ambiguous,
                        onCheckedChange = { onSetCalendarOverride(calendar.id, it) },
                    )
                }
            }
        }
    }
}

/** A calendar name + account with an on/off switch. */
@Composable
private fun CalendarToggleRow(
    calendar: CalendarInfo,
    checked: Boolean,
    showProvider: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val account = calendar.accountName.takeIf { it.isNotBlank() && it != calendar.displayName }
    val provider = if (showProvider) providerLabel(calendar.accountType) else null
    val subtitle = listOfNotNull(account, provider).joinToString(" · ").ifBlank { null }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = calendar.displayName, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Best-effort friendly name for a calendar account type, shown only to
 * disambiguate same-name/same-account calendars from different providers.
 * Falls back to the raw type for providers we don't special-case (still
 * distinguishing, just less pretty) — better than hiding it on a collision.
 */
private fun providerLabel(accountType: String?): String? {
    val type = accountType?.takeIf { it.isNotBlank() } ?: return null
    return when {
        type.equals("com.google", ignoreCase = true) -> "Google"
        type.contains("exchange", ignoreCase = true) -> "Exchange"
        type.contains("caldav", ignoreCase = true) -> "CalDAV"
        else -> type
    }
}
