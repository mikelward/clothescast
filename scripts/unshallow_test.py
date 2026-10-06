#!/usr/bin/env python3
"""Runs scripts/unshallow.sh against real shallow clones.

The versionCode is `git rev-list --count origin/main`, so the case that
matters is the one a sandbox actually produces: a depth-limited,
single-branch clone of a feature branch. There a bare fetch follows the
clone's narrowed refspec, deepens HEAD and leaves origin/main missing,
and every count of main reads nothing or a stale number. Its failure mode
is a false pass, so these assert the resulting repository, not just the
script's output.

The script body is byte-identical to mikelward/mesh's copy, whose suite
covers the bounded-fetch paths in depth; this file pins the outcome
this repository relies on and the failure reporting around it.
Byte-identical across the sibling Android repos, like
publish_github_release_test.py.

Standard library only. Run it directly: python3 scripts/unshallow_test.py
"""

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCRIPT = REPO / "scripts" / "unshallow.sh"

ENV = {
    **os.environ,
    "GIT_AUTHOR_NAME": "Test",
    "GIT_AUTHOR_EMAIL": "test@example.com",
    "GIT_COMMITTER_NAME": "Test",
    "GIT_COMMITTER_EMAIL": "test@example.com",
    "GIT_CONFIG_GLOBAL": os.devnull,
    "GIT_CONFIG_NOSYSTEM": "1",
}


def git(cwd, *args):
    return subprocess.run(
        ["git", *args], cwd=cwd, env=ENV, check=True, capture_output=True, text=True
    ).stdout.strip()


def commit(cwd, message):
    git(cwd, "commit", "--quiet", "--allow-empty", "-m", message)


def run_script(cwd):
    return subprocess.run(
        ["sh", str(SCRIPT)], cwd=cwd, env=ENV, check=True, capture_output=True, text=True
    )


def origin_with_feature(root):
    """main: 3 commits; feature: branched after 2, with 2 of its own."""
    origin = root / "origin"
    git(root, "init", "--quiet", "--initial-branch=main", str(origin))
    commit(origin, "main 0")
    commit(origin, "main 1")
    git(origin, "checkout", "--quiet", "-b", "feature")
    commit(origin, "feature 0")
    commit(origin, "feature 1")
    git(origin, "checkout", "--quiet", "main")
    commit(origin, "main 2")
    return origin


def test_single_branch_feature_clone_gets_main(root):
    origin = origin_with_feature(root)
    clone = root / "clone"
    git(root, "clone", "--quiet", "--depth", "1", "--single-branch",
        "--branch", "feature", f"file://{origin}", str(clone))
    assert git(clone, "rev-parse", "--is-shallow-repository") == "true"
    # The precondition the bug needs: origin/main is not there to count.
    assert git(clone, "for-each-ref", "refs/remotes/origin/main") == ""

    run_script(clone)

    assert git(clone, "rev-parse", "--is-shallow-repository") == "false"
    assert git(clone, "rev-list", "--count", "HEAD") == "4"
    assert git(clone, "rev-list", "--count", "origin/main") == "3"


def test_complete_clone_is_left_alone(root):
    origin = origin_with_feature(root)
    out = run_script(origin).stdout
    assert "already complete" in out, out
    assert git(origin, "rev-parse", "--is-shallow-repository") == "false"


def test_recent_failure_skips_the_fetch(root):
    # SessionStart fires on resume, clear and compaction too; after a fetch
    # failed, a fresh stamp must make the next run return without fetching.
    origin = origin_with_feature(root)
    clone = root / "clone"
    git(root, "clone", "--quiet", "--depth", "1", f"file://{origin}", str(clone))
    (clone / ".git" / "unshallow-failed").write_text("1\n")

    err = run_script(clone).stderr

    assert "skipped" in err, err
    assert git(clone, "rev-parse", "--is-shallow-repository") == "true"


def test_outside_a_repository_fails_loudly(root):
    # A shallow flag git could not report is not a complete history.
    env = {**ENV, "GIT_CEILING_DIRECTORIES": str(root.parent)}
    result = subprocess.run(
        ["sh", str(SCRIPT)], cwd=root, env=env, capture_output=True, text=True
    )
    assert result.returncode == 1, result
    assert "cannot inspect the repository" in result.stderr, result.stderr


def shallow_clone(root):
    origin = origin_with_feature(root)
    clone = root / "clone"
    git(root, "clone", "--quiet", "--depth", "1", f"file://{origin}", str(clone))
    return clone


def stub(root, name, body):
    """A `name` on a PATH prefix, running `body` as sh."""
    bin_dir = root / "bin"
    bin_dir.mkdir(exist_ok=True)
    path = bin_dir / name
    path.write_text("#!/bin/sh\n" + body)
    path.chmod(0o755)
    return {**ENV, "PATH": f"{bin_dir}:{ENV['PATH']}"}


