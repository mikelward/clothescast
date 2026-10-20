#!/usr/bin/env python3
"""
Renders one persona style (default: VAMPIRE) in several Gemini voices so you
can pick the voice that suits it best.

The style directive and language hint are read from GeminiTtsClient.kt, so
the clips use exactly the prompt the app sends, not a retyped copy.

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
import base64
import json
import os
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request
import wave

REPO = pathlib.Path(__file__).resolve().parent.parent
CLIENT_KT = REPO / "core/data/src/main/kotlin/app/clothescast/core/data/tts/GeminiTtsClient.kt"

MODEL = "gemini-2.5-flash-preview-tts"
SAMPLE_RATE = 24_000

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


def kotlin_string_const(source: str, name: str) -> str:
    """Value of a `const val NAME: String = "..." + "..."` in Kotlin source."""
    match = re.search(
        rf"const val {re.escape(name)}: String =\s*((?:\"(?:[^\"\\]|\\.)*\"\s*\+?\s*)+)",
        source,
    )
    if not match:
        raise ValueError(f"{name} not found in {CLIENT_KT.name}")
    parts = re.findall(r"\"((?:[^\"\\]|\\.)*)\"", match.group(1))
    raw = "".join(parts)
    return raw.replace("\\n", "\n").replace("\\'", "'").replace('\\"', '"').replace("\\\\", "\\")


def language_directives(source: str) -> dict[str, str]:
    """The LANGUAGE_DIRECTIVES map, and only that map (ACCENT_DIRECTIVES also has "xx-YY" keys)."""
    match = re.search(r"val LANGUAGE_DIRECTIVES: Map<String, String> = mapOf\((.*?)\n\)", source, re.S)
    if not match:
        raise ValueError(f"LANGUAGE_DIRECTIVES not found in {CLIENT_KT.name}")
    return dict(re.findall(r"\"([^\"]+)\" to \"((?:[^\"\\]|\\.)*)\"", match.group(1)))


def language_hint(source: str, tag: str) -> str | None:
    """Mirrors geminiLanguageDirectiveFor: exact language-COUNTRY entry, else the bare language."""
    directives = language_directives(source)
    # Locale.forLanguageTag is case-insensitive: lower the language, upper the region.
    # Skip a 4-letter script subtag (pt-Latn-BR); the region is the next 2-letter
    # or 3-digit one.
    subtags = tag.replace("_", "-").split("-")
    language = subtags[0].lower()
    rest = [t for t in subtags[1:] if not (len(t) == 4 and t.isalpha())]
    country = rest[0].upper() if rest and (len(rest[0]) == 2 or rest[0].isdigit()) else ""
    language = {"iw": "he", "in": "id", "ji": "yi"}.get(language, language)
    if country and f"{language}-{country}" in directives:
        return directives[f"{language}-{country}"]
    return directives.get(language)


def build_prompt(source: str, style: str, locale: str, text: str) -> str:
    # Mirrors GeminiTtsClient.synthesize for a persona: directive, then the
    # bare language hint, then the text. The forecaster default takes a
    # different, per-locale route (directiveFor + an accent directive), which
    # this script doesn't reproduce, so it refuses rather than mislead.
    if style == "WEATHER_FORECASTER":
        raise ValueError("WEATHER_FORECASTER isn't a persona; use compare-tts-directives.py")
    prompt = kotlin_string_const(source, f"GEMINI_TTS_STYLE_DIRECTIVE_{style}")
    hint = language_hint(source, locale)
    if hint:
        prompt += hint + "\n\n"
    return prompt + text


def synthesize(api_key: str, voice: str, prompt: str, timeout: float) -> tuple[bytes, int]:
    payload = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {
                "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}
            },
        },
    }
    req = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read())
    # Like GeminiTtsClient: take the first part carrying inlineData, since a
    # text part can precede it, and treat a 200 with none as a failed clip.
    try:
        parts = body["candidates"][0]["content"]["parts"]
    except (KeyError, IndexError, TypeError) as exc:
        raise ValueError(f"no audio in response: {json.dumps(body)[:200]}") from exc
    inline = next((p["inlineData"] for p in parts if isinstance(p, dict) and p.get("inlineData")), None)
    if inline is None:
        raise ValueError(f"no audio in response: {json.dumps(body)[:200]}")
    # Like GeminiTtsClient, honor the rate Gemini advertises ("audio/L16;rate=24000").
    match = re.search(r"rate=(\d+)", inline.get("mimeType", ""))
    return base64.b64decode(inline["data"]), int(match.group(1)) if match else SAMPLE_RATE


def write_wav(path: pathlib.Path, pcm: bytes, rate: int) -> None:
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(pcm)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--style", default="VAMPIRE", help="TtsStyle name (default VAMPIRE)")
    parser.add_argument("--voices", nargs="+", default=DEFAULT_VOICES)
    parser.add_argument("--rolls", type=int, default=2, help="clips per voice, to hear variance")
    parser.add_argument("--locale", default="en-GB")
    parser.add_argument("--text", help="sample to read; required for a non-English --locale")
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
    try:
        prompt = build_prompt(CLIENT_KT.read_text(encoding="utf-8"), style, args.locale, args.text)
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 2
    out = args.out or pathlib.Path(f"/tmp/tts-persona-{style.lower()}-{args.locale}")
    out.mkdir(parents=True, exist_ok=True)
    # Clear this style's earlier clips so a rating pass never mixes in stale
    # voices, rolls or locales, or a previous file where this run's call failed.
    for stale in out.glob(f"{style.lower()}-*.wav"):
        stale.unlink()

    print(f"Writing clips to {out}/\n")
    print(f"Prompt:\n{prompt}\n")

    failures = 0
    for voice in args.voices:
        for roll in range(1, args.rolls + 1):
            slug = f"{style.lower()}-{voice.lower()}-{roll}"
            print(f"  {slug} ...", end=" ", flush=True)
            started = time.monotonic()
            try:
                pcm, rate = synthesize(api_key, voice, prompt, args.timeout)
            except (urllib.error.URLError, TimeoutError, ValueError) as exc:
                failures += 1
                print(f"✗  {type(exc).__name__}: {exc}")
                continue
            elapsed = time.monotonic() - started
            write_wav(out / f"{slug}.wav", pcm, rate)
            print(f"✓  {len(pcm) // 2 / rate:.1f}s audio in {elapsed:.1f}s")

    print("\nRate with:")
    print(f"  python3 scripts/rate-tts-clips.py {out}/")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
