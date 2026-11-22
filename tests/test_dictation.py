#!/usr/bin/env python3
"""Unit tests for experimental dictation cleanup and correction learning."""
import json
import os
import runpy
import tempfile
import unittest
from pathlib import Path

os.environ["STT_NO_STATS"] = "1"
os.environ["STT_DRY_RUN"] = "1"
PTT = Path(__file__).resolve().parents[1] / "src" / "squelch-ptt"


class DictationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ptt = runpy.run_path(str(PTT), run_name="test_squelch_dictation")
        cls.g = cls.ptt["tidy"].__globals__

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.g["DICT_FILE"] = str(root / "dictionary.json")
        self.g["FILTERS_FILE"] = str(root / "filters.json")
        self.g["VOCAB_FILE"] = str(root / "vocab.json")
        self.g["LEARNED_FILE"] = str(root / "learned.json")
        self.g["LLM_FAST_FILE"] = str(root / "no-fast.json")
        self.g["LLM_FALLBACK_FILE"] = str(root / "no-fallback.json")
        self.g["OPENROUTER_KEY_FILE"] = str(root / "no-key")
        self.g["OPENROUTER_MODEL_FILE"] = str(root / "no-model")
        self.write("dictionary.json", {"entries": []})
        self.write("filters.json", {})
        self.g["_dict_cache"].update(mtime=0, terms=[], subs=[])

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, value):
        Path(self.g["DICT_FILE"]).parent.joinpath(name).write_text(json.dumps(value))

    def test_experimental_defaults(self):
        models = runpy.run_path(str(PTT.parent / "squelch-setup-models"),
                                run_name="test_model_catalog")
        defaults = [entry[0] for entry in models["CATALOG"] if entry[5]]
        self.assertEqual(defaults, ["parakeet-en", "whisper"])
        self.assertFalse(self.ptt["text_filters"]()[3])

    def test_local_mixed_punctuation(self):
        self.assertEqual(self.ptt["tidy"]("the name., make sure"),
                         "the name. make sure")

    def test_local_disfluency_rules(self):
        self.assertEqual(self.ptt["tidy_disfluency"]("the equ equilibrium constant"),
                         "the equilibrium constant")
        self.assertEqual(self.ptt["tidy_disfluency"]("never ever crazy crazy"),
                         "never ever crazy crazy")
        self.assertEqual(self.ptt["tidy_disfluency"]("the thesis"),
                         "the thesis")
        self.assertEqual(self.ptt["tidy_disfluency"]("on Tuesday, no, Wednesday"),
                         "on Wednesday")
        self.assertEqual(self.ptt["tidy_disfluency"]("copper, sorry, iron three"),
                         "iron three")
        self.assertEqual(self.ptt["tidy_disfluency"]("can you not notify me"),
                         "can you not notify me")

    def test_learned_disfluency_filter(self):
        self.assertEqual(self.ptt["tidy_disfluency_model"](
            "i want to talk about the the new plan for tomorrow"),
            "i want to talk about the new plan for tomorrow")
        normal = "this is a normal sentence that should stay completely unchanged"
        self.assertEqual(self.ptt["tidy_disfluency_model"](normal), normal)
        # Short commands bypass the rambler model, including negation.
        self.assertEqual(self.ptt["tidy_disfluency_model"]("can you not notify me"),
                         "can you not notify me")

    def test_learned_disfluency_matches_validation_fixtures(self):
        path = Path(__file__).with_name("disfluency-cases.json")
        cases = json.loads(path.read_text())["cases"]
        for case in cases:
            with self.subTest(before=case["before"]):
                self.assertEqual(self.ptt["tidy_disfluency_model"](case["before"]),
                                 case["after"])

    def test_contextual_replacement_does_not_rewrite_root_globally(self):
        text = "Anything to know after root other than banking?"
        self.assertEqual(self.ptt["correction_replacement"](text, "root", "rot"),
                         ("after rot", "after root"))
        rec = {"kind": "fix", "text": text, "fixes": [["root", "rot"]]}
        self.ptt["learn_from_edit"](rec)
        self.g["_dict_cache"].update(mtime=0, terms=[], subs=[])
        self.assertEqual(self.ptt["apply_dictionary"]("The root user"), "The root user")
        self.assertEqual(self.ptt["apply_dictionary"]("after root"), "after rot")

    def test_short_recognition_correction_keeps_context(self):
        rec = {"kind": "fix", "text": "Does it still stock?",
               "fixes": [["stock?", "suck"]]}
        self.ptt["learn_from_edit"](rec)
        self.g["_dict_cache"].update(mtime=0, terms=[], subs=[])
        self.assertEqual(self.ptt["apply_dictionary"]("Does it still stock?"),
                         "Does it still suck?")

    def test_spacing_and_capitalization_correction(self):
        rec = {"kind": "fix", "text": "Kimi speaks fluent Grugspeak.",
               "fixes": [["Grugspeak", "Grug Speak"]]}
        self.ptt["learn_from_edit"](rec)
        self.g["_dict_cache"].update(mtime=0, terms=[], subs=[])
        self.assertEqual(self.ptt["apply_dictionary"]("fluent Grugspeak"),
                         "fluent Grug Speak")

    def test_tidy_guard_allows_deletions_not_summary(self):
        self.assertEqual(self.ptt["tidy_check"]("on Tuesday, no, Wednesday",
                                               "on Wednesday"),
                         "on Wednesday")
        original = "one two three four five six seven eight nine ten"
        self.assertIsNone(self.ptt["tidy_check"](original, "one two three"))
        self.assertIsNone(self.ptt["tidy_check"](original,
                                                "one two three four five six seven eight nine ten eleven twelve thirteen"))

    def test_prompt_is_conservative_and_has_context_slots(self):
        prompt = self.g["TIDY_PROMPT"]
        self.assertIn("Do not summarize", prompt)
        self.assertIn("Preserve intentional repetition", prompt)
        self.assertIn("If a change would be a guess, keep the heard words", prompt)
        formatted = prompt.format(text="test", vocabulary="Squelch", before="")
        self.assertIn("<<<Squelch>>>", formatted)
        self.assertIn("<<<test>>>", formatted)


if __name__ == "__main__":
    unittest.main()
