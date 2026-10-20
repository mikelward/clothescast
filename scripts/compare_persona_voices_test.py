#!/usr/bin/env python3
"""
Checks compare-persona-voices.py still reads the app's real prompt out of
GeminiTtsClient.kt. The script parses Kotlin source, so a refactor there
would otherwise break the eval silently (or worse, render the wrong prompt).
"""

import importlib.util
import io
import json
from unittest import mock
import pathlib
import re
import unittest

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("cpv", HERE / "compare-persona-voices.py")
cpv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cpv)

SOURCE = cpv.CLIENT_KT.read_text(encoding="utf-8")
SETTINGS_KT = (
    cpv.REPO / "core/domain/src/main/kotlin/app/clothescast/core/domain/model/Settings.kt"
).read_text(encoding="utf-8")


def tts_styles() -> list[str]:
    body = re.search(r"enum class TtsStyle \{(.*?)\}", SETTINGS_KT, re.S).group(1)
    return re.findall(r"\b([A-Z][A-Z_]+)\b", body)


class ComparePersonaVoicesTest(unittest.TestCase):
    def test_every_style_has_a_readable_directive(self):
        styles = tts_styles()
        self.assertIn("VAMPIRE", styles)
        for style in styles:
            with self.subTest(style=style):
                # Asserts the regex still reads every directive, the forecaster
                # included, even though build_prompt refuses that one.
                directive = cpv.kotlin_string_const(
                    SOURCE, f"GEMINI_TTS_STYLE_DIRECTIVE_{style}"
                )
                self.assertTrue(directive.startswith("Read the following"))
                self.assertTrue(directive.endswith("texture.\n\n"))
                self.assertNotIn('" +', directive)

    def test_vampire_prompt_has_directive_hint_and_text(self):
        prompt = cpv.build_prompt(SOURCE, "VAMPIRE", "en-GB", "Wear a coat.")
        self.assertIn("vampire count", prompt)
        self.assertIn("'Goood eeevening'", prompt)
        self.assertIn("\n\nSpeak in British English.\n\nWear a coat.", prompt)

    def test_language_hint_mirrors_the_app_fallback(self):
        # Exact entry; bare-language fallback; and no leak from ACCENT_DIRECTIVES,
        # which also has en-US / fr-FR keys.
        self.assertEqual(cpv.language_hint(SOURCE, "en-GB"), "Speak in British English.")
        self.assertEqual(cpv.language_hint(SOURCE, "de-DE"), "Speak in German.")
        self.assertEqual(cpv.language_hint(SOURCE, "it-IT"), "Speak in Italian.")
        self.assertEqual(cpv.language_hint(SOURCE, "fr-FR"), "Speak in French.")
        self.assertIsNone(cpv.language_hint(SOURCE, "en-US"))
        # Case-insensitive, as Locale.forLanguageTag is.
        self.assertEqual(cpv.language_hint(SOURCE, "en-gb"), "Speak in British English.")
        self.assertEqual(cpv.language_hint(SOURCE, "en-Latn-GB"), "Speak in British English.")
        self.assertEqual(
            cpv.language_hint(SOURCE, "pt-Latn-BR"), cpv.language_hint(SOURCE, "pt-BR")
        )
        self.assertEqual(
            cpv.language_hint(SOURCE, "pt-br"), cpv.language_hint(SOURCE, "pt-BR")
        )
        self.assertNotEqual(
            cpv.language_hint(SOURCE, "pt-BR"), cpv.language_hint(SOURCE, "pt")
        )

    def test_forecaster_is_refused(self):
        with self.assertRaises(ValueError):
            cpv.build_prompt(SOURCE, "WEATHER_FORECASTER", "en-GB", "Wear a coat.")

    def test_empty_response_is_a_per_clip_failure(self):
        # A 200 with no candidates must surface as ValueError, which the
        # batch loop catches, not an IndexError that aborts the run.
        for body in ({"candidates": []}, {"candidates": [{"content": {"parts": []}}]}):
            with self.subTest(body=body):
                resp = mock.MagicMock()
                resp.__enter__.return_value = io.BytesIO(json.dumps(body).encode())
                with mock.patch.object(cpv.urllib.request, "urlopen", return_value=resp):
                    with self.assertRaises(ValueError):
                        cpv.synthesize("key", "Iapetus", "prompt", 1.0)

    def test_audio_after_a_text_part_is_found(self):
        body = {"candidates": [{"content": {"parts": [
            {"text": "thinking"},
            {"inlineData": {"mimeType": "audio/L16;codec=pcm;rate=16000", "data": "AAEC"}},
        ]}}]}
        resp = mock.MagicMock()
        resp.__enter__.return_value = io.BytesIO(json.dumps(body).encode())
        with mock.patch.object(cpv.urllib.request, "urlopen", return_value=resp):
            self.assertEqual(
                cpv.synthesize("key", "Iapetus", "prompt", 1.0), (b"\x00\x01\x02", 16000)
            )

    def test_unknown_style_is_an_error(self):
        with self.assertRaises(ValueError):
            cpv.kotlin_string_const(SOURCE, "GEMINI_TTS_STYLE_DIRECTIVE_NOPE")


if __name__ == "__main__":
    unittest.main()
