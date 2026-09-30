package app.clothescast.diag

import android.content.Context
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.ProcessExits

/**
 * Records why this app's recent processes ended, through androidlog's shared
 * [ProcessExits]: the package's install and update times, then the last few
 * exits oldest first, as pinned lines.
 *
 * [DiagLog] already knows when a run ended in an *uncaught exception* — the
 * file sink's crash record is written from the handler itself. What it cannot
 * see is every other way a process dies: an ANR, a native crash, an
 * out-of-memory reclaim, or the installer stopping the app to swap the APK.
 * Those leave no in-process trace, so the next run's log simply restarts with
 * no explanation.
 *
 * That matters here beyond ordinary triage. This app's visible work happens in
 * background workers and a widget, and a morning insight that never arrived
 * looks identical whether the fetch failed or the process was killed before it
 * ran. The platform keeps the answer, so ask it rather than guessing.
 *
 * Pinned, because each line is written once at startup and read whenever the
 * user next shares a report: a busy run would otherwise have pushed them out
 * of the ring by then. The exit's time and the package's update time sit
 * together because an exit that lines up with an update is the installer
 * swapping the APK rather than a bug.
 *
 * Importance is the priority Android gave the process when it died, **not**
 * proof an Activity was on screen: the alarm receiver and the widget update
 * both reach foreground importance with nothing visible, and on this app those
 * are most of what runs (Codex, PR #1160).
 *
 * The platform's free-text description is included (`includeDescription`), cut
 * to one bounded line. It is an ordinary argument, so it stays on the device,
 * in the report the user reviews before sharing; it can name the installer
 * that stopped us or a dependency that died.
 */
internal fun logRecentProcessExits(context: Context, log: DebugLog = DiagLog.log) {
    ProcessExits.logRecent(context, log, includeDescription = true)
}
