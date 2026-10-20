#!/usr/bin/env python3
"""
Evaluates the effect of making the TTS prompt explicitly weather-broadcast
flavoured — "read this like a national news weather report, enunciate clearly,
measured pace, accentuate clothing recommendations."

Candidates move in three steps:
  A) baselines — current NORMAL and NEWSREADER directives
  B) core proposal — the user's explicit weather-report idea, with and without
     the sentence/clothing-emphasis clause; B3 also adds a "standard variety,
     not regional dialect" sentence (paired with the no-variety locales)
  C) tweaks — minor wording changes that may sharpen or soften the effect

Each candidate is tested across three locales (en-US, en-AU, en-GB) using the
same accent directives the app injects, so style × accent interactions are
visible.

Usage
-----
  export GEMINI_API_KEY=your_key_here
  python3 scripts/eval-weather-report-style.py

  # only one locale:
  python3 scripts/eval-weather-report-style.py --filter en-AU

  # the persona-eval candidates against the default (Despina), on the
  # shipped en-GB and en-AU pairs, in their own directory so the rating pass
  # sees only these five voices (--overwrite keeps a rerun fresh):
  python3 scripts/eval-weather-report-style.py --out /tmp/tts-voice-trial --overwrite \
      --filter en-GB-bare-weather-B2-with-emphasis --voices Despina Algenib Alnilam Enceladus Schedar
  python3 scripts/eval-weather-report-style.py --out /tmp/tts-voice-trial --overwrite \
      --filter en-AU-no-variety-weather-B3 --voices Despina Algenib Alnilam Enceladus Schedar

  # one candidate across all locales/voices:
  python3 scripts/eval-weather-report-style.py --filter weather-B2

  # the B3 register-in-directive eval (control = post-#351 shipped pair;
  # treatment = bare-variety accent + B3 directive):
  python3 scripts/eval-weather-report-style.py --filter en-GB-bare-weather-B2-with-emphasis
  python3 scripts/eval-weather-report-style.py --filter en-GB-no-variety-weather-B3
  python3 scripts/eval-weather-report-style.py --filter en-AU-prod-weather-B2-with-emphasis
  python3 scripts/eval-weather-report-style.py --filter en-AU-no-variety-weather-B3

Output
------
Writes .wav files to /tmp/tts-weather-style/<locale>/ (or --out):
  en-US/<candidate>-<voice>.wav
  en-AU/<candidate>-<voice>.wav
  en-GB/<candidate>-<voice>.wav

Rate with:
  python3 scripts/rate-tts-clips.py /tmp/tts-weather-style/
  python3 scripts/rate-tts-clips.py /tmp/tts-weather-style/en-AU/
  python3 scripts/rate-tts-clips.py /tmp/tts-weather-style/en-GB/ --pattern weather-B2
"""

import argparse
import base64
import json
import os
import pathlib
import urllib.request
import wave

API_KEY = os.environ.get("GEMINI_API_KEY", "")
MODEL = "gemini-2.5-flash-preview-tts"
SAMPLE_RATE = 24_000

# Realistic clips in each supported language — one temperature read + one
# clothing call-to-action so we can hear whether emphasis on the recommendation
# actually fires.
SAMPLE_TEXT_EN = "Today will be cold to cool. Wear a jumper and jacket."

SAMPLE_TEXT_DE = "Heute wird es kalt bis kühl. Trag Pullover und Jacke."

def sample_text_for(locale: str) -> str:
    lang = locale.split("-")[0]
    return {"de": SAMPLE_TEXT_DE}.get(lang, SAMPLE_TEXT_EN)

# ── locales — same strings as GeminiTtsClient.kt ACCENT_DIRECTIVES ────────────
#
# en-AU has multiple variants to explore whether "Cultivated" and "educated"
# push the register too formal. The production value is en-AU; the others test
# lighter touches:
#   en-AU-general   — General Australian (the mid-tier between Broad and
#                     Cultivated; most ABC/SBS presenters sit here)
#   en-AU-natural   — drops the class label, just suppresses broad/nasal
#   en-AU-presenter — anchors to a concrete reference rather than a
#                     phonetic/social descriptor
#   en-AU-minimal   — bare minimum; useful to see how much the extra clauses
#                     are actually doing vs. the model's default

