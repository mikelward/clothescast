package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.clothescast.data.SignedInGoogleAccount
import app.clothescast.R
import app.clothescast.core.data.weather.GoogleWeatherProbe
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.effective
import app.clothescast.core.domain.model.usablePick
import app.clothescast.core.domain.model.withGoogle
import app.clothescast.ui.EdgeFadeOverlay

/**
 * Minimum number of models the confidence fetcher needs to compute a spread.
 * The picker disables a checkbox's "uncheck" path when the user is at this
 * floor with the box still checked, so they can't deselect their way below
 * it. [MultiModelConfidenceFetcher] would silently return null without two
 * usable models; the floor keeps the chip working without a settings-side
 * "you've broken something" affordance.
 */
private const val MIN_MODELS = 2

/**
 * Cap on the number of models the picker accepts. The chart palette covers
 * every [ForecastModel] entry, so this isn't a "we'd crash above" limit
 * — it's a readability floor. Five overlay lines on the per-model chart is
 * already busy; the full set turns it into a rainbow nobody can read. The
 * picker disables unchecked rows when the user is at the cap, with a one-line
 * hint directing them to deselect first. Auto picks three (see
 * [ForecastModel.defaultsFor]).
 */
private const val MAX_MODELS = 5

@Composable
internal fun ForecastersContent(
    forecastModels: Set<ForecastModel>?,
    location: Location?,
    padding: PaddingValues,
    onSetForecastModels: (Set<ForecastModel>?) -> Unit,
    // Google's own switch, independent of Auto and the Open-Meteo picks.
    // Defaulted so previews compile.
    googleForecast: Boolean = false,
    onSetGoogleForecast: (Boolean) -> Unit = {},
    // The Google forecaster uses its own BYOK Google API key (separate slot from
    // the Gemini TTS key); pass the key state and set/clear actions so the row
    // can disable without a key and the key entry can sit right below it.
    // Defaulted so previews / other callers compile.
    googleApiKeyConfigured: Boolean = false,
    onSetGoogleApiKey: (String) -> Unit = {},
    onClearGoogleApiKey: () -> Unit = {},
    // Google connectivity probe: the page kicks one when Google is enabled (or
    // opened with it on) so the user sees whether their key reaches the Weather
    // API, plus a manual "Check again". Defaulted so previews compile.
    googleProbeRunning: Boolean = false,
    googleProbeResult: GoogleWeatherProbe? = null,
    onCheckGoogleWeather: () -> Unit = {},
    // The Google forecast proxy: with it built in and the user signed in with
    // Google, the Google forecaster works without a key of their own.
    googleWeatherProxyConfigured: Boolean = false,
    // Builds with Sign in with Google show the account section always, as
    // Voice settings does (see voiceAccountSectionVisible).
    googleSignInAvailable: Boolean = false,
    googleAccount: SignedInGoogleAccount? = null,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onDeleteAccount: () -> Unit = {},
) {
    val context = LocalContext.current
    // When the stored selection is null we're in Auto mode: derive a
    // location-aware trio for display via [ForecastModel.defaultsFor] and
    // disable the per-model checkboxes. When non-null, the user has
    // explicitly customised: show their set, enable the checkboxes, and
    // the Auto switch is off. Toggling Auto on clears the stored set to
    // null; toggling any checkbox switches to a non-null effective set
    // with the toggle applied (so an Auto user who unchecks one model
    // ends up explicitly customised, starting from what Auto picked).
    // A pick too small to make a consensus without Google reads as Auto (usablePick).
    // Google counts only while reachable (a key of the user's own, or signed in
    // with the proxy built in), as on the fetch path, so the page shows the set
    // the forecast actually uses.
    val googleReachable = googleForecastReachable(
        googleApiKeyConfigured = googleApiKeyConfigured,
        googleWeatherProxyConfigured = googleWeatherProxyConfigured,
        signedIn = googleAccount != null,
    )
    val googleInUse = googleForecast && googleReachable
    val isAuto = ForecastModel.usablePick(forecastModels, googleInUse) == null
    val effective = ForecastModel.effective(forecastModels, location, googleInUse)
    // The Open-Meteo picks alone: what Auto-off persists and the two-model
    // floor counts. Google doesn't count toward the floor, since it may not
    // contribute (no key, signed out).
    val openMeteo = effective - ForecastModel.GOOGLE_WEATHER
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
            SectionCard(title = stringResource(R.string.settings_forecasters_title)) {
                Text(
                    text = stringResource(R.string.settings_forecasters_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AutoSwitchRow(
                    isAuto = isAuto,
                    onToggle = { nowAuto ->
                        if (nowAuto) {
                            onSetForecastModels(null)
                        } else {
                            // Flipping Auto off persists whatever Auto was
                            // already resolving to, so the visible checkboxes
                            // don't suddenly jump — the user then explicitly
                            // edits from that starting point.
                            onSetForecastModels(openMeteo)
                        }
                    },
                )
                HorizontalDivider()
                // Google's switch holds its slot even while it can't be reached,
                // so a pick made signed out still fits once Google is back.
                val googleSlot = forecasterSlots(openMeteoCount = 0, googleForecast = googleForecast)
                val atCap = forecasterSlots(openMeteo.size, googleForecast) >= MAX_MODELS
                if (atCap && !isAuto) {
                    Text(
                        text = stringResource(R.string.settings_forecasters_cap_hint, MAX_MODELS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Google is rendered separately at the bottom (see below), so
                // the Open-Meteo models loop excludes it.
                ForecastModel.entries.filter { it != ForecastModel.GOOGLE_WEATHER }.forEach { model ->
                    val checked = model in effective
                    // Auto mode: every row reflects the resolver's pick but is
                    // not toggleable — user has to flip Auto off first. Custom
                    // mode: block unchecking when we'd drop below MIN_MODELS
                    // Open-Meteo models, block checking when we'd exceed
                    // MAX_MODELS (Google included). An already-checked row stays
                    // interactable above the cap so the user can still toggle it
                    // off to free a slot.
                    val canToggleOff = openMeteo.size > MIN_MODELS
                    val canToggleOn = !atCap
                    val enabled = !isAuto && (if (checked) canToggleOff else canToggleOn)
                    ForecasterRow(
                        label = stringResource(forecastModelLabel(model)),
                        subtitle = stringResource(forecastModelSubtitle(model)),
                        checked = checked,
                        enabled = enabled,
                        onToggle = { nowChecked ->
                            val updated = if (nowChecked) openMeteo + model else openMeteo - model
                            if (updated.size >= MIN_MODELS && googleSlot + updated.size <= MAX_MODELS) {
                                onSetForecastModels(updated)
                            }
                        },
                    )
                }
                // BOM is omitted from ForecastModel while Open-Meteo's BOM
                // open-data delivery is suspended; surface it as a permanently-
                // disabled row so users searching for the Australian model see
                // it's known-missing rather than wonder where it went. Remove
                // this row and wire BOM through the enum when delivery resumes.
                ForecasterRow(
                    label = stringResource(R.string.settings_forecasters_bom),
                    subtitle = stringResource(R.string.settings_forecasters_bom_subtitle),
                    checked = false,
                    enabled = false,
                    onToggle = {},
                )
                // Google sits last — it isn't an Open-Meteo model, and its switch
                // is independent of Auto and the picks above, so it toggles in
                // either mode. *Adding* Google needs a route to it (a key of the
                // user's own, or Google sign-in with the proxy built in), and in
                // a custom pick a free slot under the cap; Auto makes room for it.
                // Signed out with the proxy built in and no key, turning Google
                // on starts the sign-in in the same tap (SPEC.md). An
                // already-checked Google row always stays removable, so one that
                // can't contribute (key cleared, signed out) never sticks.
                val googleChecked = googleForecast
                val signInWouldReach = googleForecastStartsSignIn(
                    signedIn = googleAccount != null,
                    googleWeatherProxyConfigured = googleWeatherProxyConfigured,
                    googleApiKeyConfigured = googleApiKeyConfigured,
                )
                val googleEnabled = googleChecked ||
                    ((googleReachable || signInWouldReach) && (isAuto || !atCap))
                ForecasterRow(
                    label = stringResource(forecastModelLabel(ForecastModel.GOOGLE_WEATHER)),
                    subtitle = stringResource(forecastModelSubtitle(ForecastModel.GOOGLE_WEATHER)),
                    checked = googleChecked,
                    enabled = googleEnabled,
                    onToggle = { nowChecked ->
                        onSetGoogleForecast(nowChecked)
                        if (nowChecked && signInWouldReach) onSignIn()
                    },
                )
                // Connectivity status for the Google forecaster — only while
                // it's actually selected. Tells the user whether their key
                // reaches the Weather API so a 403 doesn't just show up as a
                // missing line on the chart.
                if (googleChecked) {
                    GoogleProbeStatus(
                        running = googleProbeRunning,
                        result = googleProbeResult,
                        onCheckAgain = onCheckGoogleWeather,
                    )
                }
                if (voiceAccountSectionVisible(signedIn = googleAccount != null, googleSignInAvailable)) {
                    GoogleAccountSection(
                        account = googleAccount,
                        signedOutReason = R.string.settings_forecasters_google_sign_in_reason,
                        enabled = true,
                        onSignIn = onSignIn,
                        onSignOut = onSignOut,
                        onDeleteAccount = onDeleteAccount,
                    )
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.settings_forecasters_google_key_header),
                    style = MaterialTheme.typography.titleSmall,
                )
                KeyEntryFields(
                    configured = googleApiKeyConfigured,
                    statusText = stringResource(
                        if (googleApiKeyConfigured) R.string.settings_forecasters_google_key_set
                        else R.string.settings_forecasters_google_key_unset,
                    ),
                    placeholder = stringResource(R.string.settings_forecasters_google_key_placeholder),
                    saveLabel = stringResource(R.string.settings_forecasters_google_key_save),
                    replaceLabel = stringResource(R.string.settings_forecasters_google_key_replace),
                    clearLabel = stringResource(R.string.settings_forecasters_google_key_clear),
                    onSave = onSetGoogleApiKey,
                    // Clearing the key drops the Google forecaster from the
                    // selection too (it can't contribute without a key); that
                    // cleanup lives in SettingsViewModel.clearGoogleApiKey().
                    onClear = onClearGoogleApiKey,
                )
                TextButton(
                    onClick = { openUrl(context, OPEN_METEO_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_about_open_meteo)) }
            }
        }
    }
}

@Composable
private fun AutoSwitchRow(
    isAuto: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = isAuto,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_forecasters_auto_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_forecasters_auto_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = isAuto, onCheckedChange = null)
    }
}

