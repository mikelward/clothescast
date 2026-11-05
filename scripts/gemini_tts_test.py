#!/usr/bin/env python3
"""
Checks gemini_tts.py, the voice eval scripts' shared plumbing, still builds
the app's real request out of GeminiTtsClient.kt. It parses Kotlin source,
so a refactor there would otherwise break the evals silently, or worse,
render a request the app doesn't send.
"""

import base64
import io
import json
import pathlib
import re
import sys
import unittest
import urllib.error
import wave
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gemini_tts  # noqa: E402

SOURCE = gemini_tts.client_source()
SETTINGS_KT = (
    gemini_tts.REPO / "core/domain/src/main/kotlin/app/clothescast/core/domain/model/Settings.kt"
).read_text(encoding="utf-8")


def tts_styles() -> list[str]:
    body = re.search(r"enum class TtsStyle \{(.*?)\}", SETTINGS_KT, re.S).group(1)
    return re.findall(r"\b([A-Z][A-Z_]+)\b", body)


def wav_bytes(pcm: bytes, rate: int, channels: int = 1) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(pcm)
    return buf.getvalue()


def response(*parts: dict) -> mock.MagicMock:
    resp = mock.MagicMock()
    resp.__enter__.return_value = io.BytesIO(
        json.dumps({"candidates": [{"content": {"parts": list(parts)}}]}).encode()
    )
    return resp


def audio_part(data: bytes, mime: str) -> dict:
    return {"inlineData": {"mimeType": mime, "data": base64.b64encode(data).decode()}}


class PromptTest(unittest.TestCase):
    def test_every_style_has_a_readable_directive(self):
        styles = tts_styles()
        self.assertIn("VAMPIRE", styles)
        for style in styles:
            with self.subTest(style=style):
                directive = gemini_tts.kotlin_string_const(SOURCE, f"GEMINI_TTS_STYLE_DIRECTIVE_{style}")
                self.assertTrue(directive.startswith("Read the following"))
                self.assertTrue(directive.endswith("texture.\n\n"))
                self.assertNotIn('" +', directive)

    def test_a_persona_gets_its_directive_and_a_bare_language_hint(self):
        style = gemini_tts.delivery_style(SOURCE, "VAMPIRE", "en-GB")
        self.assertIn("vampire count", style)
        self.assertIn("'Goood eeevening'", style)
        self.assertTrue(style.endswith("texture.\n\nSpeak in British English."))

    def test_the_forecaster_gets_the_accent_line_and_en_au_its_register(self):
        gb = gemini_tts.delivery_style(SOURCE, "WEATHER_FORECASTER", "en-GB")
        self.assertIn("national news service", gb)
        self.assertTrue(gb.endswith("texture.\n\nSpeak with a Standard British accent."))
        self.assertNotIn("standard variety", gb)
        au = gemini_tts.delivery_style(SOURCE, "WEATHER_FORECASTER", "en-AU")
        self.assertIn("standard variety, not a regional dialect", au)
        self.assertTrue(au.endswith("Speak with an Australian accent, not broad."))

    def test_a_tried_directive_or_accent_replaces_the_apps(self):
        style = gemini_tts.delivery_style(SOURCE, "WEATHER_FORECASTER", "en-GB", "Warm.", "Be Scottish.")
        self.assertEqual(style, "Warm.\n\nBe Scottish.")
        self.assertEqual(gemini_tts.delivery_style(SOURCE, "WEATHER_FORECASTER", "en-GB", accent=""),
                         gemini_tts.directive_for(SOURCE, "WEATHER_FORECASTER", "en-GB").strip())

    def test_language_hint_mirrors_the_app_fallback(self):
        # Exact entry; bare-language fallback; and no leak from ACCENT_DIRECTIVES,
        # which also has en-US / fr-FR keys.
        self.assertEqual(gemini_tts.language_hint(SOURCE, "en-GB"), "Speak in British English.")
        self.assertEqual(gemini_tts.language_hint(SOURCE, "de-DE"), "Speak in German.")
        self.assertEqual(gemini_tts.language_hint(SOURCE, "fr-FR"), "Speak in French.")
        self.assertIsNone(gemini_tts.language_hint(SOURCE, "en-US"))
        # Case-insensitive and script-tolerant, as Locale.forLanguageTag is.
        self.assertEqual(gemini_tts.language_hint(SOURCE, "en-gb"), "Speak in British English.")
        self.assertEqual(gemini_tts.language_hint(SOURCE, "en-Latn-GB"), "Speak in British English.")
        self.assertEqual(gemini_tts.language_hint(SOURCE, "pt-Latn-BR"), gemini_tts.language_hint(SOURCE, "pt-BR"))
        self.assertNotEqual(gemini_tts.language_hint(SOURCE, "pt-BR"), gemini_tts.language_hint(SOURCE, "pt"))
        # Android's legacy codes.
        self.assertEqual(gemini_tts.language_hint(SOURCE, "iw-IL"), "Speak in Hebrew.")

    def test_unknown_style_is_an_error(self):
        with self.assertRaises(ValueError):
            gemini_tts.delivery_style(SOURCE, "NOPE", "en-GB")


