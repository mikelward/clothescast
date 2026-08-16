# Agent guide for clothescast

Rules and gotchas for AI coding agents (Claude Code, Codex, etc.) working in
this repo. Keep this file short and concrete — one-liners over essays. Add a
new rule the first time something bites you, not the third. Every rule here
loads into every agent's context each session, so verbosity taxes every
future task; the incident narrative belongs in the commit message.

## Talking to the user

- **One question at a time.** Never stack multiple questions in a single
  turn — ask the most important one, wait for the answer, then ask the
  next if you still need it. A wall of bundled questions is harder to
  answer than a short back-and-forth.
- **Ask in chat, never with `AskUserQuestion`.** That's Claude Code's
  multiple-choice question prompt, and it's broken in the Claude mobile
  app — a question asked through it may be unanswerable. Plain chat also
  keeps the question, its context, and the answer in one readable thread.
- **After asking, stop and wait for the answer.** Don't proceed on an
  assumed answer, pick a "recommended" option yourself, or keep working on
  the part the question affects.
- **Don't interrupt.** Never fire off a question while the user is still
  typing. Let them finish; a half-typed message isn't an invitation to
  jump in.
- **Keep replies short — don't dump a full page.** Lead with the single
  most important point and stop. If there's more, say the first point and
  ask whether they're ready for the next one rather than emptying
  everything at once.
- **Don't report your own caught mistakes.** A wrong turn you noticed
  and fixed before it reached the user is not news — no "one thing worth
  flagging", no post-mortem of your own reasoning, no inside baseball
  about the loop. Say what the work is and what's outstanding. A mistake
  that reached their repo, cost them something, or changes what they
  should do is different: say that plainly.
- **End the turn by restating any pending decision.** If you're waiting on
  an answer — a question you asked, or a guess autopilot recorded for
  review — the last line of the reply is that question, written out in
  about a sentence. A back-reference ("as asked above") isn't actionable
  when the question is pages back or was never actually put into words;
  restate it every turn until it's answered. Nothing pending, no line. This
  governs replies the user reads: a scheduled check that finds nothing new
  re-arms silently and produces no reply at all, so there is nothing to
  restate.

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

## Testing expectations

- **Fix any preexisting test failures as the *first* commit of the series.**
  If the tree is already red when you start a task, don't stack your work on
  top of a broken baseline. Land the fix first, on its own commit, so the
  reason each test goes red is attributable to a single change. If the
  failure is genuinely unrelated and out of scope, say so in the first
  response and confirm before skipping past it — don't silently report a
  task "done" with the tree still red.
- **Don't paper over racy / flaky tests** with `Thread.sleep`, a retry loop,
  or a bumped timeout. If a test depends on ordering (coroutine dispatch,
  recomposition, a Robolectric frame), make the ordering explicit — a test
  dispatcher you advance, `runTest`, an idling resource, a gate you release
  from the test. A test that passes "most of the time" is broken; rewrite it
  or fix the underlying cause.
- **Don't disable a failing check** (a test, lint, a Roborazzi comparison)
  to make it pass — fix the underlying issue.
- **Call out cost and reliability up front** when recommending new
  infrastructure or a new external call (another Open-Meteo request, a new
  Gemini model or endpoint, a third-party API, added Firebase usage).
  Include a rough dollar figure — free-tier vs. paid thresholds and $/month
  at expected traffic — and note reliability implications: new failure
  modes, rate limits, added latency, extra points of failure, and what the
  user sees if the dependency is down. BYOK costs land on the *user's* key,
  which makes the estimate more important here, not less. If the impact is
  effectively zero, say so explicitly rather than omitting the note.

## Spacing, padding, and margins

- **Stick to a 4dp grid.** Every padding / margin / spacing value should be a
  multiple of 4dp (`4`, `8`, `12`, `16`, `24`, `32`, ...). If you reach for
  `5`, `7`, `10`, `14`, pick the nearest grid value instead, or surface the
  off-grid value with a one-line explanation of why it's necessary.
- **Reuse the values already in use.** Before introducing a new dp value,
  check the surrounding composable and its peers. Gratuitous variation
  between `14.dp`, `16.dp`, `18.dp` on visually similar elements is exactly
  what this targets.
- **Symmetry by default; asymmetry only with a reason.** Any asymmetric
  padding gets a one-sentence justification in the PR. No silent asymmetry.
