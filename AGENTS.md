---
trigger: always_on
alwaysApply: true
last_modified: 2026-10-17
---

# Agent guide for clothescast

`CLAUDE.md` and `GEMINI.md` are symlinks to this file. Edit `AGENTS.md` and bump
`last_modified`.

**At the start of every session, print the full absolute path of the `AGENTS.md` you
loaded and its `last_modified` date**, so a stale or wrong copy is caught first.

ClothesCast: Android app (Kotlin + Compose), `:app` plus pure-Kotlin `:core:domain` and
`:core:data` (JVM-testable without the SDK).

Keep this file short — one-liners over essays, **under 24,000 bytes** (`wc -c AGENTS.md`):
Antigravity caps rule files at 24 KB and silently truncates the tail. State the rule and
the failure it prevents; incident narratives belong in commits, background and numbers in
`docs/agents-reference.md`. Add a rule the first time something bites, rewrite/trim rather
than append, and delete rules that stopped biting.

## Talking to the user

- **One question at a time** — in plain chat or the client's question tool
  (`AskUserQuestion` in Claude Code, `ask_question` in Antigravity). Stop and wait for the
  answer; don't proceed on an assumed one, pick a "recommended" option yourself, or keep
  working on what it affects. Never ask while the user is typing.
- **Answer a mid-turn message first**, before any further tool call (except merge cues,
  whose hygiene runs first and whose announced switch is the answer).
- **Message only when the user needs to see it** (maintainer, 2026-10-15): each notifies
  their phone. Work in silence; speak up for a question/blocker, something to merge or act
  on, a merge, an autopilot decision (to review when seen, never waited on), or a change
  from the plan, `SPEC.md` or their instructions (review-prompted included). Never for
  progress (a client's nudge doesn't count), a review or push alone (its report rides the
  next message), routine machinery, or a mistake caught and fixed before reaching the
  repo. The session-start `AGENTS.md` line still opens every session.
- **Keep replies short**: lead with the most important point; ask before unloading more.
- **End the turn by restating any pending decision** — the last line is the question you
  are waiting on (or an autopilot guess awaiting review), written out, every turn until
  answered. Nothing pending, no line. A scheduled check that finds nothing new re-arms
  silently and sends no reply.

## Building and testing

- **The full build is expected to work in the sandbox** (Google Maven allowlisted, SDK at
  `/opt/android-sdk`), with CI's commands:
  `./gradlew :core:domain:test :core:data:test :app:testDebugUnitTest` and
  `./gradlew :app:assembleRelease`. Verify before assuming either way (`command -v
  sdkmanager`; probe `https://maven.google.com/` — 403 from the sandbox TLS-inspection CA =
  blocked) and flag a block that shouldn't be there.
- `.claude/hooks/session-start.sh` (SDK + `scripts/unshallow.sh`) is auto-invoked only in
  Claude Code; elsewhere run `scripts/unshallow.sh` yourself, and
  `CLAUDE_CODE_REMOTE=true .claude/hooks/session-start.sh` (then export `ANDROID_HOME` and
  `ANDROID_SDK_ROOT` as the path it reports ready: only Claude Code keeps the hook's
  environment) if a remote container lacks the SDK — don't hand-install.
- **Inner build** — faster for pure-Kotlin work, and the fallback without Google Maven or
  the SDK: `cd core && ../gradlew :core:domain:test :core:data:test`. `:core:domain:test`
  is the minimum signal for a domain change. If `:app` can't run, **say so** and rely on
  CI; never claim the build passes on core tests alone.
- **Fix preexisting test failures as the first commit** (confirm before skipping if
  genuinely unrelated). **No sleeps, retries, or bumped timeouts** for flaky/racy tests —
  make ordering explicit (test dispatcher, `runTest`, idling resource, a gate).
  **Never disable a failing check** (test, lint, Roborazzi comparison).

## UI

- **4dp grid** (`4`, `8`, `12`, `16`, `24`, `32`, …); explain any off-grid value. Reuse
  sibling values; symmetry by default (justify asymmetry in one PR sentence). Verify on
  the snapshot diff CI posts — even arithmetic can render uneven. Flag off-grid spacing
  outside the diff (file, line, fix) without fixing it in the same commit.
- **Concise copy**: labels, buttons, dialogs and titles carry only the words the user
  needs; drop framing the UI already implies; justify a longer form in the PR.
- **Use a standard Material icon by default** (Material Icons/Symbols), not hand-drawn;
  ask if none or two fit.

## Git workflow

