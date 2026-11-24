#!/bin/bash
# SessionStart hook for Claude Code on the web. Installs the Android SDK so
# `:app` Gradle tasks (assembleDebug, testDebugUnitTest, lintDebug) work in
# the sandbox. CI uses android-actions/setup-android@v3 with the same
# package set; mirroring it here gives the agent CI parity.
#
# Skipped outside the remote sandbox so local dev environments aren't
# clobbered. Idempotent: re-runs after install are a no-op.
set -euo pipefail

checkout=$(cd "$(dirname "$0")/../.." && pwd)

# Deepen the clone before anything reads history -- see scripts/unshallow.sh.
#
# Above the remote-only guard on purpose, unlike everything below it: the
# versionCode is `git rev-list --count`, and a shallow clone answers that with a
# confident wrong number wherever the clone came from. On a complete clone it is
# a no-op that says so. Run directly rather than through `sh` so it needs nothing
# on PATH, and best-effort: it reports its own failures, and a clone it could
# not deepen is never a reason to refuse to start the session.
(cd "$checkout" && "$checkout/scripts/unshallow.sh") || true

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# The hook's record: when it started, how it ended, and the SDK tools' own
# output, kept where it outlasts the session's terminal.
log_dir="${XDG_STATE_HOME:-$HOME/.local/state}/clothescast"
mkdir -p "$log_dir"
LOG="$log_dir/session-start.log"
log() { printf '%s session-start: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$LOG" >&2; }
TMPZIP=""
finish() {
  status=$?
  if [ -n "$TMPZIP" ]; then rm -f "$TMPZIP"; fi
  if [ "$status" -eq 0 ]; then log "ended: Android SDK ready"; else log "ended: failed with status $status (details in $LOG)"; fi
}
trap finish EXIT
log "started"

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
# sdkmanager's LocalRepoLoaderImpl Files.walk()s the SDK root looking for
# package.xml. If the root resolves to "/" (empty / relative ANDROID_HOME,
# or a CWD-based fallback when the hook runs with cwd=/), the walk descends
# into /proc and the JVM dies on /proc/self/task/<tid>/fd/<n>/... with
# "Invalid argument" — exactly the failure we hit on a previous run. Refuse
# anything but an absolute path so the walk always starts somewhere sane.
case "$ANDROID_HOME" in
  /*) ;;
  *)
    log "ANDROID_HOME must be an absolute path, got: '$ANDROID_HOME'"
    exit 1
    ;;
esac
ANDROID_SDK_ROOT="$ANDROID_HOME"
CMDLINE_TOOLS_DIR="$ANDROID_HOME/cmdline-tools/latest"
# Pinned to the version CI installs via android-actions/setup-android@v3.
# Bump in lockstep with `app/build.gradle.kts` (compileSdk) and ci.yml.
PLATFORM="platforms;android-37.0"
BUILD_TOOLS="build-tools;36.0.0"
PLATFORM_TOOLS="platform-tools"
# Released August 2024; the last build before Google started shipping JDK 21
# checks. Any newer cmdline-tools would also be fine — pinning makes the
# install reproducible across warm-cache restores.
CMDLINE_TOOLS_VERSION="11076708"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"

persist_env() {
  if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    {
      echo "export ANDROID_HOME=\"$ANDROID_HOME\""
      echo "export ANDROID_SDK_ROOT=\"$ANDROID_SDK_ROOT\""
      echo "export PATH=\"$CMDLINE_TOOLS_DIR/bin:$ANDROID_HOME/platform-tools:\$PATH\""
    } >> "$CLAUDE_ENV_FILE"
  fi
}

# Fast path: SDK already installed (warm sandbox or re-run). Just re-export.
if [ -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ] \
  && [ -d "$ANDROID_HOME/platforms/android-37.0" ] \
  && [ -d "$ANDROID_HOME/build-tools/36.0.0" ]; then
  log "Android SDK already installed at $ANDROID_HOME; skipping install"
  persist_env
  exit 0
fi

log "installing the Android SDK to $ANDROID_HOME"
mkdir -p "$ANDROID_HOME/cmdline-tools"

if [ ! -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]; then
  TMPZIP="$(mktemp --suffix=.zip)"
  curl -fsSL --retry 3 --retry-delay 2 -o "$TMPZIP" "$CMDLINE_TOOLS_URL" 2>>"$LOG"
  # The zip extracts to `cmdline-tools/`, but sdkmanager expects the layout
  # `cmdline-tools/latest/bin/sdkmanager`, so move it into place.
  TMPDIR="$(mktemp -d)"
  unzip -q "$TMPZIP" -d "$TMPDIR" >>"$LOG" 2>&1
  rm -rf "$CMDLINE_TOOLS_DIR"
  mv "$TMPDIR/cmdline-tools" "$CMDLINE_TOOLS_DIR"
  rm -rf "$TMPDIR"
fi

export ANDROID_HOME ANDROID_SDK_ROOT
export PATH="$CMDLINE_TOOLS_DIR/bin:$PATH"

# Run sdkmanager from inside the SDK home so its cwd is a known-good
# directory: any path resolution that falls back to cwd lands here, not
# at "/" (where Files.walk recurses into /proc and crashes — see the
# absolute-path guard above for the full failure mode).
cd "$ANDROID_HOME"

# Pass --sdk_root explicitly. ANDROID_HOME is exported above and sdkmanager
# is documented to pick it up, but on at least one sandbox run the JVM
# resolved the SDK location to "/" instead and walked /proc to its death.
# Being explicit removes the ambiguity for ~no cost.
SDK_ROOT_FLAG="--sdk_root=$ANDROID_HOME"

# Accept every license prompt non-interactively. `printf | sdkmanager` would
# trip `set -o pipefail` if sdkmanager closes stdin before consuming every
# `y\n` (printf gets SIGPIPE, exits 141, pipeline fails — even though
# sdkmanager itself succeeded). Disable pipefail just for this line, so the
# pipeline's status is sdkmanager's own.
set +o pipefail
# Under `set -e` a bare failing pipeline would end the hook before the check
# below could say why, so its status is caught here instead.
license_rc=0
printf 'y\n%.0s' $(seq 1 50) | sdkmanager "$SDK_ROOT_FLAG" --licenses >>"$LOG" 2>&1 || license_rc=$?
set -o pipefail
if [ "$license_rc" -ne 0 ]; then
  log "accepting the SDK licenses failed with status $license_rc"
  exit "$license_rc"
fi
sdkmanager "$SDK_ROOT_FLAG" --install "$PLATFORM" "$BUILD_TOOLS" "$PLATFORM_TOOLS" >>"$LOG" 2>&1

persist_env
