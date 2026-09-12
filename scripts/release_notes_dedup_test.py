#!/usr/bin/env python3
"""Drives the release-notes subject collection from `.github/workflows/ci.yml`.

`deploy` is main-only, so the "Build release notes" step never runs on a pull
request and a regression in it would first surface after reaching `main` — as a
wrong Play "What's new" card, which is the worst place to find one.

The whole collection block is extracted from the workflow and run against a
throwaway git repository, rather than a copy of the helper being tested in
isolation. Two reasons, both about what a test like this gets wrong: a copy
passes forever once the workflow drifts, and exercising only the helper would
stay green if the loop stopped calling it — putting the duplicate bullets
straight back while every assertion still reported ok.

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
    both filters, the subject budget, the helper and BOTH append sites are
    all the shipped text. Fails loudly when the block cannot
    be found: a test that silently exercises nothing is worse than no test,
    and restructuring this step is exactly the change that should fail here
    rather than quietly pass.
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
    return "is_noise() {\n" + match.group("body") + "\n"


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
    """Run the block over `commits` and return (subjects, truncated).

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
            f"shas=({' '.join(shas)})\n"
            + f'head_sha="{shas[-1] if shas else ""}"\n'
            + f'base_sha="{"" if walk else "base"}"\n'
            + block
            + '\nprintf "T:%s\\n" "$truncated"\n'
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
        truncated = next(l[2:] for l in lines if l.startswith("T:")) == "true"
        return subjects, truncated


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
    subjects, truncated = collect(
        block,
        [
            ("Update dependencies", "gradle/libs.versions.toml"),
            ("Update dependencies", "gradle/libs.versions.toml"),
        ],
    )
    check("a repeated subject is collected once", subjects, ["Update dependencies"])
    check("a short range is not marked truncated", truncated, False)

    # The FIRST occurrence survives, keeping its position. The list is
    # oldest-first and the cap drops from the tail, so a repeat must never
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

    # The filters still run.
    subjects, _ = collect(
        block,
        [
            ("ci: regenerate snapshots", "app/a.kt"),
            ("docs: tidy", "app/b.kt"),
            ("Real change", "app/c.kt"),
        ],
    )
    check("prefixed commits are filtered out", subjects, ["Real change"])

    subjects, _ = collect(
        block,
        [
            ("Housekeeping only", "SPEC.md"),
            ("Real change", "app/c.kt"),
        ],
    )
    check("housekeeping-path commits are filtered out", subjects, ["Real change"])

    # The subject budget counts DISTINCT subjects, so repeats must not eat
    # the slots a later distinct subject needs. Counting commits instead
    # drops "Add call history" here and prints one bullet, with no ellipsis
    # and nowhere near the 500-char cap — a release-worthy change silently
    # gone (Codex, PR #338).
    subjects, _ = collect(
        block,
        [("Update dependencies", "gradle/libs.versions.toml")] * 50
        + [("Add call history", "app/a.kt")],
    )
    check(
        "repeats do not consume the subject budget",
        subjects,
        ["Update dependencies", "Add call history"],
    )

    # The budget still bites on genuinely distinct subjects, keeps the
    # oldest — the list is oldest-first and the cap drops from the tail —
    # and says so, since the formatter only appends the "…" marker when
    # something told it the list was cut.
    subjects, truncated = collect(
        block, [(f"Subject {i}", "app/a.kt") for i in range(60)]
    )
    check("the budget stops at 50 distinct subjects", len(subjects), 50)
    check("the budget keeps the oldest", subjects[0], "Subject 0")
    check("a budget hit marks the notes truncated", truncated, True)

    # A long range of repeats is exactly what dedup is for, and must NOT be
    # cut: the budget counts distinct subjects, so it never fires here. This
    # is also the case a separate walk bound would have cut silently.
    subjects, truncated = collect(
        block, [("Update dependencies", "gradle/libs.versions.toml")] * 200
    )
    check("a long repeated range collects one subject", subjects, ["Update dependencies"])
    check("and is not marked truncated", truncated, False)

    # A full budget followed by nothing that would have taken a slot is a
    # COMPLETE list, so it must not claim otherwise: an ellipsis there says
    # content was dropped when the tail was duplicates and housekeeping.
    subjects, truncated = collect(
        block,
        [(f"Subject {i}", "app/a.kt") for i in range(50)]
        + [("Subject 0", "app/b.kt"), ("ci: regenerate snapshots", "app/c.kt")],
    )
    check("a full budget plus a duplicate tail collects 50", len(subjects), 50)
    check("and is not marked truncated", truncated, False)

    check(
        "the shipped subject budget is 50",
        bool(re.search(r"^\s*MAX_SUBJECTS=50$", block, re.M)),
        True,
    )

    # The ancestor-walk backstop is the SECOND append site: with no usable
    # release base the "range" is only the head commit, so an all-filtered
    # result does not prove nothing is unreleased. It must still reach a
    # subject, and through the same helper — the old test pinned that via a
    # counter that no longer exists, so it is pinned by behavior instead:
    # the walk's subject is collected, and it is collected exactly once even
    # though a duplicate sits in the range.
    subjects, truncated = collect(
        block,
        [
            ("Real change", "app/a.kt"),
            ("ci: regenerate UI snapshots", "app/b.kt"),
        ],
        walk=True,
    )
    check("the ancestor walk still finds a subject", subjects, ["Real change"])
    check("the ancestor walk does not mark the notes truncated", truncated, False)

    # Routing the walk through the helper has no behavioral signature — it
    # appends once, to a list it only runs on when empty, so dedup and the
    # budget are both unreachable there. This is the one structural
    # assertion in the file, and it is the claim itself: `add_subject` is
    # the only place a subject enters the list. Without it, reverting that
    # second call site to a bare append passes every behavioral check.
    check(
        "the helper is the only append path",
        len(re.findall(r"subjects\+=\(", block)),
        1,
    )

    # An empty result is reachable and must not trip `set -u` on the array.
    subjects, _ = collect(block, [("ci: nothing to ship", "app/a.kt")])
    check("an all-filtered range yields no subjects", subjects, [])

    for failure in failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
