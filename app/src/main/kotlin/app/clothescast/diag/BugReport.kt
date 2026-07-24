package app.clothescast.diag

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import app.clothescast.BuildConfig
import app.clothescast.ClothesCastApplication
import app.clothescast.R
import app.clothescast.core.domain.model.AccessoriesFormat
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.PreambleVisibility
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.symbol
import app.clothescast.core.domain.util.coRunCatching
import app.clothescast.data.SettingsRepository
import app.clothescast.insight.InsightFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Builds a "paste-into-Claude" bug-report payload (version, device, settings,
 * the latest cached this-period + next-period ClothesCasts, recent log lines, last crash
 * if any) and hands it off via [Intent.ACTION_SEND] so the share sheet can
 * deliver it to whichever app the user picks. Also drops the text on the
 * clipboard as a paste fallback.
 */
object BugReport {
    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z").withZone(ZoneId.systemDefault())

    /**
     * How many trailing log lines the report carries. The text rides along as
     * the share intent's `EXTRA_TEXT`, which many targets (messengers
     * especially) treat as a caption with a hard character cap that silently
     * truncates a long report mid-line — doubly so once a screenshot flips the
     * intent's MIME type to `image/png` and the chooser surfaces image-share
     * targets. Capping the log tail keeps the caption small enough to survive
     * those targets intact. The header runs ~3-4 KB, so 100 lines leaves
     * comfortable headroom under a ~20 KB budget. [DiagLog] still retains its
     * full 300-line buffer; this only trims what the share carries.
     */
    private const val MAX_LOG_LINES = 100

    /**
     * The ceiling a whole shared report stays under.
     *
     * The line cap above keeps the caption friendly for messenger targets; this
     * is the hard failure it doesn't cover. Strings parcel as UTF-16, so N
     * characters cost 2N bytes on the wire, and the payload crosses Binder twice
     * — into the clipboard, then again in the chooser's `ACTION_SEND` extra. The
     * per-process Binder buffer is ~1 MB **shared** across every in-flight
     * transaction, so an unbounded report (a long clothes-rule list, a fat crash
     * trace) could throw `TransactionTooLargeException` at both ends — and since
     * both are best-effort, the tap would then do nothing whatsoever: no chooser,
     * nothing on the clipboard. The three section budgets below add up to 60,000;
     * the slack covers headers and the one over-budget line each bounded section
     * may keep.
     */
    internal const val MAX_SHARE_PAYLOAD_CHARS = 64_000

    /**
     * Ceiling for the structured section (build, device, settings, ClothesCasts),
     * bounded separately from the log so a long clothes-rule list can't crowd the
     * log out. It degrades most gracefully — a settings dump reads fine
     * truncated, a truncated log tail loses events.
     */
    private const val MAX_STRUCTURED_CHARS = 24_000

    /** Ceiling for the previous run's crash section. */
    private const val MAX_CRASH_PAYLOAD_CHARS = 16_000

    /**
     * Of that, the slice reserved for the crash itself (timestamp, exception,
     * stack) before the recent-log half of the file gets the rest. A capped
     * 12-frame trace with a cause chain fits comfortably; anything larger is a
     * pathological trace worth clipping.
     */
    private const val MAX_CRASH_STACK_CHARS = 6_000

    /** Cap for an exception message quoted into the collection-failure fallback. */
    private const val MAX_FAILURE_MESSAGE_CHARS = 300

    /** Ceiling for the recent-log section, on top of the [MAX_LOG_LINES] cap. */
    private const val MAX_LOG_PAYLOAD_CHARS = 20_000

