package app.clothescast.diag

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Locks the PRIVACY.md "Analytics and crash reporting" allowlists into a
 * regression gate. PR-time review catches new Firebase call sites today;
 * this test forces every new event name, Bundle param key, user property,
 * or Crashlytics custom key onto an explicit set below — adding one means
 * the maintainer types it in alongside the existing entries, which makes
 * the reviewer see exactly what's now crossing the device boundary.
 *
 * The hard non-transmit list itself (no calendar data, no location
 * coordinates, no insight prose, no API keys, no precise GPS, no ad
 * identifiers) still relies on PR-time review for the *semantics* of each
 * allowlisted entry. This test catches the structural drift — surface
 * growth without an allowlist update.
 */
class TelemetryPrivacyContractTest {

    private val allowedEventNames = setOf(
        "api_call",
        "notification_delivery",
        "daily_refresh",
        "scheduled_delivery_timeout",
        // The non-voice settings snapshot fans out into one event per Settings
        // page (one combined event would exceed Firebase's 25-param cap); the
        // param-key allowlist below is unchanged — same keys, regrouped.
        "settings_schedule",
        "settings_clothes",
        "settings_format",
        "settings_region",
        "settings_display",
        "settings_calendar",
        "clothes_rules_snapshot",
    )

    private val allowedParamKeys = setOf(
        "endpoint", "outcome", "status_code", "latency_ms",
        "period", "alarm_delay_ms", "total_delay_ms",
        "slot",
        "temperature_unit_setting", "temperature_unit_effective",
        "wind_speed_unit_setting", "wind_speed_unit_effective",
        "delivery_mode_daily", "delivery_mode_tonight",
        "theme_mode", "color_palette", "default_bottom", "default_top",
        "daily_enabled", "daily_time_bucket_hour", "daily_days_count",
        "tonight_enabled", "tonight_time_bucket_hour", "tonight_days_count",
        "tonight_notify_only_on_events", "daily_mention_evening_events",
        "clothes_mention_mode",
        "range_format", "clothes_format", "bottoms_format", "accessories_format",
        "delta_threshold_c",
        "delta_format",
        "use_calendar_events",
        "customised_count", "extra_rules_count", "categories_customised",
        "all_defaults",
        "sweater_delta_c", "jacket_delta_c", "coat_delta_c", "gloves_delta_c", "shorts_delta_c",
        "umbrella_delta_pct", "rain_jacket_delta_pct",
    )

    private val allowedUserPropertyKeys = setOf(
        "region_default", "region_override", "region_effective",
        "voice_locale_default", "voice_locale_override", "voice_locale_effective",
        "tts_engine_default", "tts_engine_override", "tts_engine_effective",
        "tts_style_default", "tts_style_override", "tts_style_effective",
        "gemini_voice_default", "gemini_voice_override", "gemini_voice_effective",
        "device_voice_default", "device_voice_override", "device_voice_effective",
    )

    // Set from MainActivity.handleComposeStartupCrash on devices that hit the
    // NoSuchFieldError fontWeightAdjustment startup crash. Literal boolean,
    // no user content.
    private val allowedCrashlyticsCustomKeys = setOf("compose_startup_unsupported")

    @Test
    fun `event names emitted by Telemetry match the allowlist`() {
        firstArgStrings(telemetrySource(), "logEvent") shouldBe allowedEventNames
    }

    @Test
    fun `Bundle param keys used by Telemetry match the allowlist`() {
        // Both forms appear in Telemetry.kt: `putString(PARAM_X, …)`
        // (resolved against `const val PARAM_X = "…"`) and `putString("k", …)`.
        firstArgStrings(telemetrySource(), "put(?:String|Long|Int|Boolean)") shouldBe allowedParamKeys
    }

    @Test
    fun `user-property keys set by Telemetry match the allowlist`() {
        firstArgStrings(telemetrySource(), "setUserProperty") shouldBe allowedUserPropertyKeys
    }

