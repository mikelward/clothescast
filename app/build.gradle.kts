plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    compileSdk = 35

    defaultConfig {
        applicationId = "app.clothescast"
        // Sourced from the version catalog so the same number drives both the
        // install-time gate (this line) and BuildConfig.MIN_SDK_VERSION below
        // — see the catalog entry for why the runtime guard exists.
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = 35
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
        buildConfigField("String", "GIT_BRANCH", "\"${gitBranch.replace("\"", "\\\"")}\"")
        buildConfigField("String", "GIT_SHA", "\"$gitShortSha\"")
        buildConfigField("boolean", "GIT_DIRTY", gitDirty.toString())
        buildConfigField("long", "BUILD_TIMESTAMP_MS", "${buildTimestampMs}L")
        // Mirror of `minSdk` above. The Telemetry controller reads this to
        // silence Firebase when SDK_INT < MIN_SDK_VERSION so APKs that
        // somehow land on sub-minSdk devices don't pollute Crashlytics /
        // Analytics with a config the app was never built for.
        buildConfigField("int", "MIN_SDK_VERSION", libs.versions.minSdk.get())
    }

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

    kotlinOptions {
        jvmTarget = "21"
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
}

dependencies {
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

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
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

