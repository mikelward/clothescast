package app.clothescast.diag

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.clothescast.BuildConfig
import app.mikelward.androidlog.DebugLog
import app.mikelward.androidlog.android.DebugFileSink
import app.mikelward.androidlog.android.LogcatSink
import app.mikelward.androidlog.android.PreviousRun
import app.mikelward.androidlog.safe
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * This app's diagnostic log, now a thin facade over the shared
 * `mikelward/androidlog` — the recording buffer, the privacy floor, the
 * persisted file and its crash record all live there and are shared with
 * `simmo`, `snoozemo` and `typelauncher`.
 *
 * The facade exists so the 278 call sites keep reading in this app's
 * vocabulary. What it adds over calling [DebugLog] directly is exactly two
 * things: the tag, and this app's `StateFlow` view of the crash state.
 *
 * **The tag is folded into the format string, and that is safe for the same
 * reason the format string itself is.** Every call site passes a compile-time
 * constant tag — 188 references to a per-file `TAG`, the rest string literals —
 * so `"$tag: $format"` is a concatenation of two source literals and names
 * nothing of the user's. It is a rule rather than a type-system guarantee,
 * which is what "interpolate nothing into the format" already was; this is one
 * more line held to it, in one reviewable place rather than at every call.
 *
 * The rendered line is unchanged by the move: this used to write
 * `"$timestamp $level $tag: $msg"`, and folding the tag into the message
 * produces the same shape from the library's `"$timestamp $level $message"`.
 * What does change is **logcat tag filtering** — every line now carries one
 * fixed logcat tag instead of a per-call one, so `adb logcat -s MqttPublisher`
 * no longer selects. The tag is still *in* the line, so it survives in the
 * on-device log and in a shared report; only the filter key is gone. See
 * `TODO.md`, "Decisions needing review".
 */
object DiagLog {

    /** This app's instance of the shared recording core. */
    internal val log: DebugLog = object : DebugLog() {}

    /**
     * The persisted mirror, once [install] has run. Held so [BugReport] can
     * hand it to `DebugReport.collect`, which reads the previous run and — on
     * a delivered report — consumes exactly the runs that report was built
     * from.
     */
    @Volatile
    internal var files: DebugFileSink? = null
        private set

    private val unacknowledgedCrashState = MutableStateFlow(false)

    /**
     * Observable mirror of "did a previous run end in an uncaught exception the
     * user hasn't acted on?" — backs the crash banner, and is a single source
     * of truth so an acknowledgement on one instance hides the others.
     *
     * The library derives this on its own worker and publishes it through a
     * listener rather than a `Flow`: it takes no third-party runtime
     * dependency, and coroutines would reach four APKs to deliver one boolean.
     * Wrapping the listener is this app's three lines, and they are here.
     */
    val unacknowledgedCrash: StateFlow<Boolean> = unacknowledgedCrashState.asStateFlow()

    /**
     * Records one line at the named level.
     *
     * [format] is a hard-coded format string — a source literal, never a
     * value — with one `%s` per argument. That split is the privacy floor: the
     * literal cannot name anything of the user's, and each argument is carried
     * or withheld on its own by its type. `safe(...)` and `sensitive(...)`
     * override per value.
     *
     * **The floor is applied as the line is recorded, and there is one
     * rendering.** An unmarked `String` is `•••` in the buffer, in logcat, in
     * the persisted file and in any shared report — it is never held in full
     * anywhere. That is a change from this app's previous logger, which
     * rendered everything in full and would have reduced at a boundary that
     * did not exist.
     */
    fun v(tag: String, format: String, vararg args: Any?) = log.verbose(tagged(tag, format), *args)

    fun d(tag: String, format: String, vararg args: Any?) = log.event(tagged(tag, format), *args)

    fun i(tag: String, format: String, vararg args: Any?) = log.info(tagged(tag, format), *args)

    fun w(tag: String, format: String, vararg args: Any?) = log.warning(tagged(tag, format), *args)

    fun e(tag: String, format: String, vararg args: Any?) = log.error(tagged(tag, format), *args)

    /**
     * The same, with the exception behind the line rather than rendered into
     * it. The throwable comes before [format] so it cannot bind as a trailing
     * `vararg` and lose its stack.
     *
     * Only `w` and `e` take one, because those are the levels the shared
     * library gives a throwable form — and "something threw" is what a warning
     * *is*. A null lands on the path without one, which several call sites need
     * because they log whatever a `Result` failure or a platform callback
     * handed them.
     *
     * The stack is rendered as **types and frames, never a message**. The
     * library reads no throwable's message anywhere, because a platform
     * exception quotes what it was given and that is exactly what the floor
     * bans.
     */
    fun w(tag: String, t: Throwable?, format: String, vararg args: Any?) =
        if (t == null) log.warning(tagged(tag, format), *args)
        else log.failure(t, tagged(tag, format), *args)