- **Linear history: rebase, never merge.** Use `git worktree` when available.
- Feature branches: `<agent>/<short-topic>` (`claude/…` Claude Code, `gemini/…` Gemini /
  Antigravity, `codex/…` Codex). **Your `<agent>/` branches are yours** — create, reset,
  force-push, delete the ones this session created or was assigned, freely, even after
  merge or review (this file is the standing grant); don't fold unrelated work into a
  pinned branch. Another tool's prefix, others' branches, or yours from another session:
  check first. `main` is never force-pushed or rewritten.
- **Unmerged commits are scratch**: amend, squash, reorder, split, so each push reads as
  the story for a reviewer — fixes folded in, infra before feature, no `wip`/`address
  review` noise; squash fixups unless asked to keep a boundary. `--force-with-lease`,
  never bare `--force` (it guards CI's own `ci: regenerate UI snapshots` commits).
- **Stacked PRs**: lower (infra) targets `main`, upper targets the lower branch; when the
  lower merges, `git rebase --onto origin/main <lower-branch>` the upper and carry on.
- **Merge cue (`merged` / `I merged` / `landed` / merge webhook) runs hygiene first**:
  `git fetch origin +refs/heads/main:refs/remotes/origin/main` (`git fetch origin main`
  leaves single-branch `origin/main` stale), cut a fresh `<agent>/<short-topic>` off
  `origin/main` — not the merged name, whose remote ref keeps spanning merged commits
  (reset it only when sandbox-pinned; `--force-with-lease` is no safety net after a fetch)
  — and announce the switch. The cue is about the branch
  that merged: when it's the lower PR of a stack whose upper is still open, rebase the upper
  (above) and carry on there instead. Without a remote, say a synced checkout is needed rather
  than branching off a stale `main`.
- **Committer line**: the agent authors; rebase/squash merges rewrite the committer to the
  merger — expected. Never re-author or amend merged commits to "fix" it, and never raise
  it: no PR note, chat caveat, or offer.
- **Unshallow before history-depth queries** (`git rev-list --count`, versionCode): if
  `git rev-parse --is-shallow-repository` is `true`, run `scripts/unshallow.sh` (or add
  `--unshallow` to the refspec fetch — only while shallow; it fails on a complete repo) and
  re-check.

## Commit messages and What's new

- **Every commit subject ships verbatim to Play's "What's new"** (`whatsnew-en-US`):
  CI bullets (`• `) the subjects since the last successful `main` run, oldest first, a
  repeat shown once at its first position. So each subject is end-user copy: sentence
  case, no jargon or scope prefix, ≤ ~80 chars (`Bigger outfit icons on the Today
  screen`, not `:app + :core — glanceable outfit-preview icons`); module names, file
  paths, reasoning and "fixes #123" go in the body.
- **Prefix what ships nothing user-visible**; CI filters these out ("Prepare release
  notes" in `ci.yml`): `ci:`, `test:` (only tests/fixtures/snapshots — `src/test/`,
  `src/androidTest/`, `app/snapshots/`), `internal:` (tooling, project meta), `docs:`
  (`docs/`, `*.md` at any depth, dotfiles — `PRIVACY.md` included, since "What's new" is a
  store field nobody sees at update time). Those paths drop out anyway; prefix them so
  the intent is explicit. A mixed test + production commit is bare — squash the tests into
  the feature. A dependency the app ships is bare; a test-/build-only bump you write keeps
  its prefix (the weekly batch lands bare either way).
- **Play caps "What's new" at 500 characters**: CI drops trailing bullets and appends `…`,
  so squash supporting commits into the one telling the user-facing story.
- **No model identifier** — never name the coding agent's model or version (`Opus 4.8`,
  `Sonnet`, `Gemini …`, `claude-*`, `gemini-*`) in a commit, PR, comment, or pushed
  artifact; keep it to chat. A model the product calls (the app's Gemini model IDs) is
  not covered. Generic attribution (`Co-Authored-By: Claude` / `Gemini`,
  `Claude-Session:`, "Generated with Claude Code") is fine.

## Autonomy

- **Open the PR without being asked**, ready for review (not draft) at once — don't wait
  for green CI (this file is the repo owner's standing request), unless told "just commit
  / no PR yet".
- **Watch your own PRs by subscription + one scheduled check.** Subscribe on open
  (automatic in Claude Code, or `subscribe_pr_activity` where `claude-code-remote` exposes
  it; a client with neither, like Antigravity, relies on the scheduled check alone). It
  misses CI success, pushes, the merge and Codex's `+1`, so keep one relative-delay check
  armed while open (`send_later` in Claude Code; `schedule` with `DurationSeconds` in
  Antigravity, canceled via `manage_task` `kill`) — minutes while CI or Codex is out or
  after a push, longer once only a human is left. Settle a fired check first; if the fire
  time you got isn't the one asked for, re-time it or say the watch isn't armed; end each
  turn with one check naming the PR (not a SHA). Under drive, auto-merge only where the
  ruleset requires the Codex verdict AND resolved conversations.
- **Read `mergeable_state` at every check, wake, and drive step**: if `dirty`, or `behind`
  where required, fetch base and head by refspec, unshallow, rebase onto the fetched
  `origin/<base>`, and `--force-with-lease` push unasked — after confirming your branch
  has every remote head commit and the head hasn't moved since the tip you noted before
  fetching; if not, or unsure, stop and ask.
- **Wake PRs a merge leaves behind.** First turn after your PR merges, message every other
  open PR on that base whose body ends with a session URL: name that PR and say only that
  its base now points at `<sha>`, whatever its state reads (name no merge or other PR). On
  receipt, fetch base and PR head by refspec (unshallow) and test against the fetched tip,
  not `<sha>`: `behind` if `git merge-base --is-ancestor <tip> <head>` exits 1, `dirty` if
  `git merge-tree --write-tree` (Git 2.38+) exits 1; any other exit is a failure to report.
  Skip with no session URL or local-only messaging; don't retry a failed send.
- **On merge/close**, take one last reply-and-resolve pass; anything real on a merged PR
  goes to a follow-up PR named on the thread (closed-unmerged: open nothing). Then cancel
  the check and unsubscribe, if subscribed; `list_triggers` spans the account, so match
  session and PR.
- **A scheduler, GitHub, or `git push` prompt is said once, then carry on.**
- **"Drive"**: loop through actionable `TODO.md` items (not deferred or awaiting a product
  decision) — implement, open PR, wait for Codex, address every comment, merge on green CI
  plus Codex's verdict for the head (that PR stretch is *drive to merge*), repeat until the
  work runs out or the user stops it.
- **A red baseline is the next task**: before pulling from `TODO.md`, get the test suite
  *and* `./gradlew :app:assembleRelease` green (no keystore needed; CI runs no
  `assembleDebug`); without the SDK, the inner build for `:core:*` and CI's Android job for
  `:app`, never skipped. Skipping a failure as unrelated needs the user's answer, never a guess.
- **"Autopilot"**: drive without blocking — take the cheapest-to-undo guess and log it
  under `TODO.md` *Decisions needing review* (decision, alternative, why reversible); it
  outranks stop-and-wait. The loop's own steps (commit, push, PR, checks, merge green) are
  authorized; destructive or irreversible actions outside the loop, anything reaching
  beyond this repo, and privacy uncertainty (a location, calendar title, key, identifier)
  wait for a real answer.

## Working with PRs

- Prefer `mcp__github__*` tools where mounted (Claude Code requires them); else `gh`.
- **Ordered work ("next", "then", "after that", "follow-up")** may open in parallel but
  merges only after every earlier session item and named PR has merged (any repo, no time
  cap; hold and ask if one stalls, autopilot included; no auto-merge until its turn; check
  the session record).
- **Update the PR title and body before each push** (body first) to match the full branch
  diff: title Play-ready like a subject; body scope, rationale, privacy changes, test
  plan. If nothing material changed, none is needed — but check, don't assume. With no PR
  yet, push, then do this as soon as one is opened.
- **Codex is the automated reviewer** (not Copilot), triggered automatically. Address every
  comment unasked — fix and fold into its commit, or reply declining with reasons — then
  resolve (`resolve_review_thread` or `gh api graphql` `resolveReviewThread` takes
  `PRRT_*`, not `PRRC_*`; push, reply citing the sha, resolve) once the fix is on the head
  or the point is rebutted; anything still to do stays open. Never silently dismiss; an
  acknowledgment isn't an answer.
- **Judge comments on merit.** A cited rule is a reading — check what it says; over-strict
  privacy readings quietly cost needed capability. Quote and decline, then `@codex review`
  once (or let the next push do it); escalate if it re-raises or stays silent. Genuine
  rule-vs-need conflicts go to the maintainer.
- **A second verified finding in the same mechanism is a design question**: look for the
  shape elsewhere and ask whether a design change deletes the class (maintainer's call).
- **Defer an out-of-scope finding** by pushing a `TODO.md` entry, replying with that sha,
  and resolving (a threadless finding skips only the resolve).
- **Read the Codex verdict, don't infer it**: its PR-body reaction (`eyes` reading, `+1`
  clean, revoked on push; `+1` + green CI = merge), plus `get_review_comments`,
  `get_comments` and `get_reviews` to the last page and the `codex` status (`updated_at`
  moving with no reaction usually means unread findings). Findings block the merge until
  fixed, rebutted or deferred. Nothing five minutes after a push: `@codex review`, once. Leave PR-body reactions to Codex.
- **Once Codex +1s a head, make no unnecessary change without asking** (maintainer,
  2026-10-13); required rebases, red-CI fixes and drive/autopilot steps proceed unasked.
- **Report each review in your next message**: commit read (say if it's no longer the
  head), finding count, one bullet per finding (most blocking first: claim, location,
  action) — this outranks keeping replies short; ask only the most blocking question.
- **Link every open PR in a stack, one URL per line.**
- **CI posts snapshot image diffs as a PR comment — don't hand-post** (a fork PR, where
  CI can't comment, gets one by hand; format in `docs/agents-reference.md`).
- **After every merge to `main`, report the versionCode** (`git rev-list --count
  origin/main` after a refspec fetch, unshallowed — a shallow count runs low):
  `Need versionCode 72 (b81c23d) or higher to test PR #52's fix`. Without remote Git
  (Codex cloud), say it couldn't be verified.
- **"Release notes since N"**: up to five `• ` end-user bullets in one code block for
  commits N+1..`origin/main` (freshly fetched; offline only from a complete local `main`,
  saying they may be behind — a shallow one can't answer), ranked by what a user would
  notice. Read bodies where a subject is unclear; one feature
  per bullet; skip prefixed/docs/CI commits, dependency bumps (unless nothing else
  changed — then one bullet says libraries were updated) and anything the range added then
  undid; describe a reworked feature by its final state. Range above, one line below naming what was omitted.
- Skip your own webhook echoes silently (did *I* just post this body?).

## CI

- Jobs: `JVM unit tests` (`:core:*:test` + `:app:testDebugUnitTest`, uploads Roborazzi
  snapshots as `ui-preview-snapshots`); `Android release check` (`:app:assembleRelease` on
  PRs, nothing on `main`); `Build the release AAB` (`:app:bundleRelease`, `main` only,
  needs only `classify` so R8 overlaps the tests); `Distribute and release` (needs
  `unit-tests`, `android-build`, `release-build`; downloads `app-release-aab`, no Gradle;
  GitHub prerelease `v<versionCode>`, then Play).
- **The prerelease's condition is the Play upload's minus the service account**, so every
  Play upload follows its prerelease; a superseded run publishes to neither
  (`docs/play-store-internal-testing.md`).
- **`release-build`'s `signed` output means "a bundle exists"** (nothing builds unsigned);
  only a fork skips the keystore, keyed on fork status, never the secret's absence.
- **`scripts/publish-github-release.sh` is stub-tested on every PR and byte-identical across
  the sibling Android repos** (only its `env` differs); change it in all of them.
- **Wait for CI** before claiming an Android change works — don't busy-poll: a failure
  arrives on the subscription, success is what the scheduled check is for.
- **Report a significant CI slowdown** (>25% or >30 s on a job under ~5 min) with its
  likely cause, comparing the same job's total (not a step) on the same kind of ref against
  a real recent run: `Android release check` is minutes on a PR, seconds on `main`
  (minutes there means the skip broke); AAB and publish jobs are `main`-only. Timings:
  `docs/agents-reference.md`.

## Kotlin / Compose gotchas

- A literal `/*` inside KDoc (`dir/*.png`) opens a nested comment and reports "Unclosed
  comment" at EOF; write `dir/` or `<path>.png`.
- Kotlin's no-arg `lowercase()`/`uppercase()` are already locale-invariant — don't "fix"
  them to `Locale.ROOT`; prove any change with a `tr-TR` default-locale test. The real trap
  is `String.format`/`"%d".format(x)` without a `Locale`.
- Preview wrappers live in `app/src/main/.../*Previews.kt` as `internal`, reachable from
  Roborazzi tests in `app/src/test`; don't make composables `public` for tests.
- JUnit 5 platform; Robolectric's JUnit 4 runs via `junit-vintage-engine`
  (`testRuntimeOnly`) — "no tests found" usually means it's missing.
- A detached `ComposeView` composes but never paints; off-screen rendering needs a real
  window (`widget/ComposeRender.kt`; don't "simplify" it).

## Error handling

- **Don't silently swallow exceptions.** Every catch **logs** the exception through the
  usual logger with sanitized context ("geocode failed: 404" — never calendar titles,
  locations, GPS, insight prose, or a BYOK key), **cleans up** what `try` acquired
  (`use { … }`/`finally`, partial writes, UI state), and **handles the edge case
  explicitly** (default, null, sentinel, domain exception). Narrow the type or rethrow
  `CancellationException` first. To ignore a failure on purpose, say why in a one-line
  comment and still log at debug.

## Privacy

- **Never put user PII in any artifact leaving this machine** (commits, PRs, replies,
  issues, branches, comments, fixtures, snapshots, Play Console, logs): real name, email,
  home/work/current location, GPS, addresses, phone numbers, calendar titles or attendees,
  contacts, device identifiers, BYOK keys, `google-services.json`. Use placeholders
  (`alice@example.com`, "City A", `lat = 0.0`, "Morning standup"); paraphrase bug reports;
  ask when in doubt.
- **Surface any change to what leaves the device** (insight prose fed to Gemini TTS over
  BYOK keys, weather/geocoding requests, analytics/error reporting) in the PR and commit;
  default to less — TTS endpoints log requests.
- **Firebase Analytics + Crashlytics payload limits** (`PRIVACY.md`): no calendar data,
  location, insight prose, notification text, API keys, precise GPS or ad identifiers in
  `FirebaseCrashlytics.setCustomKey(...)` or Analytics params. The telemetry toggle
  (`SettingsRepository.setTelemetryEnabled`) controls *whether* Firebase reports; this
  controls *what*.
- **`google-services.json` is per-developer and gitignored**: the Firebase plugins apply
  only when present, so CI builds without it and telemetry no-ops. To enable, configure a
  project for `app.clothescast`, drop it at `app/google-services.json`; never commit it.

## Language

- **US English everywhere** — base `values/strings.xml` (it is `en-US`, every locale's
  fallback), commits (subjects become `whatsnew-en-US`), PRs, comments, docs: `color`,
  `center`, `canceled`, `gray`, `analyze`. **Except** `values-en-rGB`, which deliberately
  uses British spelling and which `values-en-rAU`/`values-en-rZA` inherit (API 24+ locale
  fallback, not base `en-US`); other `values-*` keep their own conventions.
- **Plain, honest voice** in `PRIVACY.md`, "What's new", UI strings and docs: name what the
  app does ("signs in anonymously", not "has no accounts you sign in to"), never hedge a
  true-but-narrow claim to imply more, state caveats outright, prefer the concrete term over vague reassurance — doubly so for what crosses
  the device boundary.

## Domain conventions

- Clothes rules and outfit suggestions use **feels-like** temperature (apparent, wind-chill
  / humidity adjusted), never raw 2 m air temperature.
- **One number drives every rain surface: the blended-consensus chance of rain**
  (`DailyForecast.precipitationProbabilityMaxPct` / blended
  `HourlyForecast.precipitationProbabilityPct`, `ConsensusBlend.kt`). **≥ 10%** → prose
  "chance of rain", umbrella default (`PrecipitationProbabilityAbove(10.0)`), strip
  droplet; **≥ 50%** → confident "rain", rain-jacket default (`…Above(50.0)`). No
  per-model, weather-code or trace-amount rain path.
- The evening extras emit off the same number: a rule firing for the evening names the
  item and folds in the rain ("Tonight, rain, bring a jacket."); with no rule but ≥ 10% in
  the tonight window and an evening event with a location, a bare warning ("Tonight,
  rain." / hedged for POSSIBLE). Prose never pins a peak hour (the clause data carries it
  for chart and card); a post-midnight peak appends "overnight", an evening one nothing.
- Snow never fires rain gear: `EvaluateClothesRules`' snow gate (`isFrozenPrecipitation`)
  suppresses it even above the bar.
- `:app` owns Android concerns and which Gemini model to call; `:core:domain` holds the
  clothes/insight logic and stays pure Kotlin, JVM-testable.
- **Don't rename Gemini models from web-search guesses**: verify against the live
  `ListModels` endpoint (`GET /v1beta/models?key=…`) first; search snippets invent GA names
  (`gemini-2.5-flash-tts`) for preview-only models (#59 → #60).

## Cost and reliability

Call out cost and reliability up front for new infrastructure or external calls (another
Open-Meteo request, a Gemini model or endpoint, a third-party API, Firebase usage): $/month
vs free tier, failure modes, rate limits, latency, what the user sees when it's down. BYOK
costs land on the user's key, so the estimate matters more. Say so if nil.

## Cursor Cloud

SDK preinstalled at `/opt/android-sdk` (export `ANDROID_HOME` if a shell lacks it); CI's
commands plus `./gradlew :app:lintDebug` (no `ktlint`); no backend to start.
