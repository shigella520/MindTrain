#!/usr/bin/env python3
"""Select a weighted topic and an unseen/due published question."""

import argparse
import json
from datetime import datetime
from pathlib import Path

from coachlib import load_questions, read_json, read_jsonl, select_published_question, select_topic


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--taxonomy", default="assets/topics/java-backend-taxonomy.json")
    parser.add_argument("--questions", default="assets/questions")
    parser.add_argument("--attempts", default="learning-data/attempts.jsonl")
    parser.add_argument("--mastery", default="learning-data/mastery.json")
    parser.add_argument("--topic-id")
    parser.add_argument("--seed", type=int)
    parser.add_argument("--now")
    args = parser.parse_args()

    taxonomy = read_json(Path(args.taxonomy))
    attempts = read_jsonl(Path(args.attempts))
    mastery = read_json(Path(args.mastery))
    now = datetime.fromisoformat(args.now) if args.now else None
    if args.topic_id:
        topic = next(topic for topic in taxonomy["topics"] if topic["id"] == args.topic_id and topic["kind"] == "leaf")
        priorities = {}
    else:
        topic, priorities = select_topic(taxonomy, mastery, attempts, args.seed, now)
    question = select_published_question(load_questions(Path(args.questions)), topic["id"], attempts, args.seed)
    print(json.dumps({
        "topic": topic,
        "priority": priorities.get(topic["id"]),
        "question": question,
        "requiresGeneration": question is None,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
