# Agent guide for clothescast

Rules and gotchas for AI coding agents (Claude Code, Codex, etc.) working in
this repo. Keep this file short and concrete — one-liners over essays. Add a
new rule the first time something bites you, not the third.

## Working in this repo

- This repo is an Android app (Kotlin + Compose) with two pure-Kotlin
  sub-modules: `:core:domain` and `:core:data`. Both compile and test on a
  JVM without the Android SDK; `:app` needs AGP loadable, Android Maven
  artifacts reachable, and the Android SDK installed.
- **The Claude Code on the web sandbox for this project is configured to
  allowlist Google's Maven hosts**, so AGP + androidx + Firebase Gradle
  plugins + the Android SDK (via `sdkmanager`) are all expected to be
  reachable. When they are, the full outer `./gradlew` workflow works
  in-sandbox — same commands as CI. See "Sandbox testing" below for how
  to verify and what to do if you find them blocked.
- If for any reason `:app:assembleDebug` or `:app:testDebugUnitTest` won't
  run in your environment, **say so explicitly** and rely on CI as the
  validation surface. Do not claim "the build passes" when you only ran
  the core tests.

## Sandbox testing

- **Expected path — full parity, outer build.** The intended sandbox
  state for this project is: Google Maven hosts allowlisted and the
  Android SDK installed under `/opt/android-sdk` (`ANDROID_HOME` set).
  When that's true, run the same commands CI runs:
  ```
  ./gradlew :core:domain:test :core:data:test :app:testDebugUnitTest
  ./gradlew :app:assembleDebug
  ```
  Snapshot diffs, Compose previews, Roborazzi, everything.
- **Verify before assuming either way.** Sandbox config drifts; new forks
  inherit defaults; agents are easily fooled by a confidently-stated
  premise. Spend ten seconds confirming:
  ```
  curl -s -o /dev/null -w "%{http_code}\n" --max-time 5 https://maven.google.com/
  curl -s -o /dev/null -w "%{http_code}\n" --max-time 5 https://dl.google.com/
  command -v sdkmanager >/dev/null && echo "sdkmanager OK" || echo "no SDK"
  ```
  200 / 302 / 404 from Google Maven = reachable. 403 from the
  `sandbox-egress-production TLS Inspection CA` issuer = blocked, fall
  back to the inner build below. Missing `sdkmanager` (or
  `/opt/android-sdk` not present) = SessionStart hook didn't run or
  hasn't been wired; `:app` tasks won't work, but `:core:*` tasks
  still will via either build.
- **Fallback path — inner Gradle build, pure-Kotlin only.** If Google
  Maven is blocked or the SDK isn't installed, `:core:*:test` still
  works through a second Gradle root scoped to the pure-Kotlin modules:
  ```
  cd core && ../gradlew :core:domain:test :core:data:test
  ```
  This is enabled by `core/settings.gradle.kts`, which uses Maven Central
  and the Gradle Plugin Portal only — no AGP, no Google Maven. Project
  paths match the outer build (`:core:domain`, `:core:data`), so module
  build scripts work in both contexts unchanged. Cold first run is
  ~1m10s; warm runs reuse the daemon. `:app` is out of reach here —
  push and rely on CI for it.
- **Useful regardless of sandbox state — inner build is faster.** Even
  with the full outer build available, `cd core && ../gradlew :core:*:test`
  is the quickest signal for a pure-Kotlin core change because it skips
  AGP configuration entirely. Use it when iterating on `:core:domain` /
  `:core:data`; use the outer build when you need `:app` coverage or
  when CI fidelity matters.
- **If you find Google Maven blocked when it shouldn't be**, flag it to
  the user — sandbox config may have regressed. The current allowlist
  should cover at minimum `maven.google.com` (AGP + androidx + Firebase
  Gradle plugins) and `dl.google.com` (Android SDK platform / build-tools
  via `sdkmanager`). `firebase.google.com` and
  `firebaseinstallations.googleapis.com` are runtime hosts; the build
  itself doesn't need them.
