package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.R
import app.clothescast.core.domain.model.BandClause
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
    speaking: Boolean = false,
    initialDate: LocalDate = LocalDate.now(),
) {
    var epochDay by rememberSaveable { mutableStateOf(initialDate.toEpochDay()) }
    val selectedDate = LocalDate.ofEpochDay(epochDay)
    var showPicker by rememberSaveable { mutableStateOf(false) }

    val theme = remember(selectedDate, holidayOverrides, enabledCountries) {
        ThemeForToday().resolve(
            date = selectedDate,
            overrides = holidayOverrides,
            enabledCountries = enabledCountries,
            events = emptyList(),
            themeFromCalendarHolidays = false,
            themeFromCalendarBirthdays = false,
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
