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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.UserPreferences

private const val SETUP_GUIDE_URL =
    "https://github.com/mikelward/clothescast/blob/main/docs/smart-home.md"
private const val PRIVACY_POLICY_URL =
    "https://github.com/mikelward/clothescast/blob/main/PRIVACY.md"

@Composable
internal fun SmartHomeContent(
    bridgeEnabled: Boolean,
    host: String,
    port: Int,
    useTls: Boolean,
    username: String,
    topic: String,
    passwordSet: Boolean,
    padding: PaddingValues,
    onSetBridgeEnabled: (Boolean) -> Unit,
    onSaveConfig: (host: String, port: Int, useTls: Boolean, username: String, topic: String, password: String?) -> Unit,
    onClearPassword: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MqttBridgeCard(
            enabled = bridgeEnabled,
            host = host,
            port = port,
            useTls = useTls,
            username = username,
            topic = topic,
            passwordSet = passwordSet,
            onSetEnabled = onSetBridgeEnabled,
            onSaveConfig = onSaveConfig,
            onClearPassword = onClearPassword,
        )
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
    onSetEnabled: (Boolean) -> Unit,
    onSaveConfig: (host: String, port: Int, useTls: Boolean, username: String, topic: String, password: String?) -> Unit,
    onClearPassword: () -> Unit,
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
        Text(
            text = stringResource(R.string.settings_smart_home_mqtt_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_smart_home_mqtt_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (enabled) {
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
                    Text(stringResource(R.string.settings_smart_home_mqtt_topic_hint))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    onSaveConfig(
                        hostField,
                        parsedPort ?: UserPreferences.DEFAULT_MQTT_PORT,
                        tlsField,
                        userField,
                        topicField,
                        passwordField.ifEmpty { null },
                    )
                    passwordField = ""
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_smart_home_mqtt_save)) }
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
