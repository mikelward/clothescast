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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import app.clothescast.R
import app.clothescast.cast.DiscoveredCastRoute
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.discovery.DiscoveredService
import app.clothescast.discovery.ServiceType
import app.clothescast.ui.EdgeFadeOverlay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val SETUP_GUIDE_URL =
    "https://github.com/mikelward/clothescast/blob/main/docs/smart-home.md"
// PRIVACY_POLICY_URL lives in SettingsCommon.kt — shared with Privacy +
// Location settings.

@Composable
internal fun SmartHomeContent(
    bridgeEnabled: Boolean,
    host: String,
    port: Int,
    useTls: Boolean,
    username: String,
    topic: String,
    passwordSet: Boolean,
    lastError: String?,
    lastErrorAt: Long,
    lastPublishAt: Long,
    publishing: Boolean,
    mqttSkipPhoneSpeech: Boolean,
    discoveryRunning: Boolean,
    discoveredServices: List<DiscoveredService>,
    castAvailable: Boolean,
    castRouteName: String?,
    castPickerOpen: Boolean,
    castDiscoveredRoutes: List<DiscoveredCastRoute>,
    castInProgress: Boolean,
    castLastError: String?,
    castLastErrorAt: Long,
    castLastSuccessAt: Long,
    castEnabled: Boolean,
    castMorning: Boolean,
    castTonight: Boolean,
    castSkipPhoneSpeech: Boolean,
    padding: PaddingValues,
    onSetBridgeEnabled: (Boolean) -> Unit,
    onSaveConfig: (host: String, port: Int, useTls: Boolean, username: String, topic: String, password: String?) -> Unit,
    onClearPassword: () -> Unit,
    onPublishNow: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onUseDiscoveredService: (DiscoveredService) -> Unit,
    onOpenCastPicker: () -> Unit,
    onCloseCastPicker: () -> Unit,
    onPickCastRoute: (DiscoveredCastRoute) -> Unit,
    onClearCastRoute: () -> Unit,
    onCastNow: () -> Unit,
    onSetCastEnabled: (Boolean) -> Unit,
    onSetCastMorning: (Boolean) -> Unit,
    onSetCastTonight: (Boolean) -> Unit,
    onSetCastSkipPhoneSpeech: (Boolean) -> Unit,
    onSetMqttSkipPhoneSpeech: (Boolean) -> Unit,
) {
    // Cancel any in-flight scan when this screen leaves the composition —
    // the user backing out of Smart Home shouldn't leave the NsdManager
    // listeners chewing battery in the background.
    DisposableEffect(Unit) {
        onDispose {
            onStopDiscovery()
            onCloseCastPicker()
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
            if (castAvailable) {
                CastDestinationCard(
                    routeName = castRouteName,
                    pickerOpen = castPickerOpen,
                    discoveredRoutes = castDiscoveredRoutes,
                    castInProgress = castInProgress,
                    lastError = castLastError,
                    lastErrorAt = castLastErrorAt,
                    lastSuccessAt = castLastSuccessAt,
                    castEnabled = castEnabled,
                    castMorning = castMorning,
                    castTonight = castTonight,
                    castSkipPhoneSpeech = castSkipPhoneSpeech,
                    onOpenPicker = onOpenCastPicker,
                    onClosePicker = onCloseCastPicker,
                    onPickRoute = onPickCastRoute,
                    onClearRoute = onClearCastRoute,
                    onCastNow = onCastNow,
                    onSetCastEnabled = onSetCastEnabled,
                    onSetCastMorning = onSetCastMorning,
                    onSetCastTonight = onSetCastTonight,
                    onSetCastSkipPhoneSpeech = onSetCastSkipPhoneSpeech,
                )
            }
            MqttBridgeCard(
                enabled = bridgeEnabled,
                host = host,
                port = port,
                useTls = useTls,
                username = username,
                topic = topic,
                passwordSet = passwordSet,
                lastError = lastError,
                lastErrorAt = lastErrorAt,
                lastPublishAt = lastPublishAt,
                publishing = publishing,
                skipPhoneSpeech = mqttSkipPhoneSpeech,
                discoveryRunning = discoveryRunning,
                discoveredServices = discoveredServices,
                onSetEnabled = onSetBridgeEnabled,
                onSaveConfig = onSaveConfig,
                onClearPassword = onClearPassword,
                onPublishNow = onPublishNow,
                onSetSkipPhoneSpeech = onSetMqttSkipPhoneSpeech,
                onStartDiscovery = onStartDiscovery,
                onStopDiscovery = onStopDiscovery,
                onUseDiscoveredService = onUseDiscoveredService,
            )
        }
    }
}

