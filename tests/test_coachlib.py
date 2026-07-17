import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT_DIR = ROOT / "skill/java-interview-coach/scripts"
sys.path.insert(0, str(SCRIPT_DIR))

from coachlib import (  # noqa: E402
    add_attempt_to_session,
    append_attempt,
    append_mistake,
    create_attempt,
    create_mistake,
    create_session,
    finish_session,
    grade_answer,
    load_questions,
    normalize_answer,
    read_json,
    read_jsonl,
    select_published_question,
    select_topic,
    topic_priority,
    update_mastery,
)


class CoachLibTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.questions = load_questions(ROOT / "assets/questions")
        cls.single = next(item for item in cls.questions if item["type"] == "single_choice")
        cls.multi = next(item for item in cls.questions if item["type"] == "multiple_choice")

    def test_single_choice_formats_and_invalid_multiple(self):
        self.assertEqual(["B"], normalize_answer("B", self.single))
        self.assertEqual(["B"], normalize_answer("我选 B", self.single))
        self.assertEqual(["B"], normalize_answer("第二项", self.single))
        self.assertEqual(["B"], normalize_answer(self.single["options"][1]["text"], self.single))
        with self.assertRaises(ValueError):
            normalize_answer("AB", self.single)

    def test_multiple_choice_exact_match(self):
        expected = self.multi["correctOptionIds"]
        reverse = "".join(reversed(expected))
        self.assertTrue(grade_answer(self.multi, reverse)["correct"])
        self.assertEqual(100, grade_answer(self.multi, ",".join(expected))["score"])
        self.assertFalse(grade_answer(self.multi, expected[0])["correct"])
        wrong_extra = "".join(sorted(set(expected) | ({"D"} if "D" not in expected else {"B"})))
        self.assertFalse(grade_answer(self.multi, wrong_extra)["correct"])

    def test_grading_eval_cases(self):
        by_id = {question["id"]: question for question in self.questions}
        lines = (ROOT / "evals/grading-cases.jsonl").read_text(encoding="utf-8").splitlines()
        for line in lines:
            case = json.loads(line)
            question = by_id[case["questionId"]]
            if not case["expected"].get("valid", True):
                with self.assertRaises(ValueError, msg=case["id"]):
                    grade_answer(question, case["input"])
                continue
            result = grade_answer(question, case["input"])
            for key, expected in case["expected"].items():
                if key != "valid":
                    self.assertEqual(expected, result[key], case["id"])

    def test_topic_priority_rewards_due_weak_and_uncovered(self):
        topic = {"id": "x", "importance": 5}
        now = datetime(2026, 7, 17, tzinfo=timezone.utc)
        weak = {"masteryScore": 20, "correctCount": 1, "wrongCount": 4, "nextReviewAt": "2026-07-16T00:00:00+00:00"}
        strong = {"masteryScore": 90, "correctCount": 9, "wrongCount": 1, "nextReviewAt": "2026-08-16T00:00:00+00:00"}
        self.assertGreater(topic_priority(topic, weak, [], now, 0), topic_priority(topic, strong, [{"topicIds": ["x"]}], now, 0))

    def test_weighted_selection_is_reproducible(self):
        taxonomy = read_json(ROOT / "assets/topics/java-backend-taxonomy.json")
        mastery = {"schemaVersion": 1, "topics": {}}
        first, _ = select_topic(taxonomy, mastery, [], seed=7, now=datetime(2026, 7, 17, tzinfo=timezone.utc))
        second, _ = select_topic(taxonomy, mastery, [], seed=7, now=datetime(2026, 7, 17, tzinfo=timezone.utc))
        self.assertEqual(first["id"], second["id"])

    def test_question_selection_filters_candidates_and_prefers_unseen(self):
        candidate = {**self.single, "id": "candidate.x", "status": "candidate"}
        selected = select_published_question([candidate, self.single], self.single["topicIds"][0], [], seed=1)
        self.assertEqual(self.single["id"], selected["id"])
        attempt = {"questionId": self.single["id"], "answeredAt": "2026-07-17T00:00:00+00:00"}
        other = {**self.single, "id": "java.collections.hashmap.002"}
        selected = select_published_question([self.single, other], self.single["topicIds"][0], [attempt], seed=1)
        self.assertEqual(other["id"], selected["id"])

    def test_mastery_update_is_deterministic(self):
        attempt = create_attempt(self.single, "session-test", "B", answered_at="2026-07-17T00:00:00+00:00")
        mastery = update_mastery({"schemaVersion": 1, "topics": {}}, attempt)
        item = mastery["topics"][self.single["topicIds"][0]]
        self.assertEqual(65, item["masteryScore"])
        self.assertEqual(1, item["correctCount"])
        self.assertEqual(3, item["reviewIntervalDays"])

    def test_end_to_end_ten_main_and_one_follow_up(self):
        session = create_session(10, started_at="2026-07-17T00:00:00+00:00")
        mastery = {"schemaVersion": 1, "topics": {}}
        attempts = []
        with tempfile.TemporaryDirectory() as directory:
            attempts_path = Path(directory) / "attempts.jsonl"
            mistakes_path = Path(directory) / "mistakes.jsonl"
            parent_id = None
            for index in range(10):
                question = self.single if index % 2 == 0 else self.multi
                answer = "".join(question["correctOptionIds"])
                attempt = create_attempt(question, session["id"], answer, answered_at=f"2026-07-17T00:{index:02d}:00+00:00")
                append_attempt(attempts_path, attempt)
                append_mistake(mistakes_path, create_mistake(attempt))
                attempts.append(attempt)
                mastery = update_mastery(mastery, attempt)
                session = add_attempt_to_session(session, attempt)
                parent_id = attempt["id"]
            follow_up = create_attempt(
                self.multi, session["id"], "A", "follow_up", parent_id,
                answered_at="2026-07-17T00:11:00+00:00",
            )
            append_attempt(attempts_path, follow_up)
            append_mistake(mistakes_path, create_mistake(follow_up))
            attempts.append(follow_up)
            mastery = update_mastery(mastery, follow_up)
            session = add_attempt_to_session(session, follow_up)
            session = finish_session(session, read_jsonl(attempts_path), mastery, "2026-07-17T00:12:00+00:00")
            mistake_count = len(read_jsonl(mistakes_path))

        self.assertEqual(10, session["completedMainQuestions"])
        self.assertEqual(1, session["followUpCount"])
        self.assertEqual(11, session["summary"]["totalAttempts"])
        self.assertEqual(1, mistake_count)
        self.assertEqual("completed", session["status"])


if __name__ == "__main__":
    unittest.main()