- **Verify visually, not just by reading dp values.** After a spacing change,
  look at the snapshot diff CI posts on the PR and confirm the result is
  visually even — arithmetic that's correct but renders uneven (intrinsic
  content padding, ripple insets, baseline alignment) is still wrong.
- **Flag off-grid spacing you notice even outside the diff** — file, line,
  what's off, proposed fix — without silently fixing it in the same commit.

## Concise copy

- Keep user-facing text short. A label, action, or title should carry only
  the words the user needs — drop framing verbs and prefixes the surrounding
  UI already implies. Prefer the shortest phrasing that stays unambiguous;
  when a longer form is genuinely needed for clarity, say why in the PR.
  Applies to strings, dialog and button text, and screen titles. (Same
  instinct as the ≤80-char commit-subject rule — say it in fewer words.)

## Commits and PRs

- **Linear history.** Never merge — rebase. The repo's PRs land as a linear
  chain on `main`. A merge commit in a PR is a sign something went wrong.
- **Use `git worktree` when it's available.** Give each branch its own
  worktree instead of switching branches in place, so work in progress on one
  branch isn't disturbed by work on another.
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
- **Force-pushes are routine on your own feature branches** (per the rule
  above) and don't need confirmation, but use `git push --force-with-lease`,
  never a bare `--force` — the lease is what stops you clobbering an
  upstream push you hadn't seen (CI's own `ci: regenerate UI snapshots`
  commit, most often here). The only protected ref is `main`: never
  force-push it, and never drop commits already on it.
- **Merge cue (`merged` / `I merged` / `landed` / merge webhook) runs
  hygiene *before* engaging with the rest of the message:** `git fetch
  origin main`, cut a fresh `<agent>/<short-topic>` branch off
  `origin/main`, announce the switch. Where the sandbox has no remote, the
  cue can't be honored as written — a fresh branch needs a base that
  contains the merge, and an offline checkout can't fetch one; say so and
  ask for a synced checkout rather than branching off a stale `main`. The
  cue is about the branch that merged: when a lower PR in a stack merges
  while an upper one is still open, rebase the upper branch with `git
  rebase --onto origin/main <lower-branch>` — onto `origin/main`, not
  local `main`, which the fetch does not advance, and naming the lower
  branch as the upstream boundary so a squash merge doesn't replay the
  lower commits too. Carry on there — don't abandon it for a new topic
  branch.
- **After a merge, take a fresh `<agent>/<short-topic>`** — don't reset
  the merged name onto the new base. Its remote ref still points at the
  pre-merge tip, so `origin/<branch>..HEAD` keeps spanning the merged
  commits and unpushed-work checks report your own merged history back
  at you. When a sandbox pins the branch name so a fresh one isn't
  available, reset it. No short check reliably separates "already
  merged" from "not yet merged" here: a rebase merge rewrites the
  commits, a squash merge collapses them, `main` moves on underneath so
  a tip-to-tip diff reports upstream drift as branch work, the remote
  ref can hold a commit the local one doesn't, and no tree comparison
  sees the uncommitted work a `--hard` reset would erase. Don't reach
  for `--force-with-lease` as the safety net either — fetching updates
  the remote-tracking ref the lease compares against, so a commit you
  have already fetched passes the lease unnoticed.
- **The agent authors; whoever merges takes over the committer line.** A squash
  or rebase merge rewrites the committer to the person who pressed the button —
  the repo owner normally, the agent itself when it merges under *drive*. That's
  expected either way — never re-author or amend already-merged commits to "fix"
  authorship or signing.
- **Never raise the authorship or committer question.** The rewrite
  above is expected and needs no comment: no PR note, no chat caveat, no
  explanation of the mechanism, no offer to "fix" it. Raising it every
  session is the noise this bullet exists to stop.
- **Branches under your own agent prefix are yours.** Create, reset,
  force-push, and delete any branch carrying *your* prefix freely — no
  permission, no announcement, including a name whose work has already
  merged and commits a reviewer has already commented on. Another tool's
  prefix (`codex/…` when you are Claude) and anyone else's branch are
  not yours: check before touching them. `main` is never force-pushed or
  rewritten.
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
- **`ci:` / `test:` / `internal:` prefixes and docs-only commits are
  filtered out** of the changelog (see the "Prepare release notes" step in
  `ci.yml`) so the snapshot-regen bot's commits, test-only changes,
  internal-only changes, and `docs/` / dotfile / `*.md` changes don't
  surface as bullets. Anything else *will* appear — if a commit shouldn't
  ship in the changelog, either squash it into a sibling or give it the
  appropriate prefix.
