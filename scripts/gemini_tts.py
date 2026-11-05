"""
Shared Gemini TTS plumbing for the voice eval scripts.

Builds the request the app sends, reading the app's own strings out of
GeminiTtsClient.kt rather than retyping them: the style directives, the
accent and language lines, the two models, and which languages take the
full Flash model. Gemini 3.8 TTS reads `text` verbatim, so delivery
direction goes in the part's `speechMetadata.style`, and it answers with a
WAV file, which `synthesize` unwraps to PCM.

Imported by say.py, compare-tts-models.py and the other compare-* scripts;
gemini_tts_test.py checks it against the Kotlin source.
"""

from __future__ import annotations

import base64
import io
import json
import pathlib
import re
import time
import urllib.error
import urllib.request
import wave
from dataclasses import dataclass

REPO = pathlib.Path(__file__).resolve().parent.parent
CLIENT_KT = REPO / "core/data/src/main/kotlin/app/clothescast/core/data/tts/GeminiTtsClient.kt"
ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
DEFAULT_SAMPLE_RATE = 24_000


def client_source() -> str:
    return CLIENT_KT.read_text(encoding="utf-8")


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


def _string_map(source: str, name: str) -> dict[str, str]:
    match = re.search(rf"val {name}: Map<String, String> = mapOf\((.*?)\n\)", source, re.S)
    if not match:
        raise ValueError(f"{name} not found in {CLIENT_KT.name}")
    return dict(re.findall(r"\"([^\"]+)\" to \"((?:[^\"\\]|\\.)*)\"", match.group(1)))


def language_directives(source: str) -> dict[str, str]:
    """The LANGUAGE_DIRECTIVES map: bare language hints for personas."""
    return _string_map(source, "LANGUAGE_DIRECTIVES")


def accent_directives(source: str) -> dict[str, str]:
    """The ACCENT_DIRECTIVES map: full accent lines for the forecaster."""
    return _string_map(source, "ACCENT_DIRECTIVES")


def _language_and_country(tag: str) -> tuple[str, str]:
    # Locale.forLanguageTag is case-insensitive: lower the language, upper the
    # region. Skip a 4-letter script subtag (pt-Latn-BR); the region is the
    # next 2-letter or 3-digit one. Map the legacy codes Android reports.
    subtags = tag.replace("_", "-").split("-")
    language = subtags[0].lower()
    rest = [t for t in subtags[1:] if not (len(t) == 4 and t.isalpha())]
    country = rest[0].upper() if rest and (len(rest[0]) == 2 or rest[0].isdigit()) else ""
    return {"iw": "he", "in": "id", "ji": "yi"}.get(language, language), country


def _lookup(directives: dict[str, str], tag: str) -> str | None:
    language, country = _language_and_country(tag)
    if country and f"{language}-{country}" in directives:
        return directives[f"{language}-{country}"]
    return directives.get(language)


def language_hint(source: str, tag: str) -> str | None:
    """Mirrors geminiLanguageDirectiveFor: exact language-COUNTRY entry, else the bare language."""
    return _lookup(language_directives(source), tag)


def accent_directive(source: str, tag: str) -> str | None:
    """Mirrors geminiAccentDirectiveFor."""
    return _lookup(accent_directives(source), tag)


def directive_for(source: str, style: str, tag: str) -> str:
    """Mirrors directiveFor: en-AU's forecaster takes the register variant."""
    if style == "WEATHER_FORECASTER" and _language_and_country(tag) == ("en", "AU"):
        return kotlin_string_const(source, "GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER_REGISTER")
    return kotlin_string_const(source, f"GEMINI_TTS_STYLE_DIRECTIVE_{style}")


def delivery_style(
    source: str,
    style: str,
    tag: str,
    directive: str | None = None,
    accent: str | None = None,
) -> str:
    """
    The `speechMetadata.style` the app sends: the style's directive, then the
    forecaster's accent line or a persona's bare language hint, as
    GeminiTtsClient.synthesize builds it. Pass `directive` or `accent` to try
    a different one in its place; an empty `accent` drops the line.
    """
    if accent is None:
        accent = accent_directive(source, tag) if style == "WEATHER_FORECASTER" else language_hint(source, tag)
    text = (directive if directive is not None else directive_for(source, style, tag)).strip()
    return f"{text}\n\n{accent}" if accent else text


