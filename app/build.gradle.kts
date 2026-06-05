import java.util.Properties
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Firebase plugins are only applied when the developer has dropped their
// `google-services.json` into app/. CI sandboxes don't carry the file (it's
// in .gitignore — Firebase config is per-developer / per-environment), so
// applying the gms plugin unconditionally would fail the build with
// "File google-services.json is missing" the moment Firebase is wired up.
//
// Skipping the plugins also means the runtime SDKs find no FirebaseApp —
// the Telemetry controller guards on FirebaseApp.getApps(...).isEmpty()
// and no-ops in that case, so the app still launches and the Settings
// toggle still flips the persisted preference (it just has no SDK to
// hand the choice off to). User-built APKs with the JSON in place report
// as expected.
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = libs.plugins.gms.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
} else {
    logger.lifecycle(
        "clothescast: app/google-services.json not found — Firebase plugins skipped " +
            "and the app will run without Analytics/Crashlytics. Drop the file in " +
            "app/ to enable telemetry on this build.",
    )
}

/**
 * Runs `git` and returns its trimmed stdout. Throws — loudly — when git is
 * unavailable or the working tree isn't a repo, rather than silently shipping
 * a bogus version. Configure-time failures are the right outcome: a release
 * APK with versionCode = 0 is worse than a build that won't start.
 */
fun git(vararg args: String): String {
    val process = ProcessBuilder("git", *args)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exit = process.waitFor()
    check(exit == 0) { "git ${args.joinToString(" ")} failed (exit $exit): $output" }
    return output
}

/**
 * Escapes a string so it's safe to drop verbatim between the quotes of a
 * `buildConfigField("String", …)` value — i.e. a Java string literal in the
 * generated BuildConfig.java. Handles backslash (first, so the others aren't
 * double-escaped), quote, and the control characters a value pasted with a
 * trailing line break would otherwise inject: an unescaped newline splits the
 * literal across two lines and the compiler reports an "unclosed string
 * literal". Returns the escaped body only — callers add the surrounding quotes.
 */
fun String.asJavaStringLiteralBody(): String = this
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

// versionCode: total commit count on the current branch. Monotonically increases,
// reproducible (same commit -> same number), required by Firebase App Distribution
// and the Play Store. CI must use `actions/checkout@v4` with `fetch-depth: 0` —
// shallow clones make rev-list --count return 1 and break monotonicity across builds.
val gitCommitCount: Int = git("rev-list", "--count", "HEAD").toInt()

// versionName base. Bumped manually for marketing-meaningful releases. The short
// SHA is appended at build time so any APK in the wild can be traced to the
// commit that produced it (visible in Settings -> About at runtime).
val versionNameBase = "0.1.0"
val gitShortSha: String = git("rev-parse", "--short", "HEAD")

// "Is this a CI build?" CI builds keep the unmodified launcher icon; any
// build run outside CI gets an under-construction badge overlaid on the icon.
// The `CI=true` env var is set by GitHub Actions and most other CI providers,
// so the rule is simply: if the environment looks like CI, no badge;
// otherwise, badge.
//
// This keeps the rule trivial to reason about — "the APK on my phone came
// from CI" is exactly the question the badge answers — at the cost of
// over-badging the rare case where a developer runs `CI=true` locally.
//
// Both icon variants live in src/main/res (`@mipmap/ic_launcher` and
// `@mipmap/ic_launcher_construction`); we swap which one the manifest
// references via a manifest placeholder rather than juggling source-set
// res.srcDirs (which would error on duplicate resources within a single
// source set). The unused variant is stripped from release APKs by the
// resource shrinker since nothing references it.
val isCiBuild: Boolean = System.getenv("CI") == "true"
val launcherIconRes: String = if (isCiBuild) "@mipmap/ic_launcher" else "@mipmap/ic_launcher_construction"
println("clothescast: isCiBuild=$isCiBuild, launcherIcon=$launcherIconRes (versionCode=$gitCommitCount, HEAD=$gitShortSha)")

