#!/usr/bin/env python3
"""Grade an answer and update attempt, mastery, and session assets."""

import argparse
import json
from pathlib import Path

from coachlib import (
    add_attempt_to_session,
    append_attempt,
    append_mistake,
    create_attempt,
    create_mistake,
    read_json,
    update_mastery,
    write_json_atomic,
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--question-file", required=True)
    parser.add_argument("--question-id")
    parser.add_argument("--answer", required=True)
    parser.add_argument("--session-file", required=True)
    parser.add_argument("--attempts", default="learning-data/attempts.jsonl")
    parser.add_argument("--mastery", default="learning-data/mastery.json")
    parser.add_argument("--mistakes", default="learning-data/mistakes.jsonl")
    parser.add_argument("--attempt-type", choices=["main", "follow_up"], default="main")
    parser.add_argument("--parent-attempt-id")
    parser.add_argument("--answered-at")
    parser.add_argument("--response-time-seconds", type=int)
    args = parser.parse_args()

    data = read_json(Path(args.question_file))
    questions = data if isinstance(data, list) else [data]
    question = next((item for item in questions if not args.question_id or item["id"] == args.question_id), None)
    if question is None:
        parser.error("question was not found")
    session_path = Path(args.session_file)
    session = read_json(session_path)
    attempt = create_attempt(
        question, session["id"], args.answer, args.attempt_type, args.parent_attempt_id,
        args.answered_at, args.response_time_seconds,
    )
    mastery_path = Path(args.mastery)
    mastery = update_mastery(read_json(mastery_path), attempt)
    session = add_attempt_to_session(session, attempt)
    append_attempt(Path(args.attempts), attempt)
    append_mistake(Path(args.mistakes), create_mistake(attempt))
    write_json_atomic(mastery_path, mastery)
    write_json_atomic(session_path, session)
    print(json.dumps(attempt, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
