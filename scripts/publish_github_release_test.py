#!/usr/bin/env python3
"""Drives scripts/publish-github-release.sh against a stubbed `gh`.

`deploy` is main-only — by `if:` and by `environment: production` — so its
steps never execute on a pull request, and the release-publishing logic could
otherwise only be verified by merging it. That logic has several branches that
matter precisely when something has already gone wrong (an interrupted create,
a half-uploaded asset), which is the worst place to find a mistake. So each
branch is exercised here: a throwaway git repository supplies the commit count,
a stub `gh` on PATH records what it was called with, and the assertions pin
both the commands and their order.

Two assertions are the point, not decoration. A release must never become
visible without its bundle, so every path that uploads must upload before it
publishes; and an older push whose deploy runs late must publish nothing at
all, or it would sit at the top of the Releases page looking current.

Standard library only, matching the other scripts in this directory. Run it
directly: python3 scripts/publish_github_release_test.py
"""

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCRIPT = REPO / "scripts" / "publish-github-release.sh"

# Stands in for `gh`. Records each invocation, and answers the three things
# the script branches on: the highest versionCode already released, whether
# this run's release exists at all, and what state the asset under our name
# is in. `release list` answers with the number the real `--jq` would have
# reduced the list to, since that reduction is jq's job and not the script's.
GH_STUB = r"""#!/usr/bin/env bash
printf '%s\n' "$*" >> "$GH_LOG"
if [ "${1:-} ${2:-}" = "release list" ]; then
  printf '%s\n' "${STUB_LATEST_CODE:-0}"
fi
if [ "${1:-} ${2:-}" = "release view" ]; then
  [ "${STUB_RELEASE_EXISTS:-0}" = "1" ] || exit 1
  case "$*" in
    *--json*) [ -n "${STUB_ASSET_STATE:-}" ] && printf '%s\n' "$STUB_ASSET_STATE" ;;
    *) printf 'release\n' ;;
  esac
fi
exit 0
"""

FAILURES = []

# What the last run appended to GITHUB_ENV, for the Play steps to read.
EXPORTED = []


def run_case(name, *, release_exists, asset_state, commits=7, latest_code=0):
    """Run the script in a scratch repo; return (lines, notes, returncode)."""
    work = Path(tempfile.mkdtemp(prefix="pubrel-"))
    try:
        bindir = work / "bin"
        bindir.mkdir()
        gh = bindir / "gh"
        gh.write_text(GH_STUB, encoding="utf-8")
        gh.chmod(0o755)

        tree = work / "tree"
        bundle = tree / "app/build/outputs/bundle/release"
        bundle.mkdir(parents=True)
        aab = bundle / "app-release.aab"
        aab.write_bytes(b"not really a bundle")

        git = ["git", "-c", "user.email=t@example.com", "-c", "user.name=T"]
        subprocess.run(["git", "init", "-q", "-b", "main", str(tree)], check=True)
        for i in range(commits):
            subprocess.run(
                git + ["commit", "-q", "--allow-empty", "-m", f"commit {i}"],
                cwd=tree, check=True,
            )

        runner_temp = work / "runner-temp"
        runner_temp.mkdir()
        log = work / "gh.log"
        log.touch()
        github_env = work / "github-env"
        github_env.touch()

        env = dict(os.environ)
        env.update(
            PATH=f"{bindir}{os.pathsep}{env['PATH']}",
            GH_LOG=str(log),
            GH_TOKEN="stub-token",
            HEAD_SHA="0123456789abcdef",
            RELEASE_NOTES="• Bigger outfit icons on the Today screen",
            RUNNER_TEMP=str(runner_temp),
            AAB_PATH=str(aab),
            ASSET_BASE="clothescast",
            STUB_RELEASE_EXISTS="1" if release_exists else "0",
            STUB_ASSET_STATE=asset_state,
            STUB_LATEST_CODE=str(latest_code),
            GITHUB_ENV=str(github_env),
        )
        proc = subprocess.run(
            ["bash", str(SCRIPT)], cwd=tree, env=env,
            capture_output=True, text=True,
        )
        lines = [l for l in log.read_text(encoding="utf-8").splitlines() if l]
        notes_path = runner_temp / "gh-release-notes.md"
        notes = notes_path.read_text(encoding="utf-8") if notes_path.exists() else ""
        EXPORTED.clear()
        EXPORTED.extend(
            l for l in github_env.read_text(encoding="utf-8").splitlines() if l
        )
        if proc.returncode != 0:
            print(f"  [{name}] script exited {proc.returncode}:\n{proc.stderr}")
        return lines, notes, proc.returncode
    finally:
        shutil.rmtree(work, ignore_errors=True)


def check(name, condition, detail=""):
    if condition:
        print(f"  ok   {name}")
    else:
        print(f"  FAIL {name} {detail}")
        FAILURES.append(name)


def index_of(lines, needle):
    for i, line in enumerate(lines):
        if needle in line:
            return i
    return -1