/**
 * Returns this developer's stable Firebase App Check debug token, or `""`
 * on CI builds. On a local build we read `appCheckDebugToken=<uuid>` from
 * `local.properties` if present, otherwise we mint a fresh UUID and append
 * it to that file (preserving any existing contents) so subsequent builds
 * reuse the same value. Register the value once in Firebase Console (App
 * Check → app.clothescast.debug → Manage debug tokens) and every debug
 * build off this laptop reuses it — including across `gradlew clean`,
 * `Clear data`, and reinstalls.
 *
 * CI builds intentionally leave this empty so the Firebase Debug provider
 * falls back to its own per-install random UUID. That keeps the
 * developer's token out of FAD-distributed debug APKs (any tester would
 * otherwise extract it from `BuildConfig` and inherit the developer's
 * App Check identity). FAD testers' devices keep using the original
 * "grep Logcat once per fresh install" workflow.
 *
 * The token has no power over release App Check (Play Integrity), and
 * Firestore rules deny all client access regardless of who's authed via
 * App Check — so the worst-case exposure of a leaked debug token is
 * free-riding on the dev's debug-identity quota at the TTS proxy.
 */
fun ensureAppCheckDebugToken(localProperties: java.io.File): String {
    if (isCiBuild) return ""
    val key = "appCheckDebugToken"
    if (localProperties.exists()) {
        val props = Properties()
        localProperties.inputStream().use { stream -> props.load(stream) }
        val existing = props.getProperty(key)
        if (existing != null && existing.isNotBlank()) {
            return existing
        }
    }
    val token = UUID.randomUUID().toString()
    // Append rather than rewrite via Properties.store(): the user may
    // hand-edit local.properties (e.g. geminiProxyUrl) and Properties
    // drops comments + reorders on a full rewrite. Appending preserves
    // existing contents verbatim.
    val existing = if (localProperties.exists()) localProperties.readText() else ""
    val needsLeadingNewline = existing.isNotEmpty() && !existing.endsWith("\n")
    val addition = buildString {
        if (needsLeadingNewline) append('\n')
        append("\n# Firebase App Check debug token — generated once per developer.\n")
        append("# Register in Firebase Console (App Check → app.clothescast.debug → Manage debug tokens).\n")
        append("# Surfaced in Settings → Voice in debug builds for easy copy-paste. Keep private.\n")
        append(key).append('=').append(token).append('\n')
    }
    localProperties.appendText(addition)
    logger.lifecycle(
        "clothescast: generated App Check debug token in local.properties — register " +
            "in Firebase Console → App Check → app.clothescast.debug.",
    )
    return token
}

// Stable per-developer Firebase App Check debug token, read at the file
// level so both the debug `buildConfigField` and the debug-only res
// generator below can reference the same value. Empty on CI builds.
val appCheckDebugToken: String = ensureAppCheckDebugToken(rootProject.file("local.properties"))

// Today-screen local-build banner: shown on non-CI builds (gated on the same
// `isCiBuild` flag the launcher icon uses) so a developer can see at a glance
// which APK is installed and how fresh it is — "claude/foo · abc1234 (dirty)
// · 2 hours ago". Detached HEAD shows up as a literal "HEAD" from rev-parse;
// translate that to the short SHA so CI debug builds (rare) read sensibly.
val gitBranchRaw: String = git("rev-parse", "--abbrev-ref", "HEAD")
val gitBranch: String = if (gitBranchRaw == "HEAD") "detached@$gitShortSha" else gitBranchRaw
val gitDirty: Boolean = git("status", "--porcelain").isNotEmpty()
// Captured at Gradle configure time. Each new build invalidates the BuildConfig
// regen, but that's already AGP's normal behaviour and the dev banner needs a
// "2 hours ago" relative timestamp that's accurate for the current install.
val buildTimestampMs: Long = System.currentTimeMillis()