def models(source: str) -> tuple[str, str]:
    """(Flash-Lite, Flash): DEFAULT_GEMINI_TTS_MODEL and GEMINI_TTS_FULL_MODEL."""
    return (
        kotlin_string_const(source, "DEFAULT_GEMINI_TTS_MODEL"),
        kotlin_string_const(source, "GEMINI_TTS_FULL_MODEL"),
    )


def full_model_languages(source: str) -> set[str]:
    match = re.search(r"FULL_MODEL_LANGUAGES = setOf\((.*?)\)", source, re.S)
    if not match:
        raise ValueError(f"FULL_MODEL_LANGUAGES not found in {CLIENT_KT.name}")
    return set(re.findall(r"\"([^\"]+)\"", match.group(1)))


def model_for(source: str, tag: str) -> str:
    """Mirrors geminiTtsModelFor: the model the app picks for a locale."""
    lite, full = models(source)
    return full if _language_and_country(tag)[0] in full_model_languages(source) else lite


def pinned_voice(source: str, style: str) -> str | None:
    """Mirrors pinnedVoiceFor: a persona that always speaks in one voice."""
    match = re.search(rf"TtsStyle\.{re.escape(style)} -> \"([^\"]+)\"", source)
    return match.group(1) if match else None


def request_body(text: str, style: str, voice: str) -> dict:
    """The generateContent body GeminiTtsClient sends."""
    return {
        "contents": [{"parts": [{"text": text, "speechMetadata": {"style": style}}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}},
        },
    }


@dataclass
class Clip:
    pcm: bytes
    sample_rate: int
    seconds_to_synthesize: float
    usage: dict

    @property
    def duration(self) -> float:
        return len(self.pcm) / 2 / self.sample_rate


def decode_audio(data: bytes, mime_type: str) -> tuple[bytes, int]:
    """WAV (what 3.8 sends) to 16-bit mono PCM and its rate; headerless L16 passes through."""
    if data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        with wave.open(io.BytesIO(data)) as wf:
            if wf.getnchannels() != 1 or wf.getsampwidth() != 2:
                raise ValueError(
                    f"unsupported WAV: {wf.getnchannels()} channel(s), {8 * wf.getsampwidth()}-bit"
                )
            return wf.readframes(wf.getnframes()), wf.getframerate()
    if "wav" in mime_type.lower():
        raise ValueError(f"{mime_type} without a RIFF header")
    match = re.search(r"rate=(\d+)", mime_type)
    return data, int(match.group(1)) if match else DEFAULT_SAMPLE_RATE


def synthesize(
    api_key: str, model: str, text: str, style: str, voice: str, timeout: float = 90.0
) -> Clip:
    """One generateContent call, shaped like the app's; raises with Gemini's own message on failure."""
    req = urllib.request.Request(
        ENDPOINT.format(model=model),
        data=json.dumps(request_body(text, style, voice)).encode(),
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
        method="POST",
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = json.loads(resp.read())
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            message = json.loads(raw).get("error", {}).get("message", raw)
        except json.JSONDecodeError:
            message = raw
        raise RuntimeError(f"Gemini TTS HTTP {e.code} on {model}: {message}") from None
    elapsed = time.monotonic() - started
    try:
        parts = body["candidates"][0]["content"]["parts"]
    except (KeyError, IndexError, TypeError):
        raise RuntimeError(f"no audio from {model}: {json.dumps(body)[:200]}") from None
    # Like GeminiTtsClient: join every audio part, which must share a rate.
    pieces = [decode_audio(base64.b64decode(p["inlineData"]["data"]), p["inlineData"].get("mimeType", ""))
              for p in parts if isinstance(p, dict) and p.get("inlineData")]
    if not pieces:
        raise RuntimeError(f"no audio from {model}: {json.dumps(body)[:200]}")
    rates = {rate for _, rate in pieces}
    if len(rates) != 1:
        raise RuntimeError(f"{model} returned parts at different sample rates: {sorted(rates)}")
    return Clip(b"".join(pcm for pcm, _ in pieces), rates.pop(), elapsed, body.get("usageMetadata", {}))


def write_wav(path: pathlib.Path, pcm: bytes, rate: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(pcm)