def test_a_trace_on_stderr_does_not_hide_the_flag(root):
    # GIT_TRACE writes to stderr on a successful rev-parse; folded into the
    # flag, "true" stops matching and a shallow clone reads as complete.
    clone = shallow_clone(root)
    subprocess.run(["sh", str(SCRIPT)], cwd=clone, env={**ENV, "GIT_TRACE": "1"},
                   check=True, capture_output=True, text=True)
    assert git(clone, "rev-parse", "--is-shallow-repository") == "false"


def test_an_invalid_timeout_stays_bounded(root):
    # perl `alarm 0` and GNU `timeout 0` both mean "no deadline". The stub
    # perl records the deadline it was handed and runs nothing.
    for bad in ("0", "abc"):
        case = root / bad
        case.mkdir()
        clone = shallow_clone(case)
        seen = case / "seen"
        env = stub(case, "perl", f'echo "$3" > {seen}\nexit 1\n')
        subprocess.run(["sh", str(SCRIPT)], cwd=clone,
                       env={**env, "UNSHALLOW_TIMEOUT": bad},
                       check=True, capture_output=True, text=True)
        assert seen.read_text().strip() == "120", (bad, seen.read_text())


def test_a_failed_count_is_reported_not_blank(root):
    clone = shallow_clone(root)
    real_git = shutil.which("git")
    env = stub(root, "git",
               '[ "$1" = rev-list ] && { echo "rev-list exploded" >&2; exit 1; }\n'
               f'exec "{real_git}" "$@"\n')
    result = subprocess.run(["sh", str(SCRIPT)], cwd=clone, env=env,
                            capture_output=True, text=True)
    assert result.returncode == 0, result
    assert "deepened to" not in result.stdout, result.stdout
    assert "could not count commits" in result.stderr, result.stderr
    assert git(clone, "rev-parse", "--is-shallow-repository") == "false"


def test_a_fetch_that_cannot_write_main_is_not_success(root):
    # A stale origin/main/<x> ref blocks the refspec's destination: git
    # removes the shallow boundary, exits non-zero, and leaves origin/main
    # missing -- which is what the versionCode counts.
    origin = origin_with_feature(root)
    clone = root / "clone"
    git(root, "clone", "--quiet", "--depth", "1", "--single-branch",
        "--branch", "feature", f"file://{origin}", str(clone))
    git(clone, "update-ref", "refs/remotes/origin/main/stale", "HEAD")
    result = subprocess.run(["sh", str(SCRIPT)], cwd=clone, env=ENV,
                            capture_output=True, text=True)
    assert result.returncode == 0, result
    assert "deepened to" not in result.stdout, result.stdout
    assert "history is complete, but the fetch failed" in result.stderr, result.stderr


def test_a_fetch_killed_by_a_signal_is_not_success(root):
    # perl's `$? >> 8` is 0 for a signaled child. The stub deepens for real,
    # then dies on TERM before returning, as an OOM kill or a cancel might.
    if not shutil.which("perl"):
        return
    clone = shallow_clone(root)
    real_git = shutil.which("git")
    env = stub(root, "git",
               'for a in "$@"; do [ "$a" = fetch ] && '
               f'{{ "{real_git}" "$@"; kill -TERM $$; }}; done\n'
               f'exec "{real_git}" "$@"\n')
    result = subprocess.run(["sh", str(SCRIPT)], cwd=clone, env=env,
                            capture_output=True, text=True)
    assert result.returncode == 0, result
    assert "deepened to" not in result.stdout, result.stdout
    assert "(exit 143)" in result.stderr, result.stderr


def main():
    tests = [
        test_single_branch_feature_clone_gets_main,
        test_complete_clone_is_left_alone,
        test_recent_failure_skips_the_fetch,
        test_outside_a_repository_fails_loudly,
        test_a_trace_on_stderr_does_not_hide_the_flag,
        test_an_invalid_timeout_stays_bounded,
        test_a_failed_count_is_reported_not_blank,
        test_a_fetch_that_cannot_write_main_is_not_success,
        test_a_fetch_killed_by_a_signal_is_not_success,
    ]
    failed = 0
    for test in tests:
        root = Path(tempfile.mkdtemp(prefix="unshallow-"))
        try:
            test(root)
            print(f"ok   {test.__name__}")
        except (AssertionError, subprocess.CalledProcessError) as error:
            failed += 1
            detail = getattr(error, "stderr", "") or error
            print(f"FAIL {test.__name__}: {detail}")
        finally:
            shutil.rmtree(root, ignore_errors=True)
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
