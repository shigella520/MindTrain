#!/usr/bin/env python3
"""Import MindTrain's repository prototype into Training Core without modifying source files."""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path: Path):
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def flatten_questions(directory: Path):
    questions = []
    for path in sorted(directory.glob("*.json")):
        value = load_json(path)
        questions.extend(value if isinstance(value, list) else [value])
    return questions


def build_candidate_wrappers(root: Path, sessions: list[dict]):
    owners = {}
    for session in sessions:
        for item in session.get("questionChain", []):
            owners[item.get("questionId")] = session.get("id")
    wrappers = []
    for question in flatten_questions(root / "assets" / "candidates"):
        wrappers.append({"sessionId": owners.get(question.get("id")), "question": question})
    return [item for item in wrappers if item["sessionId"]]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--core-url", default="http://127.0.0.1:8080")
    parser.add_argument("--token", default="change-me")
    parser.add_argument("--include-private-candidates", action="store_true")
    parser.add_argument("--include-learning-data", action="store_true")
    parser.add_argument("--apply", action="store_true", help="Persist data; otherwise run a dry-run")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    sessions = [load_json(path) for path in sorted((root / "learning-data" / "sessions").glob("*.json"))]
    payload = {
        "dryRun": not args.apply,
        "taxonomy": load_json(root / "assets" / "topics" / "java-backend-taxonomy.json"),
        "questions": flatten_questions(root / "assets" / "questions"),
        "candidates": build_candidate_wrappers(root, sessions) if args.include_private_candidates else [],
        "sessions": sessions if args.include_learning_data else [],
        "attempts": load_jsonl(root / "learning-data" / "attempts.jsonl") if args.include_learning_data else [],
        "mastery": load_json(root / "learning-data" / "mastery.json") if args.include_learning_data else {},
    }
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"{args.core_url.rstrip('/')}/api/v1/imports/prototype",
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {args.token}",
            "Content-Type": "application/json",
            "Idempotency-Key": f"prototype-import-{uuid.uuid4()}",
        },
    )
    try:
        with urllib.request.urlopen(request) as response:
            print(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        print(error.read().decode("utf-8"), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
