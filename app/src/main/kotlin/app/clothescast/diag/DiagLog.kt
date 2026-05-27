package app.clothescast.diag

import android.content.Context
import android.util.Log
import app.clothescast.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide diagnostic log that mirrors every call to [android.util.Log]
 * and write-throughs each entry to a rotating on-disk file in `cacheDir`, so
 * a bug-report payload can include the last few hundred lines of context —
 * including errors logged by background workers that returned cleanly
 * before the OS later killed the process.
 *
 * Writes are serialised on a single-threaded executor. Calls that carry a
 * [Throwable] block briefly until the line reaches the kernel page cache,
 * giving "error survives process death" semantics without paying for an
 * fsync. Non-error calls queue and return immediately.
 *
 * The legacy uncaught-crash channel ([readPersistedCrash] /
 * [unacknowledgedCrash]) is preserved unchanged; uncaught exceptions
 * still spill the recent log plus the stack to `last-crash.txt` on the
 * dying thread for the post-crash banner.
 */
object DiagLog {
    private const val MAX_SNAPSHOT_LINES = 300
    private const val MAX_BYTES = 200L * 1024L
    private const val SYNC_TIMEOUT_MS = 2_000L

    /**
     * How many `at …` frames per Throwable in the chain to keep in diag-log
     * entries. The full trace still goes to `last-crash.txt` for uncaught
     * exceptions, so the snapshot just needs the call site. One frame
     * trims a typical `MqttPublisher: publish failed` block from 13 lines
     * to 4 — the cause chain text + the top frame for each link is enough
     * to read on its own, and the ten-deep Netty / executor noise that
     * otherwise dominates the 300-line buffer goes away.
     */
    private const val COMPACT_STACK_FRAMES = 1

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DiagLog-writer").apply { isDaemon = true }
    }
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    }

    @Volatile
    private var diagFileProvider: (() -> File)? = null

    @Volatile
    private var diagRotatedProvider: (() -> File)? = null

    @Volatile
    private var crashFileProvider: (() -> File)? = null

    @Volatile
    private var ackFileProvider: (() -> File)? = null

    private val unacknowledgedCrashState = MutableStateFlow(false)

    /**
     * Observable mirror of "is there an uncaught-crash file on disk from a
     * previous run that the user hasn't yet acted on?" — backs the Today
     * screen's crash banner. Single source of truth shared across every
     * `LastCrashBanner` instance, so an acknowledgement on one (e.g. on the
     * pager's page 0) flips the flow and the page 1 instance hides itself
     * immediately rather than carrying stale local state.
     *
     * Seeded by [install] from disk at process start; updated by
     * [writeCrashLog] when a fresh crash is captured, by
     * [acknowledgePersistedCrash] when the user dismisses or shares, and
     * by [refreshUnacknowledgedCrash] on lifecycle ON_RESUME (covers a
     * crash written by a sibling process while the app was backgrounded).
     */
    val unacknowledgedCrash: StateFlow<Boolean> = unacknowledgedCrashState.asStateFlow()

    fun v(tag: String, msg: String, t: Throwable? = null) = log('V', tag, msg, t).also {
        if (t == null) Log.v(tag, msg) else Log.v(tag, msg, t)
    }

    fun d(tag: String, msg: String, t: Throwable? = null) = log('D', tag, msg, t).also {
        if (t == null) Log.d(tag, msg) else Log.d(tag, msg, t)
    }

    fun i(tag: String, msg: String, t: Throwable? = null) = log('I', tag, msg, t).also {
        if (t == null) Log.i(tag, msg) else Log.i(tag, msg, t)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) = log('W', tag, msg, t).also {
        if (t == null) Log.w(tag, msg) else Log.w(tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) = log('E', tag, msg, t).also {
        if (t == null) Log.e(tag, msg) else Log.e(tag, msg, t)
    }

    /**
     * Returns the last [MAX_SNAPSHOT_LINES] lines across the rotated and
     * current diag files, oldest first. Drains any queued writes first so
     * the snapshot reflects every log call made before this point.
     */
    fun snapshot(): List<String> {
        val file = diagFileProvider?.invoke() ?: return emptyList()
        val rotated = diagRotatedProvider?.invoke() ?: return emptyList()
        runCatching {
            executor.submit { }.get(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        return readTail(file, rotated, MAX_SNAPSHOT_LINES)
    }

    /**
     * Wires the crash handler and the diag-file paths. Call once from
     * [android.app.Application.onCreate]. Before this returns, log() calls
     * are no-ops on disk; in practice install() is the first non-super line
     * of onCreate so the window is empty.
     *
     * Emits a "process start" marker carrying the current build's version
     * so bug-report readers can tell where in `diag.log` the current
     * process began. `cacheDir` survives app upgrades, so without the
     * marker a post-upgrade report would interleave lines from two
     * versions under one header. The marker doesn't claim what version
     * wrote the lines *before* it — just signals "treat earlier lines as
     * an earlier (unknown) version."
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        diagFileProvider = { File(appContext.cacheDir, "diag.log") }
        diagRotatedProvider = { File(appContext.cacheDir, "diag.log.1") }
        crashFileProvider = { File(appContext.cacheDir, "last-crash.txt") }
        ackFileProvider = { File(appContext.cacheDir, "last-crash.ack") }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
        refreshUnacknowledgedCrash()
        i("DiagLog", "---- process start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ----")
    }

    /** Returns the persisted crash log from the previous process, or null if absent. */
    fun readPersistedCrash(): String? = crashFileProvider?.invoke()
        ?.takeIf { it.exists() && it.length() > 0L }
        ?.runCatching { readText() }
        ?.getOrNull()

    /**
     * Re-reads the crash + ack files from disk and republishes
     * [unacknowledgedCrash]. Called once from [install] to seed the flow,
     * and from the crash banner's lifecycle observer on ON_RESUME so a
     * crash captured while the app was backgrounded (e.g. by a sibling
     * process sharing the same `cacheDir`) surfaces without needing a
     * process restart.
     *
     * Identity is the crash file's last-modified time, so a fresh crash
     * bumps mtime and the flow flips back to true even if the previous
     * one was acked.
     */
    fun refreshUnacknowledgedCrash() {
        val crash = crashFileProvider?.invoke() ?: return
        val ack = ackFileProvider?.invoke() ?: return
        unacknowledgedCrashState.value = isCrashUnacknowledged(crash, ack)
    }

    /** Records the currently persisted crash as seen — see [unacknowledgedCrash]. */
    fun acknowledgePersistedCrash() {
        val crash = crashFileProvider?.invoke() ?: return
        val ack = ackFileProvider?.invoke() ?: return
        writeCrashAcknowledgement(crash, ack)
        unacknowledgedCrashState.value = false
    }

    internal fun isCrashUnacknowledged(crashFile: File, ackFile: File): Boolean {
        if (!crashFile.exists() || crashFile.length() == 0L) return false
        if (!ackFile.exists()) return true
        val ackedMtime = runCatching { ackFile.readText().trim().toLong() }.getOrNull()
            ?: return true
        return ackedMtime != crashFile.lastModified()
    }

    internal fun writeCrashAcknowledgement(crashFile: File, ackFile: File) {
        if (!crashFile.exists()) return
        runCatching {
            ackFile.parentFile?.mkdirs()
            ackFile.writeText(crashFile.lastModified().toString())
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val file = crashFileProvider?.invoke() ?: return
        val stack = stackTraceString(throwable)
        val header = "Uncaught exception on thread \"${thread.name}\""
        // log() with a non-null throwable blocks on the executor, so by the
        // time snapshot() runs below the crash header line is on disk and
        // included in the recent context.
        log('E', "DiagLog", "$header: ${throwable.javaClass.name}: ${throwable.message}", throwable)
        val recent = snapshot().joinToString("\n")
        file.parentFile?.mkdirs()
        file.writeText(buildString {
            appendLine("=== ${timestampFormat.get().format(Date())} ===")
            appendLine(header)
            appendLine(stack)
            appendLine("--- recent log ---")
            append(recent)
        })
        unacknowledgedCrashState.value = true
    }

    private fun stackTraceString(t: Throwable): String =
        StringWriter().also { sw -> PrintWriter(sw).use { t.printStackTrace(it) } }
            .toString().trimEnd()

    /**
     * Formats [t] like [Throwable.printStackTrace] but keeps only the top
     * [maxFrames] frames per Throwable in the cause chain. The dropped
     * deeper frames aren't summarised — the omitted-count line `... N more`
     * is intentionally absent so each Throwable costs `1 + maxFrames` lines
     * flat, maximising how much pre-failure history fits in the 300-line
     * snapshot budget. The full trace still goes to `last-crash.txt` via
     * [writeCrashLog] for uncaught exceptions, so post-mortem debugging
     * loses nothing.
     *
     * Cyclic cause chains (`a.cause = b; b.cause = a`) are guarded via an
     * identity set so a pathological Throwable can't spin the calling
     * thread before the executor submit runs — printStackTrace does the
     * same. Suppressed exceptions surface as a one-line `Suppressed: …`
     * summary per parent so try-with-resources / `.use` close failures
     * aren't silently lost; their frames are intentionally dropped to
     * keep the budget tight. Visible for tests.
     */
    internal fun compactStackTraceString(t: Throwable, maxFrames: Int = COMPACT_STACK_FRAMES): String {
        val sb = StringBuilder()
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = t
        var depth = 0
        while (current != null) {
            if (seen.put(current, true) != null) {
                sb.append('\n').append("\t[CIRCULAR REFERENCE: ")
                    .append(current.javaClass.name).append(']')
                break
            }
            if (depth > 0) sb.append('\n').append("Caused by: ")
            sb.append(current.javaClass.name)
            current.message?.let { sb.append(": ").append(it) }
            val frames = current.stackTrace
            val keep = minOf(maxFrames, frames.size)
            for (i in 0 until keep) {
                sb.append('\n').append("\tat ").append(frames[i])
            }
            for (suppressed in current.suppressed) {
                sb.append('\n').append("\tSuppressed: ").append(suppressed.javaClass.name)
                suppressed.message?.let { sb.append(": ").append(it) }
            }
            current = current.cause
            depth++
        }
        return sb.toString()
    }

    private fun log(level: Char, tag: String, msg: String, t: Throwable?) {
        val timestamp = timestampFormat.get().format(Date())
        val formatted = if (t == null) {
            "$timestamp $level $tag: $msg"
        } else {
            "$timestamp $level $tag: $msg\n${compactStackTraceString(t)}"
        }
        val file = diagFileProvider?.invoke() ?: return
        val rotated = diagRotatedProvider?.invoke() ?: return
        val task = runCatching {
            executor.submit {
                runCatching { appendAndRotate(file, rotated, formatted, MAX_BYTES) }
            }
        }.getOrNull() ?: return
        if (t != null) {
            runCatching { task.get(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
    }

    /**
     * Append [line] (plus newline) to [file]; if the resulting file exceeds
     * [maxBytes], rename it over [rotated] so the next append starts a
     * fresh file. A single rotation slot keeps disk usage bounded.
     * Visible for tests.
     */
    internal fun appendAndRotate(file: File, rotated: File, line: String, maxBytes: Long) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
        if (file.length() > maxBytes) {
            rotated.delete()
            file.renameTo(rotated)
        }
    }

    /**
     * Returns the last [maxLines] lines across [rotated] (older) and [file]
     * (newer), concatenated in chronological order. Missing files are
     * treated as empty. Visible for tests.
     */
    internal fun readTail(file: File, rotated: File, maxLines: Int): List<String> {
        val prev = if (rotated.exists()) {
            runCatching { rotated.readLines() }.getOrDefault(emptyList())
        } else emptyList()
        val curr = if (file.exists()) {
            runCatching { file.readLines() }.getOrDefault(emptyList())
        } else emptyList()
        return (prev + curr).takeLast(maxLines)
    }
}
