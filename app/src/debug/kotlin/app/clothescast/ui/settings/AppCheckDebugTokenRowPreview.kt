package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.clothescast.ui.theme.ClothesCastTheme

/**
 * Standalone preview of the App Check debug-token row, driven with a
 * fixed UUID so it renders the visible state even on CI builds where
 * `BuildConfig.APP_CHECK_DEBUG_TOKEN` is blank (and the public
 * `AppCheckDebugTokenRow` would early-return). The matching Roborazzi
 * snapshot lives at `app/snapshots/settings_voice_app_check_debug_token.png`
 * so reviewers can see the row's layout without checking out the branch.
 *
 * The token shown here is a placeholder, not a real per-developer
 * value — it's literally the all-zeros UUID with a recognizable
 * suffix. Real tokens are generated into `local.properties` by
 * `ensureAppCheckDebugToken` in `app/build.gradle.kts`.
 */
@Preview(name = "Settings · Voice (App Check debug token)", widthDp = 360)
@Composable
internal fun SettingsVoiceAppCheckDebugTokenPreview() {
    ClothesCastTheme(dynamicColor = false) {
        Surface {
            // Same horizontal padding the Voice settings page wraps every
            // row in (see VoiceContent's outer Column) — keeps the
            // snapshot's row-edge margins matching the real screen.
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                AppCheckDebugTokenContent(token = "00000000-0000-0000-0000-deadbeefcafe")
            }
        }
    }
}
