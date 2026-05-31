package app.clothescast.diag

import android.content.Context
import android.util.Log
import app.clothescast.BuildConfig
import java.io.File
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
     * entries. A fuller (but still capped) trace goes to `last-crash.txt` for
     * uncaught exceptions — see [CRASH_STACK_FRAMES] — so the snapshot just
     * needs the call site. One frame trims a typical `MqttPublisher: publish
     * failed` block from 13 lines to 4 — the cause chain text + the top frame
     * for each link is enough to read on its own, and the ten-deep Netty /
     * executor noise that otherwise dominates the 300-line buffer goes away.
     */
    private const val COMPACT_STACK_FRAMES = 1

    /**
     * How many `at …` frames per Throwable to keep in `last-crash.txt`. More
     * generous than [COMPACT_STACK_FRAMES] because the crash file is the
     * primary post-mortem record (it's only written once, so it isn't fighting
     * the snapshot budget) — but still capped, because the deep tail of an
     * uncaught exception is platform plumbing (Looper / Choreographer /
     * ActivityThread, Compose recomposition internals) that tells you nothing,
     * and in a release build every frame is obfuscated to single letters so
     * there's no app frame worth digging out from the bottom. Keep the throw
     * site and its immediate callers; drop the rest with a `... N more`
     * summary so it's clear frames were elided rather than missing.
     */
    private const val CRASH_STACK_FRAMES = 12

    /**
     * Snapshot-side defence against an individual log entry dominating the
     * 300-line buffer. Limits the consecutive continuation lines (anything
     * not starting with a timestamp digit — stack frames, `Caused by:`,
     * `Suppressed:`) that any one entry contributes, then summarises the
     * dropped tail as `\t... [N lines elided]`.
     *
     * The current writer already routes throwables through
     * [compactStackTraceString], so freshly-written entries cost at most a
     * handful of continuation lines. The cap exists because `cacheDir`
     * survives app upgrades, so `diag.log.1` can retain fat
     * `Throwable.printStackTrace`-style entries written by an older build
     * (pre-`compactStackTraceString`) until natural rotation flushes them,
     * and a single 60-line entry from that era used to eat 20% of the
     * snapshot budget. Sized to comfortably fit a 3-4-deep
     * compact cause chain with a couple of `Suppressed:` lines per level
     * (~8 continuation lines), with headroom.
     */
    private const val MAX_CONTINUATION_LINES_PER_ENTRY = 10

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
        // Read with headroom so the cap pass below can drop fat entries
        // and the final `takeLast` still has [MAX_SNAPSHOT_LINES] worth of
        // distinct entries to surface.
        val raw = readTail(file, rotated, MAX_SNAPSHOT_LINES * 10)
        return capEntryContinuations(raw, MAX_CONTINUATION_LINES_PER_ENTRY)
            .takeLast(MAX_SNAPSHOT_LINES)
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
        // Filter the deep, useless tail (platform plumbing, obfuscated frames)
        // out of the crash record too — keep the exception, its cause chain,
        // and the top [CRASH_STACK_FRAMES] frames, not the full ~80-frame dump.
        val stack = compactStackTraceString(throwable, maxFrames = CRASH_STACK_FRAMES, omittedSummary = true)
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

    /**
     * Formats [t] like [Throwable.printStackTrace] but keeps only the top
     * [maxFrames] frames per Throwable in the cause chain, dropping the deep
     * tail of platform / framework plumbing that isn't useful for triage.
     *
     * By default the dropped frames aren't summarised, so each Throwable costs
     * a flat `1 + maxFrames` lines — that maximises how much pre-failure
     * history fits in the 300-line snapshot budget, where this runs for every
     * logged throwable. Pass [omittedSummary] `true` (as [writeCrashLog] does
     * for `last-crash.txt`, which isn't budget-constrained) to append a
     * `\t... N more` line per Throwable so a reader can tell frames were
     * elided rather than absent.
     *
     * Cyclic cause chains (`a.cause = b; b.cause = a`) are guarded via an
     * identity set so a pathological Throwable can't spin the calling
     * thread before the executor submit runs — printStackTrace does the
     * same. Suppressed exceptions surface as a one-line `Suppressed: …`
     * summary per parent so try-with-resources / `.use` close failures
     * aren't silently lost; their frames are intentionally dropped to
     * keep the budget tight. Visible for tests.
     */
    internal fun compactStackTraceString(
        t: Throwable,
        maxFrames: Int = COMPACT_STACK_FRAMES,
        omittedSummary: Boolean = false,
    ): String {
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
            if (omittedSummary && frames.size > keep) {
                sb.append('\n').append("\t... ").append(frames.size - keep).append(" more")
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

    /**
     * Groups [lines] into entries (leading line + subsequent non-leading
     * "continuation" lines) and truncates each entry's continuation tail
     * past [maxContinuation], replacing the dropped lines with a single
     * `\t... [N lines elided]` marker. A leading line is one starting with
     * a digit (the log timestamp); anything else — `\tat …` stack frames,
     * `Caused by: …`, `\tSuppressed: …`, or an orphan continuation at
     * the start of the snapshot whose header was rotated out — is a
     * continuation. The cap resets at every leading line. Visible for
     * tests.
     */
    internal fun capEntryContinuations(lines: List<String>, maxContinuation: Int): List<String> {
        val result = mutableListOf<String>()
        var continuationCount = 0
        var elidedCount = 0
        for (line in lines) {
            val isLeading = line.isNotEmpty() && line[0].isDigit()
            if (isLeading) {
                if (elidedCount > 0) {
                    result += "\t... [$elidedCount lines elided]"
                    elidedCount = 0
                }
                continuationCount = 0
                result += line
            } else if (continuationCount < maxContinuation) {
                result += line
                continuationCount++
            } else {
                elidedCount++
            }
        }
        if (elidedCount > 0) {
            result += "\t... [$elidedCount lines elided]"
        }
        return result
    }
}