    @Test
    fun `setCustomKey keys match the allowlist`() {
        // Capture quoted-literal keys for the allowlist comparison, and
        // separately flag any non-literal first arg (identifier reference,
        // function call, etc.) so a `setCustomKey(NEW_KEY, value)` call can't
        // grow the surface without showing up in the allowlist diff.
        val literalKeys = mutableSetOf<String>()
        val unresolvedCallSites = mutableListOf<String>()
        appMainKtFiles().forEach { file ->
            val text = file.readText()
            Regex("""setCustomKey\(\s*"([^"]+)"\s*,""").findAll(text).forEach {
                literalKeys += it.groupValues[1]
            }
            Regex("""setCustomKey\(\s*([^"\s][^,)]*)\s*,""").findAll(text).forEach {
                unresolvedCallSites += "${file.path}: setCustomKey(${it.groupValues[1].trim()}, …)"
            }
        }
        unresolvedCallSites.shouldBeEmpty()
        literalKeys shouldBe allowedCrashlyticsCustomKeys
    }

    @Test
    fun `Telemetry does not reference sensitive types`() {
        // Belt-and-braces on top of the snapshot primitives check: a
        // `Location` / `CalendarEvent` / `InsightSummary` / `SecureKeyStore`
        // type can't land in Telemetry.kt without either an import or a
        // fully-qualified reference. Scan for both forms — substring match
        // against the FQN catches `import app.clothescast.core.domain.model.Location`
        // AND inline `fun foo(loc: app.clothescast.core.domain.model.Location)`.
        // Comments are stripped first so KDoc references don't false-positive.
        val bannedTypes = setOf(
            "android.location.Location",
            "app.clothescast.core.domain.model.Location",
            "app.clothescast.core.domain.model.CalendarEvent",
            "app.clothescast.core.domain.model.Insight",
            "app.clothescast.core.domain.model.InsightSummary",
            "app.clothescast.data.SecureKeyStore",
        )
        val source = stripBlockAndLineComments(telemetrySource())
        val offenders = bannedTypes.filter { source.contains(it) }
        offenders.shouldBeEmpty()
    }