@Composable
private fun ForecasterRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onToggle,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Status line shown under the Google row while the Google forecaster is
 * selected. Mirrors the most recent [GoogleWeatherProbe]: a spinner while the
 * probe runs, a confirmation when the key reaches the Weather API, or an
 * actionable error (the 403 case names the Cloud Console fix) with a "Check
 * again" retry. [GoogleWeatherProbe.NoKey] renders nothing — the key entry
 * directly below already explains that case.
 */
@Composable
private fun GoogleProbeStatus(
    running: Boolean,
    result: GoogleWeatherProbe?,
    onCheckAgain: () -> Unit,
) {
    if (running) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.settings_forecasters_google_probe_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Nothing to show before the first probe, or when there's simply no key.
    if (result == null || result is GoogleWeatherProbe.NoKey) return

    val isError = result !is GoogleWeatherProbe.Reachable
    val message = when (result) {
        is GoogleWeatherProbe.Reachable ->
            stringResource(R.string.settings_forecasters_google_probe_ok)
        is GoogleWeatherProbe.Forbidden ->
            stringResource(R.string.settings_forecasters_google_probe_forbidden)
        is GoogleWeatherProbe.Failed ->
            result.httpStatus?.let {
                stringResource(R.string.settings_forecasters_google_probe_failed_http, it)
            } ?: stringResource(R.string.settings_forecasters_google_probe_failed)
        is GoogleWeatherProbe.NoKey -> return // handled above
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            // Lands after a key save or "Check again" while focus stays on
            // that control; announce it so screen-reader users hear the result.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(
            onClick = onCheckAgain,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        ) {
            Text(stringResource(R.string.settings_forecasters_google_probe_retry))
        }
    }
}

