package app.clothescast.ui.settings

import app.clothescast.diag.DiagLog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.tts.DeviceVoice
import app.clothescast.tts.GEMINI_VOICES
import app.clothescast.tts.GOOGLE_TTS_PACKAGE
import app.clothescast.tts.GeminiTtsSpeaker
import app.clothescast.tts.TtsVoiceOption
import app.clothescast.tts.insightTtsUtterance
import app.clothescast.tts.resolve
import app.clothescast.tts.toJavaLocale
import app.clothescast.tts.withSpeechAudioFocus
import app.clothescast.ui.EdgeFadeOverlay
import java.text.Collator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VoiceContent(
    selected: TtsEngine,
    geminiVoice: String,
    ttsStyle: TtsStyle,
    deviceVoice: String?,
    deviceVoices: List<DeviceVoice>,
    effectiveDeviceVoice: DeviceVoice?,
    geminiKeyConfigured: Boolean,
    voiceLocale: VoiceLocale,
    region: Region,
    temperatureUnit: TemperatureUnit,
    rangeFormat: RangeFormat,
    clothesFormat: ClothesFormat,
    bottomsFormat: BottomsFormat,
    padding: PaddingValues,
    onSetTtsEngine: (TtsEngine) -> Unit,
    onSetGeminiVoice: (String) -> Unit,
    onSetTtsStyle: (TtsStyle) -> Unit,
    onSetDeviceVoice: (String?) -> Unit,
    onSetVoiceLocale: (VoiceLocale) -> Unit,
    onSetGeminiKey: (String) -> Unit,
    onClearGeminiKey: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // While a preview is in flight (synthesis + playback) we lock the whole
    // voice section. Cloud TTS calls are billed per-character and the in-flight
    // request can't reliably be un-billed by client-side cancellation, so the
    // safe behaviour is to ignore further triggers — engine taps, voice taps,
    // locale taps, the Test Voice button — until the current preview finishes.
    // Compose callbacks run on the main thread, so the read-then-write here is
    // atomic with respect to other UI events.
    var isPreviewing by remember { mutableStateOf(false) }

    fun preview(
        engine: TtsEngine,
        gVoice: String,
        style: TtsStyle,
        dVoice: String?,
        locale: VoiceLocale,
    ) {
        if (isPreviewing) return
        isPreviewing = true
        coroutineScope.launch {
            try {
                runTtsPreview(
                    context = context,
                    engine = engine,
                    geminiVoice = gVoice,
                    ttsStyle = style,
                    deviceVoice = dVoice,
                    voiceLocale = locale,
                    region = region,
                    temperatureUnit = temperatureUnit,
                    rangeFormat = rangeFormat,
                    clothesFormat = clothesFormat,
                    bottomsFormat = bottomsFormat,
                )
            } finally {
                isPreviewing = false
            }
        }
    }

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
            SectionCard(title = stringResource(R.string.settings_tts_voice_locale_label)) {
                Text(
                    text = stringResource(R.string.settings_tts_voice_locale_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VoiceLocalePicker(
                    selected = voiceLocale,
                    region = region,
                    enabled = !isPreviewing,
                    onSelect = {
                        onSetVoiceLocale(it)
                        preview(selected, geminiVoice, ttsStyle, deviceVoice, it)
                    },
                )
            }

            SectionCard(title = stringResource(R.string.settings_tts_engine_title)) {
                Text(
                    text = stringResource(R.string.settings_tts_engine_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TtsEngine.entries.forEach { engine ->
                    RadioRow(
                        label = stringResource(ttsEngineLabel(engine)),
                        selected = engine == selected,
                        enabled = !isPreviewing,
                        onSelect = {
                            onSetTtsEngine(engine)
                            preview(engine, geminiVoice, ttsStyle, deviceVoice, voiceLocale)
                        },
                    )
                }
                when (selected) {
                    TtsEngine.GEMINI -> {
                        Text(
                            text = stringResource(R.string.settings_api_key_gemini_header),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        KeyEntryFields(
                            configured = geminiKeyConfigured,
                            statusText = stringResource(
                                if (geminiKeyConfigured) R.string.settings_api_key_status_set
                                else R.string.settings_api_key_status_unset,
                            ),
                            placeholder = stringResource(R.string.settings_api_key_placeholder),
                            saveLabel = stringResource(R.string.settings_api_key_save),
                            replaceLabel = stringResource(R.string.settings_api_key_replace),
                            clearLabel = stringResource(R.string.settings_api_key_clear),
                            onSave = onSetGeminiKey,
                            onClear = onClearGeminiKey,
                        )
                        VoicePicker(
                            title = stringResource(R.string.settings_tts_voice_label),
                            voices = GEMINI_VOICES,
                            selectedId = geminiVoice,
                            enabled = !isPreviewing,
                            onSelect = {
                                onSetGeminiVoice(it)
                                preview(TtsEngine.GEMINI, it, ttsStyle, deviceVoice, voiceLocale)
                            },
                        )
                        StylePicker(
                            selected = ttsStyle,
                            enabled = !isPreviewing,
                            onSelect = { picked ->
                                onSetTtsStyle(picked)
                                preview(TtsEngine.GEMINI, geminiVoice, picked, deviceVoice, voiceLocale)
                            },
                        )
                        TestVoiceButton(isPreviewing = isPreviewing) {
                            preview(selected, geminiVoice, ttsStyle, deviceVoice, voiceLocale)
                        }
                    }
                    TtsEngine.DEVICE -> {
                        if (!rememberIsGoogleTtsInstalled()) {
                            InstallGoogleTtsHint()
                        }
                        DeviceVoicePicker(
                            voices = deviceVoices,
                            selectedId = deviceVoice,
                            effectiveDeviceVoice = effectiveDeviceVoice,
                            enabled = !isPreviewing,
                            onSelect = { picked ->
                                onSetDeviceVoice(picked)
                                preview(selected, geminiVoice, ttsStyle, picked, voiceLocale)
                            },
                        )
                        TestVoiceButton(isPreviewing = isPreviewing) {
                            preview(selected, geminiVoice, ttsStyle, deviceVoice, voiceLocale)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceLocalePicker(
    selected: VoiceLocale,
    region: Region,
    onSelect: (VoiceLocale) -> Unit,
    enabled: Boolean = true,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val title = stringResource(R.string.settings_tts_voice_locale_label)
    // Show the locale that VoiceLocale.SYSTEM resolves to: the app's
    // configured region language when the user has set one explicitly, or the
    // device UI language otherwise. This is what the TTS engine actually uses,
    // so the user can verify what accent they'll hear without leaving Settings.
    val uiLocale = LocalContext.current.resourcesLocale()
    // Prefer the region-configured locale over the device default. Fall back
    // to uiLocale (the context resources locale) when region is SYSTEM so the
    // tag still updates with runtime app-language changes.
    // Strip Unicode extensions (e.g. `-u-fw-mon` from a Monday-week device
    // preference) — they're irrelevant to the spoken accent and just clutter
    // the picker label.
    val systemTag = VoiceLocale.SYSTEM.resolve(region.toJavaLocale() ?: uiLocale).stripExtensions().toLanguageTag()
    val labelFor: @Composable (VoiceLocale) -> String = { option ->
        val base = stringResource(voiceLocaleLabel(option))
        if (option == VoiceLocale.SYSTEM) "$base ($systemTag)" else base
    }
    OutlinedButton(
        onClick = { dialogOpen = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("$title: ${labelFor(selected)}")
    }
    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(title) },
            text = {
                // Sort by the resolved display label using a locale-aware
                // collator (so e.g. "Ç" sorts with "C" in fr-FR, "Ä" with "A"
                // in de-DE). SYSTEM stays pinned at the top — it's a "follow
                // device" option, not a language to sort with the rest.
                val collator = remember(uiLocale) { Collator.getInstance(uiLocale) }
                val (system, rest) = VoiceLocale.entries
                    .map { it to labelFor(it) }
                    .partition { it.first == VoiceLocale.SYSTEM }
                val sorted = system + rest.sortedWith(compareBy(collator) { it.second })
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    sorted.forEach { (option, label) ->
                        RadioRow(
                            label = label,
                            selected = option == selected,
                            onSelect = {
                                onSelect(option)
                                dialogOpen = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(R.string.settings_tts_voice_dismiss))
                }
            },
        )
    }
}

private fun voiceLocaleLabel(locale: VoiceLocale): Int = when (locale) {
    VoiceLocale.SYSTEM -> R.string.settings_tts_voice_locale_system
    VoiceLocale.EN_US -> R.string.settings_tts_voice_locale_en_us
    VoiceLocale.EN_GB -> R.string.settings_tts_voice_locale_en_gb
    VoiceLocale.EN_AU -> R.string.settings_tts_voice_locale_en_au
    VoiceLocale.EN_CA -> R.string.settings_tts_voice_locale_en_ca
    VoiceLocale.DE_DE -> R.string.settings_tts_voice_locale_de_de
    VoiceLocale.DE_AT -> R.string.settings_tts_voice_locale_de_at
    VoiceLocale.DE_CH -> R.string.settings_tts_voice_locale_de_ch
    VoiceLocale.FR_FR -> R.string.settings_tts_voice_locale_fr_fr
    VoiceLocale.FR_CA -> R.string.settings_tts_voice_locale_fr_ca
    VoiceLocale.IT_IT -> R.string.settings_tts_voice_locale_it_it
    VoiceLocale.ES_ES -> R.string.settings_tts_voice_locale_es_es
    VoiceLocale.ES_MX -> R.string.settings_tts_voice_locale_es_mx
    VoiceLocale.CA_ES -> R.string.settings_tts_voice_locale_ca_es
    VoiceLocale.RU_RU -> R.string.settings_tts_voice_locale_ru_ru
    VoiceLocale.PL_PL -> R.string.settings_tts_voice_locale_pl_pl
    VoiceLocale.HR_HR -> R.string.settings_tts_voice_locale_hr_hr
    VoiceLocale.SL_SI -> R.string.settings_tts_voice_locale_sl_si
    VoiceLocale.SR_RS -> R.string.settings_tts_voice_locale_sr_rs
    VoiceLocale.SR_CYRL_RS -> R.string.settings_tts_voice_locale_sr_cyrl_rs
    VoiceLocale.BG_BG -> R.string.settings_tts_voice_locale_bg_bg
    VoiceLocale.CS_CZ -> R.string.settings_tts_voice_locale_cs_cz
    VoiceLocale.SK_SK -> R.string.settings_tts_voice_locale_sk_sk
    VoiceLocale.HU_HU -> R.string.settings_tts_voice_locale_hu_hu
    VoiceLocale.RO_RO -> R.string.settings_tts_voice_locale_ro_ro
    VoiceLocale.EL_GR -> R.string.settings_tts_voice_locale_el_gr
    VoiceLocale.UK_UA -> R.string.settings_tts_voice_locale_uk_ua
    VoiceLocale.PT_BR -> R.string.settings_tts_voice_locale_pt_br
    VoiceLocale.PT_PT -> R.string.settings_tts_voice_locale_pt_pt
    VoiceLocale.NL_NL -> R.string.settings_tts_voice_locale_nl_nl
    VoiceLocale.SV_SE -> R.string.settings_tts_voice_locale_sv_se
    VoiceLocale.DA_DK -> R.string.settings_tts_voice_locale_da_dk
    VoiceLocale.NB_NO -> R.string.settings_tts_voice_locale_nb_no
    VoiceLocale.FI_FI -> R.string.settings_tts_voice_locale_fi_fi
    VoiceLocale.ET_EE -> R.string.settings_tts_voice_locale_et_ee
    VoiceLocale.LV_LV -> R.string.settings_tts_voice_locale_lv_lv
    VoiceLocale.LT_LT -> R.string.settings_tts_voice_locale_lt_lt
    VoiceLocale.TR_TR -> R.string.settings_tts_voice_locale_tr_tr
    VoiceLocale.EN_ZA -> R.string.settings_tts_voice_locale_en_za
    VoiceLocale.ID_ID -> R.string.settings_tts_voice_locale_id_id
    VoiceLocale.MS_MY -> R.string.settings_tts_voice_locale_ms_my
    VoiceLocale.FIL_PH -> R.string.settings_tts_voice_locale_fil_ph
    VoiceLocale.SW_KE -> R.string.settings_tts_voice_locale_sw_ke
    VoiceLocale.VI_VN -> R.string.settings_tts_voice_locale_vi_vn
    VoiceLocale.TH_TH -> R.string.settings_tts_voice_locale_th_th
    VoiceLocale.ZH_CN -> R.string.settings_tts_voice_locale_zh_cn
    VoiceLocale.ZH_TW -> R.string.settings_tts_voice_locale_zh_tw
    VoiceLocale.HI_IN -> R.string.settings_tts_voice_locale_hi_in
    VoiceLocale.BN_BD -> R.string.settings_tts_voice_locale_bn_bd
    VoiceLocale.JA_JP -> R.string.settings_tts_voice_locale_ja_jp
    VoiceLocale.KO_KR -> R.string.settings_tts_voice_locale_ko_kr
    VoiceLocale.AR_SA -> R.string.settings_tts_voice_locale_ar_sa
    VoiceLocale.AR_EG -> R.string.settings_tts_voice_locale_ar_eg
    VoiceLocale.AR_AE -> R.string.settings_tts_voice_locale_ar_ae
    VoiceLocale.AR_MA -> R.string.settings_tts_voice_locale_ar_ma
    VoiceLocale.HE_IL -> R.string.settings_tts_voice_locale_he_il
    VoiceLocale.FA_IR -> R.string.settings_tts_voice_locale_fa_ir
    VoiceLocale.SQ_AL -> R.string.settings_tts_voice_locale_sq_al
    VoiceLocale.AM_ET -> R.string.settings_tts_voice_locale_am_et
}

@Composable
private fun StylePicker(
    selected: TtsStyle,
    onSelect: (TtsStyle) -> Unit,
    enabled: Boolean = true,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val title = stringResource(R.string.settings_tts_style_label)
    OutlinedButton(
        onClick = { dialogOpen = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("$title: ${stringResource(ttsStyleLabel(selected))}")
    }
    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(title) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TtsStyle.entries.forEach { option ->
                        RadioRow(
                            label = stringResource(ttsStyleLabel(option)),
                            selected = option == selected,
                            onSelect = {
                                onSelect(option)
                                dialogOpen = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(R.string.settings_tts_voice_dismiss))
                }
            },
        )
    }
}

private fun ttsStyleLabel(style: TtsStyle): Int = when (style) {
    TtsStyle.WEATHER_FORECASTER -> R.string.settings_tts_style_weather_forecaster
    TtsStyle.PIRATE -> R.string.settings_tts_style_pirate
    TtsStyle.COWBOY -> R.string.settings_tts_style_cowboy
    TtsStyle.SCIFI_NARRATOR -> R.string.settings_tts_style_scifi_narrator
    TtsStyle.SCIENCE_TEACHER -> R.string.settings_tts_style_science_teacher
    TtsStyle.HISTORIAN -> R.string.settings_tts_style_historian
    TtsStyle.SPORTSCASTER -> R.string.settings_tts_style_sportscaster
    TtsStyle.STADIUM_ANNOUNCER -> R.string.settings_tts_style_stadium_announcer
    TtsStyle.STORYTELLER -> R.string.settings_tts_style_storyteller
    TtsStyle.FITNESS_INSTRUCTOR -> R.string.settings_tts_style_fitness_instructor
    TtsStyle.MORNING_PRESENTER -> R.string.settings_tts_style_morning_presenter
    TtsStyle.FATHER_CHRISTMAS -> R.string.settings_tts_style_father_christmas
    TtsStyle.SPOOKY_NARRATOR -> R.string.settings_tts_style_spooky_narrator
    TtsStyle.NEW_YEARS_HOST -> R.string.settings_tts_style_new_years_host
    TtsStyle.LEPRECHAUN -> R.string.settings_tts_style_leprechaun
    TtsStyle.KING -> R.string.settings_tts_style_king
    TtsStyle.QUEEN -> R.string.settings_tts_style_queen
    TtsStyle.PRESIDENT -> R.string.settings_tts_style_president
}

@Composable
private fun VoicePicker(
    title: String,
    voices: List<TtsVoiceOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val current = voices.firstOrNull { it.id == selectedId }
    OutlinedButton(
        onClick = { dialogOpen = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("$title: ${current?.displayName ?: selectedId}")
    }
    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(title) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    voices.forEach { option ->
                        RadioRow(
                            label = option.displayName,
                            selected = option.id == selectedId,
                            onSelect = {
                                onSelect(option.id)
                                dialogOpen = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(R.string.settings_tts_voice_dismiss))
                }
            },
        )
    }
}

/**
 * Voice picker for the on-device TTS engine. Wraps the generic [VoicePicker]
 * with two extras specific to the device path:
 *
 *  - An "Auto" entry at the top that maps to a `null` voice ID — meaning
 *    "let [app.clothescast.tts.AndroidTtsSpeaker] auto-pick the highest-
 *    quality voice for the locale." This is the default for installs that
 *    haven't opened the picker.
 *  - A "Currently:" line below the picker showing what would actually be
 *    spoken right now — the user's pin if set, else the auto-pick. Lets
 *    users see their selection without first hitting Test.
 *
 * Voices come from [app.clothescast.tts.AndroidTtsVoiceEnumerator] via the
 * Settings ViewModel; an empty list means we're still enumerating (or the
 * device has no voices at all).
 */
@Composable
private fun DeviceVoicePicker(
    voices: List<DeviceVoice>,
    selectedId: String?,
    effectiveDeviceVoice: DeviceVoice?,
    onSelect: (String?) -> Unit,
    enabled: Boolean = true,
) {
    val autoLabel = stringResource(R.string.settings_tts_device_voice_auto)
    val networkTag = stringResource(R.string.settings_tts_device_voice_network)
    val offlineTag = stringResource(R.string.settings_tts_device_voice_offline)
    val tagFor: (DeviceVoice) -> String = { if (it.isNetworkRequired) networkTag else offlineTag }
    val labelFor: (DeviceVoice) -> String = { "${it.id} · ${tagFor(it)} · q${it.quality}" }
    val voiceOptions = listOf(TtsVoiceOption(DEVICE_VOICE_AUTO_ID, autoLabel)) +
        voices.map { TtsVoiceOption(it.id, labelFor(it)) }
    VoicePicker(
        title = stringResource(R.string.settings_tts_device_voice_label),
        voices = voiceOptions,
        selectedId = selectedId ?: DEVICE_VOICE_AUTO_ID,
        enabled = enabled,
        onSelect = { id -> onSelect(id.takeIf { it != DEVICE_VOICE_AUTO_ID }) },
    )
    val currently = effectiveDeviceVoice?.let(labelFor)
        ?: stringResource(R.string.settings_tts_device_voice_currently_loading)
    Text(
        text = stringResource(R.string.settings_tts_device_voice_currently, currently),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val DEVICE_VOICE_AUTO_ID = "__auto__"

/**
 * Tracks whether `com.google.android.tts` is currently installed, refreshing
 * on every `ON_RESUME`. Lives in the composable layer (rather than the
 * SettingsViewModel) because the ViewModel instance is reused across
 * Settings entries — a one-shot package check at construction time would
 * never refresh after the user installs Google TTS via the CTA below and
 * returns to the app. The check itself is a cheap sync PackageManager
 * call (~1ms) so there's no need to push it off the main thread.
 */
@Composable
internal fun rememberIsGoogleTtsInstalled(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var installed by remember { mutableStateOf(checkGoogleTtsInstalled(context)) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installed = checkGoogleTtsInstalled(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return installed
}

private fun checkGoogleTtsInstalled(context: android.content.Context): Boolean = runCatching {
    context.packageManager.getPackageInfo(GOOGLE_TTS_PACKAGE, 0)
}.isSuccess

/**
 * Banner shown when "Speech Services by Google" isn't installed. The vendor
 * default TTS engine on most non-Pixel devices sounds notably worse than
 * Google's, and the install fixes that with one tap. Hidden once Google's
 * engine is detected.
 */
@Composable
internal fun InstallGoogleTtsHint() {
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.settings_tts_install_google_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = {
            // ACTION_VIEW with market:// goes to Play Store if installed,
            // falls back gracefully on Aurora/Sideload installs that handle
            // the scheme. We don't add a https-fallback here because users
            // without any market app installed are extremely unlikely to be
            // installing TTS engines anyway.
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=$GOOGLE_TTS_PACKAGE"),
            )
            runCatching { context.startActivity(intent) }
                .onFailure { DiagLog.w("VoiceSettings", "Couldn't open Play Store for Google TTS install", it) }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.settings_tts_install_google_button))
    }
}

@Composable
private fun TestVoiceButton(isPreviewing: Boolean, onClick: () -> Unit) {
    // Disabled while previewing — billed cloud TTS calls can't be reliably
    // un-billed once dispatched, and the in-flight playback is already what
    // a fresh tap would re-trigger. Spinner replaces the label so the user
    // sees the click was registered and something is happening.
    Button(
        onClick = onClick,
        enabled = !isPreviewing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isPreviewing) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else {
            Text(stringResource(R.string.settings_tts_test))
        }
    }
}

/**
 * Plays a preview through the chosen engine + voice. Builds a fixed canned
 * [InsightSummary] and renders it through the same TTS-utterance formatter the
 * real briefing pipeline uses, so the preview reads exactly like a real
 * morning briefing in the chosen voice locale — no separate per-locale
 * sample string to keep in sync with the prose templates. The summary is
 * deliberately broad enough to exercise band + delta + multi-item clothes
 * clauses on every preview tap, and costs the same number of BYOK tokens
 * each time so engines / voices compare on identical words.
 *
 * The sample is rendered in the selected *voice locale* so the preview
 * matches the accent and language the user is auditioning, not the app's
 * UI locale. Brand-prefix framing ("Today's ClothesCast: …") is omitted
 * for now — see TODO(brand-intro) in [FetchAndNotifyWorker.formatProse].
 *
 * Errors are surfaced as a Toast so the user can see *why* the voice failed
 * (most often: missing or wrong API key for the chosen provider).
 */
internal suspend fun runTtsPreview(
    context: android.content.Context,
    engine: TtsEngine,
    geminiVoice: String,
    ttsStyle: TtsStyle,
    deviceVoice: String?,
    voiceLocale: VoiceLocale,
    region: Region,
    temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    rangeFormat: RangeFormat = RangeFormat.DEGREES,
    clothesFormat: ClothesFormat = ClothesFormat.ITEMS,
    bottomsFormat: BottomsFormat = BottomsFormat.IF_GARMENTS,
) {
    val app = context.applicationContext as app.clothescast.ClothesCastApplication
    // Network synthesis and AudioTrack write are both blocking-ish work — Ktor
    // suspends off-Main internally, but AudioTrack.write/play are JNI calls and
    // we don't want a hot stack of preview work running on the UI dispatcher.
    withContext(Dispatchers.IO) {
        val utterance = insightTtsUtterance(
            context = context,
            summary = SAMPLE_SUMMARY,
            region = region,
            voiceLocale = voiceLocale,
            temperatureUnit = temperatureUnit,
            rangeFormat = rangeFormat,
            clothesFormat = clothesFormat,
            bottomsFormat = bottomsFormat,
        )
        withSpeechAudioFocus(context) {
            try {
                when (engine) {
                    TtsEngine.GEMINI ->
                        GeminiTtsSpeaker(
                            app.geminiTtsClient,
                            voiceName = geminiVoice,
                            style = ttsStyle,
                        ).speak(utterance.text, utterance.locale)
                    TtsEngine.DEVICE ->
                        app.deviceTtsSpeaker(deviceVoice).speak(utterance.text, utterance.locale)
                }
            } catch (_: CancellationException) {
                // Composable scope cancelled (user navigated away mid-preview); not an error.
            } catch (t: Throwable) {
                // TTS exceptions already name their provider in the message
                // (e.g. "Gemini TTS HTTP 400: …"); don't double that up.
                val message = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                DiagLog.w("VoiceSettings", "TTS preview failed for $engine", t)
                // Toast.show() posts internally, but Toast.makeText()'s constructor needs
                // a Looper on the calling thread — Dispatchers.IO has none, so hop to Main.
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                }
                // Fall back to the on-device engine so the user still hears the preview
                // and can confirm audio output is working — mirrors FetchAndNotifyWorker.
                if (engine != TtsEngine.DEVICE) {
                    try {
                        app.deviceTtsSpeaker(deviceVoice).speak(utterance.text, utterance.locale)
                    } catch (_: CancellationException) {
                        // user moved on; fine
                    } catch (fallback: Throwable) {
                        DiagLog.w("VoiceSettings", "Device TTS fallback also failed", fallback)
                    }
                }
            }
        }
    }
}

internal fun ttsEngineLabel(engine: TtsEngine): Int = when (engine) {
    TtsEngine.DEVICE -> R.string.settings_tts_engine_device
    TtsEngine.GEMINI -> R.string.settings_tts_engine_gemini
}

// Canned forecast for the voice preview: a cold day 7°C down on yesterday with
// sweater + jacket as the clothes pick. Exercises band, delta, and a multi-item
// clothes clause without dragging in a precip / tie-in time. With Celsius +
// degrees-range + items-clothes this renders (en-US) as "Today, it will be 8°.
// 7° cooler than yesterday. Wear a sweater and jacket." — the exact prose
// shifts with voice locale and the user's format settings (temperature unit,
// range format, clothes format, rain accessory), which is intentional: the
// preview should sound like what the user actually hears at briefing time.
private val SAMPLE_SUMMARY = InsightSummary(
    period = ForecastPeriod.TODAY,
    band = BandClause(TemperatureBand.COLD, TemperatureBand.COLD),
    delta = DeltaClause(degrees = 7, direction = DeltaClause.Direction.COOLER),
    clothes = ClothesClause(items = listOf("sweater", "jacket")),
)