def case_new_release():
    print("no existing release -> create, with the asset, as a prerelease")
    lines, notes, rc = run_case("new", release_exists=False, asset_state="")
    check("exits zero", rc == 0)
    creates = [l for l in lines if l.startswith("release create")]
    check("creates the release", len(creates) == 1, lines)
    if creates:
        c = creates[0]
        check("tags by versionCode", " v7 " in f" {c} ", c)
        check("attaches the bundle", "clothescast-7.aab" in c, c)
        check("marks it a prerelease", "--prerelease" in c, c)
        check("targets the head commit", "--target 0123456789abcdef" in c, c)
        check("titles it by versionCode", "--title versionCode 7" in c, c)
    check("does not stand the Play steps down",
          not any("RELEASE_SUPERSEDED" in l for l in EXPORTED), EXPORTED)
    check("uploads nothing separately", not any(l.startswith("release upload") for l in lines), lines)
    check("does not edit", not any(l.startswith("release edit") for l in lines), lines)
    check("notes carry the Play text", "Bigger outfit icons" in notes, notes)
    check("notes name the versionCode and commit",
          "versionCode 7, built from 0123456789abcdef." in notes, notes)
    check("notes say what they cover", "not a full changelog" in notes, notes)
    # The range is the notes step's, not this push's: an earlier main run that
    # went red or skipped its Play upload leaves its subjects queued, and
    # before any Play publication the base is the oldest run that step can
    # still see — so a release can carry several pushes' worth either way
    # (Codex, PR #178). Asserted from both sides, because the regression is a
    # narrowing: the positive claim can be reworded, the wrong claim cannot
    # reappear.
    check("notes name the range they cover",
          "still queued for release" in notes, notes)
    check("notes never narrow the range to one push",
          "this push" not in notes, notes)
    check("notes say the bundle is not an APK", "not an installable APK" in notes, notes)


def case_asset_uploaded():
    print("release exists with a whole asset -> publish only, never clobber")
    lines, _, rc = run_case("whole", release_exists=True, asset_state="uploaded", latest_code=7)
    check("exits zero", rc == 0)
    check("does not create", not any(l.startswith("release create") for l in lines), lines)
    check("does not upload", not any(l.startswith("release upload") for l in lines), lines)
    check("never clobbers a whole asset", not any("--clobber" in l for l in lines), lines)
    edits = [l for l in lines if l.startswith("release edit")]
    check("publishes the release", len(edits) == 1, lines)
    if edits:
        check("clears the draft flag", "--draft=false" in edits[0], edits[0])
        check("keeps it a prerelease", "--prerelease" in edits[0], edits[0])


def case_asset_absent():
    print("release exists without the asset -> upload, then publish")
    lines, _, rc = run_case("absent", release_exists=True, asset_state="", latest_code=7)
    check("exits zero", rc == 0)
    up = index_of(lines, "release upload")
    ed = index_of(lines, "release edit")
    check("uploads the bundle", up >= 0, lines)
    check("publishes the release", ed >= 0, lines)
    check("uploads BEFORE publishing", 0 <= up < ed, lines)
    check("plain upload, no clobber", not any("--clobber" in l for l in lines), lines)


def case_asset_broken():
    print("release exists with a half-uploaded asset -> clobber, then publish")
    lines, _, rc = run_case("broken", release_exists=True, asset_state="starter", latest_code=7)
    check("exits zero", rc == 0)
    up = index_of(lines, "release upload")
    ed = index_of(lines, "release edit")
    check("replaces the broken asset", up >= 0 and "--clobber" in lines[up], lines)
    check("publishes the release", ed >= 0, lines)
    check("replaces BEFORE publishing", 0 <= up < ed, lines)


def case_count_tracks_history():
    print("the tag follows the commit count, not a hard-coded version")
    lines, notes, rc = run_case("count", release_exists=False, asset_state="", commits=41)
    check("exits zero", rc == 0)
    creates = [l for l in lines if l.startswith("release create")]
    check("tags v41 in a 41-commit tree", creates and " v41 " in f" {creates[0]} ", lines)
    check("asset name follows too", creates and "clothescast-41.aab" in creates[0], lines)
    check("notes name versionCode 41", "versionCode 41," in notes, notes)


def case_superseded():
    print("a later push already released a higher versionCode -> publish nothing")
    lines, _, rc = run_case("superseded", release_exists=False, asset_state="",
                            commits=7, latest_code=9)
    check("exits zero", rc == 0)
    check("does not create", not any(l.startswith("release create") for l in lines), lines)
    check("does not upload", not any(l.startswith("release upload") for l in lines), lines)
    check("does not edit", not any(l.startswith("release edit") for l in lines), lines)
    check("stands down before touching the release",
          all(l.startswith("release list") for l in lines), lines)
    # Skipping only this step would leave the Play upload shipping the older
    # bundle, which Play accepts whenever the newer run's own upload skipped
    # or failed — the very case a newer prerelease without a newer Play
    # release describes.
    check("stands the Play steps down too", "RELEASE_SUPERSEDED=true" in EXPORTED, EXPORTED)


def case_own_tag_is_not_superseding():
    print("the run's own release is not a later push -> equal codes still publish")
    lines, _, rc = run_case("equal", release_exists=True, asset_state="uploaded",
                            commits=7, latest_code=7)
    check("exits zero", rc == 0)
    check("publishes the release", any(l.startswith("release edit") for l in lines), lines)


def case_unreadable_release_list():
    print("the release list cannot be read -> refuse, publish nothing")
    lines, _, rc = run_case("unreadable", release_exists=False, asset_state="",
                            commits=7, latest_code="not-a-number")
    check("exits non-zero", rc != 0, rc)
    check("publishes nothing",
          not any(l.startswith(("release create", "release upload", "release edit"))
                  for l in lines),
          lines)


def main():
    if not SCRIPT.exists():
        sys.exit(f"missing {SCRIPT}")
    for case in (
        case_new_release,
        case_asset_uploaded,
        case_asset_absent,
        case_asset_broken,
        case_count_tracks_history,
        case_superseded,
        case_own_tag_is_not_superseding,
        case_unreadable_release_list,
    ):
        case()
    if FAILURES:
        sys.exit(f"\n{len(FAILURES)} assertion(s) failed: {', '.join(FAILURES)}")
    print("\nall release-publishing branches behave")


if __name__ == "__main__":
    main()
