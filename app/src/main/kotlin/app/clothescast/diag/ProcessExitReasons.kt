package app.clothescast.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.getSystemService

private const val TAG = "ProcessExit"

/**
 * How many prior process exits to read. Enough to cover "what happened around
 * the time the user noticed" without turning startup into a log dump.
 */
private const val MAX_EXIT_RECORDS = 5

/**
 * Records why this app's recent processes ended.
 *
 * [DiagLog] already knows when a run ended in an *uncaught exception* — the
 * crash handler writes `last-crash.txt` on the dying thread. What it cannot see
 * is every other way a process dies: an ANR, a native crash, an out-of-memory
 * reclaim, or the installer stopping the app to swap the APK. Those leave no
 * in-process trace at all, so from the next run's point of view they are
 * indistinguishable from each other and from a clean exit — the log simply
 * restarts with no explanation.
 *
 * That matters here beyond ordinary triage. This app's visible work happens in
 * background workers and a widget, and a morning insight that never arrived
 * looks identical whether the fetch failed or the process was killed before it
 * ran. The platform keeps the answer, so ask it rather than guessing.
 *
 * Ported from the sibling Type Launcher repo deliberately unchanged in shape —
 * same names, same line format — so the two logs read alike. See `TODO.md` on
 * aligning the repos' loggers properly.
 */
fun logRecentProcessExits(context: Context) {
    val activityManager = context.getSystemService<ActivityManager>() ?: run {
        DiagLog.d(TAG, "processExits unavailable reason=noActivityManager")
        return
    }
    val exits = try {
        // pid 0 means "any process of this package" — asking by pid would miss
        // exactly the abrupt deaths this is here for.
        activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
    } catch (e: RuntimeException) {
        // A denial or a dead system_server leaves us no worse off than before
        // this existed, so report and return rather than letting the failure
        // escape into startup.
        DiagLog.w(TAG, "processExits query failed", e)
        return
    }
    if (exits.isEmpty()) {
        DiagLog.d(TAG, "processExits none")
    } else {
        logExitRecords(exits)
    }
    // Last, deliberately. The exit records are what this exists to capture and
    // are already in hand by now, so anything that can fail runs only after
    // they are safely in the log, never ahead of them.
    logOwnPackageTimestamps(context)
}

/** See [logRecentProcessExits]; split out so a later failure cannot preempt it. */
private fun logExitRecords(exits: List<ApplicationExitInfo>) {
    // Newest first, which is how the platform returns them and the order a
    // reader wants: the most recent exit is the one that explains this start.
    exits.forEach { info ->
        DiagLog.d(
            TAG,
            "processExit reason=${exitReasonName(info.reason)} " +
                "importance=${processImportanceName(info.importance)} " +
                "status=${info.status} timestamp=${info.timestamp} " +
                "description=${info.description}",
        )
    }
}

/**
 * Records when this package was last updated, next to the exit records above.
 *
 * An exit whose timestamp sits alongside the package's own update time says the
 * process died because the installer replaced the APK, rather than because
 * anything went wrong — a distinction worth making before chasing a bug that
 * isn't there.
 */
private fun logOwnPackageTimestamps(context: Context) {
    try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        DiagLog.d(
            TAG,
            "ownPackage lastUpdateTime=${info.lastUpdateTime} " +
                "firstInstallTime=${info.firstInstallTime}",
        )
    } catch (e: PackageManager.NameNotFoundException) {
        DiagLog.w(TAG, "ownPackage query failed", e)
    } catch (e: RuntimeException) {
        // The lookup is a binder call, so it can also fail as a RuntimeException
        // — a dead system_server mid-restart being the realistic case, which is
        // exactly the sort of moment this diagnostic is read about. Caught for
        // the same reason as above: this is the optional half and must not take
        // the exit records down with it.
        DiagLog.w(TAG, "ownPackage query failed", e)
    }
}

/**
 * Maps an [ApplicationExitInfo] reason to a stable, readable name.
 *
 * Named rather than numeric because a bug report is read by whoever it reaches,
 * not only by someone with the SDK constants to hand. An unrecognized reason
 * keeps its number so a future platform addition degrades to something still
 * diagnosable instead of collapsing into "unknown".
 */
internal fun exitReasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "anr"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashNative"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependencyDied"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessiveResourceUsage"
    ApplicationExitInfo.REASON_EXIT_SELF -> "exitSelf"
    ApplicationExitInfo.REASON_FREEZER -> "freezer"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initializationFailure"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "lowMemory"
    ApplicationExitInfo.REASON_OTHER -> "other"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "packageStateChange"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "packageUpdated"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permissionChange"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "userRequested"
    ApplicationExitInfo.REASON_USER_STOPPED -> "userStopped"
    else -> "unrecognized($reason)"
}

/**
 * Maps an [ApplicationExitInfo.importance] to a stable, readable name.
 *
 * Importance is the priority Android had assigned the process when it died, and
 * it is the half of an exit record that decides what the death meant. A
 * background reclaim is routine; foreground importance means the system counted
 * the process as user-aware work at that moment.
 *
 * It is **not** proof an Activity was on screen: the alarm receiver and the
 * widget update both reach foreground importance with nothing visible, and on
 * this app those are most of what runs (Codex, PR #1160). Read it as "the
 * system was not treating this as idle", not as "the user was looking".
 */
internal fun processImportanceName(importance: Int): String = when (importance) {
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foregroundService"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "topSleeping"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
    else -> "unrecognized($importance)"
}
