#!/usr/bin/env python3
"""Drives the release-notes subject collection from `.github/workflows/ci.yml`.

`deploy` is main-only, so the "Build release notes" step never runs on a pull
request and a regression in it would first surface after reaching `main` — as a
wrong Play "What's new" card, which is the worst place to find one.

The whole collection block is extracted from the workflow and run against a
throwaway git repository, rather than a copy of the helper being tested in
isolation. Two reasons, both about what a test like this gets wrong: a copy
passes forever once the workflow drifts, and exercising only the helper would
stay green if a loop stopped calling it — putting the duplicate bullets
straight back while every assertion still reported ok.

The block here spans `is_noise` through the ancestor-walk backstop, so BOTH
append sites are the shipped text: the range walk, and the fallback that
looks up to 20 ancestors when there was no usable release base.

Standard library only, matching the other scripts in this directory — no YAML
parser, so the block is located by its own source. Run it directly:
python3 scripts/release_notes_dedup_test.py
"""

import re
import subprocess
import sys
import tempfile
import textwrap
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WORKFLOW = REPO / ".github" / "workflows" / "ci.yml"


def extract_collection_block() -> str:
    """Return the workflow's subject-collection block, dedented.

    Spans the `is_noise` predicate through the ancestor-walk backstop, so
    both filters, the qualifying counter, the cap and both append sites are
    the shipped text. Fails loudly when the block cannot be found: a test
    that silently exercises nothing is worse than no test, and restructuring
    this step is exactly the change that should fail here rather than
    quietly pass.
    """
    source = WORKFLOW.read_text()
    match = re.search(
        r"^(?P<indent>[ ]*)is_noise\(\) \{\n"
        r"(?P<body>.*?\n(?P=indent)fi)\n"
        r"\n(?P=indent)# Range was all-filtered",
        source,
        re.S | re.M,
    )
    if match is None:
        sys.exit(
            f"the subject-collection block was not found in {WORKFLOW} — it must run "
            "from `is_noise() {` to the ancestor-walk `fi` that precedes the "
            "\"Range was all-filtered\" comment. If the step was restructured, "
            "update this test with it; do not delete the test."
        )
    block = "is_noise() {\n" + match.group("body") + "\n"
    return textwrap.dedent(block)


def git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=repo, capture_output=True, text=True, check=True
    )
    return result.stdout.strip()


def make_commit(repo: Path, subject: str, filename: str, nonce: int) -> str:
    """Commit `filename` with a distinct body under `subject`.

    The body carries `nonce` because two commits in these fixtures routinely
    share a subject AND a path — that is the case under test — and git refuses
    a commit whose tree is unchanged.
    """
    (repo / filename).parent.mkdir(parents=True, exist_ok=True)
    (repo / filename).write_text(f"{subject} {nonce}\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", subject)
    return git(repo, "rev-parse", "HEAD")


