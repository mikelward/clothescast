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

    def test_an_english_forecast_is_the_short_presenter_line_with_its_accent_in_the_code(self):
        us = gemini_tts.kotlin_string_const(SOURCE, "GEMINI_TTS_STYLE_FORECASTER_EN_US")
        other = gemini_tts.kotlin_string_const(SOURCE, "GEMINI_TTS_STYLE_FORECASTER_EN")
        self.assertIn("anchor", us)
        self.assertNotIn("anchor", other)
        for tag, expected in (("en-US", us), ("en-GB", other), ("en-AU", other), ("en-IN", other)):
            with self.subTest(tag=tag):
                self.assertEqual(gemini_tts.delivery_style(SOURCE, "WEATHER_FORECASTER", tag), expected)
                self.assertEqual(gemini_tts.language_code_for(SOURCE, "WEATHER_FORECASTER", tag), tag)
        self.assertEqual(gemini_tts.language_code_for(SOURCE, "WEATHER_FORECASTER", "en-AU-u-mu-celsius"), "en-AU")

    def test_other_languages_and_personas_keep_their_lines_and_send_no_code(self):
        de = gemini_tts.delivery_style(SOURCE, "WEATHER_FORECASTER", "de-AT")
        self.assertIn("national news service", de)
        self.assertTrue(de.endswith("österreichischen Akzent."))
        self.assertIsNone(gemini_tts.language_code_for(SOURCE, "WEATHER_FORECASTER", "de-AT"))
        self.assertIsNone(gemini_tts.language_code_for(SOURCE, "PIRATE", "en-GB"))

    def test_a_tried_directive_or_accent_replaces_the_apps(self):
        style = gemini_tts.delivery_style(SOURCE, "WEATHER_FORECASTER", "en-GB", "Warm.", "Be Scottish.")
        self.assertEqual(style, "Warm.\n\nBe Scottish.")
        self.assertEqual(gemini_tts.delivery_style(SOURCE, "WEATHER_FORECASTER", "en-GB", "Warm."), "Warm.")
        self.assertEqual(gemini_tts.delivery_style(SOURCE, "WEATHER_FORECASTER", "de-DE", accent=""),
                         gemini_tts.directive_for(SOURCE, "WEATHER_FORECASTER", "de-DE").strip())

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


class HolidayTest(unittest.TestCase):
    def test_holidays_take_the_apps_personas(self):
        styles = gemini_tts.holiday_styles()
        self.assertEqual(styles["HALLOWEEN"], "VAMPIRE")
        self.assertEqual(styles["CHRISTMAS_DAY"], "FATHER_CHRISTMAS")
        self.assertEqual(styles["NEW_YEARS_DAY"], "NEW_YEARS_HOST")
        self.assertNotIn("BOXING_DAY", styles)

    def test_a_holiday_switches_voice_as_the_app_does(self):
        # A male persona on a female voice takes the first male picker voice;
        # a male voice stays; a neutral persona keeps the user's; the vampire
        # and Father Christmas always speak in their pinned voice.
        first_male = next(v for v, g in gemini_tts.curated_voices() if g == "MALE")
        self.assertEqual(gemini_tts.holiday_voice("PIRATE", "Despina"), first_male)
        self.assertEqual(gemini_tts.holiday_voice("PIRATE", "Iapetus"), "Iapetus")
        self.assertEqual(gemini_tts.holiday_voice("FATHER_CHRISTMAS", "Iapetus"), "Algenib")
        self.assertEqual(gemini_tts.holiday_voice("NEW_YEARS_HOST", "Despina"), "Despina")
        self.assertEqual(gemini_tts.holiday_voice("VAMPIRE", "Despina"), "Algenib")
        self.assertEqual(gemini_tts.holiday_voice("VAMPIRE", "Charon"), "Algenib")

    def test_the_greeting_is_the_banner_closed_with_one_mark(self):
        self.assertEqual(gemini_tts.holiday_greeting("CHRISTMAS_DAY", "en-GB"), "Merry Christmas!")
        self.assertEqual(gemini_tts.holiday_greeting("TALK_LIKE_A_PIRATE_DAY", "en-US"), "Ahoy, matey!")
        self.assertEqual(gemini_tts.holiday_greeting("US_PRESIDENTS_DAY", "en-US"), "Happy Presidents' Day!")

    def test_the_greeting_is_in_the_voice_locales_language(self):
        # Every voice locale the app offers must find its own translation, as
        # Android's resource lookup does: legacy-code directories (values-iw,
        # values-in) and script-qualified ones (values-b+sr+Latn) included.
        english = gemini_tts.holiday_greeting("CHRISTMAS_DAY", "en-US")
        locales = re.findall(r'^\s+[A-Z_]+\("([a-z]{2,3}(?:-[A-Za-z]+)*)"\)', SETTINGS_KT, re.M)
        self.assertIn("sr-Latn-RS", locales)
        for tag in locales:
            if tag.startswith("en"):
                continue
            with self.subTest(tag=tag):
                self.assertNotEqual(gemini_tts.holiday_greeting("CHRISTMAS_DAY", tag), english)
        self.assertEqual(gemini_tts.holiday_greeting("CHRISTMAS_DAY", "id-ID"), "Selamat Natal!")
        self.assertNotEqual(
            gemini_tts.holiday_greeting("CHRISTMAS_DAY", "sr-Latn-RS"),
            gemini_tts.holiday_greeting("CHRISTMAS_DAY", "sr-Cyrl-RS"),
        )

    def test_resource_dirs_try_the_android_spellings(self):
        self.assertEqual(gemini_tts.resource_dirs("he-IL"),
                         ["values-he-rIL", "values-he", "values-iw-rIL", "values-iw", "values"])
        self.assertEqual(gemini_tts.resource_dirs("sr-Latn-RS"),
                         ["values-b+sr+Latn+RS", "values-b+sr+Latn", "values-sr-rRS", "values-sr", "values"])


