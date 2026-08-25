package app.clothescast.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.ui.EdgeFadeOverlay
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries

/**
 * Open-source attribution, reached from Settings → About → Licenses: every
 * third-party component bundled in the APK, and the license each ships under.
 *
 * The list is read from the committed `res/raw/aboutlibraries.json`,
 * regenerated with `./gradlew :app:exportBundledLicenses` — the AboutLibraries
 * plugin can't wire the resource in automatically under AGP 9 (see
 * app/build.gradle.kts).
 */
@Composable
internal fun LicensesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    // rememberLibraries parses the bundled JSON off the composition thread and
    // swaps it in when ready, so the page renders its chrome instantly rather
    // than holding the first frame on a 180 KB parse.
    val libraries by rememberLibraries(R.raw.aboutlibraries)
    SettingsScaffold(R.string.settings_licenses_title, onBack) { padding ->
        LicensesContent(
            libraries = libraries,
            padding = padding,
            onOpenLicenseUrl = { url -> openUrl(context, url) },
        )
    }
}

@Composable
internal fun LicensesContent(
    libraries: Libs?,
    padding: PaddingValues,
    onOpenLicenseUrl: (String) -> Unit = {},
) {
    // The tapped component's stable id, if any — its details fill the dialog
    // below. Saved (not a plain remember) so an open dialog survives rotation
    // and process death; resolved back to the library once the list is loaded.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = remember(libraries, selectedId) {
        selectedId?.let { id -> libraries?.libraries?.firstOrNull { it.uniqueId == id } }
    }
    // The export lists components in dependency-coordinate order, which reads
    // as no order at all once the coordinates themselves are hidden —
    // "Experimental annotation" lands nowhere near "Annotation". The displayed
    // name is the only thing a reader can scan by here, and there is no search,
    // so sort on exactly that. Case-insensitive so a lowercase coordinate
    // fallback name doesn't sort into its own block after the Z's.
    val sortedLibraries = remember(libraries) {
        libraries?.libraries.orEmpty()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Library::name))
    }
    val listState = rememberLazyListState()
    EdgeFadeOverlay(
        topFadeVisible = listState.canScrollBackward,
        bottomFadeVisible = listState.canScrollForward,
        modifier = Modifier.padding(padding),
    ) {
        // Just the component names, one compact row each; the version and
        // license live behind a tap so a 200-plus-row list stays scannable.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(
                items = sortedLibraries,
                key = { it.uniqueId },
            ) { library ->
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedId = library.uniqueId }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
    selected?.let { library ->
        LibraryDetailsDialog(
            library = library,
            onOpenLicenseUrl = onOpenLicenseUrl,
            onDismiss = { selectedId = null },
        )
    }
}

/**
 * Version and license(s) for a tapped [library]. The bundled export carries no
 * license text (it's excluded to keep CI's regenerate-and-diff deterministic —
 * see app/build.gradle.kts), so each license with a URL is a link to the full
 * text rather than inline body copy.
 */
@Composable
internal fun LibraryDetailsDialog(
    library: Library,
    onOpenLicenseUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
        },
        title = { Text(library.name) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                library.artifactVersion?.let { version ->
                    Text(
                        text = stringResource(R.string.settings_licenses_version, version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                library.licenses.forEach { license ->
                    val url = license.url
                    if (!url.isNullOrEmpty()) {
                        // A link to the full license text — primary color and a
                        // tap target signal it opens in the browser.
                        //
                        // It is a control, so it owes Android's 48dp minimum
                        // touch target: bodyMedium's own line box is about 20dp
                        // and the 8dp padding alone left it at roughly 36dp.
                        // The min height wins for a one-line name; a name long
                        // enough to wrap grows past it and keeps the padding.
                        Text(
                            text = license.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenLicenseUrl(url) }
                                .heightIn(min = 48.dp)
                                .wrapContentHeight(Alignment.CenterVertically)
                                .padding(vertical = 8.dp),
                        )
                    } else {
                        Text(
                            text = license.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
    )
}