def collect(block: str, commits: list, walk: bool = False) -> tuple:
    """Run the block over `commits` and return (subjects, qualifying).

    With `walk` set, `base_sha` is left empty and `shas` holds only the head
    commit, which is the shape that arms the ancestor-walk backstop.

    `commits` is a list of (subject, filename). A setup commit is made first
    and excluded from the range: the production step runs `git diff-tree`
    without `--root`, so a repository's first commit reports no paths at all
    and would be filtered as housekeeping — an artifact of the fixture, not
    the behavior under test.
    """
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        git(repo, "init", "-q", "-b", "main")
        git(repo, "config", "user.email", "test@example.com")
        git(repo, "config", "user.name", "Test")
        make_commit(repo, "setup", "app/setup.kt", 0)

        shas = [
            make_commit(repo, subject, filename, i)
            for i, (subject, filename) in enumerate(commits, start=1)
        ]
        if walk:
            # The backstop arms only when the "range" was just the head
            # commit and it was filtered out.
            shas = shas[-1:]

        script = (
            "set -euo pipefail\n"
            + f"shas=({' '.join(shas)})\n"
            + f'head_sha="{shas[-1] if shas else ""}"\n'
            + f'base_sha="{"" if walk else "base"}"\n'
            + block
            + '\nprintf "Q:%s\\n" "$qualifying"\n'
            + 'for s in ${subjects[@]+"${subjects[@]}"}; do printf "S:%s\\n" "$s"; done\n'
        )
        result = subprocess.run(
            ["bash", "-c", script],
            cwd=repo,
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise AssertionError(f"block exited {result.returncode}: {result.stderr}")
        lines = result.stdout.splitlines()
        subjects = [l[2:] for l in lines if l.startswith("S:")]
        qualifying = int(next(l[2:] for l in lines if l.startswith("Q:")))
        return subjects, qualifying


def main() -> int:
    block = extract_collection_block()
    failures: list = []

    def check(name: str, got, want) -> None:
        if got != want:
            failures.append(f"{name}: got {got!r}, expected {want!r}")
        else:
            print(f"ok: {name}")

    # The behavior this exists for: two dependency batches in one release
    # window write the same subject, and the card must carry it once.
    subjects, qualifying = collect(
        block,
        [
            ("Update dependencies", "gradle/libs.versions.toml"),
            ("Update dependencies", "gradle/libs.versions.toml"),
        ],
    )
    check("a repeated subject is collected once", subjects, ["Update dependencies"])
    check("both commits still counted as qualifying", qualifying, 2)

    # The FIRST occurrence survives, keeping its position. The list is
    # oldest-first and both caps drop from the tail, so a repeat must never
    # displace the head.
    subjects, _ = collect(
        block,
        [
            ("First", "app/a.kt"),
            ("Second", "app/b.kt"),
            ("First", "app/c.kt"),
        ],
    )
    check("first occurrence keeps its position", subjects, ["First", "Second"])

    # Dropping the date from the batch subject is what makes repeats likely,
    # so a dated and an undated subject must stay distinct.
    subjects, _ = collect(
        block,
        [
            ("Update dependencies", "gradle/libs.versions.toml"),
            ("Update dependencies (2026-09-12)", "gradle/libs.versions.toml"),
        ],
    )
    check(
        "a near-miss subject is not collapsed",
        subjects,
        ["Update dependencies", "Update dependencies (2026-09-12)"],
    )

    # Subjects are compared as text: a glob must not match a different
    # subject, and quotes must not be re-split.
    subjects, _ = collect(
        block,
        [
            ("Fix * and ? in names", "app/a.kt"),
            ("Fix * and ? in names", "app/b.kt"),
            ("Fix anything", "app/c.kt"),
            ('He said "hi"', "app/d.kt"),
            ('He said "hi"', "app/e.kt"),
        ],
    )
    check(
        "shell metacharacters are compared as text",
        subjects,
        ["Fix * and ? in names", "Fix anything", 'He said "hi"'],
    )

    # The filters still run, and filtered commits never reach the counter —
    # so the cap continues to bound qualifying commits, not walked ones.
    subjects, qualifying = collect(
        block,
        [
            ("ci: regenerate snapshots", "app/a.kt"),
            ("docs: tidy", "app/b.kt"),
            ("Real change", "app/c.kt"),
        ],
    )
    check("prefixed commits are filtered out", subjects, ["Real change"])
    check("filtered commits are not counted as qualifying", qualifying, 1)

    subjects, _ = collect(
        block,
        [
            ("Housekeeping only", "SPEC.md"),
            ("Real change", "app/c.kt"),
        ],
    )
    check("housekeeping-path commits are filtered out", subjects, ["Real change"])

    # The cap bounds the WALK, and after dedup the array no longer measures
    # it: 60 commits sharing one subject leave the array at one entry, so a
    # cap reading the array would walk every one of them. Reading the counter
    # instead, the loop stops at 50 — which is what `qualifying` reports back.
    subjects, qualifying = collect(
        block,
        [(f"Subject {i // 30}", "app/a.kt") for i in range(60)],
    )
    check("the cap stops the walk at 50 qualifying commits", qualifying, 50)
    check("dedup still applies under the cap", subjects, ["Subject 0", "Subject 1"])

    # The ancestor-walk backstop is the second append site: with no usable
    # release base the "range" is only the head commit, so an all-filtered
    # result does not prove nothing is unreleased. It must still reach a
    # subject, and through the same helper.
    subjects, qualifying = collect(
        block,
        [
            ("Real change", "app/a.kt"),
            ("ci: regenerate UI snapshots", "app/b.kt"),
        ],
        walk=True,
    )
    check("the ancestor walk still finds a subject", subjects, ["Real change"])
    # Dedup cannot show itself here — the walk appends once, to a list it
    # only runs on when it is empty — so the counter is what pins that this
    # append goes through the helper too. Reverting it to a bare
    # `subjects+=` is otherwise invisible, and one bare append left behind
    # is how the next reader concludes there is no single place subjects
    # enter the list.
    check("the ancestor walk appends through the helper", qualifying, 1)

    # An empty result is reachable and must not trip `set -u` on the array.
    subjects, qualifying = collect(block, [("ci: nothing to ship", "app/a.kt")])
    check("an all-filtered range yields no subjects", subjects, [])
    check("an all-filtered range counts none qualifying", qualifying, 0)

    for failure in failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
