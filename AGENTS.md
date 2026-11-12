---
trigger: always_on
alwaysApply: true
last_modified: 2026-11-12
---

# ClothesCast

An Android app (Kotlin, Compose) that says what to wear: `:app` plus the pure-Kotlin
`:core:domain` and `:core:data`. `SPEC.md` is the product, `docs/agents-reference.md` the
background.

## Fleet conventions

Shared by every mikelward repo and the user-level agent config; canonical in mikelward/conf
`agents/AGENTS.md`. A repo's Project section overrides it.

### This file

- **Session start**. At the start of every session, print the full absolute path of the
  `AGENTS.md` you loaded and its `last_modified` date.
- **Canonical**. AGENTS.md is canonical; `CLAUDE.md` and `GEMINI.md` point to it. Keep it
  brief, generic to all agents (noting per-agent exceptions), and under 22,000 bytes.
  Bump `last_modified` whenever you edit this file.

### Talking to the user

- **Questions**. One at a time; wait for the answer.
- **Messages**. Send one only for a question, a blocker, something to act on, or a change
  of plan. No progress reports or narrating events.
- **Claude Code**. It reliably shows only a turn's last message; put everything there.
- **Replies**. Short, key point first, ending with open PR links and any pending
  question.
- **Network access**. If you want access to a host and you can't access it or a mirror,
  ask the user to allowlist the domain(s) along with the purpose.

### Git

- **Branches**. `<agent>/<topic>` off a fresh `origin/main` (fetch by refspec). Never
  commit to `main`.
- **Your branches**. Push, force-push with lease, and delete them freely. Ask before
  touching others', `main`, settings, secrets or paid services.
- **Commits**. One per logical change; fold in review fixes.
- **Subjects**. Changelog lines: imperative, the user-visible change, ≤ 70 chars. PR
  titles match.
- **Prefixes**. `docs:`, `test:`, `build:`, `refactor:` mark what users won't notice. No
  `feat:` or `fix:`.
- **Model names**. Never in anything pushed.
- **After a merge**. Branch afresh.
- **Shallow clones**. Unshallow before trusting history or calculating a version code.
- **Language**. US English everywhere.

### Pull requests

- **Opening**. Open the PR unasked; update its title and body before each push.
- **Descriptions**. Scope, rationale and test plan, plus the change's privacy,
  reliability and cost impact (say "none" where there is none).
- **TODO.md**. Tick an item off in the PR that implements it, not a follow-up. Don't
  spend a CI run on trivia.
- **Watching**. PR events plus one scheduled check (events miss CI success and Codex's
  👍).
- **Auto-merge**. Enable it on PRs that target `main`; stack dependent ones.
- **Base moved**. After a merge, tell open PRs on that base whose body ends with a session
  URL.
- **Merge or close**. Answer remaining threads and stop watching.

### Reviews

- **Codex**. It reviews automatically. Fix, decline with a reason, or defer to `TODO.md`
  every comment; reply citing the commit, then resolve.
- **Merit**. Codex over-reads rules, privacy especially: quote and decline rather than
  cripple the code. Design changes are the user's call.
- **Verdict**. 👍 is clean, 👀 is reviewing. Only the code review gates merging: its
  findings (review comments, PR comments, reviews) block it. Security reviews often
  hang; don't wait on them.
- **Hung review**. `@codex review` after five quiet minutes, again an hour later, then
  tell the user.
- **After a 👍**. Ask before unneeded changes.
- **Reports**. Give each review's findings in your next message.

### Autonomy

- **Fix proactively**. Lint, test, presubmit, CI and review failures, unasked.
- **Proactive refactors**. Repeated bugs in one area, duplicated code, or a change with
  twins elsewhere: suggest the redesigned or shared version.
- **Drive to merge**. Once the details are agreed, see the change through unattended:
  implement it, open the PR, keep it mergeable (fix CI, answer reviews, rebase when it
  conflicts or falls behind), and merge once CI is green and Codex 👍s, or let
  auto-merge.
- **Drive**. Take `TODO.md` items one at a time and drive each to merge, until done or
  stopped.
- **Autopilot**. Drive without asking, logging guesses in `TODO.md` under *Decisions
  needing review*. Still ask before anything irreversible, outside this repo, or private.
- **Red baseline**. Fix existing failures first, in their own commit.
- **Lasting fixes**. Fix the cause in a repo. Don't propose a step that lasts only this
  session (stopping a service, a setting in this container) or a hand edit to a dotfile:
  neither persists.

### Quality

- **Tests**. Every behavior change gets one; a bug fix's fails first. Assert behavior,
  not structure. No sleeps or retries; never disable a failing check.