private fun forecastModelLabel(model: ForecastModel): Int = when (model) {
    ForecastModel.ECMWF_IFS025 -> R.string.settings_forecasters_ecmwf
    ForecastModel.ICON_SEAMLESS -> R.string.settings_forecasters_icon
    ForecastModel.GFS_SEAMLESS -> R.string.settings_forecasters_gfs
    ForecastModel.GEM_SEAMLESS -> R.string.settings_forecasters_gem
    ForecastModel.METEOFRANCE_SEAMLESS -> R.string.settings_forecasters_arpege
    ForecastModel.UKMO_SEAMLESS -> R.string.settings_forecasters_ukmo
    ForecastModel.JMA_SEAMLESS -> R.string.settings_forecasters_jma
    ForecastModel.ECMWF_AIFS025_SINGLE -> R.string.settings_forecasters_aifs
    ForecastModel.GOOGLE_WEATHER -> R.string.settings_forecasters_google
}

private fun forecastModelSubtitle(model: ForecastModel): Int = when (model) {
    ForecastModel.ECMWF_IFS025 -> R.string.settings_forecasters_ecmwf_subtitle
    ForecastModel.ICON_SEAMLESS -> R.string.settings_forecasters_icon_subtitle
    ForecastModel.GFS_SEAMLESS -> R.string.settings_forecasters_gfs_subtitle
    ForecastModel.GEM_SEAMLESS -> R.string.settings_forecasters_gem_subtitle
    ForecastModel.METEOFRANCE_SEAMLESS -> R.string.settings_forecasters_arpege_subtitle
    ForecastModel.UKMO_SEAMLESS -> R.string.settings_forecasters_ukmo_subtitle
    ForecastModel.JMA_SEAMLESS -> R.string.settings_forecasters_jma_subtitle
    ForecastModel.ECMWF_AIFS025_SINGLE -> R.string.settings_forecasters_aifs_subtitle
    ForecastModel.GOOGLE_WEATHER -> R.string.settings_forecasters_google_subtitle
}

/**
 * Whether turning the Google forecaster on starts Sign in with Google: signed
 * out, no key of the user's own, and the proxy built in (which implies the
 * build has sign-in).
 */
/**
 * Slots a pick takes against the five-model cap: its Open-Meteo models, plus
 * one for Google whenever its switch is on, reachable or not.
 */
internal fun forecasterSlots(openMeteoCount: Int, googleForecast: Boolean): Int =
    openMeteoCount + if (googleForecast) 1 else 0

/**
 * Whether the Google forecaster can be reached: a key of the user's own, or
 * signed in with the proxy built in. The fetch path asks the same of the key
 * store; the page and the probe read it from settings state.
 */
internal fun googleForecastReachable(
    googleApiKeyConfigured: Boolean,
    googleWeatherProxyConfigured: Boolean,
    signedIn: Boolean,
): Boolean = googleApiKeyConfigured || (googleWeatherProxyConfigured && signedIn)

internal fun googleForecastStartsSignIn(
    signedIn: Boolean,
    googleWeatherProxyConfigured: Boolean,
    googleApiKeyConfigured: Boolean,
): Boolean = googleWeatherProxyConfigured && !signedIn && !googleApiKeyConfigured