class ModelTest(unittest.TestCase):
    def test_both_models_are_read_from_the_client(self):
        self.assertEqual(gemini_tts.models(SOURCE), ("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts"))

    def test_the_locale_picks_the_model_as_the_app_does(self):
        lite, full = gemini_tts.models(SOURCE)
        for tag in ("fi-FI", "lt-LT", "sl-SI", "sv-SE", "sw-KE", "th-TH"):
            with self.subTest(tag=tag):
                self.assertEqual(gemini_tts.model_for(SOURCE, tag), full)
        for tag in ("en-GB", "en-AU", "de-DE", "nb-NO", "uk-UA"):
            with self.subTest(tag=tag):
                self.assertEqual(gemini_tts.model_for(SOURCE, tag), lite)

    def test_the_vampire_keeps_its_pinned_voice(self):
        self.assertEqual(gemini_tts.pinned_voice(SOURCE, "VAMPIRE"), "Algenib")
        self.assertIsNone(gemini_tts.pinned_voice(SOURCE, "PIRATE"))


class SynthesizeTest(unittest.TestCase):
    def call(self, resp):
        with mock.patch.object(gemini_tts.urllib.request, "urlopen", return_value=resp) as urlopen:
            clip = gemini_tts.synthesize("key", "gemini-3.8-flash-lite-tts", "Wear a coat.", "Warm.", "Despina")
        return clip, urlopen.call_args.args[0]

    def test_the_request_speaks_only_the_text_and_carries_the_style(self):
        _, req = self.call(response(audio_part(wav_bytes(b"\x01\x02", 24000), "audio/wav")))
        body = json.loads(req.data)
        self.assertEqual(body["contents"], [{"parts": [{"text": "Wear a coat.", "speechMetadata": {"style": "Warm."}}]}])
        self.assertEqual(body["generationConfig"]["responseModalities"], ["AUDIO"])
        self.assertEqual(
            body["generationConfig"]["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]["voiceName"], "Despina"
        )
        self.assertIn("/models/gemini-3.8-flash-lite-tts:generateContent", req.full_url)
        self.assertNotIn("key=", req.full_url)  # the key goes in a header, not the URL

    def test_a_wav_response_is_unwrapped_at_its_own_rate(self):
        clip, _ = self.call(response(audio_part(wav_bytes(b"\x01\x02\x03\x04", 16000), "audio/wav")))
        self.assertEqual((clip.pcm, clip.sample_rate), (b"\x01\x02\x03\x04", 16000))

    def test_headerless_pcm_still_works(self):
        clip, _ = self.call(response({"text": "x"}, audio_part(b"\x00\x01", "audio/L16;codec=pcm;rate=22050")))
        self.assertEqual((clip.pcm, clip.sample_rate), (b"\x00\x01", 22050))

    def test_several_audio_parts_are_joined(self):
        clip, _ = self.call(response(
            audio_part(wav_bytes(b"\x01\x02", 24000), "audio/wav"),
            audio_part(wav_bytes(b"\x03\x04", 24000), "audio/wav"),
        ))
        self.assertEqual(clip.pcm, b"\x01\x02\x03\x04")

    def test_unplayable_or_missing_audio_fails_the_clip(self):
        for resp in (
            response(),
            response(audio_part(b"\x01\x02", "audio/wav")),
            response(audio_part(wav_bytes(b"\x01\x02\x03\x04", 24000, channels=2), "audio/wav")),
        ):
            with self.subTest(resp=resp), self.assertRaises((RuntimeError, ValueError)):
                self.call(resp)

    def test_an_http_error_reports_geminis_message(self):
        err = urllib.error.HTTPError(
            "url", 400, "Bad Request", {}, io.BytesIO(b'{"error":{"message":"Invalid voice."}}')
        )
        with mock.patch.object(gemini_tts.urllib.request, "urlopen", side_effect=err):
            with self.assertRaisesRegex(RuntimeError, "HTTP 400 .*Invalid voice"):
                gemini_tts.synthesize("key", "m", "t", "s", "v")


if __name__ == "__main__":
    unittest.main()