- `:core:domain:test` alone is the minimum signal for any pure-Kotlin
  domain change, regardless of which path you take.

## Commits and PRs

- **Linear history.** Never merge — rebase. The repo's PRs land as a linear
  chain on `main`. A merge commit in a PR is a sign something went wrong.
- **One concern per PR.** If you're tempted to add infra (test framework, CI
  step, build wiring) alongside a feature, split it: infra PR first, feature
  PR rebased on top. Reviewers read smaller PRs faster and the feature PR's
  diff actually shows the feature.
- **Clean up unmerged commits before pushing for review.** Anything still
  on a feature branch (not yet merged to `main`) is scratch space — amend,
  squash, reorder, split, drop, rebase onto a different base as needed.
  Before each push, the branch's commit graph should read as the story
  you'd want a reviewer to see: review fixes squashed into the commit
  they fix, infra ahead of feature, no "fix typo" / "address review" /
  "wip" / "revert previous" / "lint" noise, no half-applied changes
  later undone. Iterating on the same branch? Reshape locally and
  force-push instead of piling fixup commits on the end. Default to
  squashing fixups into their parent unless the user has explicitly
  asked you to preserve a particular commit boundary. Only `main`'s
  history is sacred — feature branches stay malleable until they merge.
- **Stacked PRs:** the lower PR (infra) targets `main`; the upper PR
  (feature) targets the lower PR's branch. When the lower PR merges to
  `main`, rebase the upper one onto `main` — its diff cleanly shrinks to
  just the feature work.
- **Force-pushes are routine on feature branches** (per the rule above) and
  don't need confirmation. Do still confirm before anything destructive on
  shared / merged branches: force-pushing `main`, dropping commits already
  on `main`, rewriting another author's branch.

## Commit messages

- **Every commit subject ships verbatim to the Play Store changelog.** CI
  collects the subject line of *every* commit landed since the previous
  successful main CI run, formats them as `• `-prefixed bullets (oldest →
  newest), and writes the result to `whatsnew-en-US` — that's the "What's
  new" blurb internal testers and, eventually, production users see. So
  treat each subject as end-user copy: sentence case, no jargon, no scope
  prefix, ≤ ~80 chars. e.g. `Bigger outfit icons on the Today screen`,
  not `:app + :core — glanceable outfit-preview icons on Today screen`.
  Move scope, module names, and engineering detail (file paths, refactor
  reasoning, "fixes #123") into the commit body — reviewers see the body
  on the PR, but the Play release notes don't.
- **`ci:` / `test:` prefixes and docs-only commits are filtered out** of
  the changelog (see the "Prepare release notes" step in `ci.yml`) so the
  snapshot-regen bot's commits, test-only changes, and `docs/` / dotfile
  / `*.md` changes don't surface as bullets. Anything else *will* appear
  — if a commit shouldn't ship in the changelog, either squash it into a
  sibling or give it the appropriate prefix.
- **Test-only commits use a `test:` subject prefix.** A commit that
  touches only test sources / fixtures / snapshots (anything under
  `src/test/`, `src/androidTest/`, `app/snapshots/`, or equivalent —
  nothing shipped in the APK) ships no user-visible change, so it
  doesn't belong in the Play "What's new". Prefix the subject `test:`
  (e.g. `test: cover evening-rain fallthrough in InsightTextTest`) and
  CI will skip it the same way it skips `ci:`. Mixed commits (test +
  production code) stay un-prefixed and ship as normal bullets —
  squash the test work into the feature commit instead of splitting.
- **Play caps `whatsnew-en-US` at 500 bytes.** When the bullet list
  exceeds that, CI truncates and appends `…`. Avoid lining up a long
  stack of small commits if any one of them tells the user-facing story
  on its own — squash the supporting work in.

## GitHub

- Use the `mcp__github__*` MCP tools for *all* GitHub operations. The `gh`
  CLI is **not** available in this sandbox.
- Open PRs as **draft** by default. Un-draft only after CI is green and you
  (or the user) have eyeballed the change.
