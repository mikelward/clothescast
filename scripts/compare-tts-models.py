#!/usr/bin/env python3
"""
Renders briefings on Gemini 3.8 Flash-Lite TTS and Flash TTS, across locales,
voices, styles and holiday themes, so you can hear what each combination
sounds like and what the pricier model buys.

Each clip is what the app sends, built from the app's own source: the style
directive and accent (or language hint) as `speechMetadata.style`, the text
read verbatim. A holiday case also does what the app does on that day: the
holiday's persona, the voice the app would switch to (a matching-gender
voice, or the vampire's pinned one), and the spoken greeting in front of the
forecast. Pass --directive or --accent to try a different prompt everywhere.

Usage
-----
  export GEMINI_API_KEY=your_key_here
  python3 scripts/compare-tts-models.py
  python3 scripts/compare-tts-models.py --locales en-GB en-AU en-US --voices Despina Leda Kore
  python3 scripts/compare-tts-models.py --holidays all --models flash-lite
  python3 scripts/compare-tts-models.py --styles VAMPIRE PIRATE --rolls 3
  python3 scripts/compare-tts-models.py --directive "Warm and unhurried, like a friend."
  python3 scripts/compare-tts-models.py --locales sv-SE --text "Idag blir det 14 grader."
  python3 scripts/compare-tts-models.py --blind   # rate without knowing which model is which

Output
------
One directory per locale and case under --out (default /tmp/tts-models/):
  en-AU/weather_forecaster/flash-lite-despina-1.wav
  en-US/halloween/flash-lite-algenib-1.wav
Each holds prompt.txt (the text and style sent) and, with --blind, shuffled
clip-NN.wav names plus key.json. Rate a whole run, or one corner of it:
  python3 scripts/rate-tts-clips.py /tmp/tts-models/
  python3 scripts/rate-tts-clips.py /tmp/tts-models/ --pattern en-AU

Cost: about $0.001 a clip on Flash-Lite and $0.0015 on Flash at 2026 rates
(twice that from 2027). The script prints how many clips it's about to make.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import random
import sys
from dataclasses import dataclass

import gemini_tts

# One forecast per English locale, in its own words: the app says "sweater"
# for en-US and "jumper" for en-GB and en-AU, and the US usually reads °F.
ENGLISH_TEXT = {
    "en-US": "Today, it will be 50° to 52°. Wear a sweater, jacket, rain jacket, and jeans. Drizzle.",
    "en": "Today, it will be 10° to 11°. Wear a jumper, jacket, rain jacket, and jeans. Drizzle.",
}


@dataclass(frozen=True)
class Case:
    locale: str
    name: str       # directory name: the style, or the holiday
    style: str      # TtsStyle
    text: str
    holiday: str | None = None


def short_name(model: str) -> str:
    """gemini-3.8-flash-lite-tts -> flash-lite, for file names."""
    return model.removeprefix("gemini-").removesuffix("-tts").split("-", 1)[-1]


def resolve_models(names: list[str], source: str) -> list[str]:
    """Full ids pass through; 'flash-lite' and 'flash' name the app's two models."""
    known = {short_name(m): m for m in gemini_tts.models(source)}
    return [known.get(n, n) for n in names]


def default_text(locale: str) -> str | None:
    if locale.lower() == "en-us":
        return ENGLISH_TEXT["en-US"]
    return ENGLISH_TEXT["en"] if locale.lower().startswith("en") else None


def build_cases(args: argparse.Namespace) -> list[Case]:
    holidays = gemini_tts.holiday_styles()
    picked = list(holidays) if [h.lower() for h in args.holidays or []] == ["all"] else [h.upper() for h in args.holidays or []]
    unknown = [h for h in picked if h not in holidays]
    if unknown:
        raise ValueError(f"no persona for holiday(s) {', '.join(unknown)}; pick from: all {' '.join(holidays)}")
    styles = [s.upper() for s in args.styles] if args.styles else ([] if picked else ["WEATHER_FORECASTER"])
    cases = []
    for locale in args.locales:
        forecast = args.text or default_text(locale)
        if forecast is None:
            # The app speaks the forecast in the voice locale's language, so an
            # English sample under a German style would test a request it never sends.
            raise ValueError(f"--locales {locale} needs --text in that language")
        cases += [Case(locale, s.lower(), s, forecast) for s in styles]
        for h in picked:
            greeting = gemini_tts.holiday_greeting(h, locale)
            cases.append(Case(locale, h.lower(), holidays[h], f"{greeting} {forecast}", h))
    return cases


