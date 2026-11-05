#!/usr/bin/env python3
"""
Single-shot Gemini TTS helper for prompt iteration.

Synthesises one clip, writes it to a WAV, and plays it (aplay on Linux, afplay on macOS). Designed
for tweak-in-a-loop work: change one flag, rerun, listen, change another.

With no flags it sends exactly what the app sends: the en-GB
WEATHER_FORECASTER directive and accent as `speechMetadata.style`, the
default voice, and the model the app picks for the locale, all read from
`core/data/.../tts/GeminiTtsClient.kt`. Gemini 3.8 TTS reads the text
verbatim, so direction never goes in --text.

Usage
-----
  export GEMINI_API_KEY=your_key_here

  # Production request (en-GB Weather Forecaster, default voice, Flash-Lite):
  python3 scripts/say.py

  # Another style or locale, as the app would send it:
  python3 scripts/say.py --style VAMPIRE
  python3 scripts/say.py --locale de-DE --text "Heute wird es 14 Grad."

  # Try a different directive or accent line ('' drops the accent):
  python3 scripts/say.py --directive "Warm and unhurried, like a friend."
  python3 scripts/say.py --accent ""

  # Same request on the other model:
  python3 scripts/say.py --model gemini-3.8-flash-tts

  # See the request before the API call; write a file, skip playback:
  python3 scripts/say.py --print-prompt --no-play --out /tmp/foo.wav

For Flash against Flash-Lite, several locales, voices or holiday themes
side by side, use compare-tts-models.py.

Cost: each call is one Gemini TTS request (about $0.001 a short clip on
Flash-Lite at 2026 rates; the free tier covers light iteration).
"""

import argparse
import os
import pathlib

import gemini_tts

DEFAULT_TEXT = "Today will be cold to cool. Wear a jumper and jacket."


def main() -> None:
    source = gemini_tts.client_source()
    default_voice = gemini_tts.kotlin_string_const(source, "DEFAULT_GEMINI_TTS_VOICE")
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--style", default="WEATHER_FORECASTER", help="TtsStyle name (default: WEATHER_FORECASTER).")
    parser.add_argument("--locale", default="en-GB", help="Voice locale (default: en-GB).")
    parser.add_argument("--directive", help="Style directive in place of the app's.")
    parser.add_argument("--accent", help="Accent or language line in place of the app's. Pass '' to omit.")
    parser.add_argument("--voice", help=f"Gemini voice name (default: the style's pinned voice, else {default_voice}).")
    parser.add_argument("--text", default=DEFAULT_TEXT, help="Text to speak, read verbatim.")
    parser.add_argument("--model", help="Gemini TTS model id (default: the one the app picks for --locale).")
    parser.add_argument("--out", default="/tmp/say.wav", help="Output WAV path (default: /tmp/say.wav).")
    parser.add_argument("--no-play", action="store_true", help="Write the WAV but don't invoke aplay.")
    parser.add_argument("--print-prompt", action="store_true", help="Print the text and style before calling the API.")
    args = parser.parse_args()

    api_key = os.environ.get("GEMINI_API_KEY", "")
    if not api_key:
        raise SystemExit("Set GEMINI_API_KEY env var before running.")

    style_name = args.style.upper()
    try:
        style = gemini_tts.delivery_style(source, style_name, args.locale, args.directive, args.accent)
    except ValueError as exc:
        raise SystemExit(str(exc)) from None
    voice = args.voice or gemini_tts.pinned_voice(source, style_name) or default_voice
    model = args.model or gemini_tts.model_for(source, args.locale)

    if args.print_prompt:
        print(f"--- text ---\n{args.text}\n--- style ---\n{style}\n--- end ---")

    try:
        clip = gemini_tts.synthesize(api_key, model, args.text, style, voice)
    except (RuntimeError, ValueError) as exc:
        raise SystemExit(str(exc)) from None
    out = pathlib.Path(args.out)
    gemini_tts.write_wav(out, clip.pcm, clip.sample_rate)
    print(f"Wrote {out} ({clip.duration:.1f}s, {voice} on {model}).")

    if not args.no_play:
        gemini_tts.play(out)


if __name__ == "__main__":
    main()
