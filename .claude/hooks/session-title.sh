#!/usr/bin/env bash
# UserPromptSubmit hook: keep the session title in sync with the current work.
#
# Asks a fast model for a short, hyphenated label (<=16 chars) derived from
# the latest prompt and emits it via the `sessionTitle` hook output field
# (Claude Code >= ~2.1.96). The name evolves as the conversation pivots.
#
# Recursion guard (two layers): the naming call is itself `claude -p`, and
# UserPromptSubmit hooks run under `claude -p` too, so a naive call would
# re-enter this hook and loop until timeouts. We (1) run the inner call from
# an empty temp dir so it never discovers this project's .claude/settings.json
# (no project hooks, and no CLAUDE.md / git status to distract the namer), and
# (2) export CC_TITLE_HOOK_ACTIVE and bail at entry when it's set.
#
# "Never clobber a manual name" is best-effort: it works only on builds that
# pass `session_title` on UserPromptSubmit input. Per the current docs that
# field is SessionStart-only, so where it's absent `cur_title` reads empty and
# the title will keep auto-updating even after a manual /rename.
#
# Degrades silently: missing jq/claude, an unauthenticated or timed-out
# headless call, or empty output all leave the title unchanged.
set -uo pipefail

# Layer 2 of the recursion guard: an inner `claude -p` re-enters this hook.
[ -n "${CC_TITLE_HOOK_ACTIVE:-}" ] && exit 0

input="$(cat)"

# Need jq to parse the hook payload; without it, do nothing rather than guess.
command -v jq >/dev/null 2>&1 || exit 0
command -v claude >/dev/null 2>&1 || exit 0

prompt="$(printf '%s' "$input" | jq -r '.prompt // ""')"
session_id="$(printf '%s' "$input" | jq -r '.session_id // "default"')"
cur_title="$(printf '%s' "$input" | jq -r '.session_title // ""')"

# Nothing to name from.
[ -n "$prompt" ] || exit 0

marker="${TMPDIR:-/tmp}/cc-autotitle-${session_id//[^A-Za-z0-9_-]/_}"
backoff="$marker.off"
last_set=""
[ -f "$marker" ] && last_set="$(cat "$marker" 2>/dev/null)"

# Once we've backed off (manual rename seen), stay silent for the session.
[ -f "$backoff" ] && exit 0

# Respect a human-set name (only observable when the build supplies
# session_title): if a title exists and it isn't the one we last wrote,
# someone renamed it deliberately. Back off for the rest of the session.
# The flag lives in a separate file (not a sentinel string in $marker) so a
# legitimately generated title like "backed-off" can't be mistaken for it.
if [ -n "$cur_title" ] && [ "$cur_title" != "$last_set" ]; then
  : >"$backoff" 2>/dev/null || true
  exit 0
fi

# Ask a fast model for a label. Run from a fresh, private temp dir (layer 1 of
# the recursion guard) so the inner call discovers no .claude/settings.json or
# CLAUDE.md — neither this project's nor stale leftovers from a previous run.
# A `mktemp -d` per invocation guarantees that; CC_TITLE_HOOK_ACTIVE is layer 2.
# Keep it short so prompt submission isn't noticeably delayed; on timeout/
# failure we emit nothing.
namedir="$(mktemp -d "${TMPDIR:-/tmp}/cc-title.XXXXXX" 2>/dev/null)" || exit 0
trap 'rm -rf "$namedir"' EXIT

# Portable timeout: GNU `timeout` isn't on a stock macOS PATH (Homebrew
# coreutils installs it as `gtimeout`). Prefer whichever exists; if neither
# does, run unwrapped rather than failing the hook into a silent no-op. Use a
# plain string (not an array) so word-splitting works under set -u on macOS's
# bash 3.2, where "${arr[@]}" on an empty array is an "unbound variable" error.
to=""
if command -v timeout >/dev/null 2>&1; then to="timeout 25"
elif command -v gtimeout >/dev/null 2>&1; then to="gtimeout 25"
fi

# Model: `haiku` is fast and cheap and resolves on current Claude Code CLIs.
# The docs only spell out `sonnet`/`opus` aliases, so on a build where `haiku`
# doesn't resolve, point CC_TITLE_MODEL at a full Haiku id (e.g.
# claude-haiku-4-5) instead of editing this file. A bad value just makes the
# call fail and the title is left unchanged.
model="${CC_TITLE_MODEL:-haiku}"

# shellcheck disable=SC2086  # intentional word-split of $to into cmd + arg
raw="$(cd "$namedir" && CC_TITLE_HOOK_ACTIVE=1 $to claude -p --model "$model" \
  "Name this coding task as a short kebab-case label, <=16 characters, \
lowercase. Prefer short punchy words (kill not delete, fix not repair). \
Reply with ONLY the label, no quotes or other text: $prompt" 2>/dev/null)" || exit 0

# Sanitize: lowercase, keep [a-z0-9-], collapse repeats, trim.
name="$(printf '%s' "$raw" \
  | tr '[:upper:]' '[:lower:]' \
  | tr -c 'a-z0-9-' '-' \
  | sed -E 's/-+/-/g; s/^-+//; s/-+$//')"

# Fit to <=16 chars on whole-word (hyphen) boundaries where possible.
limit=16
if [ "${#name}" -gt "$limit" ]; then
  fitted=""
  IFS='-' read -ra words <<< "$name"
  for w in "${words[@]}"; do
    [ -z "$w" ] && continue
    cand="$w"; [ -n "$fitted" ] && cand="$fitted-$w"
    if [ "${#cand}" -le "$limit" ]; then fitted="$cand"; else break; fi
  done
  # First word already too long: hard cut.
  [ -z "$fitted" ] && fitted="$(printf '%s' "$name" | cut -c1-"$limit" | sed -E 's/-+$//')"
  name="$fitted"
fi

[ -n "$name" ] || exit 0

printf '%s' "$name" >"$marker" 2>/dev/null || true

jq -n --arg t "$name" \
  '{hookSpecificOutput: {hookEventName: "UserPromptSubmit", sessionTitle: $t}}'