    @Test
    fun `Telemetry body does not reference sensitive identifiers`() {
        // Bundle param keys are allowlisted, but the *values* passed alongside
        // aren't structurally checked. A future call like
        // `putString("endpoint", forecast.latitude.toString())` would pass
        // the key-allowlist gate while sending location data. Reject any
        // reference to identifiers that map onto the PRIVACY.md non-transmit
        // categories. Comments are stripped first so KDoc phrases like
        // "API keys" don't false-positive (only CamelCase / snake_case
        // identifier forms in code trip the regex).
        val bannedTokens = listOf(
            // Location / coordinates (PRIVACY.md: "No location coordinates or
            // geocoded place names", "No precise GPS").
            "latitude", "longitude", "coordinate", "coordinates",
            "gpsLocation", "placeName", "geocodedPlace",
            // Credentials (PRIVACY.md: "No API keys or other credentials").
            "apiKey", "geminiKey", "openaiKey", "secureKey", "keyStore",
            // Insight / notification text (PRIVACY.md: "No insight prose,
            // notification text, or anything else that could carry free-form
            // user content").
            "insightProse", "insightSummary",
            "notificationTitle", "notificationBody", "notificationText",
            // Calendar (PRIVACY.md: "No calendar event data. Not titles, …").
            "eventTitle", "eventLocation", "calendarEvent",
            // User / account / ad identifiers (PRIVACY.md: "No user names,
            // account identifiers, email addresses, or contact info" and
            // "advertising identifiers").
            "userName", "accountId", "emailAddress", "contactInfo",
            "advertisingId", "adId",
        )
        val source = stripBlockAndLineComments(telemetrySource())
        val offenders = bannedTokens.filter {
            source.contains(Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE))
        }
        offenders.shouldBeEmpty()
    }

    @Test
    fun `PRIVACY md still names every do-not-transmit category`() {
        // Catches a silent doc weakening: if a future commit drops one of
        // these phrases from the analytics/crash-reporting hard-limit list,
        // the contract this test enforces has drifted from the user-visible
        // promise. Scoped to just the "What's not sent" section so a
        // mention elsewhere (e.g. the Changelog reflecting an old state)
        // doesn't paper over a removal from the live contract.
        val section = privacyNotSentSection()
        // Every hard-limit category in the "What's not sent" list, including
        // the secondary phrases inside each bullet ("user names" + "email
        // addresses" come from the same bullet; "location coordinates" +
        // "geocoded place names" from another; "insight prose" + "notification
        // text" from another). Adding one of these to the required list
        // means a future edit can't drop the bullet — or the secondary
        // phrase inside it — without tripping the test.
        val required = listOf(
            "calendar event data",
            "user names",
            "email addresses",
            "location coordinates",
            "geocoded place names",
            "insight prose",
            "notification text",
            "API keys",
            "precise GPS",
            "advertising identifiers",
        )
        val missing = required.filterNot { section.contains(it, ignoreCase = true) }
        missing.shouldBeEmpty()
    }

    @Test
    fun `setUserId is never called`() {
        // No user accounts in the app; PRIVACY.md rules out account identifiers
        // in analytics. Ban the call outright.
        val offenders = appMainKtFiles()
            .filter { it.readText().contains(Regex("""\bsetUserId\s*\(""")) }
            .map { it.path }
            .toList()
        offenders.shouldBeEmpty()
    }

    @Test
    fun `telemetry snapshot data classes carry only primitive fields`() {
        // The three snapshot types fan out into Firebase Analytics event
        // params. Restricting their fields to primitives makes it
        // structurally impossible for a snapshot to carry a Location,
        // CalendarEvent, InsightSummary, ApiKey, Throwable, or any other
        // value type that could smuggle user content into a Bundle.
        val snapshotFiles = listOf(
            "src/main/kotlin/app/clothescast/diag/SettingsSnapshot.kt",
            "src/main/kotlin/app/clothescast/diag/ClothesRulesSnapshot.kt",
            "src/main/kotlin/app/clothescast/diag/SettingsAnalyticsSnapshot.kt",
        ).map(::sourceFile)
        val allowedPrimitives = setOf("String", "Int", "Long", "Boolean", "Float", "Double")
        snapshotFiles.forEach { file ->
            val ctor = Regex("""data class \w+\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
                .find(file.readText())
                ?.groupValues?.get(1)
                ?: error("No data class constructor found in ${file.name}")
            // Match both `val` and `var` primary-constructor properties.
            val fieldTypes = Regex("""\b(?:val|var)\s+\w+\s*:\s*([A-Za-z_][A-Za-z0-9_]*)""")
                .findAll(ctor)
                .map { it.groupValues[1] }
                .toSet()
            (fieldTypes - allowedPrimitives).shouldBeEmpty()
        }
    }

    /**
     * Returns the set of string values passed as the first argument to every
     * `<methodPattern>(…)` call in [source]. Handles both forms used in
     * Telemetry.kt:
     *  - `"literal"` → captured as `literal`.
     *  - `IDENTIFIER` resolved against `const val IDENTIFIER = "…"` in
     *    the same source → captured value.
     */
    private fun firstArgStrings(source: String, methodPattern: String): Set<String> {
        val literals = Regex("""\b(?:$methodPattern)\(\s*"([^"]+)"\s*,""")
            .findAll(source).map { it.groupValues[1] }
        val constRefs = Regex("""\b(?:$methodPattern)\(\s*([A-Z_][A-Z0-9_]*)\s*,""")
            .findAll(source).map {
                val ident = it.groupValues[1]
                Regex("""\bconst val $ident\s*=\s*"([^"]+)"""").find(source)
                    ?.groupValues?.get(1)
                    ?: error("Could not resolve const $ident")
            }
        return (literals + constRefs).toSet()
    }

    @Test
    fun `the choice and its debt come from one snapshot`() {
        // Two separate reads let them disagree: an opt-out landing while the
        // collector is mid-transition on an older `enabled = true` gives a
        // stale choice beside the newer debt, and the transition discharges
        // the debt then re-enables both SDKs (Codex, PR #1161). A separately
        // *collected* debt loops instead, since the transition writes it.
        // One snapshot avoids both, and neither failure is visible in
        // behavior until the exact interleaving happens.
        val source = telemetrySource()

        source.contains("settings.telemetryChoice") shouldBe true
        source.contains("telemetryDiscardOwed.first()") shouldBe false
        source.contains("combine(") shouldBe false
    }

    @Test
    fun `every Firebase producer lives in Telemetry`() {
        // A call site outside this object bypasses the applied-choice gate
        // entirely. `MainActivity.handleComposeStartupCrash` did exactly that
        // — a direct `recordException` on a path that runs before the stored
        // choice has necessarily been applied, so it could queue an
        // app-generated report during the migrating launch's window, the one
        // thing PRIVACY.md says does not happen (Codex, PR #1161). It now goes
        // through `Telemetry.recordComposeStartupCrash`, which waits.
        val offenders = appMainKtFiles()
            .filter { it.name != "Telemetry.kt" }
            .flatMap { file ->
                val text = stripBlockAndLineComments(file.readText())
                Regex("""(recordException|setCustomKey|setUserProperty|logEvent)\(""")
                    .findAll(text)
                    .map { "${file.path}: ${it.groupValues[1]}" }
            }
            .toList()

        offenders.shouldBeEmpty()
    }

    @Test
    fun `nothing produces telemetry before the stored choice is applied`() {
        // The producers race the collector otherwise. On an upgrade carrying
        // Analytics' persisted `true`, the three snapshot collectors emit
        // their initial events the moment they attach — before anything has
        // been disabled — so a user who has never agreed sends freshly
        // generated events, not merely the held report PRIVACY.md discloses
        // (Codex, PR #1161). `analyticsRef` is the same hole for any helper
        // called out of band, since every one of them reads it.
        //
        // Asserted against the source because the failure is a startup race:
        // it needs a real Firebase SDK and an upgrade's persisted flag to
        // reproduce, and it is invisible in every behavioral test here.
        val source = stripBlockAndLineComments(telemetrySource())

        // Each producer waits on the gate before it subscribes.
        for (producer in listOf(
            "settings.analyticsSnapshot",
            "settings.settingsSnapshot",
            "settings.clothesRulesSnapshot",
        )) {
            val launched = Regex(
                """scope\.launch \{\s*([^\n]*)\n[^\n]*${Regex.escape(producer)}""",
            ).find(source) ?: error("could not find the coroutine collecting $producer")
            launched.groupValues[1].trim() shouldBe "telemetryApplied.await()"
        }

        // And the reference every logEvent helper reads is published from
        // inside the choice collector, not before it.
        val assignments = Regex("analyticsRef = ").findAll(source).count()
        assignments shouldBe 1
        val beforeAssignment = source.substringBefore("analyticsRef = analytics")
        beforeAssignment.contains("applyTelemetryChoice(") shouldBe true
    }

    @Test
    fun `opting out records the discard debt in the same edit`() {
        // Written separately, the debt is lost exactly when it is needed: the
        // collector conflates, so an off then a quick on can present only the
        // final `true`, the disabled transition never runs, and nothing
        // records that the off period's reports are still there (Codex,
        // PR #1161). One edit is what makes the two inseparable.
        val source = sourceFile("src/main/kotlin/app/clothescast/data/SettingsRepository.kt").readText()
        val edit = Regex(
            """suspend fun setTelemetryEnabled\(enabled: Boolean\) \{\s*dataStore\.edit \{([^}]*\}[^}]*)\}""",
        ).find(source)?.groupValues?.get(1) ?: error("could not find setTelemetryEnabled's edit block")

        edit.contains("TELEMETRY_ENABLED") shouldBe true
        edit.contains("oweTelemetryDiscard") shouldBe true

        // And the helper it calls is what actually records the debt — both the
        // flag and the generation a later clear compares against, so a debt
        // recorded during a purge cannot be retired by that purge's clear.
        val owe = Regex(
            """private fun MutablePreferences\.oweTelemetryDiscard\(\) \{([^}]*)\}""",
        ).find(source)?.groupValues?.get(1) ?: error("could not find oweTelemetryDiscard")
        owe.contains("TELEMETRY_DISCARD_OWED") shouldBe true
        owe.contains("TELEMETRY_DISCARD_TOKEN") shouldBe true
    }

    @Test
    fun `both SDKs start with collection disabled, from the manifest`() {
        // The whole opt-in claim rests on this. FirebaseInitProvider runs
        // before Application.onCreate, so no code of ours can get there first:
        // without these two meta-data entries both SDKs come up *collecting*,
        // and a crash before the first frame is captured and scheduled from
        // someone who has never been asked. A stored `true` from a consenting
        // user overrides them at startup, so they cost a consenting user
        // nothing.
        //
        // Asserted against the manifest source because the failure is
        // invisible in behavior — every other test here would still pass, and
        // the only symptom would be reports arriving from users who never
        // opted in, which is the thing that must never happen quietly.
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()

        for (flag in listOf(
            "firebase_analytics_collection_enabled",
            "firebase_crashlytics_collection_enabled",
        )) {
            val entry = Regex(
                """<meta-data\s+android:name="$flag"\s+android:value="([^"]*)"\s*/>""",
            ).find(manifest) ?: error("$flag is not declared in AndroidManifest.xml")
            entry.groupValues[1] shouldBe "false"
        }
    }

    private fun telemetrySource(): String =
        sourceFile("src/main/kotlin/app/clothescast/diag/Telemetry.kt").readText()

    /** PRIVACY.md lives at the repo root; resolve from either `app/` or repo cwd. */
    private fun privacyMdSource(): String =
        listOf(File("../PRIVACY.md"), File("PRIVACY.md"))
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("PRIVACY.md not found from cwd ${File(".").absolutePath}")

    /**
     * The "What's not sent" subsection of PRIVACY.md's "Analytics and crash
     * reporting" — the canonical hard-limit list of what may not appear in
     * Firebase payloads. Bounded by the `What's **not** sent` marker on the
     * top and the next `##` heading (or end of file) on the bottom; the
     * trailing "The reporting service receives only what's described above"
     * paragraph stays inside the slice, which is fine since the scan is a
     * `contains` check on phrases that only appear inside the bullet list.
     */
    private fun privacyNotSentSection(): String {
        val full = privacyMdSource()
        val start = full.indexOf("What's **not** sent")
        require(start >= 0) { "PRIVACY.md missing 'What's **not** sent' section marker" }
        val rest = full.substring(start)
        val end = rest.indexOf("\n## ", startIndex = 1)
        return if (end > 0) rest.substring(0, end) else rest
    }

    /** Strips `// …` and `/* … */` so KDoc / comment phrases can't false-positive on identifier scans. */
    private fun stripBlockAndLineComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private fun appMainKtFiles(): Sequence<File> =
        sourceFile("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }

    /**
     * Gradle's default unit-test working directory is the module project dir
     * (`app/`). The fallback covers IDE runs that start from the repo root.
     */
    private fun sourceFile(relativePath: String): File {
        val candidate = File(relativePath)
        if (candidate.exists()) return candidate
        val fromRepoRoot = File("app/$relativePath")
        if (fromRepoRoot.exists()) return fromRepoRoot
        error("Source path not found from cwd ${File(".").absolutePath}: $relativePath")
    }
}