- **Keep PR title and body in sync with the branch on every push.** A PR's
  title and description are read as the canonical summary of what's
  landing; if the branch has grown, narrowed, or pivoted since the PR
  opened, the original text lies. After every push (force-push, follow-up
  commit, rebase-and-push) re-read the full branch diff vs. the base and
  update the title and body via `mcp__github__update_pull_request` so
  they describe the *current* state — not the state at PR creation.
  Subject line stays Play-Store-ready (sentence case, end-user copy, ≤
  ~80 chars, matches the squash-merge subject); body covers scope,
  rationale, privacy-relevant changes, and test plan for everything now
  in the diff. If nothing material changed, no update is needed — but
  check, don't assume.
- **Tidy the commit history before pushing for review.** Pair the
  title/body sync above with a commit-graph sync: squash fixups into
  their parent, drop "wip" / "lint" / "revert previous" noise, reorder
  so infra leads feature, and force-push the cleaned-up branch. The
  full rule lives under "Commits and PRs" — the short version is "what
  a reviewer sees on this push should be the story you want them to
  read, not your scratch work."
- **Never leave a review comment thread silently dismissed.** Either reply on
  the thread *or* resolve it — the user wants every thread to end in one of
  those two states, not "left open and ignored." When you think a comment is
  a false positive, say *why* on the thread (one or two sentences is fine):
  the reasoning is exactly what the user wants surfaced, and "Linux-only CI,
  doesn't apply" is more useful on the PR than buried in chat history.
  Acknowledgement noise ("good catch, will do") is fine and preferred over
  silence; the discipline is "say something or resolve," not "say nothing."
- **Always link every open PR in the stack.** Any time you push, summarise
  CI, or invite the user to review, list every currently-open PR on the
  feature by URL — one per line — not just the topmost one. The Claude Code
  mobile UI only renders the first PR card in a message and treats later
  links as plain text, so a single link can hide the rest of the stack
  (and may surface an already-merged PR while obscuring the live one).
  Worth the extra two lines.
- **Report when Copilot finishes reviewing a fresh push.** Copilot's
  review runs asynchronously after each push; once its review event lands
  for the latest commit, surface a one-liner naming the SHA and comment
  count — e.g. `Copilot reviewed 87d9f02 — 0 comments` or `Copilot
  reviewed 87d9f02 — 3 comments, addressing now`. Tie it to the *latest*
  pushed SHA so a stale review of a superseded commit isn't conflated with
  the current state. The user uses this to know when the automated pass
  is done vs. still pending.
- **Post a PR comment with image diffs inline whenever snapshots change.**
  The GitHub mobile app shows "Binary files not rendered" for any binary
  diff (added or modified), so PNG changes in the Files tab — including
  the bot's `ci: regenerate UI snapshots` commits — are invisible from
  the user's phone, and long-press doesn't expose an "Open in Browser"
  escape hatch on those links. After each regen commit (or any push that
  touches `app/snapshots/`), post one PR comment embedding each affected
  image as `![label](https://github.com/<owner>/<repo>/raw/<sha>/<path>.png)`.
  For modified files include both the previous and new versions labelled
  by SHA so the user can flip between them; for added files just the new
  one. Markdown-embedded images render fine in the mobile app even though
  file diffs don't. One comment per regen is enough — don't re-post if a
  later regen reverts the same bytes (the existing thread already shows
  both states).
