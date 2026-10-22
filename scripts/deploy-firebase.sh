#!/usr/bin/env bash
# Deploys the Cloud Functions and the Firestore rules and indexes from this
# checkout: installs the functions' dependencies afresh, builds and tests
# them, then deploys with the npm build of the Firebase CLI through npx,
# whichever `firebase` is on PATH. The standalone CLI binary breaks npm
# scripts in its hooks (docs/gemini-tts-proxy.md → Set up the function
# locally); npx sidesteps it and shares its login.
#
# Usage: scripts/deploy-firebase.sh [projectId]   (or: make deploy PROJECT=…)
# Without a projectId, the CLI uses the project picked by `firebase use`.
set -euo pipefail

cd "$(dirname "$0")/.."
project="${1:-}"

head="$(git rev-parse --short HEAD)"
if [[ -n "$(git status --porcelain -- functions firebase.json firestore.rules firestore.indexes.json)" ]]; then
  echo "Deploying $head with uncommitted changes to the functions or Firestore files." >&2
fi

(cd functions && npm ci && npm test)

args=(deploy --only "functions,firestore")
[[ -n "$project" ]] && args+=(--project "$project")
npx --yes firebase-tools@15 "${args[@]}"
