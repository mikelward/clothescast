package app.clothescast.ui.settings

import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.ClothesCastApplication
import app.clothescast.R
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
import app.clothescast.location.pickCityName
import app.clothescast.tts.resolveHolidayVoice
import app.clothescast.ui.today.HolidayBanner
import app.clothescast.ui.today.OutfitPreviewRow
import app.clothescast.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.round

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

        GeocoderProbeCard()
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
 * Debug-only card that resolves the device's current GPS fix, truncates the
 * coordinates to 2 decimal places (the same coarsening we want to verify
 * reverse-geocodes to a sensible city name), and dumps every relevant
 * [Address] field returned by Android's [Geocoder] so we can see exactly
 * what the framework hands us for the user's location. Drives the picker
 * via [pickCityName] using the same arguments [app.clothescast.location.ReverseGeocoder]
 * would, so the "Picked city" line shows what the Today header would label
 * this location.
 */
@Composable
private fun GeocoderProbeCard() {
    val context = LocalContext.current
    val app = context.applicationContext as ClothesCastApplication
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<GeocoderProbeState>(GeocoderProbeState.Idle) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Geocoder probe",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Resolves the current GPS fix, truncates to 2 dp, and shows every Address field Android's Geocoder returns for those coordinates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    if (state is GeocoderProbeState.Running) return@Button
                    state = GeocoderProbeState.Running
                    scope.launch {
                        state = runGeocoderProbe(context, app)
                    }
                },
                enabled = state !is GeocoderProbeState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (state is GeocoderProbeState.Running) "Probing…" else "Probe geocoder",
                )
            }
            when (val s = state) {
                GeocoderProbeState.Idle, GeocoderProbeState.Running -> Unit
                is GeocoderProbeState.Result -> GeocoderProbeResult(s.outcome)
            }
        }
    }
}

