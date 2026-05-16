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
import app.clothescast.BuildConfig
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.symbol
import app.clothescast.insight.InsightFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.util.Date
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

    /**
     * Captures the screen, builds the text payload, copies the text to the
     * clipboard, and fires the share-sheet chooser. When [includeScreenshot] is
     * false (or the capture fails), shares text only.
     */
    suspend fun share(activity: Activity, includeScreenshot: Boolean) {
        val app = activity.application as ClothesCastApplication
        val text = buildPayload(activity, app)
        val screenshotUri: Uri? = if (includeScreenshot) captureAndPersistScreenshot(activity) else null
        copyToClipboard(activity, text)
        startShare(activity, text, screenshotUri)
    }

    private suspend fun buildPayload(context: Context, app: ClothesCastApplication): String {
        val prefs = runCatching { app.settingsRepository.preferences.first() }.getOrNull()
        val geminiKeyConfigured = runCatching {
            app.secureKeyStore.geminiKeyConfiguredFlow.first()
        }.getOrDefault(false)
        val thisPeriod = runCatching {
            app.insightCache.thisPeriod.first()
        }.getOrNull()
        val nextPeriod = runCatching {
            app.insightCache.nextPeriod.first()
        }.getOrNull()
        val crash = DiagLog.readPersistedCrash()
        val recent = DiagLog.snapshot()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

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
                appendPreferences(prefs)
            }
            appendLine("API keys: Gemini=${if (geminiKeyConfigured) "set" else "unset"}")
            appendLine()
            appendLine("--- Current ClothesCasts ---")
            val region = prefs?.region ?: Region.SYSTEM
            appendInsight("This period", thisPeriod, context, region)
            appendInsight("Next period", nextPeriod, context, region)
            if (!crash.isNullOrBlank()) {
                appendLine("--- Last crash (from previous run) ---")
                appendLine(crash.trim())
                appendLine()
            }
            appendLine("--- Recent log (newest last, ${recent.size} of max 300) ---")
            if (recent.isEmpty()) {
                appendLine("(no captured log lines)")
            } else {
                recent.forEach { appendLine(it) }
            }
        }
    }

    private fun StringBuilder.appendPreferences(prefs: UserPreferences) {
        appendLine("Region: ${prefs.region.name} (${prefs.region.bcp47 ?: "system"})")
        appendLine("Temperature unit: ${prefs.temperatureUnit.name}")
        appendLine("Distance unit: ${prefs.distanceUnit.name}")
        appendLine("Schedule: ${prefs.schedule.time} ${prefs.schedule.days.sorted()} (${prefs.schedule.zoneId})")
        appendLine("Tonight enabled: ${prefs.tonightEnabled}")
        appendLine("Tonight schedule: ${prefs.tonightSchedule.time} ${prefs.tonightSchedule.days.sorted()}")
        appendLine("Tonight notify only on events: ${prefs.tonightNotifyOnlyOnEvents}")
        appendLine("Daily mention evening events: ${prefs.dailyMentionEveningEvents}")
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
            "%.4f, %.4f — %s".format(Locale.US, loc.latitude, loc.longitude, name)
        } ?: "(unset)"
        appendLine("Location: $locDesc")
        // Home pin coordinates intentionally elided — they're a privacy-sensitive
        // pinpoint that doesn't help debug forecast issues. Whether one is
        // configured at all is enough to diagnose the at-home TTS gate.
        appendLine("Home location: ${if (prefs.homeLocation != null) "configured" else "(unset)"}")
        appendLine("Skip TTS at home: ${prefs.skipTtsAtHome}")
        appendLine("Use calendar events: ${prefs.useCalendarEvents}")
        appendLine("Clothes rules (${prefs.clothesRules.size}):")
        prefs.clothesRules.forEach { appendLine("  - ${describeRule(it)}") }
    }

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

    private fun describeRule(rule: ClothesRule): String {
        val cond = when (val c = rule.condition) {
            is ClothesRule.TemperatureBelow -> "feelsLikeMin < ${c.value}${c.unit.symbol()}"
            is ClothesRule.TemperatureAbove -> "feelsLikeMax > ${c.value}${c.unit.symbol()}"
            is ClothesRule.PrecipitationProbabilityAbove -> "precipMaxPct > ${c.percent}"
        }
        return "${rule.item} when $cond"
    }

    private fun StringBuilder.appendInsight(
        label: String,
        insight: Insight?,
        context: Context,
        region: Region,
    ) {
        appendLine("$label:")
        if (insight == null) {
            appendLine("  (no cached insight)")
            appendLine()
            return
        }
        val prose = runCatching { InsightFormatter.forRegion(context, region).format(insight.summary) }
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
        appendLine()
    }

    private suspend fun captureAndPersistScreenshot(activity: Activity): Uri? {
        val bitmap = runCatching { captureWindow(activity) }.getOrNull() ?: return null
        return runCatching {
            val dir = File(activity.cacheDir, "bug-reports").apply { mkdirs() }
            // Wipe older screenshots — keep only the freshest one to avoid cache bloat.
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "screenshot-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(
                activity,
                activity.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX,
                file,
            )
        }.getOrNull()
    }

    private suspend fun captureWindow(activity: Activity): Bitmap? {
        val window = activity.window ?: return null
        val view: View = window.decorView
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            val location = IntArray(2)
            view.getLocationInWindow(location)
            val rect = Rect(
                location[0],
                location[1],
                location[0] + view.width,
                location[1] + view.height,
            )
            try {
                requestPixelCopy(window, rect, bitmap) { ok ->
                    cont.resume(if (ok) bitmap else null)
                }
            } catch (t: Throwable) {
                DiagLog.w("BugReport", "PixelCopy.request threw", t)
                cont.resume(null)
            }
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

    private fun startShare(activity: Activity, text: String, screenshotUri: Uri?) {
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
        runCatching { activity.startActivity(chooser) }
            .onFailure { DiagLog.w("BugReport", "share intent failed", it) }
    }

    private fun copyToClipboard(context: Context, text: String) {
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java) ?: return
            cm.setPrimaryClip(ClipData.newPlainText("ClothesCast bug report", text))
        }.onFailure { DiagLog.w("BugReport", "clipboard copy failed", it) }
    }
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
