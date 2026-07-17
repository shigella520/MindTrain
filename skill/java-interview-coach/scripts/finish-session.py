#!/usr/bin/env python3
"""Finish a session and persist its deterministic summary."""

import argparse
import json
from pathlib import Path

from coachlib import finish_session, read_json, read_jsonl, write_json_atomic


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("session_file")
    parser.add_argument("--attempts", default="learning-data/attempts.jsonl")
    parser.add_argument("--mastery", default="learning-data/mastery.json")
    parser.add_argument("--ended-at")
    args = parser.parse_args()
    path = Path(args.session_file)
    result = finish_session(read_json(path), read_jsonl(Path(args.attempts)), read_json(Path(args.mastery)), args.ended_at)
    write_json_atomic(path, result)
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
