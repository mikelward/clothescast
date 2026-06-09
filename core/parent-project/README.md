# Why this directory exists

This is the project directory for the synthetic intermediate `:core` project
in the **inner** (sandbox-fallback) Gradle build rooted at `core/`.

`core/settings.gradle.kts` includes `:core:domain` and `:core:data` so module
paths match the outer build, which implicitly creates a parent `:core`
project. Gradle 9 requires every project to have an existing directory and
forbids two projects sharing one, so the parent can point neither at the
nonexistent `core/core/` (its default) nor at `core/` itself (the inner
build's root project). It points here instead.

No build script lives here on purpose — the `:core` project carries nothing.