android {
    // Pinned to app.clothescast. Renamed from app.adaptweather as part of the
    // product rename; existing FAD testers had to uninstall + reinstall,
    // losing their encrypted API keys (Gemini), schedule,
    // location, and clothes rules. Once a sideloaded build with applicationId
    // X is in the wild, switching to Y means installs of Y are a different
    // app — the applicationId is the install identity; do not change it again
    // without a planned migration story.
    namespace = "app.clothescast"
    compileSdk = 36
    // Pin to the build-tools the CI / sandbox setup installs. AGP 8.10's default
    // is 35.0.0, so without this AGP would fetch 35.0.0 on demand even though
    // we pre-install 36.0.0 — defeating the point of pinning the SDK packages in
    // ci.yml / session-start.sh (and failing when on-demand downloads are
    // blocked). Keep this in lockstep with the build-tools version installed there.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.clothescast"
        // Sourced from the version catalog so the same number drives both the
        // install-time gate (this line) and BuildConfig.MIN_SDK_VERSION below
        // — see the catalog entry for why the runtime guard exists.
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = 36
        versionCode = gitCommitCount
        // semver build metadata: <base>+<commitCount>.<shortSha>. Some downstream
        // tools (Crashlytics, Bugsnag, screenshots in bug reports) carry only
        // versionName, not versionCode — embedding the count means one string
        // identifies the build everywhere.
        versionName = "$versionNameBase+$gitCommitCount.$gitShortSha"

        // Manifest-merger placeholder consumed by AndroidManifest.xml's
        // android:icon attribute. See the launcherIconRes block above for
        // the rule.
        manifestPlaceholders["launcherIconRes"] = launcherIconRes

        // Dev banner fields surfaced on the Today screen for local (non-CI)
        // builds. IS_LOCAL_BUILD piggybacks on the same `isCiBuild` flag the
        // launcher icon uses, so the badge and the banner agree on which
        // APKs are "the developer's own machine" — FAD-distributed debug
        // APKs and Play release builds (both built on CI) get neither.
        // Defined for all variants so the composable compiles regardless of
        // which buildType is active.
        buildConfigField("boolean", "IS_LOCAL_BUILD", (!isCiBuild).toString())
        buildConfigField("String", "GIT_BRANCH", "\"${gitBranch.asJavaStringLiteralBody()}\"")
        buildConfigField("String", "GIT_SHA", "\"$gitShortSha\"")
        buildConfigField("boolean", "GIT_DIRTY", gitDirty.toString())
        buildConfigField("long", "BUILD_TIMESTAMP_MS", "${buildTimestampMs}L")
        // Mirror of `minSdk` above. The Telemetry controller reads this to
        // silence Firebase when SDK_INT < MIN_SDK_VERSION so APKs that
        // somehow land on sub-minSdk devices don't pollute Crashlytics /
        // Analytics with a config the app was never built for.
        buildConfigField("int", "MIN_SDK_VERSION", libs.versions.minSdk.get())

        // Base URL for the developer's Cloud Function TTS proxy. Per-developer
        // (each fork has its own Firebase project), so it's sourced — in this
        // priority — from the `GEMINI_PROXY_URL` env var, a `geminiProxyUrl=…`
        // line in `<rootDir>/local.properties`, or a `geminiProxyUrl` Gradle
        // property (`~/.gradle/gradle.properties` or `-PgeminiProxyUrl=…` on
        // the command line). Empty when none of those are set, which is the
        // CI case — `AppCheckGeminiCallPlanner` treats blank as "shared key
        // disabled" and falls back to BYOK semantics. Format: scheme + host +
        // optional single path segment, no trailing slash, e.g.
        // https://us-central1-myproj.cloudfunctions.net/tts.
        //
        // Full setup walkthrough — Firebase project, App Check, function
        // deploy, where to get this URL — lives in docs/gemini-tts-proxy.md.
        val geminiProxyUrl: String = run {
            val fromEnv = System.getenv("GEMINI_PROXY_URL")?.trim()?.takeIf { it.isNotBlank() }
            val fromLocalProps: String? = rootProject.file("local.properties")
                .takeIf { it.exists() }
                ?.let { file ->
                    val props = Properties()
                    file.inputStream().use { props.load(it) }
                    props.getProperty("geminiProxyUrl")?.trim()?.takeIf { it.isNotBlank() }
                }
            val fromGradleProps = providers.gradleProperty("geminiProxyUrl").orNull?.trim()?.takeIf { it.isNotBlank() }
            fromEnv ?: fromLocalProps ?: fromGradleProps ?: ""
        }
        buildConfigField("String", "GEMINI_PROXY_URL", "\"${geminiProxyUrl.asJavaStringLiteralBody()}\"")
    }

    // Defence-in-depth: nuke any stale `app_check_debug.xml` from an
    // earlier iteration of this branch where we were (incorrectly)
    // hoping the SDK would read the token from a resource string. It
    // never did. The token's real plumbing is now in
    // `app/src/debug/.../AppCheckProviderFactoryProvider.kt`, which
    // pre-populates the SDK's SharedPreferences store. This `delete`
    // ensures a `gradlew clean`-less local checkout that previously
    // generated the XML doesn't ship a forever-stale resource.
    file("build/generated/appCheck/debug/res/values/app_check_debug.xml").delete()

    // Widget picker previews are generated res drawables — see the
    // GenerateWidgetPreviewsTask + androidComponents.onVariants block after
    // this android{} block, which wires them into the AGP resource pipeline.

    signingConfigs {
        // Stable debug-keystore signing. When the env vars below are present
        // (decoded from GitHub Secrets in the workflow), every CI build is
        // signed with the same identity — so the user's installed APK upgrades
        // in place rather than failing with "App not installed (signature
        // mismatch)" on each new build, which would otherwise force a wipe of
        // their encrypted Gemini key + saved settings.
        //
        // Locally, the env vars are absent, so AGP's auto-generated
        // ~/.android/debug.keystore is used (the conventional dev path —
        // unchanged behaviour for anyone running `./gradlew assembleDebug`
        // from their own machine).
        getByName("debug") {
            // All four vars must be present for the override; if only some are
            // set we'd otherwise silently install blank credentials and let
            // signing fail with a confusing message at the end of the build.
            // Treat partial configuration as user error, fail fast.
            val keystorePath = System.getenv("DEBUG_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
            val storePass = System.getenv("DEBUG_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
            val alias = System.getenv("DEBUG_KEY_ALIAS")?.takeIf { it.isNotBlank() }
            val keyPass = System.getenv("DEBUG_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

            val anySet = keystorePath != null || storePass != null || alias != null || keyPass != null
            val allSet = keystorePath != null && storePass != null && alias != null && keyPass != null

            if (anySet && !allSet) {
                error(
                    "Partial debug-keystore configuration. Set all of DEBUG_KEYSTORE_FILE, " +
                        "DEBUG_KEYSTORE_PASSWORD, DEBUG_KEY_ALIAS, DEBUG_KEY_PASSWORD — or none, " +
                        "to fall back to AGP's default debug keystore.",
                )
            }

            if (allSet) {
                val keystore = file(keystorePath!!)
                check(keystore.exists()) {
                    "DEBUG_KEYSTORE_FILE is set but does not exist: ${keystore.path}"
                }
                storeFile = keystore
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
        // Upload key for Play App Signing. Same env-var-or-fail pattern as the
        // debug block above: in CI the four UPLOAD_* env vars are populated from
        // GitHub Secrets and bundleRelease produces a Play-uploadable AAB; locally
        // the env vars are absent, the signing config has no storeFile, and AGP
        // fails loudly on bundleRelease/assembleRelease — which is the right
        // default (better than silently shipping a debug-signed release).
        create("release") {
            val keystorePath = System.getenv("UPLOAD_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
            val storePass = System.getenv("UPLOAD_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
            val alias = System.getenv("UPLOAD_KEY_ALIAS")?.takeIf { it.isNotBlank() }
            val keyPass = System.getenv("UPLOAD_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

            val anySet = keystorePath != null || storePass != null || alias != null || keyPass != null
            val allSet = keystorePath != null && storePass != null && alias != null && keyPass != null

            if (anySet && !allSet) {
                error(
                    "Partial upload-keystore configuration. Set all of UPLOAD_KEYSTORE_FILE, " +
                        "UPLOAD_KEYSTORE_PASSWORD, UPLOAD_KEY_ALIAS, UPLOAD_KEY_PASSWORD — or none, " +
                        "to leave the release signing config empty (bundleRelease will then fail).",
                )
            }

            if (allSet) {
                val keystore = file(keystorePath!!)
                check(keystore.exists()) {
                    "UPLOAD_KEYSTORE_FILE is set but does not exist: ${keystore.path}"
                }
                storeFile = keystore
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-debug.pro",
            )
            applicationIdSuffix = ".debug"
            // Debug-only so the per-developer App Check token doesn't
            // end up readable in any release artefact. Empty on CI
            // builds; Settings → Voice hides the row when empty.
            buildConfigField(
                "String",
                "APP_CHECK_DEBUG_TOKEN",
                "\"$appCheckDebugToken\"",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // HiveMQ MQTT Client pulls in six Netty submodules (netty-buffer,
            // netty-codec, netty-handler, netty-resolver, netty-transport,
            // netty-transport-native-unix-common), each of which ships its
            // own META-INF/INDEX.LIST + io.netty.versions.properties. AGP's
            // resource merger refuses to pick a winner; exclude the duplicate
            // metadata since neither file is referenced at runtime.
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // PNGs land *under source control* at app/snapshots/ so GitHub
            // renders image diffs natively in PR "Files changed" view — no
            // artifact download required to see what changed. CI commits any
            // new/updated PNGs back to the PR branch (see ci.yml).
            it.systemProperty(
                "roborazzi.output.dir",
                file("snapshots").absolutePath,
            )
            // Always (re-)record snapshots when tests run. The diff gate is "the
            // PNGs in the repo changed and CI committed an update" — reviewers
            // see the pixel diff directly in the PR. When the catalogue is
            // mature, switch to verifyRoborazzi* on stable surfaces for a
            // hard-fail regression gate.
            it.systemProperty("roborazzi.test.record", "true")
        }
        // Required because kotlinx-coroutines-android is on the unit-test classpath
        // (transitively via androidx.lifecycle). Without this, AndroidDispatcherFactory
        // calls the unmocked Looper.getMainLooper() and throws "not mocked", which
        // poisons MainDispatcherLoader for the rest of the JVM. With defaults enabled,
        // getMainLooper() returns null, AndroidDispatcherFactory fails cleanly, and
        // Dispatchers.setMain installs a TestMainDispatcher as expected.
        unitTests.isReturnDefaultValues = true
        // Roborazzi/Robolectric need real Android resources (drawables, strings,
        // themes) on the unit-test classpath to render Compose previews to PNG.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // Some androidx lint checks ship compiled against a newer Kotlin
        // Analysis API than AGP 8.7.3's bundled lint provides, so they throw
        // IncompatibleClassChangeError ("Found class
        // KaCallableMemberCall, but interface was expected") mid-analysis and
        // abort the whole run before any real issue is reported — a binary
        // incompatibility between the detector jars and the lint binary, not a
        // code problem. AGP's own crash message recommends disabling the
        // offending detectors. Disable the ones that crash here:
        //   NullSafeMutableLiveData      — androidx.lifecycle 2.8.7
        //   FrequentlyChangingValue      — Compose runtime
        //   RememberInComposition        — Compose runtime
        //   AutoboxingStateCreation      — Compose runtime
        //   AutoboxingStateValueProperty — Compose runtime
        // Drop these once an AGP / androidx bump realigns the analysis API.
        disable += setOf(
            "NullSafeMutableLiveData",
            "FrequentlyChangingValue",
            "RememberInComposition",
            "AutoboxingStateCreation",
            "AutoboxingStateValueProperty",
        )
    }
}

// AGP 9 provides Kotlin support built-in (the standalone kotlin-android plugin
// is gone), so Kotlin compiler options move here from the old `kotlinOptions`
// block inside `android { }`.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

// ----------------------------------------------------------------------------
// Widget picker previews -> generated res drawables copied from snapshots
// ----------------------------------------------------------------------------
// The launcher widget picker shows android:previewImage for each widget (see
// res/xml/*_widget_info.xml). Rather than ship a hand-drawn glyph or a second
// committed copy of each render, we reuse the Roborazzi snapshots the test
// suite already produces under app/snapshots/ as the real preview art. Those
// live outside res/ (not a resource dir), so a task copies them into a
// generated res/drawable-nodpi under valid drawable resource names. The
// snapshot stays the single source of truth -- when the regen bot updates a
// snapshot the preview follows automatically; nothing to keep in sync by hand,
// and no duplicate PNG bytes in git.
//
// Wired into AGP's resource pipeline via onVariants + addGeneratedSourceDirectory
// rather than a configuration-time copy under build/: AGP owns the task's output
// dir and orders it after `clean`, so a clean build
// (`./gradlew clean :app:assembleDebug`) regenerates the drawables before
// resource merging instead of failing on missing @drawable/widget_preview_*.

// previewImage drawable name -> source snapshot under app/snapshots.
val widgetPreviewMapping = mapOf(
    "widget_preview_outfit" to "widget_today_tshirt_shorts",
    "widget_preview_feels_like" to "feels_like_widget_today",
    "widget_preview_feels_like_week" to "feels_like_widget_week",
    // All-indicators strip (temperature / humidity / wind / UV) so the
    // conditions widget's picker preview shows its full range of icons.
    "widget_preview_conditions" to "conditions_strip_all_indicators_light",
)

abstract class GenerateWidgetPreviewsTask : DefaultTask() {
    // The source snapshot PNGs. NAME_ONLY: only the file name + contents matter
    // for up-to-date checks, not the absolute path, so the cache survives a
    // checkout in a different directory.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sourceSnapshots: ConfigurableFileCollection

    // drawable resource name -> source snapshot base name (no extension).
    @get:Input
    abstract val previewToSnapshot: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val drawableDir = outputDir.get().dir("drawable-nodpi").asFile
        // Start clean so a renamed/removed mapping can't leave an orphan behind.
        drawableDir.deleteRecursively()
        drawableDir.mkdirs()
        val byName = sourceSnapshots.files.associateBy { it.name }
        previewToSnapshot.get().forEach { (drawable, snapshot) ->
            val src = byName["$snapshot.png"]
                ?: error(
                    "Widget preview source snapshot missing: $snapshot.png -- " +
                        "run the snapshot tests to regenerate it.",
                )
            src.copyTo(File(drawableDir, "$drawable.png"), overwrite = true)
        }
    }
}

androidComponents {
    onVariants { variant ->
        val taskName = "generate${variant.name.replaceFirstChar { it.uppercase() }}WidgetPreviews"
        val generate = tasks.register<GenerateWidgetPreviewsTask>(taskName) {
            previewToSnapshot.set(widgetPreviewMapping)
            sourceSnapshots.from(
                widgetPreviewMapping.values.map { file("snapshots/$it.png") },
            )
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            generate,
            GenerateWidgetPreviewsTask::outputDir,
        )
    }
}

dependencies {
    // Force Guava off the vulnerable 31.1-android that firebase-analytics pulls
    // transitively (via play-services-measurement, which still pins it even in
    // its latest release). Clears CVE-2023-2976 / GHSA-7g45-4rm6-3mm3 (moderate:
    // FileBackedOutputStream temp-dir disclosure, fixed in 32.0.0). The app
    // doesn't use that API, so this is alert hygiene, not a behavior change.
    constraints {
        implementation(libs.guava)
    }

    implementation(project(":core:domain"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.vico.compose.m3)
    // Drag-to-reorder for the Settings → Display "Home screen layout" list.
    implementation(libs.reorderable)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)

    // Encrypted on-device storage of the user's Gemini API key.
    // Tink wraps an Android-Keystore master key around a Tink AEAD keyset; ciphertext
    // is persisted in DataStore Preferences. EncryptedSharedPreferences is deprecated.
    implementation(libs.tink.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)

    // Home-screen App Widget — Glance speaks Compose at the source level but
    // emits RemoteViews under the hood, so there's no separate XML layout to
    // maintain.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Ktor with the OkHttp engine for production HTTP. Tests in :core:data use MockEngine,
    // so the engine choice is :app's concern.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Ktor CIO server for the local phone-pairing HTTP endpoint plus the
    // Cast media-hosting endpoint (the receiver fetches PNG + WAV from a
    // tiny LAN server on the phone — Cast doesn't accept sender-pushed bytes).
    implementation(libs.ktor.server.cio)

    // Google Cast — adds the route discovery / session APIs (via
    // androidx.mediarouter) and RemoteMediaClient. Initialised lazily in
    // ClothesCastApplication; the call is wrapped in try/catch so non-Play
    // devices and Cast-less emulators don't crash on startup.
    implementation(libs.play.services.cast.framework)

    // QR code generation: encodes the pairing URL into a BitMatrix that we render
    // as an Android Bitmap. Pure-Java, no Android SDK dependency.
    implementation(libs.zxing.core)

    // HiveMQ MQTT Client — drives the optional Smart Home bridge that publishes
    // the rendered insight prose to a user-hosted MQTT broker so Home Assistant
    // (or any MQTT consumer) can speak it on a sensor trigger. Pulled into :app
    // because the bridge is an Android-side feature that piggybacks on the
    // existing twice-daily refresh; :core stays pure-Kotlin and broker-free.
    implementation(libs.hivemq.mqtt.client)

    // Play in-app updates. Used to check whether a newer build is on the Play
    // Store and to drive the FLEXIBLE update flow (background download +
    // "Restart" confirmation) from the Today-screen banner. Returns
    // UPDATE_NOT_AVAILABLE for non-Play installs, but we still gate the call on
    // PackageManager.getInstallSourceInfo to skip the round-trip on F-Droid /
    // sideloaded builds.
    implementation(libs.play.app.update.ktx)

    // Firebase Analytics + Crashlytics. The BoM aligns SDK versions; the
    // dependencies compile and link without google-services.json, but
    // FirebaseApp.initializeApp() needs config from the gms plugin's
    // generated resources to actually connect at runtime — see the
    // conditional plugin apply at the top of this file. The Telemetry
    // controller guards initialization on FirebaseApp.getApps(...) so the
    // SDK calls aren't reached when Firebase didn't init.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    // App Check (Play Integrity in release, Debug provider in debug) +
    // anonymous Auth: the TTS proxy gates on a valid App Check token and
    // counts usage per anonymous Firebase Authentication uid (verified
    // server-side, so the client can't spoof its quota bucket). Installations
    // stays for the migration window — the client sends the legacy FID
    // alongside the ID token so a not-yet-redeployed function keeps working;
    // it's dropped once old app versions age out. All no-op on builds
    // assembled without google-services.json — FirebaseApp doesn't init, the
    // planner falls back to BYOK semantics, and the rest of the app
    // continues to assemble and run.
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.installations)
    // `.await()` on the App Check token / anonymous sign-in / ID token Tasks.
    // Production (not test-only) because the planner runs in the main coroutine flow.
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // Gradle 9 no longer injects the JUnit Platform launcher onto the test
    // runtime classpath; declare it explicitly (version from the junit-bom).
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.datastore.preferences.core)
    // TestListenableWorkerBuilder + WorkManagerTestInitHelper for unit-testing
    // FetchAndNotifyWorker's doWork() path and its enqueue companion methods.
    testImplementation(libs.androidx.work.testing)
    // SettingsViewModelTest stubs the geocoding client with a Ktor MockEngine so the
    // tests don't need network. Same library that :core:data already uses.
    testImplementation(libs.ktor.client.mock)

    // Snapshot rendering of @Preview composables to PNG. Robolectric provides the
    // Android runtime; Roborazzi captures the rendered Surface; the JUnit 4 +
    // ui-test-junit4 stack is what AndroidJUnit4 needs. Vintage engine bridges
    // the JUnit 4 tests onto the existing JUnit 5 platform so they run alongside
    // the rest of the unit tests in the same `:app:testDebugUnitTest` task.
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.register<Exec>("installAndRun") {
    dependsOn("installDebug")
    commandLine(
        "adb", "shell", "am", "start",
        "-n", "app.clothescast.debug/app.clothescast.MainActivity",
    )
}

configurations.matching { it.name.startsWith("test") || it.name.startsWith("debugUnitTest") }.all {
    // kotlinx-coroutines-android arrives transitively via androidx.lifecycle. Its
    // AndroidDispatcherFactory is the highest-priority MainDispatcherFactory on the
    // service-loader, and it calls Looper.getMainLooper() at static init time —
    // which throws "not mocked" against the AGP stub Android jar and poisons
    // MainDispatcherLoader for the rest of the JVM, breaking every coroutine test
    // that touches Dispatchers.setMain. Excluding it from the test classpath only
    // leaves kotlinx-coroutines-test, whose TestMainDispatcherFactory then wins the
    // service-loader race cleanly. Production code is unaffected; the runtime
    // classpath still has it (via implementation deps).
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-android")
}

