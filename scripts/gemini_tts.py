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


HOLIDAY_VOICE_KT = REPO / "core/domain/src/main/kotlin/app/clothescast/core/domain/model/HolidayVoice.kt"
TTS_VOICES_KT = REPO / "app/src/main/kotlin/app/clothescast/tts/TtsVoices.kt"
RES = REPO / "app/src/main/res"


def holiday_styles() -> dict[str, str]:
    """holidayTtsStyle: the HolidayId -> TtsStyle persona switch, e.g. HALLOWEEN -> VAMPIRE."""
    body = re.search(r"fun holidayTtsStyle\(.*?\{(.*?)\n\}", HOLIDAY_VOICE_KT.read_text(encoding="utf-8"), re.S)
    if not body:
        raise ValueError(f"holidayTtsStyle not found in {HOLIDAY_VOICE_KT.name}")
    return dict(re.findall(r"HolidayId\.([A-Z_]+) -> TtsStyle\.([A-Z_]+)", body.group(1)))


def persona_gender(style: str) -> str:
    """suggestedVoiceGender: MALE, FEMALE or NEUTRAL."""
    body = re.search(r"val TtsStyle\.suggestedVoiceGender.*?when \(this\) \{(.*?)\n    \}",
                     HOLIDAY_VOICE_KT.read_text(encoding="utf-8"), re.S)
    if not body:
        raise ValueError(f"suggestedVoiceGender not found in {HOLIDAY_VOICE_KT.name}")
    for styles, gender in re.findall(r"((?:\s*TtsStyle\.[A-Z_]+,?)+)\s*-> VoiceGender\.([A-Z]+)", body.group(1)):
        if style in re.findall(r"TtsStyle\.([A-Z_]+)", styles):
            return gender
    return "NEUTRAL"


def curated_voices() -> list[tuple[str, str]]:
    """GEMINI_VOICES, in picker order: (voice, gender)."""
    return re.findall(r'TtsVoiceOption\("([^"]+)", "[^"]*", VoiceGender\.([A-Z]+)\)',
                      TTS_VOICES_KT.read_text(encoding="utf-8"))


def holiday_voice(style: str, user_voice: str) -> str:
    """
    resolveHolidayVoice, then the client's pinnedVoiceFor: keep the user's
    voice unless the persona implies the other gender, in which case the first
    picker voice of that gender; a pinned persona (the vampire) overrides both.
    """
    pinned = pinned_voice(client_source(), style)
    if pinned:
        return pinned
    gender = persona_gender(style)
    voices = curated_voices()
    if gender == "NEUTRAL" or dict(voices).get(user_voice) == gender:
        return user_voice
    return next((v for v, g in voices if g == gender), voices[0][0])


def resource_dirs(tag: str) -> list[str]:
    """
    The `values-*` directories Android would try for a locale, most specific
    first: script-qualified BCP-47 ones (`values-b+sr+Latn`), then region,
    then language, then the default. Hebrew, Indonesian and Yiddish live under
    their legacy codes (`values-iw`, `values-in`, `values-ji`), as Android
    still names them, so both spellings are tried.
    """
    language, country = _language_and_country(tag)
    scripts = [t.title() for t in tag.replace("_", "-").split("-")[1:] if len(t) == 4 and t.isalpha()]
    legacy = {"he": "iw", "id": "in", "yi": "ji"}
    dirs = []
    for lang in dict.fromkeys([language, legacy.get(language, language)]):
        for script in scripts:
            if country:
                dirs.append(f"values-b+{lang}+{script}+{country}")
            dirs.append(f"values-b+{lang}+{script}")
        if country:
            dirs.append(f"values-{lang}-r{country}")
        dirs.append(f"values-{lang}")
    return dirs + ["values"]


def _resource_string(name: str, tag: str) -> str | None:
    for d in resource_dirs(tag):
        path = RES / d / "strings.xml"
        if path.exists():
            match = re.search(rf'<string name="{re.escape(name)}">(.*?)</string>', path.read_text(encoding="utf-8"))
            if match:
                return match.group(1).replace("\\'", "'").replace('\\"', '"')
    return None


def holiday_greeting(holiday: str, tag: str) -> str:
    """
    The spoken greeting holidayTtsGreeting puts before the forecast, in the
    voice locale's language: the banner string, closed with "!" unless it
    brings its own punctuation. (Every holiday with a persona is festive.)
    """
    greeting = _resource_string(f"holiday_banner_{holiday.lower()}", tag)
    if greeting is None:
        raise ValueError(f"no holiday_banner_{holiday.lower()} string")
    greeting = greeting.strip()
    return greeting if greeting[-1] in ".!?" else greeting + "!"


def play(path: pathlib.Path) -> None:
    """Plays a WAV: aplay on Linux, afplay on macOS."""
    import shutil
    import subprocess

    player = next((p for p in ("aplay", "afplay") if shutil.which(p)), None)
    if player is None:
        print(f"No aplay or afplay to play {path}; open it in any audio player.")
        return
    subprocess.run([player, "-q", str(path)] if player == "aplay" else [player, str(path)], check=False)


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
