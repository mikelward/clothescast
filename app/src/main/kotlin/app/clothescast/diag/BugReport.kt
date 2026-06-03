package app.clothescast.diag

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import app.clothescast.BuildConfig
import app.clothescast.ClothesCastApplication
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
import app.clothescast.data.SettingsRepository
import app.clothescast.insight.InsightFormatter
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds a "paste-into-Claude" bug-report payload (version, device, settings,
 * the latest cached this-period + next-period ClothesCasts, recent log lines, last crash
 * if any) and hands it off via [Intent.ACTION_SEND] so the share sheet can
 * deliver it to whichever app the user picks. Also drops the text on the
 * clipboard as a paste fallback.
 */
object BugReport {
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z").withZone(ZoneId.systemDefault())

    /**
     * How many trailing log lines the report carries. The report ships as a
     * pure-text `EXTRA_TEXT` share, and many targets (messengers especially)
     * treat that as a caption with a hard character cap that silently truncates
     * a long report mid-line. Capping the log tail keeps the whole payload —
     * header plus log — small enough to survive those targets intact. The
     * header runs ~3-4 KB, so 100 lines leaves comfortable headroom under a
     * ~20 KB budget. [DiagLog] still retains its full 300-line buffer; this
     * only trims what the share carries.
     */
    private const val MAX_LOG_LINES = 100

    /**
     * Builds the text payload, copies it to the clipboard, and fires the
     * share-sheet chooser as a pure-text intent. No file is attached and no
     * screenshot: an image- or stream-typed intent surfaces share targets that
     * treat `EXTRA_TEXT` as a *caption* with a hard character cap, which
     * silently truncated long bug reports mid-line. Keeping the payload text-
     * only — and capped to [MAX_LOG_LINES] log lines — fits it under those
     * caption limits while still carrying everything the recipient needs
     * (settings, cached insights, recent log).
     */
    suspend fun share(activity: Activity) {
        val app = activity.application as ClothesCastApplication
        val text = buildPayload(activity, app)
        copyToClipboard(activity, text)
        startShare(activity, text)
    }

    private suspend fun buildPayload(context: Context, app: ClothesCastApplication): String {
        val prefs = runCatching { app.settingsRepository.preferences.first() }.getOrNull()
        val geminiKeyConfigured = runCatching {
            app.secureKeyStore.geminiKeyConfiguredFlow.first()
        }.getOrDefault(false)
        val mqttPasswordConfigured = runCatching {
            app.secureKeyStore.mqttPasswordConfiguredFlow.first()
        }.getOrDefault(false)
        val mqttPublishStatus = runCatching {
            app.settingsRepository.mqttPublishStatus.first()
        }.getOrNull()
        val castStatus = runCatching {
            app.settingsRepository.castStatus.first()
        }.getOrNull()
        val thisSnapshot = runCatching {
            app.insightCache.thisPeriod.first()
        }.getOrNull()
        val nextSnapshot = runCatching {
            app.insightCache.nextPeriod.first()
        }.getOrNull()
        val thisPeriod = if (thisSnapshot != null && prefs != null) {
            runCatching { app.deriveInsight(thisSnapshot, prefs).insight }.getOrNull()
        } else null
        val nextPeriod = if (nextSnapshot != null && prefs != null) {
            runCatching { app.deriveInsight(nextSnapshot, prefs).insight }.getOrNull()
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
            val periodPreamble = prefs?.periodPreamble ?: PreambleVisibility.ALWAYS
            val wearPreamble = prefs?.wearPreamble ?: PreambleVisibility.ALWAYS
            appendInsight("This period", thisPeriod, thisSnapshot, prefs, context, region, tempUnit, rangeFormat, clothesFormat, bottomsFormat, periodPreamble, wearPreamble)
            appendInsight("Next period", nextPeriod, nextSnapshot, prefs, context, region, tempUnit, rangeFormat, clothesFormat, bottomsFormat, periodPreamble, wearPreamble)
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
        appendLine("Distance unit: ${prefs.distanceUnit.name}")
        appendLine("Daily enabled: ${prefs.dailyEnabled}")
        appendLine("Schedule: ${prefs.schedule.time} ${prefs.schedule.days.sorted()} (${prefs.schedule.zoneId})")
        appendLine("Tonight enabled: ${prefs.tonightEnabled}")
        appendLine("Tonight schedule: ${prefs.tonightSchedule.time} ${prefs.tonightSchedule.days.sorted()}")
        appendLine("Tonight notify only on events: ${prefs.tonightNotifyOnlyOnEvents}")
        appendLine("Daily mention evening events: ${prefs.dailyMentionEveningEvents}")
        appendLine("Clothes mention mode: ${prefs.clothesMentionMode}")
        appendLine("Range format: ${prefs.rangeFormat}")
        appendLine("Bottoms format: ${prefs.bottomsFormat}")
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

    private fun describeRule(rule: ClothesRule): String {
        val cond = when (val c = rule.condition) {
            is ClothesRule.TemperatureBelow -> "feelsLikeMin < ${c.value}${c.unit.symbol()}"
            is ClothesRule.TemperatureAbove -> "feelsLikeMax > ${c.value}${c.unit.symbol()}"
            is ClothesRule.PrecipitationProbabilityAbove -> "precipMaxPct > ${c.percent}"
        }
        return "${rule.item.itemKey} when $cond"
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
        periodPreamble: PreambleVisibility,
        wearPreamble: PreambleVisibility,
    ) {
        appendLine("$label:")
        if (insight == null) {
            appendLine("  (no cached insight)")
        } else {
            val prose = runCatching {
                InsightFormatter.forRegion(context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat, periodPreamble, wearPreamble)
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

    private fun startShare(activity: Activity, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ClothesCast bug report — ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "Share bug report")
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
