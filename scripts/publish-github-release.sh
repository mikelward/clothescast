#!/usr/bin/env bash
#
# Publishes the signed release bundle as a GitHub prerelease. Called from the
# `Publish a GitHub release` step of `deploy` in `.github/workflows/ci.yml`,
# which owns the gating and the comment explaining why the step exists.
#
# A separate file rather than an inline `run:` block because `deploy` never
# executes on a pull request — it is main-only by `if:` and by
# `environment: production` — so an inline body could only be verified by
# merging it. Here `scripts/publish_github_release_test.py` drives it against
# a stubbed `gh` and a throwaway git repository, and CI runs that on every PR.
#
# Nothing here is app-specific: the bundle's path and the asset's base name
# come in through the environment, so this file is byte-identical across the
# sibling repos that publish this way and a fix does not need finding three
# times. Reads: GH_TOKEN, HEAD_SHA, RELEASE_NOTES, RUNNER_TEMP, AAB_PATH,
# ASSET_BASE. Expects to run from the repository root of an unshallow
# checkout. Writes RELEASE_SUPERSEDED=true to GITHUB_ENV, when set, if a
# later push has already published — see that branch for why the whole run
# stands down rather than only this step.

set -euo pipefail

# Named by versionCode, which is the identifier these repos already use to
# ask for a build ("need versionCode 512 or higher"), and
# `git rev-list --count HEAD` is that identifier's own definition — the same
# command the Gradle build runs. So nothing about the version scheme is
# restated here: no base version to keep in step, no versionName format to
# reassemble. The bundle carries its own versionName either way.
#
# Depends on the unshallow checkout (fetch-depth: 0): a shallow count is
# silently short, and the tag would then name a versionCode no APK carries.
code=$(git rev-list --count HEAD)
tag="v${code}"

# Stand down if a later push already published. Nothing here reaches Play, so
# nothing downstream would reject a stale bundle the way Play's API does: the
# Releases page orders by publication time, so an older versionCode published
# second sits at the top and reads as current — which is the one question this
# whole step exists to answer.
#
# `deploy`'s `concurrency` block stops two of these running at once, but
# GitHub's docs say queued jobs start in the order each began waiting, not
# push order, so an older push's deploy can reach this point after a newer
# one finished. The release list is the record of what published, so it is
# asked directly rather than inferred from run order — and deliberately not
# folded into `Build release notes`' own supersession probe, which keys on a
# successful *Play* upload because it doubles as the notes' range base:
# counting a GitHub prerelease there would move that base and drop those
# commits from the first real Play card.
#
# Drafts count, and cannot wedge a later push: the commit count strictly
# increases along main, so only a genuinely newer run can hold a higher tag.
# Refuses rather than guesses when the list can't be read — a skipped publish
# costs a re-run, a wrong one puts the wrong bundle at the top of the page.
# `--limit 100` is the same accepted trade as the workflow's `per_page=100`.
latest=$(gh release list --limit 100 --json tagName \
  --jq '[.[] | .tagName | select(test("^v[0-9]+$")) | ltrimstr("v") | tonumber] | max // 0')
case "$latest" in
  '' | *[!0-9]*)
    echo "Could not read the existing releases (got '${latest}'); refusing to publish." >&2
    exit 1
    ;;
esac
if [ "$latest" -gt "$code" ]; then
  echo "A later push already published v${latest}; skipping v${code} so the newest release stays the newest publication."
  # Stand the whole run down, not just this step. Skipping only here would
  # leave the Play upload to ship the older bundle — Play accepts it whenever
  # the newer run's own upload skipped or failed, which is exactly when a
  # newer prerelease exists without a newer Play release. That inversion is
  # older than this step (the notes step's probe keys on a *successful* Play
  # upload, so it cannot see the newer run either), but this step is what
  # finally makes the newer publication detectable, so it is the place to act
  # on it. Nothing is stranded: a superseding run descends from this one, and
  # the notes still measure from the last Play publication, so these commits
  # ride the next upload that lands.
  if [ -n "${GITHUB_ENV:-}" ]; then
    echo "RELEASE_SUPERSEDED=true" >> "$GITHUB_ENV"
  fi
  exit 0