def main() -> int:
    source = gemini_tts.client_source()
    default_voice = gemini_tts.kotlin_string_const(source, "DEFAULT_GEMINI_TTS_VOICE")

    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--locales", "--locale", nargs="+", default=["en-GB"])
    parser.add_argument("--styles", "--style", nargs="+", help="TtsStyle names (default WEATHER_FORECASTER)")
    parser.add_argument("--holidays", nargs="+", help="HolidayId names with a persona, or 'all'")
    parser.add_argument("--voices", nargs="+", default=[default_voice],
                        help=f"the user's voice (default {default_voice}); a holiday may switch it as the app does")
    parser.add_argument("--text", help="forecast to read; required for a non-English locale")
    parser.add_argument("--directive", help="try this directive instead of the app's")
    parser.add_argument("--accent", help="try this accent line instead of the app's; '' drops it")
    parser.add_argument("--models", nargs="+", default=["flash-lite", "flash"],
                        help="'flash-lite', 'flash' or full model ids (default both)")
    parser.add_argument("--rolls", type=int, default=2, help="clips per combination, to hear variance")
    parser.add_argument("--blind", action="store_true", help="shuffle into clip-NN.wav; key.json says which is which")
    parser.add_argument("--timeout", type=float, default=90.0, help="seconds per request")
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("/tmp/tts-models"))
    args = parser.parse_args()

    api_key = os.environ.get("GEMINI_API_KEY", "")
    if not api_key:
        print("Set GEMINI_API_KEY before running.", file=sys.stderr)
        return 2
    try:
        cases = build_cases(args)
        styles = {c: gemini_tts.delivery_style(source, c.style, c.locale, args.directive, args.accent) for c in cases}
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 2
    models = resolve_models(args.models, source)

    # A holiday switches voices the way the app would, so two user voices can
    # land on the same one (both female picks become Charon for Father Christmas).
    jobs: dict[Case, list[tuple[str, str, int]]] = {}
    for case in cases:
        voices = [gemini_tts.holiday_voice(case.style, v) if case.holiday
                  else gemini_tts.pinned_voice(source, case.style) or v for v in args.voices]
        voices = list(dict.fromkeys(voices))
        jobs[case] = [(m, v, r) for m in models for v in voices for r in range(1, args.rolls + 1)]
    total = sum(len(j) for j in jobs.values())
    print(f"Making {total} clips under {args.out}/ ({', '.join(short_name(m) for m in models)}).\n")

    failures = 0
    for case, case_jobs in jobs.items():
        out = args.out / case.locale / case.name
        out.mkdir(parents=True, exist_ok=True)
        # Clear earlier clips so a rating pass never mixes in a stale model,
        # voice or roll, or a previous file where this run's call failed.
        for stale in [*out.glob("*.wav"), out / "key.json"]:
            stale.unlink(missing_ok=True)
        style = styles[case]
        (out / "prompt.txt").write_text(f"text:\n{case.text}\n\nstyle:\n{style}\n", encoding="utf-8")
        app_model = short_name(gemini_tts.model_for(source, case.locale))
        print(f"{case.locale} {case.name} ({case.style}; the app uses {app_model} here)")

        names = {job: f"{short_name(job[0])}-{job[1].lower()}-{job[2]}" for job in case_jobs}
        if args.blind:
            case_jobs = random.sample(case_jobs, len(case_jobs))
            names = {job: f"clip-{i:02d}" for i, job in enumerate(case_jobs, 1)}
        key = {}
        for model, voice, roll in case_jobs:
            name = names[(model, voice, roll)]
            try:
                clip = gemini_tts.synthesize(api_key, model, case.text, style, voice, args.timeout)
            except (RuntimeError, ValueError, OSError) as exc:
                failures += 1
                print(f"  {name}: FAILED: {exc}")
                continue
            gemini_tts.write_wav(out / f"{name}.wav", clip.pcm, clip.sample_rate)
            key[name] = {"model": model, "voice": voice, "roll": roll}
            label = name if args.blind else f"{short_name(model):10} {voice:12} #{roll}"
            print(
                f"  {label}  {clip.seconds_to_synthesize:5.1f}s to make, "
                f"{clip.duration:4.1f}s long, {clip.usage.get('candidatesTokenCount', '?')} output tokens"
            )
        if args.blind:
            (out / "key.json").write_text(json.dumps(key, indent=2) + "\n", encoding="utf-8")
    print(f"\nRate with: python3 scripts/rate-tts-clips.py {args.out}/")
    if args.blind:
        print("Each directory's key.json says which clip is which (look after rating).")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