@Composable
private fun GeocoderProbeResult(outcome: GeocoderProbeOutcome) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (outcome.errorMessage != null) {
                Text(
                    text = outcome.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (outcome.rawLatitude != null && outcome.rawLongitude != null) {
                ProbeField(
                    "Raw fix",
                    "%.6f, %.6f".format(Locale.US, outcome.rawLatitude, outcome.rawLongitude),
                )
            }
            if (outcome.sentLatitude != null && outcome.sentLongitude != null) {
                ProbeField(
                    "Sent (2 dp, rounded)",
                    "%.2f, %.2f".format(Locale.US, outcome.sentLatitude, outcome.sentLongitude),
                )
            }

            Text(
                text = "Android Geocoder",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (outcome.geocoder.errorMessage != null) {
                Text(
                    text = outcome.geocoder.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (outcome.geocoder.pickedCity != null) {
                ProbeField("Picked city", outcome.geocoder.pickedCity)
            }
            outcome.geocoder.addresses.forEachIndexed { index, fields ->
                Text(
                    text = "Address ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                ProbeField("featureName", fields.featureName)
                ProbeField("locality", fields.locality)
                ProbeField("subLocality", fields.subLocality)
                ProbeField("subAdminArea", fields.subAdminArea)
                ProbeField("adminArea", fields.adminArea)
                ProbeField("countryCode", fields.countryCode)
                ProbeField("countryName", fields.countryName)
                ProbeField("postalCode", fields.postalCode)
                ProbeField("premises", fields.premises)
                ProbeField("thoroughfare", fields.thoroughfare)
                ProbeField("subThoroughfare", fields.subThoroughfare)
                fields.addressLines.forEachIndexed { lineIndex, line ->
                    ProbeField("addressLine[$lineIndex]", line)
                }
            }

            Text(
                text = "Nominatim (OSM, zoom=10)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (outcome.nominatim.errorMessage != null) {
                Text(
                    text = outcome.nominatim.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val nom = outcome.nominatim.result
            if (nom != null) {
                ProbeField("city", nom.city)
                ProbeField("town", nom.town)
                ProbeField("village", nom.village)
                ProbeField("municipality", nom.municipality)
                ProbeField("suburb", nom.suburb)
                ProbeField("city_district", nom.cityDistrict)
                ProbeField("county", nom.county)
                ProbeField("state", nom.state)
                ProbeField("country_code", nom.countryCode)
                ProbeField("country", nom.country)
                ProbeField("display_name", nom.displayName)
            }
        }
    }
}

@Composable
private fun ProbeField(label: String, value: String?) {
    val display = value?.takeIf { it.isNotBlank() } ?: "—"
    Text(
        text = "$label: $display",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
}

private sealed interface GeocoderProbeState {
    data object Idle : GeocoderProbeState
    data object Running : GeocoderProbeState
    data class Result(val outcome: GeocoderProbeOutcome) : GeocoderProbeState
}

private data class GeocoderProbeOutcome(
    val rawLatitude: Double?,
    val rawLongitude: Double?,
    val sentLatitude: Double?,
    val sentLongitude: Double?,
    val geocoder: GeocoderOutcome,
    val nominatim: NominatimOutcome,
    val errorMessage: String?,
)

private data class GeocoderOutcome(
    val addresses: List<AddressFields>,
    val pickedCity: String?,
    val errorMessage: String?,
)

private data class NominatimOutcome(
    val result: NominatimResult?,
    val errorMessage: String?,
)

private data class NominatimResult(
    val displayName: String?,
    val city: String?,
    val town: String?,
    val village: String?,
    val municipality: String?,
    val suburb: String?,
    val cityDistrict: String?,
    val county: String?,
    val state: String?,
    val countryCode: String?,
    val country: String?,
)

private data class AddressFields(
    val featureName: String?,
    val locality: String?,
    val subLocality: String?,
    val subAdminArea: String?,
    val adminArea: String?,
    val countryCode: String?,
    val countryName: String?,
    val postalCode: String?,
    val premises: String?,
    val thoroughfare: String?,
    val subThoroughfare: String?,
    val addressLines: List<String>,
)

private suspend fun runGeocoderProbe(
    context: android.content.Context,
    app: ClothesCastApplication,
): GeocoderProbeState.Result {
    val fix = app.locationResolver.resolve()
        ?: return GeocoderProbeState.Result(
            GeocoderProbeOutcome(
                rawLatitude = null,
                rawLongitude = null,
                sentLatitude = null,
                sentLongitude = null,
                geocoder = GeocoderOutcome(
                    addresses = emptyList(),
                    pickedCity = null,
                    errorMessage = null,
                ),
                nominatim = NominatimOutcome(result = null, errorMessage = null),
                errorMessage = "LocationResolver returned no fix — check coarse + background location grants.",
            ),
        )
    val sentLat = round(fix.latitude * 100.0) / 100.0
    val sentLng = round(fix.longitude * 100.0) / 100.0

    val (geocoderOutcome, nominatimOutcome) = coroutineScope {
        val geocoderJob = async { runAndroidGeocoder(context, sentLat, sentLng) }
        val nominatimJob = async { runNominatim(sentLat, sentLng) }
        geocoderJob.await() to nominatimJob.await()
    }
    return GeocoderProbeState.Result(
        GeocoderProbeOutcome(
            rawLatitude = fix.latitude,
            rawLongitude = fix.longitude,
            sentLatitude = sentLat,
            sentLongitude = sentLng,
            geocoder = geocoderOutcome,
            nominatim = nominatimOutcome,
            errorMessage = null,
        ),
    )
}

private suspend fun runAndroidGeocoder(
    context: android.content.Context,
    lat: Double,
    lon: Double,
): GeocoderOutcome {
    if (!Geocoder.isPresent()) {
        return GeocoderOutcome(
            addresses = emptyList(),
            pickedCity = null,
            errorMessage = "Geocoder.isPresent() is false on this device — no reverse-geocode backend.",
        )
    }
    val geocoder = Geocoder(context, Locale.getDefault())
    val (raw, errorMessage) = try {
        withTimeoutOrNull(10_000L) { fetchAddresses(geocoder, lat, lon) }
            ?.let { it to null }
            ?: (emptyList<Address>() to "Geocoder timed out after 10 s.")
    } catch (t: Throwable) {
        emptyList<Address>() to "Geocoder threw ${t.javaClass.simpleName}: ${t.message}"
    }
    val fields = raw.map { it.toFields() }
    val picked = fields.firstOrNull()?.let {
        pickCityName(
            locality = it.locality,
            subLocality = it.subLocality,
            subAdminArea = it.subAdminArea,
            adminArea = it.adminArea,
            countryCode = it.countryCode,
            countryName = it.countryName,
            postalCode = it.postalCode,
            addressLines = it.addressLines,
        )
    }
    return GeocoderOutcome(addresses = fields, pickedCity = picked, errorMessage = errorMessage)
}

/**
 * Reverse-geocodes via OpenStreetMap's free Nominatim service. `zoom=10`
 * collapses to the city level so a suburb-grained address (Sydney's Balmain,
 * NYC's Brooklyn) resolves to the recognisable metro name in `address.city`
 * rather than the suburb in `locality` that Android's Geocoder hands back.
 *
 * Usage policy compliance:
 *   - Identify the app + a contact URL in `User-Agent`.
 *   - Cap at 1 req/sec (a button-triggered debug call is well inside this).
 *   - Don't bulk reverse-geocode; this is a single-coord probe.
 */
private suspend fun runNominatim(lat: Double, lon: Double): NominatimOutcome = withContext(Dispatchers.IO) {
    val latParam = URLEncoder.encode("%.4f".format(Locale.US, lat), "UTF-8")
    val lonParam = URLEncoder.encode("%.4f".format(Locale.US, lon), "UTF-8")
    val url = URL(
        "https://nominatim.openstreetmap.org/reverse" +
            "?lat=$latParam&lon=$lonParam&zoom=10&format=jsonv2&addressdetails=1",
    )
    val conn = try {
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty(
                "User-Agent",
                "ClothesCast/${BuildConfig.VERSION_NAME} (https://github.com/mikelward/clothescast)",
            )
            setRequestProperty("Accept", "application/json")
        }
    } catch (t: Throwable) {
        return@withContext NominatimOutcome(
            result = null,
            errorMessage = "Nominatim connect failed: ${t.javaClass.simpleName} ${t.message}",
        )
    }
    try {
        val code = conn.responseCode
        if (code !in 200..299) {
            return@withContext NominatimOutcome(
                result = null,
                errorMessage = "Nominatim HTTP $code",
            )
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        NominatimOutcome(result = parseNominatim(body), errorMessage = null)
    } catch (t: IOException) {
        NominatimOutcome(
            result = null,
            errorMessage = "Nominatim IO error: ${t.javaClass.simpleName} ${t.message}",
        )
    } catch (t: Throwable) {
        NominatimOutcome(
            result = null,
            errorMessage = "Nominatim threw ${t.javaClass.simpleName}: ${t.message}",
        )
    } finally {
        try { conn.disconnect() } catch (_: Throwable) { /* ignore */ }
    }
}

private fun parseNominatim(body: String): NominatimResult {
    val json = JSONObject(body)
    val address = json.optJSONObject("address")
    fun field(name: String) = address?.optString(name).orEmpty().ifBlank { null }
    return NominatimResult(
        displayName = json.optString("display_name").ifBlank { null },
        city = field("city"),
        town = field("town"),
        village = field("village"),
        municipality = field("municipality"),
        suburb = field("suburb"),
        cityDistrict = field("city_district"),
        county = field("county"),
        state = field("state"),
        countryCode = field("country_code")?.uppercase(Locale.US),
        country = field("country"),
    )
}

private suspend fun fetchAddresses(geocoder: Geocoder, lat: Double, lon: Double): List<Address> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        suspendCancellableCoroutine { cont ->
            val listener = object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (cont.isActive) cont.resume(addresses.toList())
                }

                override fun onError(errorMessage: String?) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
            try {
                geocoder.getFromLocation(lat, lon, 5, listener)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    } else {
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            try {
                geocoder.getFromLocation(lat, lon, 5).orEmpty()
            } catch (t: IOException) {
                emptyList()
            } catch (t: IllegalArgumentException) {
                emptyList()
            }
        }
    }

private fun Address.toFields(): AddressFields {
    val lines = if (maxAddressLineIndex >= 0) {
        (0..maxAddressLineIndex).mapNotNull { getAddressLine(it) }
    } else {
        emptyList()
    }
    return AddressFields(
        featureName = featureName,
        locality = locality,
        subLocality = subLocality,
        subAdminArea = subAdminArea,
        adminArea = adminArea,
        countryCode = countryCode,
        countryName = countryName,
        postalCode = postalCode,
        premises = premises,
        thoroughfare = thoroughfare,
        subThoroughfare = subThoroughfare,
        addressLines = lines,
    )
}
