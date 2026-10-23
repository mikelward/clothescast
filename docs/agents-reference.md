# Agent reference notes

Background and reference material moved out of `AGENTS.md`, which every agent
session loads whole and which must stay under Antigravity's 24,000-byte rule
cap. `AGENTS.md` keeps the rules; this file keeps the why and the numbers.

## Firebase project

Production is `clothescast-ecce9`: substitute it for `<projectId>` in the
deploy, bucket and log commands (`functions/README.md`).

## Sandbox testing

- **Full parity (expected).** Google Maven hosts allowlisted and the Android
  SDK under `/opt/android-sdk` (`ANDROID_HOME` set). Run what CI runs:
  ```
  ./gradlew :core:domain:test :core:data:test :app:testDebugUnitTest
  ./gradlew :app:assembleRelease
  ```
  Snapshot diffs, Compose previews, Roborazzi — everything works.
- **Verify before assuming either way.** Sandbox config drifts, new forks
  inherit defaults, and a confidently stated premise (including the one in
  `AGENTS.md`) is easy to believe:
  ```
  curl -s -o /dev/null -w "%{http_code}\n" --max-time 5 https://maven.google.com/
  curl -s -o /dev/null -w "%{http_code}\n" --max-time 5 https://dl.google.com/
  command -v sdkmanager >/dev/null && echo "sdkmanager OK" || echo "no SDK"
  ```
  200 / 302 / 404 from Google Maven means reachable; 403 from the
  `sandbox-egress-production TLS Inspection CA` issuer means blocked. A missing
  `sdkmanager` (or no `/opt/android-sdk`) means the SessionStart hook didn't
  run: `:app` tasks won't work, `:core:*` tasks still will.
- **Inner build (fallback, and faster for core work).** `core/settings.gradle.kts`
  is a second Gradle root for the pure-Kotlin modules using Maven Central and
  the Gradle Plugin Portal only — no AGP, no Google Maven:
  ```
  cd core && ../gradlew :core:domain:test :core:data:test
  ```
  Project paths match the outer build, so module scripts work unchanged in
  both. Cold first run ~1m10s; warm runs reuse the daemon. It skips AGP
  configuration, so it's the quickest signal while iterating on
  `:core:domain` / `:core:data` even when the outer build works.
- **Allowlist.** At minimum `maven.google.com` (AGP, androidx, Firebase Gradle
  plugins) and `dl.google.com` (SDK platforms / build-tools via `sdkmanager`).
  `firebase.google.com` and `firebaseinstallations.googleapis.com` are runtime
  hosts the build doesn't need.

## CI

- **Why the release build runs in parallel.** The bundle used to be a step
  inside `deploy`, so its ~6 min started only after the tests finished — pure
  critical path for work that depends on nothing the tests produce. R8 is
  ~150s of that build's ~183s (measured locally 2026-08-29), so sharing
  compiles between jobs wins nothing; taking the whole job off the critical
  path is the win.
- **Job timings, measured 2026-08-29** from `main` run 33232048288 (the first
  run with the release build split out of `deploy`) and PR run 33231688207.
  Whole-job wall clock, dominant step in parentheses. A sanity check, not a
  live baseline — compare against a real recent run.
  - `JVM unit tests` — 3m33s on `main` (`Run unit tests` 2m17s), 3m45s on a
    PR. Was 5m23s on `main` with a 3m59s step on 2026-08-27; the alarm-test
    gate change is the difference.
  - `Build the release AAB` — 6m13s on `main` (`Bundle release AAB` 5m25s).
    `main`-only; reports `skipped` on a PR. The critical path for a `main` run.
  - `Android release check` — 48s on `main` (gradle step skipped, setup only).
    7m07s on the PR run above, against 5m00s on 2026-08-27 — one cold-cache
    sample on a branch whose previous run was canceled mid-build; a number to
    watch, not a confirmed regression.
  - `Distribute and release` — 21s on `main`, a **no-publish sample**: every
    commit in that push carried a filtered prefix, so `RELEASE_NOTES_SKIP=true`
    skipped the Play upload (`PLAY_AVAILABLE` was `true`). Was 6m45s when this
    job still built the AAB. The publish itself is unmeasured on the new
    shape; expect it to be legitimately longer than 21s.
  - Whole `main` run: **6m56s**, down from 14m59s on 2026-08-28 and 12m29s on
    2026-08-27.

  **Re-date this list when you refresh it.** Undated figures drifted out of
  true once and cost an agent a wrong "significant regression" call against a
  run that was in fact slightly faster than baseline.
- **Snapshot diff comment.** On every same-repo PR run, the "Post snapshot
  image diffs as a PR comment" step in `ci.yml` diffs `app/snapshots/` against
  the merge-base and upserts one `<!-- ui-snapshot-diffs -->`-marked comment
  embedding each affected image, SHA-pinned, before/after side by side for
  modified files. It exists because the GitHub mobile app shows "Binary files
  not rendered" for PNG diffs (the bot's `ci: regenerate UI snapshots` commits
  included), while markdown-embedded images render. Hand-posted comments
  suffered typo'd / stale-SHA URLs and are retired; a fork PR, where CI can't
  comment, still gets one by hand in the same format:
  `![label](https://github.com/<owner>/<repo>/raw/<sha>/<path>.png)`, before +
  after labeled by SHA for modified files, just the new image for added ones.

## Kotlin / Compose

- **Kotlin `lowercase()` / `uppercase()`.** Unlike Java's `toLowerCase()`, the
  no-argument stdlib overloads fold with the invariant locale, so they aren't
  subject to the Turkish dotless-ı trap: `code.lowercase()` on an ISO country
  code is correct, and `lowercase(Locale.ROOT)` is a no-op (sites that pass it
  are documenting, not changing behavior). The explicit `lowercase(locale)`
  overload is the locale-sensitive one, for user-visible text only (see
  `InsightFormatter.decapitalize`). It has been mistakenly "fixed" before.
- **Rasterizing Compose off-screen** (e.g. for a Glance widget, which emits
  RemoteViews and can't host Compose/Vico). A detached `ComposeView` with
  `setContent` + `measure`/`layout`/`draw` composes but yields a blank bitmap.
  It needs a real window: host it in a `Presentation` on a `VirtualDisplay`
  backed by an `ImageReader`, set the ViewTree lifecycle / savedstate /
  viewmodel owners, `show()`, and sample the reader until the frame settles —
  Vico and other async content draw a few frames after first composition, so
  capturing early misses the chart. See `widget/ComposeRender.kt`.

## Cursor Cloud

- The update script installs the Android SDK idempotently at
  `/opt/android-sdk` (command-line tools + `platforms;android-35` +
  `build-tools;35.0.0`). `ANDROID_HOME` / `ANDROID_SDK_ROOT` are exported via
  `~/.bashrc`; source it or `export ANDROID_HOME=/opt/android-sdk` if a shell
  hasn't picked it up. All three modules build and test there with the same
  commands as CI.
- Lint: `./gradlew :app:lintDebug`. No `ktlint` plugin is wired, so Kotlin
  style checks don't go beyond compiler warnings.
- Client-only Android app: no backend server, database, or containers. It
  calls Open-Meteo (free, keyless) and optionally Gemini (BYOK); testing is
  JVM-based.