fi

name="${ASSET_BASE}-${code}.aab"
asset="${RUNNER_TEMP}/${name}"
cp "$AAB_PATH" "$asset"

# Same text as the Play "What's new" card, already truncated to Play's cap
# upstream. Through a file rather than an argument: the subjects are
# commit-authored and must never be parsed as options.
notes="${RUNNER_TEMP}/gh-release-notes.md"
{
  printf '%s\n\n' "${RELEASE_NOTES}"
  printf 'versionCode %s, built from %s.\n\n' "$code" "$HEAD_SHA"
  # Says what the notes cover without restating `Build release notes`' range,
  # which is not this push and is not one thing: normally from the last run
  # that published to Play, and before there has ever been one from the
  # oldest run still visible to that step's own API call — either way it can
  # span many pushes, so any wording naming a boundary here would be a second
  # copy of a rule that lives there. "Still queued" is true in every case.
  # They are also not a changelog of everything new in the bundle: a push
  # that failed before `deploy` has its subjects omitted here as well as from
  # the Play card. The commit is named above regardless, and `git log` from it
  # is the complete answer.
  printf "Notes are the same set as the Play \"What's new\" card: every release-worthy commit still queued for release, not just the newest push's — and not a full changelog of the bundle.\n\n"
  printf 'The attached bundle is what goes to Play, not an installable APK.\n'
} > "$notes"

# Idempotent: a re-run, or a workflow_dispatch on a tip already released,
# finds its own release rather than failing on the existing tag.
#
# It uploads only when the asset is absent, and never with `--clobber`: that
# deletes the existing asset before sending the new one, so a transient
# failure mid-upload would leave the release with no bundle at all —
# `gh release upload`'s own documentation warns the original is then
# unrecoverable. And there is nothing to gain by replacing it: the commit
# count strictly increases along main, so a tag names exactly one commit and
# an asset already under it is this same build. The case idempotency exists
# for is the opposite one — a first attempt that created the release and died
# before its upload landed.
if gh release view "$tag" >/dev/null 2>&1; then
  # The asset's *state*, not just its name. An upload that fails partway can
  # leave GitHub holding an empty asset under the intended name — the
  # release-asset API documents this and says to delete it and retry — so a
  # name-only check would call a broken upload done and publish an unusable
  # bundle. Only `uploaded` counts as present.
  #
  # Its own command rather than a pipeline, because under `pipefail` a failed
  # `gh` would read as "asset missing"; and the name reaches jq through the
  # environment rather than spliced into the program.
  state=$(ASSET_NAME="$name" gh release view "$tag" --json assets \
    --jq '.assets[] | select(.name == env.ASSET_NAME) | .state')
  case "$state" in
    uploaded)
      : # already there, and whole
      ;;
    "")
      gh release upload "$tag" "$asset"
      ;;
    *)
      # Some other state means the bundle under that name is not usable, so
      # there is nothing to preserve and `--clobber`'s delete-then-upload is
      # the right move — the one case it is.
      gh release upload "$tag" "$asset" --clobber
      ;;
  esac
  # `--draft=false` because `gh release create` with an asset creates the
  # release as a draft, uploads, and only then publishes it — so a create
  # interrupted mid-upload leaves an invisible draft that no amount of
  # re-running would publish on its own. Last in the branch, after the
  # upload, so the release never becomes visible without its bundle.
  gh release edit "$tag" \
    --title "versionCode ${code}" --notes-file "$notes" \
    --prerelease --draft=false
else
  gh release create "$tag" "$asset" \
    --title "versionCode ${code}" --notes-file "$notes" \
    --prerelease --target "$HEAD_SHA"
fi