LOCALES: dict[str, str] = {
    "en-US": "Speak with a General American accent.",
    "en-AU":           "Speak with a Cultivated Australian accent — clear and educated, not broad.",
    "en-AU-general":   "Speak with a General Australian accent — clear and natural, not broad.",
    "en-AU-natural":   "Speak with a natural Australian accent — clear, not broad or nasal.",
    "en-AU-presenter": "Speak with the clear, natural accent of an Australian broadcast news presenter.",
    "en-AU-minimal":   "Speak with an Australian accent — clear and natural.",
    "en-GB":           "Speak with a Standard Southern British accent.",
    "en-GB-bbc":       "Speak with a Standard Southern British accent, as spoken by a BBC news presenter.",
    "en-GB-presenter": "Speak with the clear accent of a British television news presenter.",
    "en-GB-measured":  "Speak with a Standard British accent — clear, measured, and natural.",
    # en-GB-prod: the shipped string (control for the "trim clarity" eval).
    # en-GB-bare: candidate trim — relies on the WEATHER_FORECASTER directive's
    # "Enunciate clearly" to carry the clarity hint instead of duplicating it
    # in the accent clause.
    "en-GB-prod":      "Speak with a Standard British accent — clear and natural.",
    "en-GB-bare":      "Speak with a Standard British accent.",
    # en-GB-no-variety / en-AU-no-variety / en-AU-prod: the "B3 register-in-
    # directive" eval. Hypothesis: with the WEATHER_FORECASTER directive
    # carrying "Use the language's standard variety, not a regional dialect."
    # the per-locale variety descriptors ("Standard", "General") become
    # redundant and the accent strings can drop them. Pair the bare-variety
    # locale with the B3 directive candidate; the prod locale with B2.
    "en-AU-prod":         "Speak with a General Australian accent, not broad.",
    "en-AU-no-variety":   "Speak with an Australian accent, not broad.",
    "en-GB-no-variety":   "Speak with a British accent.",
    # de-DE falls back to the language-only "de" key in the app (no explicit
    # de-DE entry in ACCENT_DIRECTIVES); de-AT has its own entry.
    "de-DE": "Sprich auf Deutsch in einem klaren, hochdeutschen Akzent.",
    "de-AT": "Sprich auf Deutsch mit einem klaren österreichischen Akzent.",
    # de-no-variety: B3 candidate — drops "in einem hochdeutschen Akzent",
    # relying on the directive to convey the standard register.
    "de-no-variety":      "Sprich auf Deutsch.",
}

# ── A: baselines (current production directives) ─────────────────────────────

BASELINE_NORMAL = (
    "Read the following in a clean, crisp studio voice with no audio effects, "
    "background noise, or vinyl-style texture:\n\n"
)

BASELINE_NEWSREADER = (
    "Read the following in a clear, educated newsreader style — articulate but "
    "not theatrical or exaggerated. No audio effects, background noise, or "
    "vinyl-style texture:\n\n"
)

# ── B: user's explicit weather-broadcast proposal ─────────────────────────────
#
# B1 — basic form: national-news framing + enunciation + measured speed.
# B2 — full form: adds "accentuate ends of sentences and clothing
#      recommendations" per the original suggestion.

WEATHER_B1_BASIC = (
    "Read the following weather forecast in the style of a weather report on a "
    "national news service. Enunciate clearly and use a measured speed. "
    "No audio effects, background noise, or vinyl-style texture:\n\n"
)

WEATHER_B2_WITH_EMPHASIS = (
    "Read the following weather forecast in the style of a weather report on a "
    "national news service. Enunciate clearly and use a measured speed. "
    "Accentuate the ends of sentences and give a gentle emphasis to clothing "
    "recommendations. No audio effects, background noise, or vinyl-style "
    "texture:\n\n"
)

