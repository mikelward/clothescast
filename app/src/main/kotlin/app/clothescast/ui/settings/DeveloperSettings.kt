package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.ClothesCastApplication
import app.clothescast.R
import app.clothescast.location.ReverseGeocoder
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.usecase.ThemeForToday
import app.clothescast.tts.resolveHolidayVoice
import app.clothescast.ui.today.HolidayBanner
import app.clothescast.ui.today.OutfitPreviewRow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * A canned mild-spring day so the Developer preview renders an outfit + can
 * speak a sample briefing offline, independent of any real forecast (which we
 * only have for the next few days, not for an arbitrary picked date).
 */
private val DEV_SAMPLE_INSIGHT = Insight(
    summary = InsightSummary(
        period = ForecastPeriod.TODAY,
        band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD),
        clothes = ClothesClause(listOf("sweater")),
    ),
    recommendedItems = listOf("sweater"),
    generatedAt = Instant.parse("2026-04-26T07:30:00Z"),
    forDate = LocalDate.of(2026, 4, 26),
    outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
    nextOutfit = OutfitSuggestion(OutfitSuggestion.Top.THICK_JACKET, OutfitSuggestion.Bottom.LONG_PANTS),
)

/**
 * Developer settings page. Lets you pick any date and see how that day's
 * holiday theme renders — the recoloured outfit image and the (possibly
 * combined) banner — and hear the TTS, all driven by a fixed mild-weather
 * sample so it works with no live forecast.
 */
