// Sandbox-friendly settings root for the pure-Kotlin core modules.
//
// The outer `clothescast` build pins AGP and Firebase Gradle plugins at its
// root, and resolving those plugin markers requires Google's Maven repo. In
// environments where Google Maven is unreachable (e.g. agent sandboxes
// behind an egress allowlist), the outer build can't load — even for
// `:core:domain:test`, which has no Android dependencies of its own.
//
// This inner settings file exposes the same `:core:domain` / `:core:data`
// projects rooted under `core/`, but pulls plugins from only Maven Central
// and the Gradle Plugin Portal. Invoke it from this directory:
//
//   cd core && ../gradlew :core:domain:test :core:data:test
//
// CI keeps using the outer build; this is purely an offramp for sandboxes
// and for developers who want to iterate on pure-Kotlin modules without
// configuring AGP. The project paths intentionally match the outer build
// (`:core:domain`, `:core:data`) so `project(":core:domain")` references
// in module build scripts resolve identically in both contexts.
@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "core"

include(":core:domain")
include(":core:data")
// The intermediate ":core" project exists only to give the leaf projects
// their outer-build paths. Its default directory would be `core/` relative
// to this settings root — i.e. the nonexistent `core/core/` — which Gradle 9
// rejects at configuration time ("Configuring project ':core' without an
// existing directory is not allowed"); it can't share this root directory
// either ("Multiple projects in this build have project directory"). Park it
// in a dedicated empty directory — it carries no build script either way.
project(":core").projectDir = file("parent-project")
project(":core:domain").projectDir = file("domain")
project(":core:data").projectDir = file("data")
