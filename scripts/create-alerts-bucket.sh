#!/usr/bin/env bash
# Creates the Cloud Storage bucket the alerts ingester publishes to
# (`<projectId>-alerts`, docs/ALERTS.md → Architecture → B), once per
# project: anyone may read a file by name (no listing), and the functions'
# runtime account may write. It holds only public alert data. Safe to re-run.
#
# Usage: scripts/create-alerts-bucket.sh <projectId>   (or: make alerts-bucket PROJECT=…)
set -euo pipefail

project="${1:?usage: $0 <projectId>}"
bucket="gs://${project}-alerts"

if gcloud storage buckets describe "$bucket" --project "$project" >/dev/null 2>&1; then
  echo "$bucket exists; checking its access."
else
  # us-central1, beside the functions; inside Cloud Storage's always-free region.
  gcloud storage buckets create "$bucket" --project "$project" \
    --location us-central1 --uniform-bucket-level-access --no-public-access-prevention
fi

# Read by name only: legacyObjectReader grants objects.get without objects.list.
gcloud storage buckets add-iam-policy-binding "$bucket" --project "$project" \
  --member allUsers --role roles/storage.legacyObjectReader >/dev/null

# Second-generation functions run as the default compute service account.
number="$(gcloud projects describe "$project" --format 'value(projectNumber)')"
gcloud storage buckets add-iam-policy-binding "$bucket" --project "$project" \
  --member "serviceAccount:${number}-compute@developer.gserviceaccount.com" \
  --role roles/storage.objectAdmin >/dev/null

echo "Ready: https://storage.googleapis.com/${project}-alerts/v1/index.json (after the first ingester run)."