- **Report Android versionCode after every merge to `main`.** When a PR
  merges, fetch `main` and run `git rev-list --count origin/main` to get
  the versionCode (`app/build.gradle.kts` derives it from this count).
  Report it as e.g. `Need versionCode 72 (b81c23d) or higher to test PR
  #52's HTTP-error surfacing` — number, short SHA, and a one-clause
  summary of what the change gates. The user uses this to know which
  Firebase / locally-built APK contains their fix.
  **Sandbox clones are usually shallow** (`git rev-parse --is-shallow-repository`
  returns `true`), which silently truncates `rev-list --count` and makes the
  reported number lower than the real APK's. Run `git fetch --unshallow origin
  main` once at the start of any session that will report versionCodes — the
  user has been bitten by an under-by-15 count.
- **Keep watching merged PRs for late review comments.** Reviewers and
  bots routinely comment *after* merge (Copilot review, human follow-up).
  Stay subscribed to the PR's activity after the merge and handle each
  new comment per the "say something or resolve" rule above — reply,
  resolve, or open a follow-up PR with the fix. Stop watching once every
  comment posted on or after the merge commit has been answered or
  resolved, or after ~24h of silence with no new activity, whichever
  comes first. Don't drop the watch the moment the merge button is
  clicked.

## CI

- Two jobs: `JVM unit tests` (~2m) runs `:core:*:test` + `:app:testDebugUnitTest`;
  `Android debug build` (~3.5m) runs `:app:assembleDebug`. Both upload
  artifacts; Roborazzi PNG snapshots upload as `ui-preview-snapshots` from
  the JVM-tests job.
- After pushing, **wait for CI** before claiming a change works on Android.
  The webhook subscription delivers events; don't poll.
- **Report significant CI timing regressions.** After CI finishes on a push,
  compare the new timings against recent runs of the same job. Only call
  out *significant* slowdowns (rule of thumb: >25% or >30s on a job under
  ~5min) — don't narrate routine wobble. When you do report one, name the
  likely cause: a new heavy dependency (Robolectric cold start, a
  build-tools download), a slow new test, cache invalidation. Spotting a
  real regression early lets the user decide whether to invest in
  mitigation before more tests pile on.

## Kotlin / Compose gotchas

- **`/*` inside KDoc opens a nested block comment.** Kotlin counts pairs, so
  a literal `/*` inside `/** … */` (e.g. a path like `dir/*.png`) opens an
  inner comment that the outer `*/` then closes — leaving the outer comment
  unterminated through to EOF. Compiler reports "Unclosed comment" at the
  end of the file, far from the actual cause. Avoid literal `/*` in doc
  text: write `dir/` or `<path>.png` instead of `dir/*.png`.
- **Compose `@Preview`s in tests.** Snapshot tests live in `app/src/test`
  (Roborazzi/Robolectric). Preview wrappers live in `app/src/main/.../*Previews.kt`
  with `internal` visibility so they're reachable from tests in the same
  module. Don't make screen-internal composables `public` just for tests —
  `internal` is enough.
- **JUnit 4 + JUnit 5 coexistence.** The repo uses the JUnit 5 platform
  (`useJUnitPlatform()`); Robolectric needs `@RunWith(AndroidJUnit4::class)`
  which is JUnit 4. The bridge is `junit-vintage-engine` as `testRuntimeOnly`.
  If you see "no tests found" after adding a `@Test`-annotated JUnit 4
  class, check that `vintage-engine` is on the test classpath.

## Privacy

- **Surface any change to what we send off device.** When a change touches
  data that crosses the device boundary — anything in the rendered insight
  prose (it's fed to Gemini TTS over BYOK keys),
  weather / geocoding requests, or future analytics / error reporting —
  call it out explicitly in the PR description and commit message. Calendar
  event titles, locations, contacts, identifiers: default is "less, not
  more" — if you're broadening what leaves the device, flag it for review
  even if it seems harmless. The TTS endpoints log requests; "the user's
  3pm standup" landing in someone's logs is exactly the surprise the user
  doesn't want.
- **Firebase Analytics + Crashlytics: hard payload limits.** PRIVACY.md
  spells out what may / may not appear in Firebase payloads — calendar
  data, location, insight prose, notification text, API keys, precise
  GPS, and ad identifiers are all out of scope. Do **not** call
  `FirebaseCrashlytics.setCustomKey(...)` with any of those, and don't
  feed user content into Analytics event params either. The runtime
  toggle (`SettingsRepository.setTelemetryEnabled`) controls *whether*
  Firebase reports; that contract controls *what's in* a report.
- **`google-services.json` is per-developer / per-environment.** It's in
  `.gitignore` and never checked in. The Firebase Gradle plugins in
  `app/build.gradle.kts` are applied conditionally on the JSON's
  presence — CI builds without it still assemble (Telemetry no-ops at
  runtime via `FirebaseApp.getApps(...).isEmpty()`). To enable Firebase
  on a build: create / configure your Firebase project against
  applicationId `app.clothescast`, download `google-services.json`,
  drop it at `app/google-services.json`, and rebuild. Don't commit it.

## Domain conventions

- Clothes rules and outfit suggestions both look at *feels-like*
  temperatures (apparent, wind-chill / humidity adjusted) — never raw 2 m
  air temperature. That's what the user actually experiences stepping
  outside.
- **Per-model rain doesn't pick clothes, but it does mention rain.** The
  morning insight's evening tie-in clause has two emission paths. If the
  user's clothes rules fire for the evening window (e.g. it'll be cold
  enough for a jacket), the clause names the item *and* folds in the
  per-model rain time when one's detected: "Bring a jacket tonight, rain
  at 9pm." If no clothes rule fires but a per-model series spots rain ≥
  30% in the tonight window and the user has an evening event with a
  location, the clause still emits — without recommending clothes —
  as a bare rain warning ("Rain tonight at 9pm." / "Chance of rain
  tonight at 9pm." for the POSSIBLE tier). The principle: we don't
  recommend clothes the user hasn't asked for (an umbrella isn't a
  default, see the comment on `ClothesRule.DEFAULTS`), but we *do*
  surface rain when a model spots it — staying silent on evening rain
  because no rule happened to trigger is exactly the case the per-model
  tier exists to catch.
- The `:app` module owns Android concerns; LLM choice (which Gemini model
  to call) is `:app`'s problem. The `:core:domain` module is pure Kotlin
  and must stay that way — it's where the clothes / insight logic lives
  and must remain testable on a JVM.
- **Don't rename Gemini models from web-search guesses.** When a TTS / text
  model ID seems "deprecated" or "promoted to GA", verify against the live
  `ListModels` endpoint (`GET /v1beta/models?key=…`) before changing
  defaults — search snippets routinely fabricate confident-sounding GA
  names (`gemini-2.5-flash-tts`) for models that only exist as previews
  (`gemini-2.5-flash-preview-tts`). PR #59 → #60 was a same-day
  rename-and-revert because of this.

## Cursor Cloud specific instructions

- **Android SDK is installed** at `/opt/android-sdk`. The update script
  installs it idempotently if missing (command-line tools + platforms;android-35
  + build-tools;35.0.0). `ANDROID_HOME` / `ANDROID_SDK_ROOT` are exported via
  `~/.bashrc`; source it or `export ANDROID_HOME=/opt/android-sdk` before
  running Gradle if your shell hasn't picked it up.
- **All three modules build and test on this VM.** Unlike the AGENTS.md note
  about agent sandboxes lacking the SDK, the Cursor Cloud VM has it. You can
  run the full CI-equivalent locally:
  ```
  export ANDROID_HOME=/opt/android-sdk
  ./gradlew :core:domain:test :core:data:test :app:testDebugUnitTest
  ./gradlew :app:assembleDebug
  ```
- **Pre-existing flaky test:** `SettingsViewModelTest > initial state reflects
  repository defaults` may fail with `expected:<CELSIUS> but was:<FAHRENHEIT>`
  depending on the JVM's default locale. This is a pre-existing issue, not
  caused by your changes. CI passes this test because the GitHub runner locale
  defaults differently.
- **Android Lint is available** for the `:app` module via AGP; run
  `./gradlew :app:lintDebug` when app-side validation needs lint coverage.
  There is no separate `ktlint` plugin wired, so Kotlin style checks are not
  available beyond compiler warnings.
- **This is a client-only Android app.** No backend server, database, or
  Docker containers to start. The app calls Open-Meteo (free, keyless) and
  optionally Gemini (BYOK). Testing is purely JVM-based.
- **`CLAUDE.md` is a symlink** to `AGENTS.md` — editing either edits both.