    /**
     * Captures the screen, builds the text payload, copies the text to the
     * clipboard, and fires the share-sheet chooser. When [includeScreenshot] is
     * true (the default) the current window is grabbed and attached as a PNG so
     * the report shows what the user was looking at; when it's false (or the
     * capture fails) the report shares text-only. The text payload is capped to
     * [MAX_LOG_LINES] log lines so the `EXTRA_TEXT` caption stays under the
     * length limits image-share targets impose.
     *
     * Returns whether the report was *retained* somewhere the user can still get
     * it — i.e. the clipboard copy landed. `ACTION_SEND` gives no delivery or
     * selection callback, so a launched chooser is no proof the report was sent
     * (the user can back out of the sheet); the clipboard copy is the durable
     * fallback that survives that. The post-crash banner gates its
     * acknowledgement on this, so a share that reached nobody leaves the banner
     * up for a retry instead of quietly dismissing it.
     *
     * [mainDispatcher], [payloadCollect], [screenshotCapture], [clipboardWrite],
     * and [chooserLaunch] are injectable test seams (production uses the
     * defaults): each delivery route can fail on its own, and both the return
     * value and the failure notice are behavior a test must be able to drive
     * every way, without a real window, `ClipboardManager`, or share target.
     */
    suspend fun share(
        activity: Activity,
        includeScreenshot: Boolean = true,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
        payloadCollect: suspend (Context, ClothesCastApplication) -> String = ::buildPayload,
        screenshotCapture: suspend (Activity) -> Uri? = ::captureAndPersistScreenshot,
        clipboardWrite: (Context, String) -> Boolean = ::copyToClipboard,
        chooserLaunch: (Activity, String, Uri?) -> Boolean = ::startShare,
    ): Boolean {
        val app = activity.application as? ClothesCastApplication
        val text = try {
            // Off the main thread: the payload reads preferences, the insight
            // cache, and the crash file from disk, and share() is launched from a
            // UI tap — blocking there janks the frame the share sheet animates in
            // over.
            withContext(Dispatchers.IO) {
                if (app == null) error("no ClothesCastApplication") else payloadCollect(activity, app)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // A report is most useful after something has already gone wrong.
            // Never turn a failure while inspecting that state into another
            // crash: this runs from a tap, where an escaping throwable takes the
            // app down. Retain a small shareable diagnostic instead.
            DiagLog.w("BugReport", "payload collection failed", t)
            buildFallbackPayload(t)
        }
        // The capture draws the live window via PixelCopy and the clipboard /
        // chooser hand-off touches the Activity, so all three are pinned to the
        // main thread — the payload build above hopped to IO and its continuation
        // must not leave this on a worker thread.
        return withContext(mainDispatcher) {
            val screenshotUri: Uri? = if (includeScreenshot) screenshotCapture(activity) else null
            // Guarded: both are injectable seams, and share() runs in a caller's
            // coroutine scope where an escaping throwable would take the app down
            // with it — the one thing a bug-report path must never do.
            // Each logs what it caught: these guards also cover the work *around*
            // the inner logged ones (building the chooser intent, resolving the
            // clipboard service), so without this the user could see only the
            // generic toast while the log said nothing about why.
            val copied = runCatching { clipboardWrite(activity, text) }
                .onFailure { DiagLog.w("BugReport", "clipboard hand-off threw", it) }
                .getOrDefault(false)
            val launched = runCatching { chooserLaunch(activity, text, screenshotUri) }
                .onFailure { DiagLog.w("BugReport", "chooser hand-off threw", it) }
                .getOrDefault(false)
            // Neither route landed: the tap would otherwise do nothing visible at
            // all — no chooser, nothing on the clipboard — and the user would
            // retry into the same silence. Say so instead.
            if (!copied && !launched) notifyShareFailed(activity)
            copied
        }
    }

    /**
     * The report to fall back on when collecting the real one threw. Deliberately
     * tiny and dependency-free — it reads nothing off disk, because a disk read
     * is what most plausibly just failed — but it still carries the in-memory log
     * tail, which is the diagnostic the report exists for. Without it the
     * fallback would be four lines that say nothing about what went wrong, on the
     * one path that only runs when something already has.
     */
    private fun buildFallbackPayload(failure: Throwable): String = buildString {
        appendLine("ClothesCast bug report")
        appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        // The message is clipped: an exception can carry a whole response body
        // or a serialized value, and an unbounded fallback would blow the very
        // ceiling this path exists to stay under — on the one path that runs
        // when the normal report couldn't even be built.
        val why = failure.message?.take(MAX_FAILURE_MESSAGE_CHARS)
        appendLine("Report collection failed: ${failure.javaClass.name}" + (why?.let { ": $it" } ?: ""))
        // If reading the log fails too — plausible, since the same storage
        // problem could be behind both — say so in the report. Falling through
        // to the empty-log sentinel would tell the reader "nothing was logged"
        // when the truth is "the log couldn't be read," and lose the second
        // failure entirely.
        val recent = runCatching { DiagLog.snapshot() }
            .onFailure {
                DiagLog.w("BugReport", "log snapshot failed while building the fallback report", it)
                appendLine("Recent log unavailable: ${it.javaClass.name}")
            }
            .getOrDefault(emptyList())
        append(renderRecentLog(recent))
    }

    /** Tells the user a share reached neither the chooser nor the clipboard. */
    private fun notifyShareFailed(context: Context) {
        // Logged, not swallowed: this is the last user-visible fallback after
        // both delivery routes already failed, so if it throws too the tap does
        // nothing at all — and the log is then the only place that can say why.
        runCatching {
            Toast.makeText(context, R.string.bug_report_share_failed, Toast.LENGTH_LONG).show()
        }.onFailure { DiagLog.w("BugReport", "share-failed notice could not be shown", it) }
    }

    private suspend fun buildPayload(context: Context, app: ClothesCastApplication): String {
        // coRunCatching, not runCatching: these reads suspend, and the stdlib
        // form would swallow a cancellation of the sharing coroutine and keep
        // building (and then sharing) a gutted report from a cancelled scope.
        val prefs = coRunCatching { app.settingsRepository.preferences.first() }.getOrNull()
        val geminiKeyConfigured = coRunCatching {
            app.secureKeyStore.geminiKeyConfiguredFlow.first()
        }.getOrDefault(false)
        val mqttPasswordConfigured = coRunCatching {
            app.secureKeyStore.mqttPasswordConfiguredFlow.first()
        }.getOrDefault(false)
        val mqttPublishStatus = coRunCatching {
            app.settingsRepository.mqttPublishStatus.first()
        }.getOrNull()
        val castStatus = coRunCatching {
            app.settingsRepository.castStatus.first()
        }.getOrNull()
        val thisSnapshot = coRunCatching {
            app.insightCache.thisPeriod.first()
        }.getOrNull()
        val nextSnapshot = coRunCatching {
            app.insightCache.nextPeriod.first()
        }.getOrNull()
        val thisPeriod = if (thisSnapshot != null && prefs != null) {
            coRunCatching { app.deriveInsight(thisSnapshot, prefs).insight }.getOrNull()
        } else null
        val nextPeriod = if (nextSnapshot != null && prefs != null) {
            coRunCatching { app.deriveInsight(nextSnapshot, prefs).insight }.getOrNull()
        } else null
        val crash = DiagLog.readPersistedCrash()
        val recent = DiagLog.snapshot()
        val now = TIMESTAMP_FORMAT.format(Instant.now())

        val head = buildString {
            appendLine("ClothesCast bug report")
            appendLine("Captured: $now")
            appendLine()
            appendLine("--- Build ---")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
            appendLine("Application id: ${BuildConfig.APPLICATION_ID}")
            appendLine()
            appendLine("--- Device ---")
            appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Locale: ${Locale.getDefault().stripExtensions().toLanguageTag()}")
            appendLine()
            appendLine("--- Settings ---")
            if (prefs == null) {
                appendLine("(failed to read preferences)")
            } else {
                appendPreferences(prefs, mqttPasswordConfigured, mqttPublishStatus, castStatus)
            }
            appendLine("API keys: Gemini=${if (geminiKeyConfigured) "set" else "unset"}")
            appendLine()
            appendLine("--- Current ClothesCasts ---")
            val region = prefs?.region ?: Region.SYSTEM
            val tempUnit = prefs?.temperatureUnit ?: TemperatureUnit.CELSIUS
            val rangeFormat = prefs?.rangeFormat ?: RangeFormat.DEGREES
            val clothesFormat = prefs?.clothesFormat ?: ClothesFormat.ITEMS
            val bottomsFormat = prefs?.bottomsFormat ?: BottomsFormat.IF_GARMENTS
            val accessoriesFormat = prefs?.accessoriesFormat ?: AccessoriesFormat.ALWAYS
            val periodPreamble = prefs?.periodPreamble ?: PreambleVisibility.ALWAYS
            val wearPreamble = prefs?.wearPreamble ?: PreambleVisibility.ALWAYS
            appendInsight("This period", thisPeriod, thisSnapshot, prefs, context, region, tempUnit, rangeFormat, clothesFormat, bottomsFormat, accessoriesFormat, periodPreamble, wearPreamble)
            appendInsight("Next period", nextPeriod, nextSnapshot, prefs, context, region, tempUnit, rangeFormat, clothesFormat, bottomsFormat, accessoriesFormat, periodPreamble, wearPreamble)
        }
        return assemble(head, crash, recent)
    }

    /**
     * Joins the three sections with each one bounded on its own, so no single
     * section can crowd the others out or push the whole report past what the
     * share can carry.
     *
     * Prefix-truncating the assembled report would drop the log — appended last —
     * exactly when a long settings dump or a fat crash trace is what pushed it
     * over, losing the diagnostic the report exists for. Visible for tests.
     */
    internal fun assemble(head: String, crash: String?, recent: List<String>): String {
        val boundedHead = if (head.length > MAX_STRUCTURED_CHARS) {
            head.take(MAX_STRUCTURED_CHARS) + "\n…(details truncated to keep the report shareable)\n"
        } else {
            head
        }
        val crashSection = if (crash.isNullOrBlank()) {
            ""
        } else {
            buildString {
                appendLine("--- Last crash (from previous run) ---")
                appendLine(boundCrash(crash.trim()))
                appendLine()
            }
        }
        return boundedHead + crashSection + renderRecentLog(recent)
    }

    /**
     * Bounds `last-crash.txt` while keeping **both** halves that matter.
     *
     * The file is written head-first — timestamp, exception, stack — and then
     * appends the recent log around the crash ([DiagLog.writeCrashLog]). So
     * neither end can be dropped wholesale: a head-only cut loses the events
     * leading up to the crash, and a tail-only cut loses the crash itself, which
     * is the thing a post-crash report exists to carry. The stack half gets a
     * reserved slice off the front; whatever it doesn't use goes to the newest
     * log lines. Visible for tests.
     */
    internal fun boundCrash(crash: String): String {
        val marker = "--- recent log ---"
        val split = crash.indexOf(marker)
        if (split < 0) {
            // Not the shape we wrote (an older build, or a partial file): keep
            // the newest lines, which is the safer default for a log-like blob.
            return boundedLogTail(crash.split("\n"), MAX_CRASH_PAYLOAD_CHARS).joinToString("\n")
        }
        val stack = crash.take(split + marker.length)
        val log = crash.substring(split + marker.length).removePrefix("\n")
        val boundedStack = if (stack.length > MAX_CRASH_STACK_CHARS) {
            stack.take(MAX_CRASH_STACK_CHARS) + "\n…(stack truncated)\n$marker"
        } else {
            stack
        }
        val remaining = MAX_CRASH_PAYLOAD_CHARS - boundedStack.length
        if (remaining <= 0) return boundedStack
        return boundedStack + "\n" + boundedLogTail(log.split("\n"), remaining).joinToString("\n")
    }

    /**
     * The "Recent log" section — the newest [MAX_LOG_LINES] lines, further
     * bounded by [MAX_LOG_PAYLOAD_CHARS] so a handful of fat entries can't blow
     * the budget on line count alone. Shared with the collection-failure
     * fallback, which needs it most.
     */
    private fun renderRecentLog(recent: List<String>): String = buildString {
        val byLine = recent.takeLast(MAX_LOG_LINES)
        val kept = boundedLogTail(byLine, MAX_LOG_PAYLOAD_CHARS)
        val dropped = recent.size - kept.size
        appendLine("--- Recent log (newest last, ${kept.size} of ${recent.size} shown, max $MAX_LOG_LINES) ---")
        if (recent.isEmpty()) {
            appendLine("(no captured log lines)")
        } else {
            if (dropped > 0) appendLine("($dropped older line(s) omitted to keep the report shareable)")
            kept.forEach { appendLine(it) }
        }
    }

    private fun StringBuilder.appendPreferences(
        prefs: UserPreferences,
        mqttPasswordConfigured: Boolean,
        mqttPublishStatus: SettingsRepository.MqttPublishStatus?,
        castStatus: SettingsRepository.CastStatus?,
    ) {
        appendLine("Region: ${prefs.region.name} (${prefs.region.bcp47 ?: "system"})")
        appendLine("Temperature unit: ${prefs.temperatureUnit.name}")
        appendLine("Wind speed unit: ${prefs.windSpeedUnit.name}")
        appendLine("Daily enabled: ${prefs.dailyEnabled}")
        appendLine("Schedule: ${prefs.schedule.time} ${prefs.schedule.days.sorted()} (${prefs.schedule.zoneId})")
        appendLine("Tonight enabled: ${prefs.tonightEnabled}")
        appendLine("Tonight schedule: ${prefs.tonightSchedule.time} ${prefs.tonightSchedule.days.sorted()}")
        appendLine("Tonight notify only on events: ${prefs.tonightNotifyOnlyOnEvents}")
        appendLine("Daily mention evening events: ${prefs.dailyMentionEveningEvents}")
        appendLine("Clothes mention mode: ${prefs.clothesMentionMode}")
        appendLine("Range format: ${prefs.rangeFormat}")
        appendLine("Bottoms format: ${prefs.bottomsFormat}")
        appendLine("Accessories format: ${prefs.accessoriesFormat}")
        appendLine("Delta threshold: ${prefs.deltaThresholdC?.let { "${it}C" } ?: "off"}")
        appendLine("Delta format: ${prefs.deltaFormat}")
        appendLine("Delivery (morning): ${prefs.deliveryMode}")
        appendLine("Delivery (tonight): ${prefs.tonightDeliveryMode}")
        appendLine("TTS engine: ${prefs.ttsEngine}")
        appendLine("Voice locale: ${prefs.voiceLocale}")
        appendLine("Gemini voice: ${prefs.geminiVoice}")
        appendLine("TTS style: ${prefs.ttsStyle}")
        appendLine("Device voice: ${prefs.deviceVoice ?: "(auto)"}")
        appendLine("Use device location: ${prefs.useDeviceLocation}")
        val locDesc = prefs.location?.let { loc ->
            val name = loc.displayName ?: "(unnamed)"
            "%.2f, %.2f — %s".format(Locale.US, loc.latitude, loc.longitude, name)
        } ?: "(unset)"
        appendLine("Location: $locDesc")
        appendLine("Calendar access: ${prefs.calendarEnabled}")
        appendLine("Use calendar events: ${prefs.useCalendarEvents}")
        appendLine("Clothes rules (${prefs.clothesRules.size}):")
        prefs.clothesRules.forEach { appendLine("  - ${describeRule(it)}") }
        appendMqttSettings(prefs, mqttPasswordConfigured, mqttPublishStatus)
        appendCastSettings(prefs, castStatus)
    }

    private fun StringBuilder.appendMqttSettings(
        prefs: UserPreferences,
        mqttPasswordConfigured: Boolean,
        mqttPublishStatus: SettingsRepository.MqttPublishStatus?,
    ) {
        appendLine("MQTT bridge enabled: ${prefs.mqttBridgeEnabled}")
        appendLine("MQTT skip phone speech: ${prefs.mqttSkipPhoneSpeech}")
        val hostLine = prefs.mqttHost?.takeIf { it.isNotBlank() }?.let { "$it:${prefs.mqttPort}" }
            ?: "(unset)"
        appendLine("MQTT broker: $hostLine")
        appendLine("MQTT TLS: ${prefs.mqttUseTls}")
        appendLine("MQTT topic: ${prefs.mqttTopic}")
        appendLine("MQTT username: ${if (!prefs.mqttUsername.isNullOrBlank()) "set" else "unset"}")
        appendLine("MQTT password: ${if (mqttPasswordConfigured) "set" else "unset"}")
        val statusLine = when {
            mqttPublishStatus == null -> "(no publish attempted)"
            mqttPublishStatus.errorMessage == null -> "success at ${formatTimestamp(mqttPublishStatus.recordedAtMs)}"
            else -> "failed at ${formatTimestamp(mqttPublishStatus.recordedAtMs)} — ${mqttPublishStatus.errorMessage}"
        }
        appendLine("MQTT last publish: $statusLine")
        if (mqttPublishStatus != null &&
            mqttPublishStatus.errorMessage != null &&
            mqttPublishStatus.lastSuccessAtMs > 0L
        ) {
            appendLine("MQTT last success: ${formatTimestamp(mqttPublishStatus.lastSuccessAtMs)}")
        }
    }

    private fun StringBuilder.appendCastSettings(
        prefs: UserPreferences,
        castStatus: SettingsRepository.CastStatus?,
    ) {
        appendLine("Cast enabled: ${prefs.castEnabled}")
        appendLine("Cast morning: ${prefs.castMorning}")
        appendLine("Cast tonight: ${prefs.castTonight}")
        appendLine("Cast skip phone speech: ${prefs.castSkipPhoneSpeech}")
        val display = prefs.castRouteName?.takeIf { it.isNotBlank() }
            ?: prefs.castRouteId?.let { "(unnamed, id=$it)" }
            ?: "(unset)"
        appendLine("Cast display: $display")
        val statusLine = when {
            castStatus == null -> "(no cast attempted)"
            castStatus.errorMessage == null -> "fetched at ${formatTimestamp(castStatus.recordedAtMs)}"
            else -> "failed at ${formatTimestamp(castStatus.recordedAtMs)} — ${castStatus.errorMessage}"
        }
        appendLine("Cast last attempt: $statusLine")
        // Both highwater timestamps are reported independently so a
        // triage reader can immediately see "published at X but the
        // display never fetched (fetched stays at 0 or stale)" — the
        // firewall-between-display-and-phone failure mode the in-app
        // status row now surfaces.
        if (castStatus != null && castStatus.lastPublishedAtMs > 0L) {
            appendLine("Cast last published: ${formatTimestamp(castStatus.lastPublishedAtMs)}")
        }
        if (castStatus != null && castStatus.lastFetchedAtMs > 0L) {
            appendLine("Cast last fetched: ${formatTimestamp(castStatus.lastFetchedAtMs)}")
        }
    }

    private fun formatTimestamp(epochMs: Long): String =
        TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMs))

    /**
     * Humanises age relative to now so a glance at the bug report tells you
     * whether the prose was rendered fresh during this run (seconds old) or
     * served from cache (hours / days). Stops at days because anything older
     * is already obviously stale.
     */
    internal fun humanAge(generatedAt: Instant, now: Instant = Instant.now()): String {
        val d = Duration.between(generatedAt, now)
        if (d.isNegative) return "in the future"
        val seconds = d.seconds
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${d.toMinutes()}m ago"
            seconds < 86_400 -> "${d.toHours()}h ago"
            else -> "${d.toDays()}d ago"
        }
    }

    private fun describeRule(rule: ClothesRule): String =
        "${rule.item.itemKey} when ${describeCondition(rule.condition)}"

    private fun describeCondition(c: ClothesRule.Condition): String = when (c) {
        is ClothesRule.TemperatureBelow -> "feelsLikeMin < ${c.value}${c.unit.symbol()}"
        is ClothesRule.TemperatureAbove -> "feelsLikeMax > ${c.value}${c.unit.symbol()}"
        is ClothesRule.PrecipitationProbabilityAbove -> "precipMaxPct > ${c.percent}"
    }

    private fun StringBuilder.appendInsight(
        label: String,
        insight: Insight?,
        snapshot: ForecastSnapshot?,
        prefs: UserPreferences?,
        context: Context,
        region: Region,
        temperatureUnit: TemperatureUnit,
        rangeFormat: RangeFormat,
        clothesFormat: ClothesFormat,
        bottomsFormat: BottomsFormat,
        accessoriesFormat: AccessoriesFormat,
        periodPreamble: PreambleVisibility,
        wearPreamble: PreambleVisibility,
    ) {
        appendLine("$label:")
        if (insight == null) {
            appendLine("  (no cached insight)")
        } else {
            val prose = runCatching {
                InsightFormatter.forRegion(context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat, accessoriesFormat, periodPreamble, wearPreamble)
                    .format(insight.summary)
            }
                .getOrElse { "(prose render failed: ${it.javaClass.simpleName})" }
            appendLine("  Prose: $prose")
            appendLine("  Generated: ${insight.generatedAt} (${humanAge(insight.generatedAt)})")
            appendLine("  For date: ${insight.forDate}")
            insight.confidence?.let {
                appendLine("  Confidence: ${it.level} (tempSpread=${it.tempSpreadC}°C, " +
                    "precipSpread=${it.precipSpreadPp}pp, ${it.modelsConsulted.size} models)")
            }
            insight.outfit?.let { appendLine("  Outfit: ${it.top} + ${it.bottom}") }
            insight.nextOutfit?.let { appendLine("  Next outfit: ${it.top} + ${it.bottom}") }
            appendLine("  Has events: ${insight.hasEvents}")
            if (insight.recommendedItems.isNotEmpty()) {
                appendLine("  Recommended items: ${insight.recommendedItems.joinToString(", ")}")
            }
            if (insight.hourly.isNotEmpty()) {
                appendLine("  Hourly (${insight.hourly.size} entries) feels-like min/max: " +
                    "%.1f / %.1f °C".format(
                        Locale.US,
                        insight.hourly.minOf { it.feelsLikeC },
                        insight.hourly.maxOf { it.feelsLikeC },
                    ))
            }
        }
        snapshot?.let { appendSnapshotDebug(it, prefs) }
        appendLine()
    }

    private fun StringBuilder.appendSnapshotDebug(
        snapshot: ForecastSnapshot,
        prefs: UserPreferences?,
    ) {
        snapshot.location?.let { loc ->
            val name = loc.displayName ?: "(unnamed)"
            appendLine("  Snapshot location: %.2f, %.2f — %s".format(
                Locale.US, loc.latitude, loc.longitude, name,
            ))
        }
        val historic = snapshot.historicYesterday
        if (historic != null) {
            val expected = snapshot.bundle.today.date.minusDays(1)
            val freshness = if (historic.date == expected) "matches" else "stale (expected $expected)"
            appendLine("  Historic yesterday (${historic.date}, $freshness) feels-like min/max: " +
                "%.1f / %.1f °C".format(
                    Locale.US, historic.feelsLikeMinC, historic.feelsLikeMaxC,
                ))
        } else {
            appendLine("  Historic yesterday: (none recorded — delta falls back to upstream)")
        }
        val yesterday = snapshot.bundle.yesterday
        appendLine("  Yesterday (${yesterday.date}) feels-like min/max: " +
            "%.1f / %.1f °C (24h aggregate)".format(
                Locale.US, yesterday.feelsLikeMinC, yesterday.feelsLikeMaxC,
            ))
        if (yesterday.hourly.isEmpty()) {
            appendLine("  Yesterday hourly: 0 entries")
        } else {
            appendLine("  Yesterday hourly: ${yesterday.hourly.size} entries, " +
                "${yesterday.hourly.first().time}..${yesterday.hourly.last().time}, " +
                "feels-like min/max %.1f / %.1f °C".format(
                    Locale.US,
                    yesterday.hourly.minOf { it.feelsLikeC },
                    yesterday.hourly.maxOf { it.feelsLikeC },
                ))
            val morningStart = prefs?.schedule?.time
            val eveningEnd = prefs?.tonightSchedule?.time
            if (morningStart != null && eveningEnd != null && morningStart.isBefore(eveningEnd)) {
                val slice = yesterday.hourly.filter { it.time >= morningStart && it.time < eveningEnd }
                if (slice.isEmpty()) {
                    appendLine("    daytime [$morningStart..$eveningEnd) slice: 0 entries " +
                        "(delta falls back to 24h aggregate)")
                } else {
                    appendLine("    daytime [$morningStart..$eveningEnd) slice: ${slice.size} entries, " +
                        "feels-like min/max %.1f / %.1f °C".format(
                            Locale.US,
                            slice.minOf { it.feelsLikeC },
                            slice.maxOf { it.feelsLikeC },
                        ))
                }
            }
        }
    }

    private suspend fun captureAndPersistScreenshot(activity: Activity): Uri? {
        // The share can outlive the screen that started it (it runs on the
        // application scope). A destroyed window has nothing worth capturing and
        // PixelCopy against its stale token fails anyway — go straight to a
        // text-only report instead of spending a 10-30 MB buffer finding out.
        if (activity.isFinishing || activity.isDestroyed) return null
        // Logged, not silently dropped: a throw before PixelCopy's own guard —
        // allocating the bitmap, reading the decor view — would otherwise turn
        // into a text-only report with nothing in the log to say why the
        // screenshot is missing. coRunCatching, so cancellation still propagates.
        val bitmap = coRunCatching { captureWindow(activity) }
            .onFailure { DiagLog.w("BugReport", "window capture failed", it) }
            .getOrNull() ?: return null
        // Compressing a full-window PNG and pruning previous files would block the
        // main thread long enough to jank the share-sheet open, so persist on
        // Dispatchers.IO.
        return try {
            withContext(Dispatchers.IO) {
                coRunCatching {
                    val dir = File(activity.cacheDir, SCREENSHOT_DIR_NAME).apply { mkdirs() }
                    // Prune old captures but keep the most recent couple: a
                    // FileProvider URI from an earlier share may still be held by
                    // its target (an unsent email draft, a messaging app that
                    // reads attachments lazily), and deleting every file here
                    // retroactively broke that grant — the attachment failed with
                    // FileNotFoundException when the target finally read it.
                    prunePersistedScreenshots(dir, keepNewest = SCREENSHOT_KEEP_PREVIOUS)
                    val file = File(dir, "screenshot-${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    FileProvider.getUriForFile(
                        activity,
                        activity.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX,
                        file,
                    )
                }.onFailure { DiagLog.w("BugReport", "screenshot persist failed", it) }.getOrNull()
            }
        } finally {
            // Only the PNG on disk outlives this call; free the full-window
            // ARGB_8888 buffer (10-30 MB on current phones) now instead of
            // waiting for GC. Safe even on cancellation: withContext waits for
            // its block, so the compress has finished with the bitmap by the time
            // we get here.
            bitmap.recycle()
        }
    }

    /**
     * Deletes all but the [keepNewest] most recent `screenshot-*.png` captures in
     * [dir], newest judged by the millis embedded in the filename (falling back
     * to `lastModified` for a name that doesn't parse). Called before each new
     * capture is written, so the directory holds at most [keepNewest] + 1 files —
     * bounded growth without invalidating the URI a previous share target may
     * still hold. Visible for tests.
     */
    internal fun prunePersistedScreenshots(dir: File, keepNewest: Int) {
        val captures = dir.listFiles { file ->
            file.isFile && file.name.startsWith("screenshot-") && file.name.endsWith(".png")
        } ?: return
        captures
            .sortedByDescending { file ->
                file.name.removePrefix("screenshot-").removeSuffix(".png").toLongOrNull()
                    ?: file.lastModified()
            }
            .drop(keepNewest)
            .forEach { it.delete() }
    }

    private suspend fun captureWindow(activity: Activity): Bitmap? {
        val window = activity.window ?: return null
        val view: View = window.decorView
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = createBitmap(view.width, view.height)
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height,
        )
        return awaitPixelCopyInto(bitmap) { onResult -> requestPixelCopy(window, rect, bitmap, onResult) }
    }

    /**
     * Suspends until [request] reports whether the copy into [bitmap] landed,
     * returning the bitmap on success and null on failure. The bitmap is a
     * full-window ARGB_8888 buffer (10-30 MB on current phones), so every path
     * that does not hand it to the caller recycles it: a failed copy, a
     * synchronous throw from [request], and a caller cancelled before the result
     * arrived. In the cancelled case the recycle happens in the (now ignored)
     * result callback rather than eagerly at cancellation time, because PixelCopy
     * may still be writing into the buffer until then. Visible for tests.
     */
    internal suspend fun awaitPixelCopyInto(
        bitmap: Bitmap,
        request: (onResult: (Boolean) -> Unit) -> Unit,
    ): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            request { ok ->
                if (ok) {
                    cont.resume(bitmap) { _, _, _ -> bitmap.recycle() }
                } else {
                    bitmap.recycle()
                    cont.resume(null)
                }
            }
        } catch (t: Throwable) {
            DiagLog.w("BugReport", "PixelCopy.request threw", t)
            bitmap.recycle()
            cont.resume(null)
        }
    }

    private fun requestPixelCopy(
        window: Window,
        rect: Rect,
        bitmap: Bitmap,
        onResult: (Boolean) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        PixelCopy.request(window, rect, bitmap, { result ->
            onResult(result == PixelCopy.SUCCESS)
        }, handler)
    }

    /** Returns whether the chooser actually launched. */
    private fun startShare(activity: Activity, text: String, screenshotUri: Uri?): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, "ClothesCast bug report — ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, text)
            if (screenshotUri != null) {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, screenshotUri)
                clipData = ClipData.newRawUri("ClothesCast screenshot", screenshotUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        val chooser = Intent.createChooser(send, "Share bug report")
        if (screenshotUri != null) {
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // The share runs on the application scope, so it can outlive the screen
        // that started it. Starting an activity from a torn-down one targets a
        // dead token, so launch from the application context with NEW_TASK
        // instead — the chooser still opens, which is the whole point of not
        // tying the share to the screen.
        val launchContext: Context = if (activity.isFinishing || activity.isDestroyed) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.applicationContext
        } else {
            activity
        }
        return runCatching { launchContext.startActivity(chooser); true }
            .onFailure { DiagLog.w("BugReport", "share intent failed", it) }
            .getOrDefault(false)
    }

    /**
     * Returns whether the report actually landed on the clipboard — the durable
     * retained delivery [share] reports back to its caller.
     */
    private fun copyToClipboard(context: Context, text: String): Boolean =
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            if (cm == null) {
                false
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("ClothesCast bug report", text))
                true
            }
        }.onFailure { DiagLog.w("BugReport", "clipboard copy failed", it) }
            .getOrDefault(false)
}

