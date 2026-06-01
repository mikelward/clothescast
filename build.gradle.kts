// Plugins are declared with `apply false` at the root so subprojects share a
// single version per plugin. Without this, two modules each pulling in the
// Kotlin Gradle Plugin via their own plugins {} block fail with "plugin
// already on the classpath with an unknown version". The :core:* modules apply
// kotlin.jvm; :app gets Kotlin from AGP 9's built-in support (no standalone
// kotlin-android plugin), and layers kotlin.compose + kotlin.serialization on top.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.roborazzi) apply false
    // Firebase plugins. Loaded onto the classpath here but only applied in
    // :app, and there only when app/google-services.json is present — CI
    // builds without the JSON still assemble. See app/build.gradle.kts.
    alias(libs.plugins.gms.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}
