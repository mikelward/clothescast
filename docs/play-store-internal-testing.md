# Play Store internal testing — auto-publish setup

One-time Play Console + GitHub Secrets setup so every push to `main` produces a
signed AAB that lands on the Play Store **Internal testing** track without you
clicking anything in the Play Console.

## What this gets you

- Push to `main` → CI builds + signs the release AAB → uploads to Play Console
  on the `internal` track with `status: completed` → testers in the internal
  list get the new version on next Play Store check (typically minutes to a
  few hours, depending on Play caching on each device).
- The same signed bundle is also published as a **GitHub prerelease**, tagged
  `v<versionCode>` with `clothescast-<versionCode>.aab` attached and the same
  "What's new" notes. It is not a second way to install anything — nobody
  installs an AAB — it is the durable record of what shipped: the workflow
  artifact expires and is reachable only from its own run's page, and the
  Actions list titles a run by its last commit, so a push tipped with a
  housekeeping commit reads as housekeeping even when it published. The
  release is permanent, linkable, and lists every build in one place.
- So the internal track is the only channel a *device* gets a build from.
  Firebase App Distribution used to ship the debug APK to the same testers in
  parallel; it was removed once the internal track proved sufficient, and the
  `app-debug-apk` CI artifact that outlived it has been removed too. Anyone
  who wants a build by hand builds one locally — see `README.md`.

## Prerequisites

- The Play Console app listing exists (you've created the app in Play Console
  and uploaded a first AAB by hand at least once *or* the API call below
  successfully creates the first internal-track release for you — Google has
  flipped this requirement back and forth; in practice the very first upload
  sometimes needs to be a manual browser upload).
- Internal testing track has at least one tester email or list.
- The upload AAB signing chain is set up (`UPLOAD_KEYSTORE_BASE64` + companion
  secrets — see `app/build.gradle.kts` and the existing CI step). Play App
  Signing re-signs with the actual signing key on download.

## Setup checklist (one-time, ~15 minutes)

### 1. Create a Google Cloud service account

[console.cloud.google.com](https://console.cloud.google.com) → pick the
project linked to your Play Console (Play Console → **Setup → API access**
shows which GCP project, and lets you link one if none yet).

- IAM & Admin → **Service Accounts → Create service account**.
- Name: `play-publisher-ci` (anything sensible).
- Skip the optional role-grant step — Play permissions are granted in Play
  Console, not via GCP IAM.
- Open the new service account → **Keys → Add key → Create new key → JSON**.
  A JSON file downloads. Copy its entire contents (curly braces and all).
  **Don't commit it.**

### 2. Grant the service account release permission in Play Console

Play Console → **Setup → API access**.

- If you haven't already, click the prompt to **link** your GCP project. The
  service account from step 1 should appear in the list.
- Next to the service account, click **Grant access**.
- App permissions: add this app (ClothesCast).
- Account permissions: at minimum, **Release apps to testing tracks** and
  **Release to production, exclude devices, and use Play App Signing**
  (the latter is needed to upload AABs that Play re-signs). The "Admin (all
  permissions)" preset also works but is more access than needed.
- **Invite user** → **Send invite**. Permissions take effect within a minute.

### 3. Add the GitHub Secret

GitHub repo → **Settings → Secrets and variables → Actions → New repository
secret**.

| Name | Value |
|---|---|
| `PLAY_SERVICE_ACCOUNT_JSON` | The full JSON content from step 1 (paste the whole `{ … }` block). |

### 4. Trigger a build

Push any commit to `main` (or merge a PR). The CI run on `main` should now
finish with an "Upload AAB to Play Store internal track" step. Watch for
green.

## App content declarations (manual, one-time)

Play Console → **Monitor and improve → Policy and programs → App content**
(verified 2026-08-27). "Policy" is no longer a top-level item — it is nested a
level deeper, which is what makes this page hard to find from the menu. The
direct URLs, which skip the hunt:

    .../app/<app-id>/app-content/overview                 the App content hub
    .../app/<app-id>/app-content/data-privacy-security    the Data safety form

under `https://play.google.com/console/u/<n>/developers/<developer-id>`. All three
parts come from the address bar of any Play Console page for the app —
including `/u/<n>/`, the Google account index, which is only `0` when the Play
account is the first one signed into that browser. Note `app-content`
alone does not resolve — it needs the `/overview` child.


CI uploads the AAB, but Play still won't *release* a build until the **App
content** declarations are complete. These are browser-only forms in the Play
Console — there's no API for them and nothing in this repo can satisfy them.
The upload step can go green while the release stays blocked "in review" until
they're filled in.

### Foreground service permissions

> **Symptom:** the release is blocked with
> *"You must let us know whether your app uses any Foreground Service
> permissions."*

The app declares three foreground-service permissions
(`AndroidManifest.xml`) because `ScheduledDeliveryService` runs as a
foreground service for the scheduled daily briefing — see
`docs/schedule-lifecycle.md`. Any declared `FOREGROUND_SERVICE_*` permission
triggers this mandatory declaration.

Fill it in at **Play Console → App content → Foreground service permissions
→ Start declaration**. It's easy to miss in the sidebar; the direct deep
link is `.../app/<appId>/app-content/foreground-services` (replace the
`test-and-release` / `app-dashboard` slug in any console URL with
`app-content/foreground-services`). Declare both types we ship and paste
this justification (each form field caps at ~500 chars, so trim to fit):

