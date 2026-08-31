@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // mikelward/androidlog publishes its own Maven repository into the
        // `maven` branch of that repository, which raw.githubusercontent.com
        // serves. A Maven repository is a static directory tree over HTTP, so
        // there is no third party in the trust path -- and nothing here that
        // is not already public.
        //
        // Scoped to that one group. Without `includeGroup` this repository
        // would be consulted for every unresolved coordinate in the build,
        // which is slow and widens what a compromised branch could answer.
        maven {
            name = "androidlog"
            url = uri("https://raw.githubusercontent.com/mikelward/androidlog/maven")
            content { includeGroup("com.mikelward.androidlog") }
        }
    }
}

// mikelward/androidlog is resolved from the repository declared above, by
// version, like any other dependency -- see gradle/libs.versions.toml.
//
// OPTING IN to a local checkout instead: `-PandroidlogLocal`, or
// `androidlogLocal=true` in a local gradle.properties. That wires the checkout
// in as a composite build, and Gradle substitutes it for the coordinate before
// anything resolves remotely -- the fast edit-there-rebuild-here loop, still
// worth having when changing both repositories at once.
//
// It is OFF by default, and deliberately keyed on a property rather than on
// the directory merely existing. A composite build puts androidlog's toolchain
// alongside this one in a single Gradle invocation, and AGP's
// `AgpVersionCompatibilityRule` refuses to compare two versions at all -- 9.3.1
// against 9.3.2 fails to configure. Taking the coordinate is what ended that
// lockstep; a sibling clone left over from working on androidlog itself must
// not quietly reinstate it.
// Read as a BOOLEAN, not merely as present (Codex, PR #1184). `isPresent` is
// true for any value at all, so `androidlogLocal=false` -- the obvious way to
// write "off" in a gradle.properties -- would have turned the composite ON and
// reinstated the very lockstep this guards against. An unrecognized value is an
// error rather than a guess: the dangerous direction here is silently enabling.
val androidlogLocal = providers.gradleProperty("androidlogLocal").orNull?.let { raw ->
    when (raw.trim().lowercase()) {
        // Gradle hands `-PandroidlogLocal` with no value over as an empty
        // string, and that bare form is the one the docs above name.
        "", "true" -> true
        "false" -> false
        else -> error("androidlogLocal must be true or false (or bare), not \"$raw\"")
    }
} ?: false

if (androidlogLocal) {
    val androidlog = listOf(file(".androidlog"), file("../androidlog"))
        .firstOrNull { it.isDirectory }
        ?: error(
            "androidlogLocal is set but no checkout was found: " +
                "git clone https://github.com/mikelward/androidlog ../androidlog, " +
                "or drop the property to resolve the published version"
        )
    includeBuild(androidlog)
    logger.lifecycle("androidlog: using the local checkout at $androidlog")
}

rootProject.name = "clothescast"

include(":core:domain")
include(":core:data")
include(":app")