@Composable
private fun MqttBridgeCard(
    enabled: Boolean,
    host: String,
    port: Int,
    useTls: Boolean,
    username: String,
    topic: String,
    passwordSet: Boolean,
    lastError: String?,
    lastErrorAt: Long,
    lastPublishAt: Long,
    publishing: Boolean,
    skipPhoneSpeech: Boolean,
    discoveryRunning: Boolean,
    discoveredServices: List<DiscoveredService>,
    onSetEnabled: (Boolean) -> Unit,
    onSaveConfig: (host: String, port: Int, useTls: Boolean, username: String, topic: String, password: String?) -> Unit,
    onClearPassword: () -> Unit,
    onPublishNow: () -> Unit,
    onSetSkipPhoneSpeech: (Boolean) -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onUseDiscoveredService: (DiscoveredService) -> Unit,
) {
    val context = LocalContext.current
    // Local form state seeded from the persisted prefs, rebuilt whenever the
    // persisted values change so a config edit elsewhere (or a settings reset)
    // refreshes the fields. The user only commits with Save; partial typing
    // doesn't reach DataStore until then.
    var hostField by rememberSaveable(host) { mutableStateOf(host) }
    var portField by rememberSaveable(port) { mutableStateOf(port.toString()) }
    var tlsField by rememberSaveable(useTls) { mutableStateOf(useTls) }
    var userField by rememberSaveable(username) { mutableStateOf(username) }
    var topicField by rememberSaveable(topic) { mutableStateOf(topic) }
    var passwordField by rememberSaveable { mutableStateOf("") }
    // Default the password input to hidden when one is already saved; re-key on
    // `passwordSet` so a Clear (true → false) re-expands and a first-time Save
    // (false → true) collapses without extra wiring.
    var showPasswordField by rememberSaveable(passwordSet) { mutableStateOf(!passwordSet) }

    val parsedPort = remember(portField) { portField.toIntOrNull() }
    val canSave = hostField.isNotBlank() && parsedPort != null && parsedPort in 1..65535 &&
        topicField.isNotBlank()

    SectionCard(title = stringResource(R.string.settings_smart_home_mqtt_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_smart_home_mqtt_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = onSetEnabled)
        }

        if (enabled) {
            DiscoveryPicker(
                running = discoveryRunning,
                services = discoveredServices,
                onStart = onStartDiscovery,
                onStop = onStopDiscovery,
                onUse = { service ->
                    // Mirror the saved config back into local form state so
                    // the textfields show the pick immediately, without
                    // waiting on the round-trip through DataStore.
                    hostField = service.host
                    if (service.type == ServiceType.MQTT) portField = service.port.toString()
                    onUseDiscoveredService(service)
                },
            )
            OutlinedTextField(
                value = hostField,
                onValueChange = { hostField = it.trim() },
                label = { Text(stringResource(R.string.settings_smart_home_mqtt_host)) },
                placeholder = { Text(stringResource(R.string.settings_smart_home_mqtt_host_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = portField,
                onValueChange = { portField = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.settings_smart_home_mqtt_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_smart_home_mqtt_tls),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = tlsField,
                    onCheckedChange = { checked ->
                        tlsField = checked
                        // Auto-bump the default port when the user toggles TLS
                        // — saves a manual re-type for the common case
                        // (8883 for TLS, 1883 plain). If the user has
                        // explicitly picked a non-default port, leave it.
                        if (checked && parsedPort == UserPreferences.DEFAULT_MQTT_PORT) {
                            portField = UserPreferences.DEFAULT_MQTT_TLS_PORT.toString()
                        } else if (!checked && parsedPort == UserPreferences.DEFAULT_MQTT_TLS_PORT) {
                            portField = UserPreferences.DEFAULT_MQTT_PORT.toString()
                        }
                    },
                )
            }
            OutlinedTextField(
                value = userField,
                onValueChange = { userField = it.trim() },
                label = { Text(stringResource(R.string.settings_smart_home_mqtt_username)) },
                placeholder = { Text(stringResource(R.string.settings_smart_home_mqtt_username_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showPasswordField) {
                OutlinedTextField(
                    value = passwordField,
                    onValueChange = { passwordField = it },
                    label = {
                        Text(
                            if (passwordSet) {
                                stringResource(R.string.settings_smart_home_mqtt_password_replace)
                            } else {
                                stringResource(R.string.settings_smart_home_mqtt_password)
                            },
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_smart_home_mqtt_password_status_set),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { showPasswordField = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_smart_home_mqtt_password_replace)) }
            }
            if (passwordSet) {
                TextButton(
                    onClick = {
                        onClearPassword()
                        passwordField = ""
                    },
                ) { Text(stringResource(R.string.settings_smart_home_mqtt_password_clear)) }
            }
            OutlinedTextField(
                value = topicField,
                onValueChange = { topicField = it.trim() },
                label = { Text(stringResource(R.string.settings_smart_home_mqtt_topic)) },
                supportingText = {
                    Text(
                        stringResource(
                            R.string.settings_smart_home_mqtt_topic_hint,
                            topicField.ifBlank { UserPreferences.DEFAULT_MQTT_TOPIC },
                        ),
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val replacingExisting = passwordSet && passwordField.isNotEmpty()
                    onSaveConfig(
                        hostField,
                        parsedPort ?: UserPreferences.DEFAULT_MQTT_PORT,
                        tlsField,
                        userField,
                        topicField,
                        passwordField.ifEmpty { null },
                    )
                    passwordField = ""
                    // First-time save (passwordSet false → true) collapses via
                    // the rememberSaveable key; replace of an existing password
                    // leaves passwordSet unchanged, so collapse manually here.
                    if (replacingExisting) showPasswordField = false
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_smart_home_mqtt_save)) }

            if (lastError != null && lastErrorAt > 0L) {
                MqttLastErrorBanner(message = lastError, recordedAtMs = lastErrorAt)
            }

            OutlinedButton(
                onClick = onPublishNow,
                enabled = host.isNotBlank() && !publishing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (publishing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    if (publishing) {
                        stringResource(R.string.settings_smart_home_mqtt_publishing)
                    } else {
                        stringResource(R.string.settings_smart_home_mqtt_publish_now)
                    },
                )
            }

            MqttLastPublishStatus(publishing = publishing, lastPublishAt = lastPublishAt)

            MqttToggleRow(
                title = stringResource(R.string.settings_smart_home_mqtt_skip_phone_speech_title),
                description = stringResource(R.string.settings_smart_home_mqtt_skip_phone_speech_description),
                checked = skipPhoneSpeech,
                onCheckedChange = onSetSkipPhoneSpeech,
            )
        }

        TextButton(
            onClick = { openUrl(context, SETUP_GUIDE_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_smart_home_mqtt_setup_guide)) }
        TextButton(
            onClick = { openUrl(context, PRIVACY_POLICY_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_smart_home_mqtt_open_privacy)) }
    }
}

@Composable
private fun MqttLastErrorBanner(message: String, recordedAtMs: Long) {
    val timestamp = remember(recordedAtMs) {
        Instant.ofEpochMilli(recordedAtMs)
            .atZone(ZoneId.systemDefault())
            .format(ERROR_TIMESTAMP_FORMAT)
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.settings_smart_home_mqtt_last_error_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
            )
        }
    }
}

private val ERROR_TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

@Composable
private fun MqttToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun MqttLastPublishStatus(publishing: Boolean, lastPublishAt: Long) {
    val text = when {
        publishing -> stringResource(R.string.settings_smart_home_mqtt_publishing)
        lastPublishAt > 0L -> {
            val timestamp = remember(lastPublishAt) {
                Instant.ofEpochMilli(lastPublishAt)
                    .atZone(ZoneId.systemDefault())
                    .format(ERROR_TIMESTAMP_FORMAT)
            }
            stringResource(R.string.settings_smart_home_mqtt_last_published, timestamp)
        }
        else -> stringResource(R.string.settings_smart_home_mqtt_never_published)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DiscoveryPicker(
    running: Boolean,
    services: List<DiscoveredService>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUse: (DiscoveredService) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = if (running) onStop else onStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    when {
                        running -> R.string.settings_smart_home_discover_stop
                        else -> R.string.settings_smart_home_discover_scan
                    },
                ),
            )
        }
        if (running && services.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_smart_home_discover_scanning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_smart_home_discover_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (services.isNotEmpty()) {
            Text(
                text = stringResource(R.string.settings_smart_home_discover_results),
                style = MaterialTheme.typography.labelMedium,
            )
            services.forEach { service ->
                DiscoveredServiceRow(service = service, onUse = { onUse(service) })
            }
        }
    }
}

@Composable
private fun DiscoveredServiceRow(
    service: DiscoveredService,
    onUse: () -> Unit,
) {
    val typeLabel = stringResource(
        when (service.type) {
            ServiceType.HOME_ASSISTANT -> R.string.settings_smart_home_discover_label_ha
            ServiceType.MQTT -> R.string.settings_smart_home_discover_label_mqtt
        },
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = "${service.host}:${service.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                if (service.name.isNotBlank() && service.name != service.host) {
                    Text(
                        text = service.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onUse) {
                Text(stringResource(R.string.settings_smart_home_discover_use))
            }
        }
    }
}