- **`FOREGROUND_SERVICE_DATA_SYNC`** — *"At a user-chosen time each day an
  exact alarm wakes the app to fetch the weather forecast and generate the
  daily clothing briefing in the background. A data-sync foreground service
  runs for this short window and shows a 'Preparing your ClothesCast'
  notification so the user knows their scheduled briefing is being prepared.
  The work must outlive the alarm broadcast, so a foreground service is
  required."*
- **`FOREGROUND_SERVICE_MEDIA_PLAYBACK`** — *"When the scheduled briefing is
  set to be spoken aloud, the app runs a media-playback foreground service
  for the run — preparing the forecast and then playing the spoken audio —
  and shows a notification while it does. Android 15+ refuses audio focus to
  background apps, so a media-playback foreground service is the only
  supported way to play the user's scheduled audio briefing at the chosen
  time."* (The service holds the `mediaPlayback` type across the whole
  speech-enabled run, not just the seconds of playback — keep the wording
  truthful to that so the declaration matches the shipped APK.)

The form asks for a short screen recording demonstrating each declared type.
A speech-enabled run uses `mediaPlayback` start to finish, so one video won't
exercise `dataSync` — record two short clips and upload both (e.g. unlisted
YouTube links) where the form asks:

- **`mediaPlayback`** — a scheduled briefing set to *speak*: the "Preparing
  your ClothesCast" notification appearing, then the spoken forecast playing.
- **`dataSync`** — a *non-speech* scheduled run (notification-only, or
  cast / MQTT delivery): the "Preparing your ClothesCast" notification across
  the fetch and the forecast notification posting, with no speech.

After submitting, **Save** the declaration, then go back to the blocked
release and **Send for review** again. The fix is per app, not per release —
once accepted it stops blocking future uploads unless the declared FGS types
change.

## Troubleshooting

- **"Upload AAB to Play Store internal track" is skipped** → `PLAY_SERVICE_ACCOUNT_JSON`
  isn't set, or you pushed to a feature branch (only `main` publishes). The
  GitHub prerelease is unaffected in the first case — it does not gate on that
  secret, which is the point of it — so the signed bundle is still on the
  Releases page even when nothing reached Play.
- **"Publish a GitHub release" says a later push already published** → an
  older push's deploy reached that step after a newer one had already
  published a higher `versionCode`. Standing down is correct: it keeps the
  newest release the newest build, and it stands the Play upload down too so
  Play cannot receive the older bundle either. The older push's commits are
  not lost — the notes range still measures from the last Play publication.
- **Release blocked: "You must let us know whether your app uses any
  Foreground Service permissions"** → complete the Foreground service
  permissions declaration under **App content** — see the section above. The
  AAB uploads fine; the *release* stays blocked until the declaration is
  saved.
- **`APK specifies a version code that has already been used`** → Play
  rejects re-uploads with the same `versionCode`. The repo derives
  `versionCode` from `git rev-list --count HEAD`, so a force-push to `main`
  that doesn't increase the count would collide. Land a real new commit.
- **`The caller does not have permission`** → step 2 didn't take effect, or
  the service account in step 1 doesn't match the one granted access. Check
  Play Console → API access shows the same email as the JSON's
  `client_email`.
- **`Package not found: app.clothescast`** → Play Console listing doesn't
  exist yet, or `applicationId` doesn't match. Verify in Play Console that
  the package id is exactly `app.clothescast`.
- **`Changes cannot be sent for review automatically`** → can happen on the
  very first internal release if Play Console is mid-review of the listing.
  Either wait for the listing review to complete, or set
  `changesNotSentForReview: true` on the action (then push the green
  "Send for review" button manually in Play Console once).

## Getting a build another way

The internal track is the only channel CI publishes to that a device can
install from — the GitHub prerelease alongside it carries the same bundle, but
an AAB is not installable. The trade the track makes is latency: a build
reaches a device minutes to hours after CI goes green, depending on Play's
caching, where Firebase App Distribution used to take about thirty seconds.

When that wait doesn't suit — iterating on a fix with someone, or testing a
branch that will never reach `main` — build a debug APK locally:
`./gradlew :app:installDebug`, or `assembleDebug` and transfer the APK by
hand. `README.md` has the steps. It carries a different package id
(`app.clothescast.debug`) and a different signing key, so it installs
alongside the Play build rather than over it.

CI used to upload the same thing as the `app-debug-apk` artifact. That was
removed: it needed a PR before a branch would build at all, and — since the
stored debug keystore went with Firebase App Distribution — every runner
signed with a key it generated for that run, so no two artifacts installed
over each other. A second one failed with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and uninstalling first wiped that
install's settings. A local build has neither problem: `~/.android/debug.keystore`
is stable, so successive builds upgrade in place. None of the sibling repos
(typelauncher, simmo, snoozemo) ship a debug-APK artifact either.

The Play upload itself no-ops when `PLAY_SERVICE_ACCOUNT_JSON` is unset, but
that alone doesn't make a `main` run pass on a repo without the release
secrets: `Decode upload keystore from secret` runs first and fails the job
outright when `UPLOAD_KEYSTORE_BASE64` is missing. That's deliberate — a push
to `main` that cannot produce a signed AAB should say so rather than go
quietly green — so a fresh clone needs the upload-signing secrets configured
before its `main` runs go green. PRs are unaffected either way; `deploy`
never runs on one.
