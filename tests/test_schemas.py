import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]


class SchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.question_schema = json.loads((ROOT / "schemas/question.schema.json").read_text(encoding="utf-8"))
        cls.validator = Draft202012Validator(cls.question_schema, format_checker=FormatChecker())
        cls.question = json.loads((ROOT / "assets/questions/core-sample-questions.json").read_text(encoding="utf-8"))[0]

    def assert_invalid(self, question):
        self.assertTrue(list(self.validator.iter_errors(question)))

    def test_formal_questions_match_schema(self):
        questions = json.loads((ROOT / "assets/questions/core-sample-questions.json").read_text(encoding="utf-8"))
        for question in questions:
            self.assertEqual([], list(self.validator.iter_errors(question)), question["id"])

    def test_generation_rejects_missing_source(self):
        question = copy.deepcopy(self.question)
        question["sources"] = []
        self.assert_invalid(question)

    def test_generation_rejects_missing_answer(self):
        question = copy.deepcopy(self.question)
        question.pop("correctOptionIds")
        self.assert_invalid(question)

    def test_generation_rejects_incomplete_analysis(self):
        question = copy.deepcopy(self.question)
        question["explanation"]["optionAnalysis"].pop()
        self.assert_invalid(question)

    def test_generation_rejects_missing_version(self):
        question = copy.deepcopy(self.question)
        question["javaVersions"] = []
        self.assert_invalid(question)

    def test_multiple_choice_requires_two_or_three_answers(self):
        question = copy.deepcopy(self.question)
        question["type"] = "multiple_choice"
        question["correctOptionIds"] = ["B"]
        self.assert_invalid(question)


if __name__ == "__main__":
    unittest.main()
