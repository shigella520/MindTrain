#!/usr/bin/env python3
"""Deterministic domain logic for the Java interview coach Skill."""

from __future__ import annotations

import json
import random
import re
import tempfile
import uuid
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable

OPTION_IDS = ("A", "B", "C", "D")


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json_atomic(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
        temporary = Path(stream.name)
    temporary.replace(path)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def load_questions(root: Path) -> list[dict[str, Any]]:
    questions: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*.json")):
        data = read_json(path)
        questions.extend(data if isinstance(data, list) else [data])
    return [item for item in questions if isinstance(item, dict)]


def parse_datetime(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def normalize_answer(raw: str, question: dict[str, Any]) -> list[str]:
    value = raw.strip()
    if not value:
        raise ValueError("answer is empty")

    upper = value.upper()
    separators = r"[\s,，、/;+＋和及与]*"
    direct = re.fullmatch(rf"[A-D](?:{separators}[A-D])*", upper)
    if direct:
        selected = re.findall(r"[A-D]", upper)
    else:
        marker = re.search(
            rf"(?:答案|选择|我选|选|ANSWER|CHOOSE|SELECT)(?:是|为|IS|:|：)?\s*([A-D](?:{separators}[A-D])*)",
            upper,
        )
        if marker:
            selected = re.findall(r"[A-D]", marker.group(1))
        else:
            ordinal_map = {
                "第一项": "A", "第一个": "A", "选项一": "A",
                "第二项": "B", "第二个": "B", "选项二": "B",
                "第三项": "C", "第三个": "C", "选项三": "C",
                "第四项": "D", "第四个": "D", "选项四": "D",
            }
            selected = [option for phrase, option in ordinal_map.items() if phrase in value]
            if not selected:
                exact_text = [item["id"] for item in question["options"] if value == item["text"]]
                selected = exact_text

    selected = sorted(set(selected), key=OPTION_IDS.index)
    if not selected:
        raise ValueError("answer does not contain a recognizable option")
    if question["type"] == "single_choice" and len(selected) != 1:
        raise ValueError("single-choice question requires exactly one option")
    return selected


def grade_answer(question: dict[str, Any], raw: str) -> dict[str, Any]:
    selected = normalize_answer(raw, question)
    expected = sorted(set(question["correctOptionIds"]), key=OPTION_IDS.index)
    correct = selected == expected
    return {
        "selectedOptionIds": selected,
        "correctOptionIds": expected,
        "correct": correct,
        "score": 100 if correct else 0,
    }


def create_attempt(
    question: dict[str, Any],
    session_id: str,
    raw_answer: str,
    attempt_type: str = "main",
    parent_attempt_id: str | None = None,
    answered_at: str | None = None,
    response_time_seconds: int | None = None,
) -> dict[str, Any]:
    result = grade_answer(question, raw_answer)
    timestamp = answered_at or iso_now()
    return {
        "schemaVersion": 1,
        "id": f"attempt-{uuid.uuid4()}",
        "sessionId": session_id,
        "questionId": question["id"],
        "questionVersion": question["version"],
        "questionType": question["type"],
        "attemptType": attempt_type,
        "parentAttemptId": parent_attempt_id,
        "topicIds": question["topicIds"],
        "rawAnswer": raw_answer,
        "selectedOptionIds": result["selectedOptionIds"],
        "correctOptionIds": result["correctOptionIds"],
        "correct": result["correct"],
        "score": result["score"],
        "answeredAt": timestamp,
        "responseTimeSeconds": response_time_seconds,
        "model": question.get("model"),
        "promptVersion": question.get("promptVersion", "unknown"),
    }


def append_attempt(path: Path, attempt: dict[str, Any]) -> None:
    existing = read_jsonl(path)
    if any(item.get("id") == attempt.get("id") for item in existing):
        raise ValueError(f"attempt already exists: {attempt.get('id')}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(attempt, ensure_ascii=False, separators=(",", ":")) + "\n")


def create_mistake(attempt: dict[str, Any]) -> dict[str, Any] | None:
    if attempt["correct"]:
        return None
    return {
        "schemaVersion": 1,
        "id": f"mistake-{uuid.uuid4()}",
        "attemptId": attempt["id"],
        "sessionId": attempt["sessionId"],
        "questionId": attempt["questionId"],
        "questionVersion": attempt["questionVersion"],
        "topicIds": attempt["topicIds"],
        "selectedOptionIds": attempt["selectedOptionIds"],
        "correctOptionIds": attempt["correctOptionIds"],
        "recordedAt": attempt["answeredAt"],
        "resolved": False,
    }


def append_mistake(path: Path, mistake: dict[str, Any] | None) -> None:
    if mistake is None:
        return
    existing = read_jsonl(path)
    if any(item.get("attemptId") == mistake["attemptId"] for item in existing):
        raise ValueError(f"mistake already exists for attempt: {mistake['attemptId']}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(mistake, ensure_ascii=False, separators=(",", ":")) + "\n")


def topic_priority(
    topic: dict[str, Any],
    mastery: dict[str, Any] | None,
    attempts: list[dict[str, Any]],
    now: datetime,
    jitter: float,
) -> float:
    topic_attempts = [attempt for attempt in attempts if topic["id"] in attempt.get("topicIds", [])]
    uncovered = 1.0 if not topic_attempts else 0.0
    if mastery:
        due = 1.0 if parse_datetime(mastery["nextReviewAt"]) <= now else 0.0
        weakness = 1.0 - mastery["masteryScore"] / 100.0
        total = mastery["correctCount"] + mastery["wrongCount"]
        error_frequency = mastery["wrongCount"] / total if total else 0.0
    else:
        due, weakness, error_frequency = 1.0, 0.5, 0.0
    importance = topic["importance"] / 5.0
    return (
        0.30 * due
        + 0.25 * weakness
        + 0.20 * error_frequency
        + 0.15 * uncovered
        + 0.10 * importance
        + jitter
    )


def select_topic(
    taxonomy: dict[str, Any],
    mastery_store: dict[str, Any],
    attempts: list[dict[str, Any]],
    seed: int | None = None,
    now: datetime | None = None,
) -> tuple[dict[str, Any], dict[str, float]]:
    rng = random.Random(seed)
    current = now or datetime.now(timezone.utc)
    leaves = [topic for topic in taxonomy["topics"] if topic["kind"] == "leaf"]
    priorities = {
        topic["id"]: topic_priority(
            topic,
            mastery_store.get("topics", {}).get(topic["id"]),
            attempts,
            current,
            rng.uniform(0.0, 0.05),
        )
        for topic in leaves
    }
    weights = [max(priorities[topic["id"]], 0.001) for topic in leaves]
    return rng.choices(leaves, weights=weights, k=1)[0], priorities


def select_published_question(
    questions: list[dict[str, Any]],
    topic_id: str,
    attempts: list[dict[str, Any]],
    seed: int | None = None,
) -> dict[str, Any] | None:
    rng = random.Random(seed)
    available = [
        question for question in questions
        if question.get("status") == "published" and topic_id in question.get("topicIds", [])
    ]
    if not available:
        return None
    attempted_ids = {attempt["questionId"] for attempt in attempts}
    unseen = [question for question in available if question["id"] not in attempted_ids]
    if unseen:
        return rng.choice(unseen)
    last_seen: dict[str, datetime] = {}
    for attempt in attempts:
        if attempt["questionId"] in {question["id"] for question in available}:
            timestamp = parse_datetime(attempt["answeredAt"])
            last_seen[attempt["questionId"]] = max(timestamp, last_seen.get(attempt["questionId"], timestamp))
    oldest = min(last_seen.get(question["id"], datetime.min.replace(tzinfo=timezone.utc)) for question in available)
    due = [question for question in available if last_seen.get(question["id"], oldest) == oldest]
    return rng.choice(due)


def update_mastery(store: dict[str, Any], attempt: dict[str, Any]) -> dict[str, Any]:
    result = json.loads(json.dumps(store))
    result.setdefault("schemaVersion", 1)
    topics = result.setdefault("topics", {})
    answered_at = parse_datetime(attempt["answeredAt"])
    for topic_id in attempt["topicIds"]:
        current = topics.get(topic_id, {})
        old_score = int(current.get("masteryScore", 50))
        correct = bool(attempt["correct"])
        streak = int(current.get("consecutiveCorrect", 0)) + 1 if correct else 0
        interval = [3, 7, 14, 30][min(max(streak - 1, 0), 3)] if correct else 1
        topics[topic_id] = {
            "topicId": topic_id,
            "masteryScore": round(old_score * 0.7 + attempt["score"] * 0.3),
            "correctCount": int(current.get("correctCount", 0)) + (1 if correct else 0),
            "wrongCount": int(current.get("wrongCount", 0)) + (0 if correct else 1),
            "consecutiveCorrect": streak,
            "reviewIntervalDays": interval,
            "lastQuestionId": attempt["questionId"],
            "lastAnsweredAt": attempt["answeredAt"],
            "nextReviewAt": (answered_at + timedelta(days=interval)).isoformat(timespec="seconds"),
        }
    return result


def create_session(question_count: int = 10, scope: str = "java-backend-full-stack", started_at: str | None = None) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "id": f"session-{uuid.uuid4()}",
        "status": "active",
        "target": {"questionCount": question_count, "questionTypes": ["single_choice", "multiple_choice"], "scope": scope},
        "completedMainQuestions": 0,
        "followUpCount": 0,
        "questionChain": [],
        "startedAt": started_at or iso_now(),
        "endedAt": None,
        "summary": None,
    }


def add_attempt_to_session(session: dict[str, Any], attempt: dict[str, Any]) -> dict[str, Any]:
    if session["status"] != "active":
        raise ValueError("cannot add an attempt to a finished session")
    result = json.loads(json.dumps(session))
    if any(item["attemptId"] == attempt["id"] for item in result["questionChain"]):
        raise ValueError(f"attempt already recorded in session: {attempt['id']}")
    result["questionChain"].append({
        "attemptId": attempt["id"],
        "questionId": attempt["questionId"],
        "attemptType": attempt["attemptType"],
        "parentAttemptId": attempt.get("parentAttemptId"),
        "correct": attempt["correct"],
    })
    if attempt["attemptType"] == "main":
        result["completedMainQuestions"] += 1
    else:
        result["followUpCount"] += 1
    return result


def _type_summary(attempts: Iterable[dict[str, Any]], question_type: str) -> dict[str, Any]:
    selected = [attempt for attempt in attempts if attempt["questionType"] == question_type]
    correct = sum(1 for attempt in selected if attempt["correct"])
    return {"attempts": len(selected), "correct": correct, "accuracy": round(correct / len(selected), 4) if selected else 0.0}


def finish_session(
    session: dict[str, Any],
    attempts: list[dict[str, Any]],
    mastery_store: dict[str, Any],
    ended_at: str | None = None,
) -> dict[str, Any]:
    result = json.loads(json.dumps(session))
    session_attempts = [attempt for attempt in attempts if attempt["sessionId"] == session["id"]]
    correct = sum(1 for attempt in session_attempts if attempt["correct"])
    topic_stats: dict[str, dict[str, int]] = defaultdict(lambda: {"attempts": 0, "correct": 0})
    for attempt in session_attempts:
        for topic_id in attempt["topicIds"]:
            topic_stats[topic_id]["attempts"] += 1
            topic_stats[topic_id]["correct"] += 1 if attempt["correct"] else 0
    topic_performance = {
        topic_id: {**stats, "accuracy": round(stats["correct"] / stats["attempts"], 4)}
        for topic_id, stats in sorted(topic_stats.items())
    }
    weak_topics = sorted(topic_performance, key=lambda topic_id: (topic_performance[topic_id]["accuracy"], topic_id))[:5]
    review_dates = [
        item["nextReviewAt"] for topic_id, item in mastery_store.get("topics", {}).items() if topic_id in topic_stats
    ]
    result["status"] = "completed"
    result["endedAt"] = ended_at or iso_now()
    result["summary"] = {
        "totalAttempts": len(session_attempts),
        "correctAttempts": correct,
        "accuracy": round(correct / len(session_attempts), 4) if session_attempts else 0.0,
        "singleChoice": _type_summary(session_attempts, "single_choice"),
        "multipleChoice": _type_summary(session_attempts, "multiple_choice"),
        "topicPerformance": topic_performance,
        "wrongQuestionIds": [attempt["questionId"] for attempt in session_attempts if not attempt["correct"]],
        "weakTopicIds": weak_topics,
        "nextReviewAt": min(review_dates) if review_dates else None,
    }
    return result
