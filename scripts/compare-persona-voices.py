#!/usr/bin/env python3
"""
Renders one persona style (default: VAMPIRE) in several Gemini voices so you
can pick the voice that suits it best.

The style directive and language hint are read from GeminiTtsClient.kt (via
gemini_tts.py), so the clips use exactly the request the app sends, model
included, not a retyped copy. Pass --model to hear the other one.

Usage
-----
  export GEMINI_API_KEY=your_key_here
  python3 scripts/compare-persona-voices.py
  python3 scripts/compare-persona-voices.py --style VAMPIRE --rolls 3
  python3 scripts/compare-persona-voices.py --voices Iapetus Algenib Orus

Output
------
Writes <style>-<voice>-<roll>.wav into /tmp/tts-persona-<style>-<locale>/
(each locale its own directory, so a run never clears another's clips) and prints
each clip's synthesis time. The app gives up on a request after
GEMINI_TTS_SOCKET_TIMEOUT_MS, so a slow voice is worth noting.

Rate them with:
  python3 scripts/rate-tts-clips.py /tmp/tts-persona-vampire-en-GB/
"""

import argparse
import os
import pathlib
import sys

import gemini_tts

# Algenib is the current vampire pin and Charon the old holiday default, as
# baselines. The rest are Gemini's deeper or more characterful male voices,
# including ones the everyday picker dropped for being theatrical (Orus),
# which is the point for a persona. Descriptors are Google's.
DEFAULT_VOICES = [
    "Iapetus",     # Clear (previous vampire pin)
    "Charon",      # Informative (previous holiday default)
    "Algenib",     # Gravelly (current vampire pin)
    "Orus",        # Firm
    "Enceladus",   # Breathy
    "Alnilam",     # Firm
    "Rasalgethi",  # Informative
    "Schedar",     # Even
]

SAMPLE_TEXT = (
    "Today, it will be 10° to 11°. Wear a jumper, jacket, rain jacket, "
    "and jeans. Drizzle."
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--style", default="VAMPIRE", help="TtsStyle name (default VAMPIRE)")
    parser.add_argument("--voices", nargs="+", default=DEFAULT_VOICES)
    parser.add_argument("--rolls", type=int, default=2, help="clips per voice, to hear variance")
    parser.add_argument("--locale", default="en-GB")
    parser.add_argument("--text", help="sample to read; required for a non-English --locale")
    parser.add_argument("--model", help="default: the one the app picks for --locale")
    parser.add_argument("--timeout", type=float, default=90.0, help="seconds per request")
    parser.add_argument("--out", type=pathlib.Path)
    args = parser.parse_args()

    api_key = os.environ.get("GEMINI_API_KEY", "")
    if not api_key:
        print("Set GEMINI_API_KEY before running.", file=sys.stderr)
        return 2

    # The app speaks the forecast in the voice locale's language, so an English
    # sample under a German hint would test a prompt it never sends.
    if args.text is None:
        if not args.locale.lower().startswith("en"):
            print(f"--locale {args.locale} needs --text in that language.", file=sys.stderr)
            return 2
        args.text = SAMPLE_TEXT

    style = args.style.upper()
    source = gemini_tts.client_source()
    try:
        delivery = gemini_tts.delivery_style(source, style, args.locale)
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 2
    model = args.model or gemini_tts.model_for(source, args.locale)
    out = args.out or pathlib.Path(f"/tmp/tts-persona-{style.lower()}-{args.locale}")
    out.mkdir(parents=True, exist_ok=True)
    # Clear this style's earlier clips so a rating pass never mixes in stale
    # voices, rolls or locales, or a previous file where this run's call failed.
    for stale in out.glob(f"{style.lower()}-*.wav"):
        stale.unlink()

    print(f"Writing clips to {out}/\n")
    print(f"Model: {model}\n\nStyle:\n{delivery}\n\nText:\n{args.text}\n")

    failures = 0
    for voice in args.voices:
        for roll in range(1, args.rolls + 1):
            slug = f"{style.lower()}-{voice.lower()}-{roll}"
            print(f"  {slug} ...", end=" ", flush=True)
            try:
                clip = gemini_tts.synthesize(api_key, model, args.text, delivery, voice, args.timeout,
                                             gemini_tts.language_code_for(source, style, args.locale))
            except (RuntimeError, ValueError, OSError) as exc:
                failures += 1
                print(f"✗  {type(exc).__name__}: {exc}")
                continue
            gemini_tts.write_wav(out / f"{slug}.wav", clip.pcm, clip.sample_rate)
            print(f"✓  {clip.duration:.1f}s audio in {clip.seconds_to_synthesize:.1f}s")

    print("\nRate with:")
    print(f"  python3 scripts/rate-tts-clips.py {out}/")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
