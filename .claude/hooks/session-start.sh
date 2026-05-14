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
ANDROID_SDK_ROOT="$ANDROID_HOME"
CMDLINE_TOOLS_DIR="$ANDROID_HOME/cmdline-tools/latest"
# Pinned to the version CI installs via android-actions/setup-android@v3.
# Bump in lockstep with `app/build.gradle.kts` (compileSdk) and ci.yml.
PLATFORM="platforms;android-35"
BUILD_TOOLS="build-tools;35.0.0"
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
  && [ -d "$ANDROID_HOME/platforms/android-35" ] \
  && [ -d "$ANDROID_HOME/build-tools/35.0.0" ]; then
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

# Accept every license prompt non-interactively. `printf | sdkmanager` would
# trip `set -o pipefail` if sdkmanager closes stdin before consuming every
# `y\n` (printf gets SIGPIPE, exits 141, pipeline fails — even though
# sdkmanager itself succeeded). Disable pipefail just for this line and
# read sdkmanager's exit code directly from PIPESTATUS.
set +o pipefail
printf 'y\n%.0s' $(seq 1 50) | sdkmanager --licenses >/dev/null
license_rc=${PIPESTATUS[1]}
set -o pipefail
if [ "$license_rc" -ne 0 ]; then
  echo "sdkmanager --licenses failed (exit $license_rc)" >&2
  exit "$license_rc"
fi
sdkmanager --install "$PLATFORM" "$BUILD_TOOLS" "$PLATFORM_TOOLS" >/dev/null

persist_env
echo "Android SDK ready at $ANDROID_HOME."
