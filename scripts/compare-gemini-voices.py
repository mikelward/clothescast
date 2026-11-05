#!/usr/bin/env python3
"""
Tests male voice candidates (Charon, Sadaltager) with the current
production directive and en-GB accent to pick one to add to the picker.

Usage
-----
  export GEMINI_API_KEY=your_key_here
  python3 scripts/compare-gemini-voices.py

Output
------
Writes one .wav file per voice into /tmp/tts-voices/:
  charon-en-GB.wav, sadaltager-en-GB.wav

Run rate-tts-clips.py against the output directory to rate them:
  python3 scripts/rate-tts-clips.py /tmp/tts-voices/
"""

import os
import pathlib

import gemini_tts

API_KEY = os.environ.get("GEMINI_API_KEY", "")
MODEL = gemini_tts.models(gemini_tts.client_source())[0]

DIRECTIVE = (
    "Read the following in a clear, educated newsreader style — articulate but "
    "not theatrical or exaggerated. No audio effects, background noise, or "
    "vinyl-style texture:\n\n"
)
ACCENT = "Speak with a Standard Southern British accent.\n\n"

SAMPLE_TEXT = (
    "It's 12 degrees and overcast with light rain this afternoon. "
    "Wear a waterproof jacket and consider an umbrella — "
    "the rain should ease by evening."
)

# Male voice candidates not yet tested with the newsreader directive.
# Charon was "ok" in early en-AU testing; Sadaltager is untested.
VOICES = [
    "Charon",       # Informative
    "Sadaltager",   # Knowledgeable
]


def synthesize(voice: str) -> gemini_tts.Clip:
    # Gemini 3.8 reads the text verbatim: direction goes in the style.
    return gemini_tts.synthesize(API_KEY, MODEL, SAMPLE_TEXT, (DIRECTIVE + ACCENT).strip(), voice)





def main() -> None:
    if not API_KEY:
        raise SystemExit("Set GEMINI_API_KEY env var before running.")

    out = pathlib.Path("/tmp/tts-voices")
    print(f"Writing clips to {out}/\n")
    print(f"Directive: {DIRECTIVE.strip()!r}")
    print(f"Accent:    {ACCENT.strip()!r}")
    print(f"Text:      {SAMPLE_TEXT!r}\n")

    for voice in VOICES:
        slug = f"{voice.lower()}-en-GB"
        print(f"  {slug} ...", end=" ", flush=True)
        try:
            clip = synthesize(voice)
            gemini_tts.write_wav(out / f"{slug}.wav", clip.pcm, clip.sample_rate)
            print(f"✓  ({clip.duration:.1f}s)")
        except Exception as exc:
            print(f"✗  {exc}")

    print(f"\nDone. Rate with:")
    print(f"  python3 scripts/rate-tts-clips.py {out}/")


if __name__ == "__main__":
    main()
