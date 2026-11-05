#!/usr/bin/env python3
"""
Renders the same briefing on Gemini 3.8 Flash-Lite TTS and Flash TTS, so you
can hear what the pricier model buys.

By default each clip uses exactly what the app sends for the locale and
style: the directive and accent (or language hint) as `speechMetadata.style`,
read from GeminiTtsClient.kt. Pass --directive or --accent to try a
different prompt on both models at once.

Usage
-----
  export GEMINI_API_KEY=your_key_here
  python3 scripts/compare-tts-models.py
  python3 scripts/compare-tts-models.py --locale en-AU --voices Despina Leda
  python3 scripts/compare-tts-models.py --style VAMPIRE --rolls 3
  python3 scripts/compare-tts-models.py --directive "Warm and unhurried, like a friend."
  python3 scripts/compare-tts-models.py --locale sv-SE --text "Idag blir det 14 grader."
  python3 scripts/compare-tts-models.py --blind   # rate without knowing which is which

Output
------
Writes <model>-<voice>-<roll>.wav into /tmp/tts-models-<style>-<locale>/ (or
clip-NN.wav plus key.json with --blind) and prints each clip's synthesis
time, length and output tokens. prompt.txt there records the text and style
sent. Rate them with:
  python3 scripts/rate-tts-clips.py /tmp/tts-models-weather_forecaster-en-GB/

Cost: about $0.001 a clip on Flash-Lite and $0.0015 on Flash at 2026 rates
(twice that from 2027). The free tier covers a few runs.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import random
import sys

import gemini_tts

SAMPLE_TEXT = (
    "Today, it will be 10° to 11°. Wear a jumper, jacket, rain jacket, "
    "and jeans. Drizzle."
)


def short_name(model: str) -> str:
    """gemini-3.8-flash-lite-tts -> flash-lite, for file names."""
    return model.removeprefix("gemini-").removesuffix("-tts").split("-", 1)[-1]


def main() -> int:
    source = gemini_tts.client_source()
    default_voice = gemini_tts.kotlin_string_const(source, "DEFAULT_GEMINI_TTS_VOICE")

    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--locale", default="en-GB")
    parser.add_argument("--style", default="WEATHER_FORECASTER", help="TtsStyle name")
    parser.add_argument("--voices", nargs="+", help=f"default: the style's pinned voice, else {default_voice}")
    parser.add_argument("--text", help="what to read; required for a non-English --locale")
    parser.add_argument("--directive", help="try this directive instead of the app's")
    parser.add_argument("--accent", help="try this accent line instead of the app's; '' drops it")
    parser.add_argument("--models", nargs="+", default=list(gemini_tts.models(source)))
    parser.add_argument("--rolls", type=int, default=2, help="clips per model and voice, to hear variance")
    parser.add_argument("--blind", action="store_true", help="shuffle into clip-NN.wav; key.json says which is which")
    parser.add_argument("--timeout", type=float, default=90.0, help="seconds per request")
    parser.add_argument("--out", type=pathlib.Path)
    args = parser.parse_args()

    api_key = os.environ.get("GEMINI_API_KEY", "")
    if not api_key:
        print("Set GEMINI_API_KEY before running.", file=sys.stderr)
        return 2
    # The app speaks the forecast in the voice locale's language, so an English
    # sample under a German style would test a request it never sends.
    if args.text is None:
        if not args.locale.lower().startswith("en"):
            print(f"--locale {args.locale} needs --text in that language.", file=sys.stderr)
            return 2
        args.text = SAMPLE_TEXT

    style_name = args.style.upper()
    try:
        style = gemini_tts.delivery_style(source, style_name, args.locale, args.directive, args.accent)
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 2
    voices = args.voices or [gemini_tts.pinned_voice(source, style_name) or default_voice]
    out = args.out or pathlib.Path(f"/tmp/tts-models-{style_name.lower()}-{args.locale}")
    out.mkdir(parents=True, exist_ok=True)
    # Clear earlier clips so a rating pass never mixes in a stale model, voice
    # or roll, or a previous file where this run's call failed.
    for stale in [*out.glob("*.wav"), out / "key.json"]:
        stale.unlink(missing_ok=True)

    app_model = gemini_tts.model_for(source, args.locale)
    (out / "prompt.txt").write_text(f"text:\n{args.text}\n\nstyle:\n{style}\n", encoding="utf-8")
    print(f"Writing clips to {out}/")
    print(f"The app uses {app_model} for {args.locale}.\n")
    print(f"Text:\n{args.text}\n\nStyle:\n{style}\n")

    jobs = [(m, v, r) for m in args.models for v in voices for r in range(1, args.rolls + 1)]
    names = {job: f"{short_name(job[0])}-{job[1].lower()}-{job[2]}" for job in jobs}
    if args.blind:
        order = random.sample(jobs, len(jobs))
        names = {job: f"clip-{i:02d}" for i, job in enumerate(order, 1)}
        jobs = order

    failures = 0
    key = {}
    for model, voice, roll in jobs:
        name = names[(model, voice, roll)]
        try:
            clip = gemini_tts.synthesize(api_key, model, args.text, style, voice, args.timeout)
        except (RuntimeError, ValueError, OSError) as exc:
            failures += 1
            print(f"  {name}: FAILED: {exc}")
            continue
        gemini_tts.write_wav(out / f"{name}.wav", clip.pcm, clip.sample_rate)
        key[name] = {"model": model, "voice": voice, "roll": roll}
        label = name if args.blind else f"{short_name(model):10} {voice:10} #{roll}"
        print(
            f"  {label}  {clip.seconds_to_synthesize:5.1f}s to make, "
            f"{clip.duration:4.1f}s long, {clip.usage.get('candidatesTokenCount', '?')} output tokens"
        )
    if args.blind:
        (out / "key.json").write_text(json.dumps(key, indent=2) + "\n", encoding="utf-8")
        print(f"\nWhich is which: {out / 'key.json'} (look after rating)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