    fun e(tag: String, t: Throwable?, format: String, vararg args: Any?) =
        if (t == null) log.error(tagged(tag, format), *args)
        else log.error(t, tagged(tag, format), *args)

    /** Two source literals, joined — see the class comment for why that is safe. */
    private fun tagged(tag: String, format: String): String = "$tag: $format"

    /** The recent log, oldest first, as the bug report and the crash record show it. */
    fun snapshot(): List<String> = log.snapshot()

    /**
     * Wires logcat and the persisted mirror. Call once from
     * [android.app.Application.onCreate].
     *
     * `start()` runs before `addSink` so the rotation is queued ahead of this
     * run's first write, and everything it touches happens on the library's own
     * daemon worker — neither call blocks cold start.
     *
     * Emits a process-start marker carrying this build's version, so a reader
     * can tell where in the log the current process began: `cacheDir` survives
     * app upgrades, so without it a post-upgrade report interleaves two
     * versions under one header.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        deleteLegacyFiles(appContext)
        log.addSink(LogcatSink("ClothesCastDebug"))
        val sink = DebugFileSink(log, appContext)
        sink.start()
        log.addSink(sink)
        sink.addCrashListener { unacknowledged ->
            unacknowledgedCrashState.value = unacknowledged
        }
        files = sink
        // The build's own version and code are fixed vocabulary — every install
        // of this build reports the same pair — so the name is marked safe
        // rather than withheld as the String type would otherwise have it.
        i(
            "DiagLog",
            "---- process start %s (%s) ----",
            safe(BuildConfig.VERSION_NAME),
            BuildConfig.VERSION_CODE,
        )
    }

    /**
     * Removes the files this app's own logger used to write.
     *
     * They are dead the moment the library takes over — it writes its own
     * `androidlog.log` / `androidlog-prev-*` and never reads these — and
     * `cacheDir` survives app upgrades, so left alone they would sit there
     * indefinitely holding log lines written under the *old* rendering, where
     * every argument was kept in full. Deleting them is the migration: the
     * reduced rendering is not retroactive, so the only way those lines stop
     * being readable is to remove them.
     *
     * Best-effort and silent on failure by design — this runs on the cold-start
     * path before the log exists to record anything, and a file that cannot be
     * deleted is not a reason to fail startup. A leftover is inert: nothing
     * reads it.
     */
    private fun deleteLegacyFiles(context: Context) {
        listOf("diag.log", "diag.log.1", "last-crash.txt", "last-crash.ack").forEach { name ->
            runCatching { File(context.cacheDir, name).delete() }
        }
    }

    /**
     * The previous run's log, as a handle rather than a string.
     *
     * The handle is what [consumePreviousRun] takes back, so a report deletes
     * exactly the runs it contained and nothing else — two overlapping report
     * flows cannot have the first destroy a run only the second had read. A
     * caller that got null read nothing and so deletes nothing.
     *
     * Reads disk on the library's worker; call it off the main thread.
     */
    internal fun readPreviousRun(): PreviousRun? = files?.readPreviousRun()

    /**
     * Consumes the runs [run] was read from, once the report carrying them has
     * actually reached the user.
     *
     * "Reached the user" means the clipboard copy landed, not that a chooser
     * opened: `ACTION_SEND` reports nothing back, so a launched sheet the user
     * backed out of would otherwise spend a crash log on a share that never
     * happened.
     */
    internal fun consumePreviousRun(run: PreviousRun) {
        files?.clearPreviousRun(run)
    }

    /**
     * Asks the library to re-derive the crash state from disk.
     *
     * Called from the crash banner's lifecycle observer on `ON_RESUME`, so a
     * crash captured while the app was backgrounded surfaces without a process
     * restart. The derivation runs on the library's worker and publishes
     * through the listener [install] registered, so two screens cannot write
     * stale answers over each other.
     */
    fun refreshUnacknowledgedCrash() {
        files?.requestCrashRecompute()
    }

    /**
     * Publishes a crash state directly, for tests of the screens that react to
     * it.
     *
     * The *derivation* — which files mean an unacknowledged crash, and when —
     * belongs to the library and is tested there. What this app owns is the
     * mirror and the UI reading it, and driving those from disk would mean
     * seeding the library's own files and then waiting for its worker to
     * publish: a race dressed up as a test.
     */
    @VisibleForTesting
    internal fun publishCrashStateForTest(unacknowledged: Boolean) {
        unacknowledgedCrashState.value = unacknowledged
    }

    /** Records the current crash as seen — see [unacknowledgedCrash]. */
    fun acknowledgePersistedCrash() {
        files?.acknowledgeCrashBanner()
    }
}