- **Docs**. Keep `SPEC.md` and `README.md` current.
- **CI**. Wait for it before claiming success.
- **Errors**. Never swallow one silently: every catch logs it with context (no user
  data), cleans up what the `try` acquired, and handles the case explicitly. To ignore
  one on purpose, say why in a comment and still log it at debug level.
- **Logs**. Every process logs that it started, that it ended and how, and each error,
  where that outlasts it, a logout and a reboot: the journal, or a file under
  `~/.local/state`. Never only `/tmp`, a runtime directory or `/dev/null`.
- **Cost**. State the cost and reliability of new dependencies or services up front.
- **Privacy**. No user data (personal paths, hostnames, tokens, keys, private remotes)
  leaves this machine; use placeholders.
- **Portability**. Flag anything that won't run on Linux and macOS.
- **UI text**. Words a person would say, as few as do the job. Say what a setting does;
  leave out what works by default, what the screen already shows, and how it works.
- **Terminology**. Standard domain terms, never coined ones: "Text console layout is
  Dvorak", not "The text consoles type Dvorak"; "dimming inactive windows", not "the dim".

## Project

- **Build**. CI runs `./gradlew :core:domain:test :core:data:test :app:testDebugUnitTest`
  and `./gradlew :app:assembleRelease`; both should work here. For pure-Kotlin work,
  `cd core && ../gradlew :core:domain:test` is faster. `.claude/hooks/session-start.sh`
  sets up the SDK; if `:app` still won't build, say so.
- **What's new**. Every commit subject ships verbatim to Play's "What's new"
  (`whatsnew-en-US`) as a `• ` bullet, oldest first since the last successful `main` run,
  repeats once. So title each like a changelog line (maintainer, 2026-10-23): an imperative
  verb, then the feature in the user's words, sentence case, ≤ ~80 chars, e.g. `Fix …`,
  `Improve …`, `Add …` (`Show bigger outfit icons on the Today screen`, not `:app + :core —
  glanceable outfit-preview icons`), with an optional parenthetical saying how. Scenario,
  mechanism, module names, file paths and "fixes #123" go in the body. PR titles match.
  This overrides the fleet's ≤ 70.
- **Prefixes**. Prefix what ships nothing user-visible (CI filters these out), overriding
  the fleet's list: `ci:`, `test:` (only tests/fixtures/snapshots — `src/test/`,
  `src/androidTest/`, `app/snapshots/`), `internal:` (tooling, build, refactors, project
  meta), `docs:` (`docs/`, `*.md` at any depth, dotfiles — `PRIVACY.md` included, since
  "What's new" is a store field nobody sees at update time). Never `build:` or
  `refactor:`: CI doesn't filter them, so they ship. A mixed test + production commit is
  bare. A dependency the app ships is bare; a test-/build-only bump you write keeps its
  prefix (the weekly batch lands bare).
- **500 characters**. Play caps "What's new" there: CI drops trailing bullets and appends
  `…`, so squash supporting commits into the one telling the user-facing story.
- **After a merge**. Report the versionCode (`git rev-list --count origin/main` after an
  unshallowed refspec fetch): `Need versionCode 72 (b81c23d) or higher to test PR #52's
  fix`. Without remote Git, say it couldn't be verified.
- **Release notes since N**. Up to five `• ` end-user bullets in one code block for
  commits N+1..`origin/main` (freshly fetched; offline only from a complete local `main`,
  saying it may be behind), worded as titles, one per feature (a reworked one by its final
  state; read bodies where unclear), ranked by what the user risks without it (broken or
  wrong first, polish last). Skip prefixed/docs/CI commits, dependency bumps (unless
  nothing else changed — then one bullet) and anything the range added then undid. Range
  above, one line below naming what was omitted.
- **UI**. 4dp grid, symmetric, concise copy, standard Material icons.
- **Weather**. Use feels-like temperature. One chance-of-rain number drives every rain
  surface; snow never calls for rain gear.
- **Layers**. `:core:domain` stays pure Kotlin; `:app` owns Android and the Gemini model.
- **Gemini model names**. Check them against the live API, not search results.
- **Kotlin traps**. `/*` inside KDoc opens a comment; `String.format` needs a `Locale`;
  an off-screen `ComposeView` needs a real window.
- **Privacy**. Only what `PRIVACY.md` lists leaves the device (coordinates for forecasts,
  text for online speech); Firebase never carries locations, calendar data, insight text
  or keys. Flag any change to what leaves.
- **Voice**. Plain and honest. Base strings are US English; `values-en-rGB` is British on
  purpose.
- **Backend**. Delete a function only once the app that stops calling it has rolled out.
- **Shared script**. `scripts/publish-github-release.sh` is identical across the Android
  apps: change them all.