- **Test-only commits use a `test:` subject prefix.** A commit that
  touches only test sources / fixtures / snapshots (anything under
  `src/test/`, `src/androidTest/`, `app/snapshots/`, or equivalent —
  nothing shipped in the APK) ships no user-visible change, so it
  doesn't belong in the Play "What's new". Prefix the subject `test:`
  (e.g. `test: cover evening-rain fallthrough in InsightTextTest`) and
  CI will skip it the same way it skips `ci:`. Mixed commits (test +
  production code) stay un-prefixed and ship as normal bullets —
  squash the test work into the feature commit instead of splitting.

- **Internal-only commits use an `internal:` subject prefix.** A commit
  that ships nothing user-visible in the APK and isn't test-only — agent
  guides, repo tooling, project meta — takes `internal:` (e.g. `internal:
  document the internal: changelog prefix`) and CI skips it the same way
  it skips `ci:` / `test:`. Note the path-based filter already drops
  `*.md` (at any depth), `docs/`, and dotfile-only commits regardless of
  prefix (PRIVACY.md is the exception — it's treated as non-docs so
  privacy-policy updates still surface), so a pure-`AGENTS.md` change is
  dropped either way — but still prefix it so the subject's intent is
  explicit and never reads like a shippable bullet. Reach for `internal:`
  especially when an internal-only change touches paths that *would*
  otherwise ship a bullet.
