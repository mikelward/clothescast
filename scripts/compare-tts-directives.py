#!/usr/bin/env python3
"""
Tests two levers for fixing the posh/theatrical British register:
  A) changing the global style directive
  B) changing the en-GB accent clause

Generates one clip per candidate × voice so you can hear each lever
independently and see whether combining them helps or hurts.

Usage
-----
  export GEMINI_API_KEY=your_key_here
  python3 scripts/compare-tts-directives.py

Output
------
Writes clips into /tmp/tts-en-gb/:
  style-A*  — style directive variants, accent clause held constant
  accent-B*  — accent clause variants, style directive held constant
  combo-C*   — promising combos

Rate with:
  python3 scripts/rate-tts-clips.py /tmp/tts-en-gb/
"""

import os
import pathlib

import gemini_tts

API_KEY = os.environ.get("GEMINI_API_KEY", "")
MODEL = gemini_tts.models(gemini_tts.client_source())[0]
SAMPLE_TEXT = (
    "It's 12 degrees and overcast with light rain this afternoon. "
    "Wear a waterproof jacket and consider an umbrella — "
    "the rain should ease by evening."
)

# ── Held-constant pieces ──────────────────────────────────────────────────────

STYLE_NEWSREADER = (
    "Read the following in a clear, educated newsreader style — articulate but "
    "not theatrical or exaggerated. No audio effects, background noise, or "
    "vinyl-style texture:\n\n"
)
STYLE_CONVERSATIONAL = (
    "Read the following clearly and conversationally — educated but not formal "
    "or posh, not theatrical or exaggerated. No audio effects, background noise, "
    "or vinyl-style texture:\n\n"
)

ACCENT_CURRENT = "Speak with a Standard Southern British accent."
ACCENT_MODERN = (
    "Speak in a natural, modern Southern British accent — "
    "conversational and clear, not formal, posh, or received pronunciation."
)

# ── Candidates ────────────────────────────────────────────────────────────────

CANDIDATES = [
    # A: style directive variants, accent held at current
    ("style-A1-newsreader",      STYLE_NEWSREADER,      ACCENT_CURRENT),
    ("style-A2-conversational",  STYLE_CONVERSATIONAL,  ACCENT_CURRENT),

    # B: accent clause variants, style held at newsreader
    ("accent-B1-current",        STYLE_NEWSREADER,      ACCENT_CURRENT),
    ("accent-B2-modern",         STYLE_NEWSREADER,      ACCENT_MODERN),

    # C: combo
    ("combo-C1-conv+modern",     STYLE_CONVERSATIONAL,  ACCENT_MODERN),
]

# Note: style-A1 and accent-B1 are identical (current production) — they'll
# produce the same audio, useful as a sanity-check that the two runs match.

VOICES = ["Erinome", "Kore"]


def synthesize(style: str, accent: str | None, voice: str) -> gemini_tts.Clip:
    # Gemini 3.8 reads the text verbatim: direction goes in the style.
    delivery = style.strip() + ("\n\n" + accent if accent else "")
    return gemini_tts.synthesize(API_KEY, MODEL, SAMPLE_TEXT, delivery, voice)





def main() -> None:
    if not API_KEY:
        raise SystemExit("Set GEMINI_API_KEY env var before running.")

    out = pathlib.Path("/tmp/tts-en-gb")
    print(f"Writing {len(CANDIDATES) * len(VOICES)} clips to {out}/\n")

    for label, style, accent in CANDIDATES:
        for voice in VOICES:
            slug = f"{label}-{voice.lower()}"
            print(f"  {slug} ...", end=" ", flush=True)
            try:
                clip = synthesize(style, accent, voice)
                gemini_tts.write_wav(out / f"{slug}.wav", clip.pcm, clip.sample_rate)
                print(f"✓  ({clip.duration:.1f}s)")
            except Exception as exc:
                print(f"✗  {exc}")

    print(f"\nDone. Rate with:")
    print(f"  python3 scripts/rate-tts-clips.py {out}/")


if __name__ == "__main__":
    main()