@Composable
internal fun DeveloperPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Serialize Speak the same way VoiceSettings serializes its preview:
    // cloud TTS is billable and cancellation isn't a reliable cost control,
    // so an in-flight guard stops rapid taps queuing duplicate paid requests.
    var isSpeaking by remember { mutableStateOf(false) }
    SettingsScaffold(R.string.settings_root_developer, onBack) { padding ->
        DeveloperContent(
            region = state.region,
            holidayOverrides = state.holidayOverrides,
            enabledCountries = state.effectiveEnabledHolidayCountries,
            themeFromCalendarHolidays = state.calendarEnabled && state.themeFromCalendarHolidays,
            themeFromCalendarBirthdays = state.calendarEnabled && state.themeFromCalendarBirthdays,
            loadEventsForDay = viewModel::calendarEventsForDay,
            padding = padding,
            speaking = isSpeaking,
            onResolveCoords = { lat, lon ->
                (context.applicationContext as ClothesCastApplication)
                    .reverseGeocoder.resolveDiagnostic(lat, lon)
            },
            onSpeak = onSpeak@{ holidayId ->
                if (isSpeaking) return@onSpeak
                isSpeaking = true
                // Speak the picked day in its holiday voice so the preview
                // demonstrates the auto-selected persona, not just the
                // user's default voice. Resolve against the everyday
                // forecaster style, not the user's saved one: a deliberate
                // persona pick (e.g. Father Christmas) wins over the holiday
                // in the real briefing, but here that would mask the day's
                // own persona — every previewed day would speak in the
                // saved voice (Towel Day saying "Ho ho ho"), defeating the
                // point of the preview.
                val selection =
                    resolveHolidayVoice(holidayId, state.geminiVoice, TtsStyle.WEATHER_FORECASTER)
                scope.launch {
                    try {
                        runTtsPreview(
                            context = context,
                            engine = state.ttsEngine,
                            geminiVoice = selection.voiceName,
                            ttsStyle = selection.style,
                            deviceVoice = state.deviceVoice,
                            voiceLocale = state.voiceLocale,
                            region = state.region,
                        )
                    } finally {
                        isSpeaking = false
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeveloperContent(
    region: Region,
    holidayOverrides: Map<HolidayId, HolidayOverride>,
    enabledCountries: Set<String>,
    padding: PaddingValues,
    onSpeak: (HolidayId?) -> Unit,
    themeFromCalendarHolidays: Boolean = false,
    themeFromCalendarBirthdays: Boolean = false,
    loadEventsForDay: suspend (LocalDate) -> List<CalendarEvent> = { emptyList() },
    speaking: Boolean = false,
    initialDate: LocalDate = LocalDate.now(),
    onResolveCoords: suspend (Double, Double) -> ReverseGeocoder.DiagnosticResult =
        { _, _ -> ReverseGeocoder.DiagnosticResult.EMPTY },
) {
    var epochDay by rememberSaveable { mutableStateOf(initialDate.toEpochDay()) }
    val selectedDate = LocalDate.ofEpochDay(epochDay)
    var showPicker by rememberSaveable { mutableStateOf(false) }

    // Pull the picked day's calendar events so the preview themes from
    // calendar holidays / birthdays the same way the Today screen does. Only
    // read when a calendar-sourced toggle is on — matches the Today screen's
    // gate and avoids a needless calendar query (and permission surprise)
    // otherwise.
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    LaunchedEffect(selectedDate, themeFromCalendarHolidays, themeFromCalendarBirthdays) {
        events = if (themeFromCalendarHolidays || themeFromCalendarBirthdays) {
            loadEventsForDay(selectedDate)
        } else {
            emptyList()
        }
    }

    val theme = remember(
        selectedDate,
        holidayOverrides,
        enabledCountries,
        events,
        themeFromCalendarHolidays,
        themeFromCalendarBirthdays,
    ) {
        ThemeForToday().resolve(
            date = selectedDate,
            overrides = holidayOverrides,
            enabledCountries = enabledCountries,
            events = events,
            themeFromCalendarHolidays = themeFromCalendarHolidays,
            themeFromCalendarBirthdays = themeFromCalendarBirthdays,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_developer_preview_day_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_developer_preview_day_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = selectedDate.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { showPicker = true }) {
                        Text(stringResource(R.string.settings_developer_pick_date))
                    }
                    TextButton(onClick = { epochDay = LocalDate.now().toEpochDay() }) {
                        Text(stringResource(R.string.settings_developer_today))
                    }
                }
                Text(
                    text = stringResource(
                        R.string.settings_developer_resolved_label,
                        theme?.id?.name ?: stringResource(R.string.settings_developer_no_theme),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (theme != null) {
            HolidayBanner(theme = theme, region = region, modifier = Modifier.fillMaxWidth())
        } else {
            Text(
                text = stringResource(R.string.settings_developer_no_theme),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutfitPreviewRow(
            insight = DEV_SAMPLE_INSIGHT,
            outfitTopColors = theme?.topOverrides ?: emptyMap(),
            outfitBottomColors = theme?.bottomOverrides ?: emptyMap(),
            outfitTopStrokes = theme?.topStrokeOverrides ?: emptyMap(),
            outfitBottomStrokes = theme?.bottomStrokeOverrides ?: emptyMap(),
        )

        Button(
            onClick = { onSpeak(theme?.id) },
            enabled = !speaking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_developer_speak))
        }

        ReverseGeocodeTesterCard(onResolve = onResolveCoords)
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = epochDay * MILLIS_PER_DAY,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { epochDay = it / MILLIS_PER_DAY }
                    showPicker = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * One line of the structured-fields block in the reverse-geocode
 * tester: a name + a value (rendered as `(null)` / `(blank)` so an
 * empty value isn't a silent gap), styled the same way regardless of
 * which field it is.
 */
@Composable
private fun StructuredFieldRow(name: String, value: String?) {
    val display = when {
        value == null -> "(null)"
        value.isBlank() -> "(blank)"
        else -> value
    }
    Text("$name: $display", style = MaterialTheme.typography.bodySmall)
}

/**
 * Parses a `"lat, lon"` string — comma-separated, whitespace around the
 * comma optional — into a coordinate pair, or null if either half is
 * unparseable or out of range. Accepts the format Google Maps' "What's
 * here?" copy button uses (`40.70, -74.01`) so a developer can paste
 * straight from the browser.
 */
internal fun parseCoordPair(input: String): Pair<Double, Double>? {
    val parts = input.split(',')
    if (parts.size != 2) return null
    val lat = parts[0].trim().toDoubleOrNull() ?: return null
    val lon = parts[1].trim().toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return lat to lon
}

/**
 * Developer-only reverse-geocode tester: punch in any (lat, lon), hit
 * Resolve, and see exactly what the platform Geocoder returns — raw
 * addressLines, plus the [ReverseGeocoder.Result] that
 * `deriveAddressDetail` produces from them. Useful for reproducing the
 * occasional "street wasn't stripped" report against a specific
 * coordinate without having to physically stand on it. Hardcoded
 * English labels — this surface ships in debug only and never reaches a
 * translator.
 */
@Composable
private fun ReverseGeocodeTesterCard(
    onResolve: suspend (Double, Double) -> ReverseGeocoder.DiagnosticResult,
) {
    val scope = rememberCoroutineScope()
    var coordsText by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ReverseGeocoder.DiagnosticResult?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Reverse-geocode coords", style = MaterialTheme.typography.titleMedium)
            Text(
                "Hits the platform Geocoder with any (lat, lon) and shows the raw " +
                    "addressLines plus the derived addressDetail. Use it to reproduce a " +
                    "specific coordinate's reverse-geocode output without leaving your desk.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = coordsText,
                onValueChange = { coordsText = it },
                label = { Text("Lat, lon") },
                placeholder = { Text("40.70, -74.01") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onClick@{
                    val pair = parseCoordPair(coordsText)
                    if (pair == null) {
                        inputError = "Enter \"lat, lon\" — e.g. 40.70, -74.01."
                        result = null
                        return@onClick
                    }
                    inputError = null
                    loading = true
                    result = null
                    scope.launch {
                        try {
                            result = onResolve(pair.first, pair.second)
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (loading) "Resolving…" else "Resolve")
            }
            inputError?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            result?.let { r ->
                Text(
                    "addressLines (${r.addressLines.size}):",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (r.addressLines.isEmpty()) {
                    Text(
                        "(none — Geocoder returned no address)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    r.addressLines.forEachIndexed { i, line ->
                        Text("[$i] $line", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("structured fields:", style = MaterialTheme.typography.labelMedium)
                StructuredFieldRow("subLocality", r.parts.subLocality)
                StructuredFieldRow("locality", r.parts.locality)
                StructuredFieldRow("adminArea", r.parts.adminArea)
                StructuredFieldRow("postalCode", r.parts.postalCode)
                StructuredFieldRow("countryName", r.parts.countryName)
                StructuredFieldRow("countryCode", r.parts.countryCode)
                Text("derived addressDetail:", style = MaterialTheme.typography.labelMedium)
                Text(
                    r.derived.addressDetail ?: "(null)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "city: ${r.derived.city ?: "(null)"} · country: ${r.derived.countryCode ?: "(null)"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
