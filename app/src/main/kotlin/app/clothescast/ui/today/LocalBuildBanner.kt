package app.clothescast.ui.today

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.BuildConfig
import app.clothescast.ClothesCastApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dev affordance shown in place of the Play update banner on local
 * (non-CI) builds: a developer's own APK has no auto-update path, so
 * seeing the branch + commit + "2 hours ago" makes it obvious at a glance
 * which build is installed and how fresh it is.
 *
 * Gated on the same `IS_LOCAL_BUILD` flag the launcher-icon badge uses
 * (see `app/build.gradle.kts`). The CI debug APK and Play release
 * builds (both built on CI) get neither badge nor banner —
 * `main · abc1234 · 6 days ago` would be noise to non-dev users.
 *
 * Dismissal is per-build (keyed on [BuildConfig.GIT_SHA]): once dismissed,
 * the banner stays hidden until a build from a new commit is installed.
 */
@Composable
internal fun LocalBuildBanner(modifier: Modifier = Modifier) {
    // The dismissed-SHA pref is read via a lifecycle-scoped flow that can't run
    // in @Preview / snapshot composition — no-op so a full-screen preview (the
    // scaffold's banner stack) renders cleanly.
    if (LocalInspectionMode.current) return
    if (!BuildConfig.IS_LOCAL_BUILD) return

    val context = LocalContext.current
    val settings = (context.applicationContext as ClothesCastApplication).settingsRepository
    val coroutineScope = rememberCoroutineScope()

    val dismissedSha by settings.dismissedLocalBuildSha.collectAsStateWithLifecycle(initialValue = "")
    if (dismissedSha == BuildConfig.GIT_SHA) return

    LocalBuildBannerCard(
        branch = BuildConfig.GIT_BRANCH,
        sha = BuildConfig.GIT_SHA,
        dirty = BuildConfig.GIT_DIRTY,
        buildTimestampMs = BuildConfig.BUILD_TIMESTAMP_MS,
        modifier = modifier,
        onDismiss = {
            coroutineScope.launch { settings.setDismissedLocalBuildSha(BuildConfig.GIT_SHA) }
        },
    )
}

@Composable
internal fun LocalBuildBannerCard(
    branch: String,
    sha: String,
    dirty: Boolean,
    buildTimestampMs: Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    // Refresh "2 hours ago" once a minute so the banner stays accurate while
    // the user keeps the app open across the minute boundary.
    var now by remember { mutableLongStateOf(nowProvider()) }
    LaunchedEffect(buildTimestampMs) {
        while (true) {
            delay(60_000L)
            now = nowProvider()
        }
    }

    val relative = DateUtils.getRelativeTimeSpanString(
        buildTimestampMs,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
    val shaSuffix = if (dirty) "$sha (dirty)" else sha
    val shortBranch = branch.substringAfterLast('/')

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Local build",
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                    )
                }
            }
            Row {
                Text(
                    text = shortBranch,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = " · $shaSuffix · $relative",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
    }
}
