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
    }
}

// mikelward/androidlog — the shared debug log, tracked @main. There is no
// version to bump: a composite build substitutes the local checkout for the
// coordinate before anything resolves remotely.
//
// CI checks it out into `.androidlog/`; locally it is a sibling clone, which
// the session-start hook makes. Failing loudly beats a build that silently
// resolves `0.0` from a repository and gets a 404 twenty lines later.
val androidlog = listOf(file(".androidlog"), file("../androidlog")).firstOrNull { it.isDirectory }
    ?: error(
        "androidlog not found — git clone https://github.com/mikelward/androidlog ../androidlog"
    )
includeBuild(androidlog)

rootProject.name = "clothescast"

include(":core:domain")
include(":core:data")
include(":app")
