#!/usr/bin/env python3
"""Parse and exactly grade one single- or multiple-choice answer."""

import argparse
import json
from pathlib import Path

from coachlib import grade_answer, read_json


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("question_file")
    parser.add_argument("answer")
    parser.add_argument("--question-id")
    args = parser.parse_args()
    data = read_json(Path(args.question_file))
    questions = data if isinstance(data, list) else [data]
    question = next((item for item in questions if not args.question_id or item["id"] == args.question_id), None)
    if question is None:
        parser.error("question was not found")
    try:
        result = grade_answer(question, args.answer)
    except ValueError as exc:
        print(json.dumps({"valid": False, "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"valid": True, **result}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
