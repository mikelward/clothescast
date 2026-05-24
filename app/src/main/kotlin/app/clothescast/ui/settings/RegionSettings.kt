package app.clothescast.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.DistanceUnitSetting
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TemperatureUnitSetting
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.TimeFormatSetting
import app.clothescast.core.domain.model.symbol
import app.clothescast.ui.EdgeFadeOverlay
import java.text.Collator

@Composable
internal fun RegionContent(
    region: Region,
    temperatureUnitSetting: TemperatureUnitSetting,
    distanceUnitSetting: DistanceUnitSetting,
    timeFormatSetting: TimeFormatSetting,
    resolvedTemperatureUnit: TemperatureUnit,
    resolvedDistanceUnit: DistanceUnit,
    resolvedTimeFormat: TimeFormat,
    padding: PaddingValues,
    onSetRegion: (Region) -> Unit,
    onSetTemperatureUnit: (TemperatureUnitSetting) -> Unit,
    onSetDistanceUnit: (DistanceUnitSetting) -> Unit,
    onSetTimeFormat: (TimeFormatSetting) -> Unit,
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
            SectionCard(title = stringResource(R.string.settings_region_language_title)) {
                Text(
                    text = stringResource(R.string.settings_region_language_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RegionLanguagePicker(selected = region, onSelect = onSetRegion)
            }
            SectionCard(title = stringResource(R.string.settings_temperature_unit_title)) {
                TemperatureUnitSetting.entries.forEach { setting ->
                    val label = if (setting == TemperatureUnitSetting.AUTO)
                        "${stringResource(R.string.settings_unit_auto)} (${resolvedTemperatureUnit.symbol()})"
                    else
                        stringResource(temperatureUnitSettingLabel(setting))
                    RadioRow(
                        label = label,
                        selected = setting == temperatureUnitSetting,
                        onSelect = { onSetTemperatureUnit(setting) },
                    )
                }
            }
            SectionCard(title = stringResource(R.string.settings_distance_unit_title)) {
                DistanceUnitSetting.entries.forEach { setting ->
                    val label = if (setting == DistanceUnitSetting.AUTO)
                        "${stringResource(R.string.settings_unit_auto)} (${resolvedDistanceUnit.symbol()})"
                    else
                        stringResource(distanceUnitSettingLabel(setting))
                    RadioRow(
                        label = label,
                        selected = setting == distanceUnitSetting,
                        onSelect = { onSetDistanceUnit(setting) },
                    )
                }
            }
            SectionCard(title = stringResource(R.string.settings_time_format_title)) {
                TimeFormatSetting.entries.forEach { setting ->
                    val label = if (setting == TimeFormatSetting.AUTO) {
                        val resolvedLabel = stringResource(timeFormatLabel(resolvedTimeFormat))
                        "${stringResource(R.string.settings_unit_auto)} ($resolvedLabel)"
                    } else {
                        stringResource(timeFormatSettingLabel(setting))
                    }
                    RadioRow(
                        label = label,
                        selected = setting == timeFormatSetting,
                        onSelect = { onSetTimeFormat(setting) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RegionLanguagePicker(
    selected: Region,
    onSelect: (Region) -> Unit,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uiLocale = context.resourcesLocale()
    // System tag and label for "Follow system locale": both derived from the
    // device locale, not the per-app override. On API 33+ the OS applies
    // per-app locales at the process level, so Resources.getSystem() reflects
    // the app override — we use LocaleManager.getSystemLocales() there.
    // The label itself is also rendered in the device language so it reads
    // naturally regardless of which in-app region is active.
    val systemLocale = remember { context.deviceSystemLocale().stripExtensions() }
    val systemTag = remember(systemLocale) { systemLocale.toLanguageTag() }
    val systemLabel = remember(systemLocale) {
        val cfg = Configuration(context.resources.configuration)
            .also { it.setLocale(systemLocale) }
        context.createConfigurationContext(cfg)
            .resources.getString(R.string.settings_region_language_system)
    }
    val labelFor: @Composable (Region) -> String = { option ->
        if (option == Region.SYSTEM) "$systemLabel ($systemTag)"
        else stringResource(regionLabel(option))
    }
    val title = stringResource(R.string.settings_region_language_title)
    OutlinedButton(
        onClick = { dialogOpen = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Include the title in the button text so TalkBack announces this as
        // a language picker even when read in isolation from the surrounding
        // SectionCard header. Mirrors VoiceLocalePicker in VoiceSettings.kt.
        Text("$title: ${labelFor(selected)}")
    }
    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(title) },
            text = {
                // Sort by the resolved display label using a locale-aware
                // collator so the order reads naturally in the user's UI
                // language. SYSTEM stays pinned at the top — it's a "follow
                // device" option, not a language to sort with the rest.
                val collator = remember(uiLocale) { Collator.getInstance(uiLocale) }
                val labelled = Region.entries.map { option -> option to labelFor(option) }
                val (system, rest) = labelled.partition { it.first == Region.SYSTEM }
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
                // Reuse the already-translated "Done" from the voice locale
                // picker — same dismiss semantics, avoids duplicating the
                // string in 30+ locale files.
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(R.string.settings_tts_voice_dismiss))
                }
            },
        )
    }
}

private fun regionLabel(region: Region): Int = when (region) {
    Region.SYSTEM -> R.string.settings_region_language_system
    Region.EN_US -> R.string.settings_region_language_en_us
    Region.EN_GB -> R.string.settings_region_language_en_gb
    Region.EN_AU -> R.string.settings_region_language_en_au
    Region.EN_CA -> R.string.settings_region_language_en_ca
    Region.DE_DE -> R.string.settings_region_language_de_de
    Region.DE_AT -> R.string.settings_region_language_de_at
    Region.DE_CH -> R.string.settings_region_language_de_ch
    Region.FR_FR -> R.string.settings_region_language_fr_fr
    Region.FR_CA -> R.string.settings_region_language_fr_ca
    Region.IT_IT -> R.string.settings_region_language_it_it
    Region.ES_ES -> R.string.settings_region_language_es_es
    Region.ES_MX -> R.string.settings_region_language_es_mx
    Region.CA_ES -> R.string.settings_region_language_ca_es
    Region.RU_RU -> R.string.settings_region_language_ru_ru
    Region.PL_PL -> R.string.settings_region_language_pl_pl
    Region.HR_HR -> R.string.settings_region_language_hr_hr
    Region.SL_SI -> R.string.settings_region_language_sl_si
    Region.SR_RS -> R.string.settings_region_language_sr_rs
    Region.SR_CYRL_RS -> R.string.settings_region_language_sr_cyrl_rs
    Region.BG_BG -> R.string.settings_region_language_bg_bg
    Region.CS_CZ -> R.string.settings_region_language_cs_cz
    Region.SK_SK -> R.string.settings_region_language_sk_sk
    Region.HU_HU -> R.string.settings_region_language_hu_hu
    Region.RO_RO -> R.string.settings_region_language_ro_ro
    Region.EL_GR -> R.string.settings_region_language_el_gr
    Region.UK_UA -> R.string.settings_region_language_uk_ua
    Region.PT_BR -> R.string.settings_region_language_pt_br
    Region.PT_PT -> R.string.settings_region_language_pt_pt
    Region.NL_NL -> R.string.settings_region_language_nl_nl
    Region.SV_SE -> R.string.settings_region_language_sv_se
    Region.DA_DK -> R.string.settings_region_language_da_dk
    Region.NB_NO -> R.string.settings_region_language_nb_no
    Region.FI_FI -> R.string.settings_region_language_fi_fi
    Region.ET_EE -> R.string.settings_region_language_et_ee
    Region.LV_LV -> R.string.settings_region_language_lv_lv
    Region.LT_LT -> R.string.settings_region_language_lt_lt
    Region.TR_TR -> R.string.settings_region_language_tr_tr
    Region.EN_ZA -> R.string.settings_region_language_en_za
    Region.ID_ID -> R.string.settings_region_language_id_id
    Region.MS_MY -> R.string.settings_region_language_ms_my
    Region.FIL_PH -> R.string.settings_region_language_fil_ph
    Region.SW_KE -> R.string.settings_region_language_sw_ke
    Region.VI_VN -> R.string.settings_region_language_vi_vn
    Region.TH_TH -> R.string.settings_region_language_th_th
    Region.ZH_CN -> R.string.settings_region_language_zh_cn
    Region.ZH_TW -> R.string.settings_region_language_zh_tw
    Region.HI_IN -> R.string.settings_region_language_hi_in
    Region.BN_BD -> R.string.settings_region_language_bn_bd
    Region.JA_JP -> R.string.settings_region_language_ja_jp
    Region.KO_KR -> R.string.settings_region_language_ko_kr
    Region.AR_SA -> R.string.settings_region_language_ar_sa
    Region.HE_IL -> R.string.settings_region_language_he_il
    Region.FA_IR -> R.string.settings_region_language_fa_ir
    Region.SQ_AL -> R.string.settings_region_language_sq_al
    Region.AM_ET -> R.string.settings_region_language_am_et
}

private fun temperatureUnitSettingLabel(setting: TemperatureUnitSetting): Int = when (setting) {
    TemperatureUnitSetting.AUTO -> R.string.settings_unit_auto
    TemperatureUnitSetting.CELSIUS -> R.string.settings_temperature_unit_celsius
    TemperatureUnitSetting.FAHRENHEIT -> R.string.settings_temperature_unit_fahrenheit
}

private fun distanceUnitSettingLabel(setting: DistanceUnitSetting): Int = when (setting) {
    DistanceUnitSetting.AUTO -> R.string.settings_unit_auto
    DistanceUnitSetting.KILOMETERS -> R.string.settings_distance_unit_kilometers
    DistanceUnitSetting.MILES -> R.string.settings_distance_unit_miles
}

private fun timeFormatSettingLabel(setting: TimeFormatSetting): Int = when (setting) {
    TimeFormatSetting.AUTO -> R.string.settings_unit_auto
    TimeFormatSetting.TWELVE_HOUR -> R.string.settings_time_format_12h
    TimeFormatSetting.TWENTY_FOUR_HOUR -> R.string.settings_time_format_24h
}

private fun timeFormatLabel(format: TimeFormat): Int = when (format) {
    TimeFormat.TWELVE_HOUR -> R.string.settings_time_format_12h
    TimeFormat.TWENTY_FOUR_HOUR -> R.string.settings_time_format_24h
}
