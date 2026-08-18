package app.clothescast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun RadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick = null so the row's selectable handles the click; this avoids
        // a doubled "selected" announcement from TalkBack and gives the whole
        // row a single tap target.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Tappable row used by the settings root list to drill into a sub-page. */
@Composable
internal fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        }
    }
}

// The literal substring LinkifiedText turns into a tappable link. Lives as a
// constant so the source string and the linkified label can't drift; the link
// target is the API-key creation deep-link below so the user lands on the
// action they're here to do (not the AI Studio home screen). "get a key" reads
// as natural in-sentence English; if/when this string is translated, the
// per-locale linked phrase needs a per-locale constant alongside (see the
// TODO.md entry).
private const val AISTUDIO_LINK_LABEL = "get a key"
private const val AISTUDIO_URL = "https://aistudio.google.com/app/apikey"

/**
 * Plain Text replacement that turns the literal "get a key" inside the supplied
 * string into a clickable link pointing at AI Studio's API-key creation page.
 * The Gemini-key copy in onboarding and settings uses the phrase inline so
 * we don't need to render a bare URL.
 */
@Composable
internal fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        val idx = text.indexOf(AISTUDIO_LINK_LABEL)
        if (idx < 0) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text, 0, idx)
                val link = LinkAnnotation.Url(
                    url = AISTUDIO_URL,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                )
                withLink(link) { append(AISTUDIO_LINK_LABEL) }
                append(text, idx + AISTUDIO_LINK_LABEL.length, text.length)
            }
        }
    }
    Text(text = annotated, modifier = modifier, style = style, color = color)
}

// Open-Meteo's free tier is CC BY 4.0 and asks for a "Weather data by
// Open-Meteo.com" credit with a link back to the site. Surfaced from both
// the About page and the Forecasters picker.
internal const val OPEN_METEO_URL = "https://open-meteo.com/"

// Canonical PRIVACY.md on the public repo. Both Privacy settings and the
// Location settings page link to it — keep them in lockstep by sharing
// the constant rather than duplicating per page.
internal const val PRIVACY_POLICY_URL =
    "https://github.com/mikelward/clothescast/blob/main/PRIVACY.md"

internal fun openUrl(context: android.content.Context, url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

internal fun openAppDetails(context: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
