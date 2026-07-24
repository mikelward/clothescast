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
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import app.clothescast.BuildConfig
import app.clothescast.ClothesCastApplication
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
     * Captures the screen, builds the text payload, copies the text to the
     * clipboard, and fires the share-sheet chooser. When [includeScreenshot] is
     * true (the default) the current window is grabbed and attached as a PNG so
     * the report shows what the user was looking at; when it's false (or the
     * capture fails) the report shares text-only. The text payload is capped to
     * [MAX_LOG_LINES] log lines so the `EXTRA_TEXT` caption stays under the
     * length limits image-share targets impose.
     */
    suspend fun share(activity: Activity, includeScreenshot: Boolean = true) {
        val app = activity.application as ClothesCastApplication
        val text = buildPayload(activity, app)
        val screenshotUri: Uri? = if (includeScreenshot) captureAndPersistScreenshot(activity) else null
        copyToClipboard(activity, text)
        startShare(activity, text, screenshotUri)
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
        val recent = DiagLog.snapshot().takeLast(MAX_LOG_LINES)
        val now = TIMESTAMP_FORMAT.format(Instant.now())

        return buildString {
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
            if (!crash.isNullOrBlank()) {
                appendLine("--- Last crash (from previous run) ---")
                appendLine(crash.trim())
                appendLine()
            }
            appendLine("--- Recent log (newest last, ${recent.size} of max $MAX_LOG_LINES) ---")
            if (recent.isEmpty()) {
                appendLine("(no captured log lines)")
            } else {
                recent.forEach { appendLine(it) }
            }
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
        val bitmap = coRunCatching { captureWindow(activity) }.getOrNull() ?: return null
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
        return runCatching { activity.startActivity(chooser); true }
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
 * [budgetChars], returned oldest-first; at least the single newest line is kept
 * even if it alone exceeds the budget. Keeps the freshest context — a crash
 * entry and the events around it sit at the *end* of a log, so a head-first
 * truncation would drop exactly what the reader needs.
 */
internal fun boundedLogTail(lines: List<String>, budgetChars: Int): List<String> {
    val kept = ArrayDeque<String>()
    var used = 0
    for (line in lines.asReversed()) {
        val cost = line.length + 1 // + the newline appendLine adds
        if (used + cost > budgetChars && kept.isNotEmpty()) break
        kept.addFirst(line)
        used += cost
    }
    return kept
}

/** Walks the [ContextWrapper] chain to find the host [Activity], or returns null. */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