- **Docs-only commits use a `docs:` subject prefix.** A commit touching
  only `docs/`, `*.md` files (READMEs, setup guides — any depth), or
  dotfiles takes `docs:` (e.g. `docs: fix stale Firebase Console nav`).
  Prefix it even though the path filter already drops it from the
  changelog — the prefix makes the intent explicit and keeps the subject
  from reading like end-user copy (and the subject filter now skips
  `docs:` directly, same as `ci:` / `test:` / `internal:`). Exception: a
  PRIVACY.md-only change ships as a bullet (it's treated as non-docs), so
  leave that one unprefixed.
- **Play caps `whatsnew-en-US` at 500 characters.** When the bullet list
  exceeds that, CI drops whole trailing bullets (oldest first stay) and
  appends `…`. Avoid lining up a long stack of small commits if any one
  of them tells the user-facing story on its own — squash the supporting
  work in.

## Autonomy

- **Open the PR without being asked.** Pushing a finished branch and
  opening its pull request are one step, not two — don't park a branch
  waiting for "please open a PR." The exception is an explicit
  instruction not to ("just commit", "no PR yet"), which holds until the
  user lifts it. This file is the repo owner's standing request for that
  PR, so a client-level rule reading "open a PR only when the user
  explicitly asks" is already satisfied — the ask is here, and it doesn't
  need repeating per branch.
- **Stay subscribed to PR activity on your own PRs — events are the
  watch.** Keep (or create) the `subscribe_pr_activity` subscription for
  every PR you open, so CI failures, reviews, and comments wake the session
  and get acted on the moment they land, without the operator having to
  relay them. Subscribed events render as raw payloads in the operator's
  conversation and nothing session-side can filter them; that cost is
  accepted — reaction latency outranks a quiet chat. Arm auto-merge at PR
  open too, so a clean verdict merges without a turn. Unsubscribe only when
  the PR merges or closes.
- **Keep one scheduled fallback check per open PR alongside the
  subscription — ~5 minutes while CI or the Codex verdict is outstanding,
  ~30 once only a human is left.** Events are the primary signal; the check
  covers what webhooks drop — CI success, merges, and Codex's clean
  verdict, which is a reaction and emits no event at all. Never end a turn idle with one of yours open: arm the next
  check with whatever the client offers (`send_later`, a scheduled task /
  cron, `/loop`), and arm it *without asking* — that is hygiene, not a
  decision. Someone else's PR is not your polling job unless you're asked.
  Merged or closed is terminal: take one more check for CI and Codex on the
  final head, but settle for what's known if a report may never land, then
  run a last reply-or-resolve pass and cancel the watch in full — the
  pending trigger, *and* `unsubscribe_pr_activity` if you ever subscribed.
  Open a follow-up PR, with its own watch, for anything a merged one still
  needs.
- **What the polling costs.** Twelve wake-ups an hour per PR at the fast
  cadence, two at the slow one — each a model turn plus a few GitHub API
  calls, so roughly a dollar an hour while a PR is waiting on its merge
  gate. The scheduler is the single point of failure: one missed re-arm
  ends the watch silently, with no error anywhere. If you can't arm the
  next check, say so in the reply rather than leaving a PR that looks
  watched and isn't.
- **One pending check per PR, settled at the top of the turn.** Two chains
  each re-arming themselves double the cost every time a webhook starts a
  turn while one is already pending; parking the re-arm at the *end* of the
  turn loses it when the turn is interrupted, which once left a PR unwatched
  for two hours. So settle it first, and settle it to exactly one: leave a
  correctly-timed check alone — pushing its deadline forward every turn is
  how a busy PR never gets polled — and when it's missing, already fired, or
  mis-timed, either `update_trigger` it in place or arm the replacement
  before deleting the old, because an overlap beats a gap. Then diagnose,
  fix, and reply.
- **If a scheduler or GitHub call prompts, say so once and carry on.**
  Permissions load at session start, so writing a settings file mid-session
  can't fix the session you're in.
- **A fired check doesn't necessarily retire itself.** A `send_later`
  one-shot has come back re-armed +24 h, turning a five-minute check
  into a daily wake-up while the session still looked watched. Reconcile
  it when it fires — update it into the next check, or replace it the
  same way — so exactly one stays outstanding.
- **Verify the fire time you got is the one you asked for.** A requested
  5 minutes came back as 100. Re-arm when they disagree, or say the
  watch isn't armed — the woken turn can't see the drift, only that a
  check arrived.
- **`list_triggers` spans the whole account.** The docs never say so, and
  it reads as session-scoped — that's the trap. Filter on
  `persistent_session_id`, and match the trigger to *this* PR as well
  (one session can watch several), or the delete kills another live watch.
- **Don't bake a SHA into the check prompt — say "the current head".** The
  prompt predates the work it describes; one fired naming a commit four
  behind head. Same for CI status and review counts: name what to re-read,
  not what it contained.
- **"Drive" means run the loop automatically**: pick the next task,
  implement it, open the PR, wait for the automatic Codex review, address
  every comment, merge once CI is green and Codex's verdict for the current
  head is in — then pick the next actionable `docs/TODO.md` item and go
  around again. Actionable means ready to build: skip anything explicitly
  deferred or waiting on a product decision rather than guessing the
  decision. Driving ends when the work runs out or the user says stop, not
  when one PR merges.
- **A red baseline is the next task.** Before pulling anything from
  `docs/TODO.md`, run the suite (`./gradlew :core:domain:test
  :core:data:test :app:testDebugUnitTest`) *and* `./gradlew
  :app:assembleDebug`, and get both green — a baseline can be red only in
  Android packaging, resource merging, or the manifest merge, which the
  test task never touches. Where the SDK isn't available, use the inner
  build for `:core:*` and take CI's Android job as the signal for `:app`
  rather than skipping it. A preexisting
  failure is work to do, not a thing to classify as "unrelated" and step
  around — deciding it's out of scope is exactly the call that goes wrong,
  and the cost is every later PR merged onto an unverified tree. Fix it
  first (as its own first commit, per "Testing expectations"), then pick
  the task. That section's "genuinely unrelated, out of scope" escape
  hatch is the only way past a red tree, and it needs a real answer from
  the user — not a call you make on your own, and not one autopilot
  guesses.
- **"Autopilot" is drive without blocking on the user.** Wherever drive
  would stop and ask, autopilot takes its best guess and keeps going,
  preferring the option that is cheapest to undo or change later. Record
  each guess in `docs/TODO.md` under a `Decisions needing review`
  heading — what was decided, what the alternative was, and why it's
  reversible — creating the heading if it isn't there, so nothing
  guessed silently becomes permanent. While autopilot is in effect it
  outranks "after asking, stop and wait for the answer"; that rule
  governs everywhere else. The carve-out is for destructive or
  irreversible actions *outside* the loop — rewriting shared history,
  deleting work, anything reaching a system beyond this repo — which
  still wait for a real answer. The loop's own steps don't count:
  committing, pushing, opening a PR, reading its CI
  and review state, arming the next scheduled check, and merging a green
  PR are authorized here, so autopilot must not stall on them — the
  carve-out is aimed at destructive writes to systems outside the repo,
  not at the loop's own GitHub reads and follow-ups. Privacy uncertainty
  is never inside the loop either: if you can't tell whether something
  is user data — a location, a calendar title, a key, an identifier — it
  waits for a real answer, since a push can't be un-published and a
  `docs/TODO.md` note doesn't retract it.

## GitHub

- Use the `mcp__github__*` MCP tools for *all* GitHub operations. The `gh`
  CLI is **not** available in this sandbox.
- **"Drive to merge"** is the PR stretch of *drive* (see "Autonomy" above):
  open the PR, wait for the automatic Codex review, address every review
  comment — fix it if you agree, reply on the thread saying why if you don't
  — and merge once CI is green and Codex's verdict for the current head is
  in.
- Open PRs as **ready for review** (non-draft) immediately — don't wait for
  CI to go green or for an eyeball pass before marking them ready.
- **Keep PR title and body in sync with the branch on every push.** A PR's
  title and description are read as the canonical summary of what's
  landing; if the branch has grown, narrowed, or pivoted since the PR
  opened, the original text lies. After every push (force-push, follow-up
  commit, rebase-and-push) re-read the full branch diff vs. the base and
  update the title and body via `mcp__github__update_pull_request` so
  they describe the *current* state — not the state at PR creation.
  Subject line stays Play-Store-ready (sentence case, end-user copy, ≤
  ~80 chars, matches the rebase-merge subject); body covers scope,
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
- **Codex is the automated reviewer on this repo** — not Copilot. Its
  reviews are triggered automatically; you don't request them, except when
  nothing has come back five minutes after a push — that means it never
  picked the push up.
- **Address Codex comments automatically — don't wait to be asked.** When a
  Codex review lands, treat each comment like a real review note: read it,
  decide whether it's a real issue or a false positive, and if it's real,
  fix it in the same PR. Fold the fix into the commit it belongs to
  (rebase / `--fixup`) rather than tacking on an "address review" commit,
  per the *clean up unmerged commits* rule under "Commits and PRs". Group
  several small fixes into one commit when they share a topic.
- **`resolve_review_thread` works — pass the thread ID, not a comment ID.**
  `mcp__github__pull_request_read` / `get_review_comments` returns each
  thread's node ID (`PRRT_*`) on `review_threads[].id`. Pass that straight
  to `mcp__github__resolve_review_thread` as `threadId`. A comment's node
  ID (`PRRC_*`) fails with `Could not resolve to PullRequestReviewThread
  node` — they're different objects. So reply *then* resolve; there's no
  "replied-but-unresolved, please resolve in the UI" caveat to report.
- **Report when Codex finishes reviewing a fresh push.** Codex's
  review runs asynchronously after each push; once its review event lands
  for the latest commit, surface a one-liner naming the SHA and comment
  count — e.g. `Codex reviewed 87d9f02 — 0 comments` or `Codex
  reviewed 87d9f02 — 3 comments, addressing now`. Tie it to the *latest*
  pushed SHA so a stale review of a superseded commit isn't conflated with
  the current state. The user uses this to know when the automated pass
  is done vs. still pending.
- **Read the Codex verdict, don't infer it.** It reacts to the PR body
  (`issue_read` → `reactions`), not to a review thread, whose `Useful?` bar
  reads true on any PR it has commented on. `eyes` means reading, `+1` means
  clean, and Codex revokes it on push — so a visible one belongs to the
  visible head, and `+1` with green CI is a merge. The count names no
  author, so leave PR-body reactions to Codex: nobody else's is revoked, and
  a review is the attributable form, naming the commit it read. Findings
  arrive as review comments, as a top-level comment, or as a review — read
  `get_review_comments`, `get_comments` and `get_reviews` to the last page,
  since all three page oldest first — and they block the merge until fixed
  or rebutted; an acknowledgement is not an answer. Nothing from Codex since
  the push, five minutes on, means it never picked it up — comment `@codex
  review`, once. Reading the verdict is a protocol, not a glance: a state
  report draws on ALL the sources — the PR-body reactions, the reviews, the
  review comments and issue comments to their last pages, and the `codex`
  commit status where the ruleset requires it (a separate API surface from
  check runs) — because the reaction is only the clean channel, and
  `updated_at` moving without a reaction usually means unread findings.
- **Skip echo events silently.** `mcp__github__add_reply_to_pull_request_comment`
  / `add_issue_comment` post under whichever GitHub identity backs the MCP
  auth (typically the repo owner's), so a moment after you post a reply the
  same body comes back as a webhook event authored by that identity. That's
  the echo of your own reply, not user feedback — treat it as a duplicate
  and continue the in-progress task without a chat-side acknowledgement.
  The test is "did *I* just post this body?", not "who is the author?" — a
  real review comment from the same identity still gets the usual
  reply-or-resolve handling.
- **CI posts snapshot image diffs as a PR comment — don't hand-post.**
  The GitHub mobile app shows "Binary files not rendered" for any binary
  diff (added or modified), so PNG changes in the Files tab — including
  the bot's `ci: regenerate UI snapshots` commits — are invisible from
  the user's phone. The "Post snapshot image diffs as a PR comment" step
  in `ci.yml` handles this: on every same-repo PR run it diffs
  `app/snapshots/` against the merge-base and upserts a single
  `<!-- ui-snapshot-diffs -->`-marked comment embedding each affected
  image, SHA-pinned, with before/after side by side for modified files
  (markdown-embedded images render fine in the mobile app even though
  file diffs don't). Hand-posted comments are retired — they historically
  suffered typo'd / stale-SHA image URLs. Only fork PRs (where CI can't
  comment) still warrant a manual comment, in the same format:
  `![label](https://github.com/<owner>/<repo>/raw/<sha>/<path>.png)`,
  before + after labelled by SHA for modified files, just the new image
  for added ones.
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
  **Exception:** Sandboxes without remote Git support, such as Codex cloud, may
  continue without fetching `origin`; state that the versionCode could not be
  verified from the full history.
- **Canceling the watch**: see the polling bullet under "Autonomy".

## CI

- Two jobs: `JVM unit tests` runs `:core:*:test` + `:app:testDebugUnitTest`;
  `Android debug build` runs `:app:assembleDebug`. Both upload artifacts;
  Roborazzi PNG snapshots upload as `ui-preview-snapshots` from the
  JVM-tests job.
- **Job timings, measured 2026-07-24** — whole-job wall clock, with the
  dominant step in parentheses:
  - `JVM unit tests` — ~4m45s on a PR, ~5m on `main` (`Run unit tests`
    step ~4m).
  - `Android debug build` — ~4m20s on a PR (`Assemble debug APK` step
    ~3m20s), ~9m30s on `main`.

  **Re-date this list when you refresh it.** The previous figures (~2m /
  ~3.5m) carried no date, had drifted well out of true, and cost an agent
  a wrong "significant regression" call against a run that was in fact
  slightly *faster* than baseline.
- **Compare like with like: PR against PR, `main` against `main`.** The
  `main` Android job is roughly double the PR one because `Bundle release
  AAB` (~4m30s), Firebase App Distribution, and the Play upload only run
  there — all three report `skipped` on a PR. Comparing a PR job against a
  `main` number, or against a *step* time rather than the job total,
  manufactures a regression that doesn't exist.
- After pushing, **wait for CI** before claiming a change works on Android.
  Don't busy-poll inside the turn — the webhook subscription delivers events.
  The scheduled PR check under "Autonomy" is the exception, and the reason it
  exists: those events get dropped often enough to stall a PR silently.
- **Report significant CI timing regressions.** After CI finishes on a push,
  compare the new timings against recent runs of the same job *on the same
  kind of ref* (see above). Only call out *significant* slowdowns (rule of
  thumb: >25% or >30s on a job under ~5min) — don't narrate routine wobble,
  and check the numbers against a real recent run rather than the figures
  above, which are a sanity check and not a live baseline. When you do
  report one, name the likely cause: a new heavy dependency (Robolectric
  cold start, a build-tools download), a slow new test, cache invalidation.
  Spotting a real regression early lets the user decide whether to invest
  in mitigation before more tests pile on.

## Kotlin / Compose gotchas

- **`/*` inside KDoc opens a nested block comment.** Kotlin counts pairs, so
  a literal `/*` inside `/** … */` (e.g. a path like `dir/*.png`) opens an
  inner comment that the outer `*/` then closes — leaving the outer comment
  unterminated through to EOF. Compiler reports "Unclosed comment" at the
  end of the file, far from the actual cause. Avoid literal `/*` in doc
  text: write `dir/` or `<path>.png` instead of `dir/*.png`.
- **Kotlin's `lowercase()` / `uppercase()` are already locale-invariant —
  don't "fix" them to `Locale.ROOT`.** Unlike Java's `String.toLowerCase()`,
  the no-argument Kotlin stdlib overloads fold with the invariant locale, so
  they are *not* subject to the Turkish dotless-ı trap. `code.lowercase()`
  on an ISO country code is correct as written; rewriting it to
  `code.lowercase(Locale.ROOT)` is a pure no-op. The locale-sensitive form is
  the explicit `lowercase(locale)` overload — that one is only for
  user-visible text (see `InsightFormatter.decapitalize`). Some sites do pass
  `Locale.ROOT` explicitly; that's documentation, not a behavior difference.
  This has been mistakenly "fixed" before — verify with a test under
  `Locale.setDefault(Locale.forLanguageTag("tr-TR"))` before touching any of
  it. (The default locale *does* matter for `String.format` / `"%d".format(x)`
  without a `Locale` argument — that one is a real trap.)
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
- **An unattached `ComposeView` composes but never paints.** To rasterise a
  composable to a `Bitmap` off-screen from a non-Activity context (e.g. a
  Glance widget — Glance emits RemoteViews and can't host Compose/Vico), a
  detached `ComposeView` with `setContent` + `measure`/`layout`/`draw` yields
  a *blank* bitmap. It needs a real window: host the `ComposeView` in a
  `Presentation` on a `VirtualDisplay` backed by an `ImageReader`, set the
  ViewTree lifecycle / savedstate / viewmodel owners, `show()`, and sample the
  reader until the frame settles — Vico (and any async content) draws a few
  frames *after* first composition, so capturing too early misses the chart.
  See `widget/ComposeRender.kt`; don't "simplify" it back to a detached view.

## Error handling

- **Don't silently swallow exceptions.** A bare `catch (_: Throwable) {}` or
  `catch (e: Exception) { /* ignore */ }` hides real failures in the field
  and burns hours when something eventually breaks. Every catch block needs
  to do three things: **log** the exception with enough context for a reader
  to identify the failed call — the operation, the failure mode — but
  **sanitized context only** (route through the usual logger, not `println`
  or `Log.e` with a bare message). Never log calendar titles, locations, GPS
  coordinates, insight prose, or a BYOK key; the *Privacy* section applies to
  logs too, so redact or summarize ("geocode failed: 404", not the address).
  **Clean up** what
  the `try` block acquired — closeables, network handles, partial writes,
  in-progress UI state — so a failure doesn't leak resources or leave the
  app half-mutated (`use { … }` / `finally` blocks for closeables);
  and **handle the edge case explicitly** — pick how the caller sees this
  failure (default value, null, sentinel error result, rethrow as a domain
  exception) rather than letting control fall through. Catching
  `Throwable` (or `Exception` blanket) also swallows `CancellationException`
  in coroutine code, which breaks structured concurrency — narrow the type,
  or rethrow `CancellationException` first. If you genuinely do want to
  ignore a specific failure, name the reason in a one-line comment
  ("Open-Meteo returns 4xx for unknown geocodes, treat as empty result")
  and still log at debug so it's traceable.

## Privacy

- **Never put user PII in any artifact that leaves this machine.** That
  includes commit subjects and bodies, PR titles / descriptions / comments,
  review replies, issue text, branch names, code comments, test fixtures,
  snapshot data, and anything else that ends up on GitHub, the Play
  Console, or in logs. PII covers — but isn't limited to — the user's
  real name, email address, home / work / current location, GPS
  coordinates, addresses, phone numbers, calendar event titles or
  attendees, contact names, device identifiers, BYOK API keys, and the
  contents of `google-services.json`. Use generic placeholders
  (`alice@example.com`, "City A", `lat = 0.0`, "Morning standup") in
  examples, fixtures, and reproductions. If a user-supplied bug report
  contains PII, paraphrase it in the commit / PR — don't quote verbatim.
  When in doubt, ask before pushing.
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

## Language

- **Use US English everywhere.** Base English strings
  (`app/src/main/res/values/strings.xml`), commit subjects and bodies, PR
  titles / descriptions, code comments, and docs all default to American
  spelling and idiom: `color` not `colour`, `center` not `centre`,
  `canceled` not `cancelled`, `gray` not `grey`, `analyze` not `analyse`.
  The base `values/` locale *is* `en-US` — it's the final fallback for
  every locale, so authoring it in American spelling keeps the default
  consistent. Commit subjects matter doubly: CI funnels them straight into
  `whatsnew-en-US`, the US English Play "What's new", so a stray
  Britishism ships to users. **Exception — localized English files keep
  their own conventions.** `values-en-rGB` deliberately overrides base
  with British spelling, and `values-en-rAU` / `values-en-rZA` inherit
  `en-rGB` via Android's API 24+ locale fallback (not base `en-US`) — so
  don't "correct" British spelling in any `values-en-*` file, and don't
  assume an untranslated en-AU/en-ZA string resolves to US English. Other
  non-English `values-*` files likewise keep their own conventions; this
  rule is about the default English we author in base `values/` and in
  our commits, PRs, comments, and docs.

- **Plain, honest voice in user-facing copy.** Write privacy
  disclosures (`PRIVACY.md`), changelog / "What's new" bullets, settings
  and UI strings, and docs the way you'd say them out loud — direct and
  specific, no weasel words. If the app does something, name it plainly:
  the free voice "signs in anonymously," not "has no accounts you sign
  in to." Never hedge a true-but-narrow claim to imply something broader;
  if a caveat matters, state it outright. Prefer the concrete term ("an
  anonymous, random ID — no name, email, or password") over the vague
  reassurance ("no data held about you"). Applies doubly to anything
  describing what crosses the device boundary — see the Privacy section:
  the reader should come away knowing exactly what happens, not
  comfortably misled.

## Domain conventions

- Clothes rules and outfit suggestions both look at *feels-like*
  temperatures (apparent, wind-chill / humidity adjusted) — never raw 2 m
  air temperature. That's what the user actually experiences stepping
  outside.
- **One number drives every rain surface — the blended-consensus chance of
  rain.** The prose's "chance of rain", the umbrella / rain-jacket clothes
  defaults, and the conditions-strip droplet all key off the single
  blended probability of precipitation that already lives on
  `DailyForecast.precipitationProbabilityMaxPct` / the blended
  `HourlyForecast.precipitationProbabilityPct` (the cross-model consensus
  blend — see `ConsensusBlend.kt`). Two bars: **≥ 10%** → prose "chance of
  rain" + umbrella default + strip droplet; **≥ 50%** → prose confident
  "rain" + rain-jacket default. There is no longer a per-model /
  weather-code / trace-amount rain path: rain is never surfaced from a
  lone model's drizzle code, and the umbrella default is a plain
  `PrecipitationProbabilityAbove(10.0)` (rain jacket
  `PrecipitationProbabilityAbove(50.0)`), not an OR of probability and a
  code floor. The morning insight's evening extras still has two emission
  paths off this number: when a clothes rule fires for the evening window
  the clause names the item and folds in the rain ("Tonight, rain, bring a
  jacket."); when no rule fires but the blended chance clears 10% in the
  tonight window and the user has an evening event with a location, the
  clause emits as a bare rain warning ("Tonight, rain." / the hedged
  chance-of-rain wording for the POSSIBLE tier). The prose deliberately
  doesn't pin a peak hour ("rain at 9pm") — the peak time still rides the
  clause data for the chart and cast card. A post-midnight peak appends
  "overnight"; an evening peak adds no timing word. Snow never fires the
  rain-gear defaults: the rule engine's snow gate (`EvaluateClothesRules`,
  via `isFrozenPrecipitation`) suppresses rain gear on snow days even when
  snow clears the probability bar.
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
- **All three modules build and test on this VM.** The Cursor Cloud VM has
  the Android SDK installed, so you can run the full CI-equivalent locally:
  ```
  export ANDROID_HOME=/opt/android-sdk
  ./gradlew :core:domain:test :core:data:test :app:testDebugUnitTest
  ./gradlew :app:assembleDebug
  ```
- **Android Lint is available** for the `:app` module via AGP; run
  `./gradlew :app:lintDebug` when app-side validation needs lint coverage.
  There is no separate `ktlint` plugin wired, so Kotlin style checks are not
  available beyond compiler warnings.
- **This is a client-only Android app.** No backend server, database, or
  Docker containers to start. The app calls Open-Meteo (free, keyless) and
  optionally Gemini (BYOK). Testing is purely JVM-based.
- **`CLAUDE.md` is a symlink** to `AGENTS.md` — editing either edits both.