class MatrixTest(unittest.TestCase):
    """compare-tts-models.py's case list: the requests a run will send."""

    @classmethod
    def setUpClass(cls):
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "ctm", pathlib.Path(__file__).resolve().parent / "compare-tts-models.py"
        )
        cls.ctm = importlib.util.module_from_spec(spec)
        # Its dataclass looks itself up in sys.modules while being defined.
        sys.modules["ctm"] = cls.ctm
        spec.loader.exec_module(cls.ctm)

    def args(self, **kw):
        import argparse

        base = dict(locales=["en-GB"], styles=None, holidays=None, text=None)
        return argparse.Namespace(**{**base, **kw})

    def test_each_english_locale_reads_its_own_words(self):
        cases = self.ctm.build_cases(self.args(locales=["en-GB", "en-AU", "en-US"]))
        text = {c.locale: c.text for c in cases}
        self.assertIn("jumper", text["en-GB"])
        self.assertIn("jumper", text["en-AU"])
        self.assertIn("sweater", text["en-US"])
        self.assertEqual({c.style for c in cases}, {"WEATHER_FORECASTER"})

    def test_a_holiday_case_greets_then_forecasts_in_its_persona(self):
        cases = self.ctm.build_cases(self.args(locales=["en-US"], holidays=["halloween"]))
        self.assertEqual(len(cases), 1)
        self.assertEqual(cases[0].style, "VAMPIRE")
        self.assertTrue(cases[0].text.startswith("Happy Halloween! Today, it will be"))

    def test_all_holidays_and_styles_combine(self):
        cases = self.ctm.build_cases(self.args(holidays=["ALL"], styles=["PIRATE"]))
        self.assertEqual(len(cases), 1 + len(gemini_tts.holiday_styles()))

    def test_bad_input_is_refused_not_guessed(self):
        with self.assertRaisesRegex(ValueError, "BOXING_DAY"):
            self.ctm.build_cases(self.args(holidays=["BOXING_DAY"]))
        with self.assertRaisesRegex(ValueError, "--text"):
            self.ctm.build_cases(self.args(locales=["de-DE"]))

    def test_short_model_names_resolve(self):
        self.assertEqual(
            self.ctm.resolve_models(["flash-lite", "flash"], SOURCE),
            ["gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts"],
        )


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
        self.assertNotIn("languageCode", body["generationConfig"]["speechConfig"])

    def test_a_language_code_goes_in_the_speech_config(self):
        body = gemini_tts.request_body("Hi.", "Warm.", "Despina", "en-AU")
        self.assertEqual(body["generationConfig"]["speechConfig"]["languageCode"], "en-AU")

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
