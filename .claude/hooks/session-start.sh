#!/bin/bash
# SessionStart hook for Claude Code on the web. Installs the Android SDK so
# `:app` Gradle tasks (assembleDebug, testDebugUnitTest, lintDebug) work in
# the sandbox. CI uses android-actions/setup-android@v3 with the same
# package set; mirroring it here gives the agent CI parity.
#
# Skipped outside the remote sandbox so local dev environments aren't
# clobbered. Idempotent: re-runs after install are a no-op.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

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
    echo "ANDROID_HOME must be an absolute path, got: '$ANDROID_HOME'" >&2
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

# mikelward/androidlog — the shared debug log, included as a composite build
# by settings.gradle.kts, which fails the build outright if it is missing. So
# this runs *before* the SDK fast path below: a warm sandbox that already has
# the SDK still needs the checkout, and returning early would leave every
# Gradle invocation in the session dead on settings evaluation.
#
# Ahead of the fast path also means it refreshes every session rather than
# pinning whatever `main` was the first time this container ran — there is no
# version here, so "what @main says now" is the only correct answer.
#
# Best-effort: an unreachable GitHub keeps whatever checkout is already there
# and says so, rather than failing session startup over it.
ANDROIDLOG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/.androidlog"
if [ -d "$ANDROIDLOG_DIR/.git" ]; then
  git -C "$ANDROIDLOG_DIR" fetch --depth 1 origin HEAD \
    && git -C "$ANDROIDLOG_DIR" reset --hard FETCH_HEAD \
    || echo "androidlog: could not refresh $ANDROIDLOG_DIR — keeping the existing checkout." >&2
elif [ ! -d "$ANDROIDLOG_DIR" ]; then
  git clone --depth 1 https://github.com/mikelward/androidlog "$ANDROIDLOG_DIR" \
    || echo "androidlog: clone failed — Gradle will not configure until $ANDROIDLOG_DIR exists." >&2
fi

# Fast path: SDK already installed (warm sandbox or re-run). Just re-export.
if [ -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ] \
  && [ -d "$ANDROID_HOME/platforms/android-37.0" ] \
  && [ -d "$ANDROID_HOME/build-tools/36.0.0" ]; then
  echo "Android SDK already installed at $ANDROID_HOME — skipping install."
  persist_env
  exit 0
fi

echo "Installing Android SDK to $ANDROID_HOME ..."
mkdir -p "$ANDROID_HOME/cmdline-tools"

if [ ! -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]; then
  TMPZIP="$(mktemp --suffix=.zip)"
  trap 'rm -f "$TMPZIP"' EXIT
  curl -fsSL --retry 3 --retry-delay 2 -o "$TMPZIP" "$CMDLINE_TOOLS_URL"
  # The zip extracts to `cmdline-tools/`, but sdkmanager expects the layout
  # `cmdline-tools/latest/bin/sdkmanager`, so move it into place.
  TMPDIR="$(mktemp -d)"
  unzip -q "$TMPZIP" -d "$TMPDIR"
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
# sdkmanager itself succeeded). Disable pipefail just for this line and
# read sdkmanager's exit code directly from PIPESTATUS.
set +o pipefail
printf 'y\n%.0s' $(seq 1 50) | sdkmanager "$SDK_ROOT_FLAG" --licenses >/dev/null
license_rc=${PIPESTATUS[1]}
set -o pipefail
if [ "$license_rc" -ne 0 ]; then
  echo "sdkmanager --licenses failed (exit $license_rc)" >&2
  exit "$license_rc"
fi
sdkmanager "$SDK_ROOT_FLAG" --install "$PLATFORM" "$BUILD_TOOLS" "$PLATFORM_TOOLS" >/dev/null

persist_env
echo "Android SDK ready at $ANDROID_HOME."