/** Cache subdirectory holding the captures attached to bug reports. */
private const val SCREENSHOT_DIR_NAME = "bug-reports"

/**
 * How many previous captures survive a new one. Two covers the realistic window
 * (the share the user just sent plus one before it) at ~a few MB of cache;
 * anything older has no live URI grant worth preserving.
 */
private const val SCREENSHOT_KEEP_PREVIOUS = 2

/**
 * The newest lines of [lines] (oldest-first) whose combined length fits
 * [budgetChars], returned oldest-first. Keeps the freshest context — a crash
 * entry and the events around it sit at the *end* of a log, so a head-first
 * truncation would drop exactly what the reader needs.
 *
 * A single newest line that alone exceeds the budget is kept **clamped to it**
 * rather than whole: returning nothing would drop the freshest context
 * entirely, but returning it whole would blow the very ceiling this exists to
 * enforce. `last-crash.txt` is the case that can reach that size — it is
 * written in one go and `cacheDir` survives app upgrades, so a file from an
 * older build is read back into the report unchanged.
 */
internal fun boundedLogTail(lines: List<String>, budgetChars: Int): List<String> {
    val kept = ArrayDeque<String>()
    var used = 0
    for (line in lines.asReversed()) {
        val cost = line.length + 1 // + the newline appendLine adds
        if (used + cost > budgetChars) {
            if (kept.isNotEmpty()) break
            kept.addFirst(line.take((budgetChars - LOG_TRUNCATION_MARKER.length).coerceAtLeast(0)) + LOG_TRUNCATION_MARKER)
            break
        }
        kept.addFirst(line)
        used += cost
    }
    return kept
}

/** Marks a log line cut short so a reader can tell it was clamped, not written that way. */
private const val LOG_TRUNCATION_MARKER = "…(truncated)"

/** Walks the [ContextWrapper] chain to find the host [Activity], or returns null. */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