# ── C: suggested tweaks ───────────────────────────────────────────────────────
#
# C1 — "authoritative yet approachable": real broadcast meteorologists sit
#      between newsreader gravity and friendly presenter warmth; naming this
#      register explicitly may get closer than either baseline alone.
#
# C2 — "deliberate cadence" instead of "measured speed": Gemini steers on
#      prosodic cues like "cadence" and "pace" better than bare "speed", and
#      "deliberate" also nudges it toward clearer consonants. Keeps the
#      sentence-final uplift from B2.
#
# C3 — adds a "pause briefly between forecast segments" note; the sample text
#      has two natural breaks (morning conditions / noon showers / evening
#      recovery) and explicit pause direction may help the model breathe them.

TWEAK_C1_AUTHORITATIVE = (
    "Read the following weather forecast as a presenter on a national broadcast "
    "news service — authoritative yet approachable, as if speaking to a large "
    "general audience. Enunciate clearly at a measured pace. No audio effects, "
    "background noise, or vinyl-style texture:\n\n"
)

TWEAK_C2_CADENCE = (
    "Read the following weather forecast in the style of a national-news "
    "weather report. Use a deliberate cadence: unhurried, clearly enunciated, "
    "with a natural lift at the end of each sentence. Give a gentle extra "
    "emphasis to clothing advice. No audio effects, background noise, or "
    "vinyl-style texture:\n\n"
)

TWEAK_C3_WITH_PAUSES = (
    "Read the following weather forecast in the style of a national-news "
    "weather report. Enunciate clearly at a measured pace, pause briefly "
    "between forecast segments, and give a gentle extra emphasis to clothing "
    "recommendations. No audio effects, background noise, or vinyl-style "
    "texture:\n\n"
)

# ── B3: register-in-directive (paired with no-variety accents) ───────────────
#
# B3 — adds an explicit "use the language's standard variety, not a regional
#      dialect" sentence early in the directive. Hypothesis: the per-locale
#      variety descriptors in ACCENT_DIRECTIVES ("Standard British", "General
#      Australian", "hochdeutsch") become redundant once the directive carries
#      this signal, and the accent strings can collapse to just the language /
#      regional flavour. Test against the no-variety locales above.

WEATHER_B3_REGISTER_IN_DIRECTIVE = (
    "Read the following weather forecast in the style of a weather report on a "
    "national news service. Use the language's standard variety, not a "
    "regional dialect. Enunciate clearly and use a measured speed. "
    "Accentuate the ends of sentences and give a gentle emphasis to clothing "
    "recommendations. No audio effects, background noise, or vinyl-style "
    "texture:\n\n"
)

# ── candidate table ───────────────────────────────────────────────────────────

CANDIDATES: list[tuple[str, str]] = [
    ("baseline-A1-normal",                  BASELINE_NORMAL),
    ("baseline-A2-newsreader",              BASELINE_NEWSREADER),
    ("weather-B1-basic",                    WEATHER_B1_BASIC),
    ("weather-B2-with-emphasis",            WEATHER_B2_WITH_EMPHASIS),
    ("weather-B3-register-in-directive",    WEATHER_B3_REGISTER_IN_DIRECTIVE),
    ("tweak-C1-authoritative",              TWEAK_C1_AUTHORITATIVE),
    ("tweak-C2-cadence",                    TWEAK_C2_CADENCE),
    ("tweak-C3-with-pauses",                TWEAK_C3_WITH_PAUSES),
]

VOICES = ["Erinome", "Kore", "Aoede", "Despina", "Iapetus", "Charon", "Leda"]

# Male voices that scored 10 as the Halloween vampire in
# compare-persona-voices.py, trialed here for the everyday forecaster.
PERSONA_CANDIDATES = ["Algenib", "Alnilam", "Enceladus", "Schedar"]
VOICES += PERSONA_CANDIDATES


# ── helpers ───────────────────────────────────────────────────────────────────

def synthesize(directive: str, accent: str, voice: str, sample: str) -> bytes:
    prompt = directive + accent + "\n\n" + sample
    payload = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {
                "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}
            },
        },
    }
    url = (
        f"https://generativelanguage.googleapis.com/v1beta/models/"
        f"{MODEL}:generateContent?key={API_KEY}"
    )
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req) as resp:
        body = json.loads(resp.read())
    inline = body["candidates"][0]["content"]["parts"][0]["inlineData"]
    return base64.b64decode(inline["data"])


