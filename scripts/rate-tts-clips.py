#!/usr/bin/env python3
"""
Plays each .wav in a directory via aplay, prompts for a rating and optional
notes, then prints a summary you can paste back for recommendations.

Usage
-----
  python3 scripts/rate-tts-clips.py /tmp/tts-compare/
  python3 scripts/rate-tts-clips.py /tmp/tts-compare/ --pattern "en-GB"

Rating format
-------------
  <score>            e.g.  7
  <score> - <notes>  e.g.  8 - sounds a bit robotic
  s                  skip (no rating recorded)
  r                  replay the clip, then re-prompt
  q                  quit early (prints results so far)
"""

import argparse
import pathlib
import subprocess
import sys


def play(path: pathlib.Path) -> None:
    subprocess.run(["aplay", "-q", str(path)], check=False)


def prompt_rating(path: pathlib.Path) -> tuple[str, str] | None:
    """Returns (score, notes) or None if skipped."""
    while True:
        try:
            raw = input("  Rating [1-10 / s=skip / r=replay / q=quit]: ").strip()
        except (EOFError, KeyboardInterrupt):
            return None
        if raw.lower() == "q":
            return None
        if raw.lower() == "s":
            return ("s", "")
        if raw.lower() == "r":
            print("  Replaying...")
            play(path)
            continue
        parts = raw.split("-", 1)
        score = parts[0].strip()
        notes = parts[1].strip() if len(parts) > 1 else ""
        if score.isdigit() and 1 <= int(score) <= 10:
            return (score, notes)
        print("  Enter a number 1–10, 's', 'r', or 'q'.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", help="Directory containing .wav files")
    parser.add_argument(
        "--pattern", default="", help="Only play files whose path contains this string (locale, candidate, or voice)"
    )
    args = parser.parse_args()

    directory = pathlib.Path(args.directory)
    files = sorted(f for f in directory.rglob("*.wav") if args.pattern in str(f))

    if not files:
        sys.exit(f"No .wav files found in {directory}" + (f" matching '{args.pattern}'" if args.pattern else ""))

    results: list[tuple[str, str, str]] = []  # (filename, score, notes)

    print(f"\nFound {len(files)} clip(s). Press Enter after aplay finishes.\n")

    for i, path in enumerate(files, 1):
        # Label with the directory passed in plus the path under it, so the
        # locale folder shows however deep the clip is or which folder was
        # passed: every locale's clip shares a file name.
        label = f"{directory.resolve().name}/{path.relative_to(directory)}"
        print(f"[{i}/{len(files)}] {label}")
        play(path)
        result = prompt_rating(path)
        if result is None:
            print("\n--- quit ---")
            break
        score, notes = result
        if score != "s":
            results.append((label, score, notes))
        print()

    if not results:
        return

    print("\n" + "=" * 60)
    print("RESULTS — paste this back:")
    print("=" * 60)
    for name, score, notes in results:
        line = f"  {name}: {score}"
        if notes:
            line += f" — {notes}"
        print(line)
    print("=" * 60)


if __name__ == "__main__":
    main()