def parse_voices(raw: list[str]) -> list[str]:
    """Splits commas and restores the canonical capitalization ("algenib" -> "Algenib")."""
    known = {v.lower(): v for v in VOICES}
    names = [n.strip() for item in raw for n in item.split(",") if n.strip()]
    return [known.get(n.lower(), n[:1].upper() + n[1:].lower()) for n in names]


def write_wav(path: pathlib.Path, pcm: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(pcm)


# ── main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--filter",
        default="",
        metavar="SUBSTRING",
        help=(
            "Only generate clips whose slug contains this string. "
            "Examples: 'en-AU', 'weather-B2', 'en-GB-baseline', 'erinome'"
        ),
    )
    parser.add_argument(
        "--reverse",
        action="store_true",
        help=(
            "Process clips in reverse order. Run two instances in parallel — "
            "one without --reverse, one with — to work from both ends toward "
            "the middle without treading on each other."
        ),
    )
    parser.add_argument(
        "--voices",
        nargs="+",
        metavar="VOICE",
        help=(
            "Only these voices, space- or comma-separated, any case "
            "(e.g. --voices Algenib Despina, or --voices algenib,despina). Default: all."
        ),
    )
    parser.add_argument(
        "--out",
        type=pathlib.Path,
        default=pathlib.Path("/tmp/tts-weather-style"),
        help="Output directory (default /tmp/tts-weather-style).",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Regenerate matching clips instead of skipping ones already on disk.",
    )
    args = parser.parse_args()
    if args.overwrite and args.reverse:
        # The two-instance split relies on each worker skipping the other's
        # clips; overwriting would make both render (and delete) everything.
        parser.error("--overwrite can't be combined with --reverse")
    voices = parse_voices(args.voices) if args.voices else VOICES

    if not API_KEY:
        raise SystemExit("Set GEMINI_API_KEY env var before running.")

    # Build full clip list, then apply filter
    clips: list[tuple[str, str, str, str, str]] = [  # (slug, directive, accent, voice, sample)
        (locale, f"{label}-{voice.lower()}", directive, accent, voice, sample_text_for(locale))
        for locale, accent in LOCALES.items()
        for label, directive in CANDIDATES
        for voice in voices
        if args.filter in f"{locale}-{label}-{voice.lower()}"
    ]
    if not clips:
        raise SystemExit(f"No clips match filter '{args.filter}'.")

    if args.reverse:
        clips = list(reversed(clips))

    out = args.out
    print(f"Writing {len(clips)} clip(s) to {out}/<locale>/\n")

    for locale, slug, directive, accent, voice, sample in clips:
        path = out / locale / f"{slug}.wav"
        if path.exists() and not args.overwrite:
            print(f"  {locale}/{slug} ... skip (already exists)")
            continue
        print(f"  {locale}/{slug} ...", end=" ", flush=True)
        try:
            pcm = synthesize(directive, accent, voice, sample)
            write_wav(path, pcm)
            print(f"ok  ({len(pcm) // 2 / SAMPLE_RATE:.1f}s)")
        except Exception as exc:
            # Under --overwrite (single worker: it refuses --reverse), don't
            # leave the previous run's clip to be rated as this run's. Without
            # it the clip didn't exist before, and a parallel worker may own it.
            if args.overwrite:
                path.unlink(missing_ok=True)
            print(f"FAIL  {exc}")

    print(f"\nDone. Rate with:")
    print(f"  python3 scripts/rate-tts-clips.py {out}/")
    print(f"\nBy locale:")
    for locale in LOCALES:
        print(f"  python3 scripts/rate-tts-clips.py {out}/{locale}/")
    print(f"\nBy voice:")
    for v in voices:
        print(f"  python3 scripts/rate-tts-clips.py {out}/ --pattern {v.lower()}")


if __name__ == "__main__":
    main()
