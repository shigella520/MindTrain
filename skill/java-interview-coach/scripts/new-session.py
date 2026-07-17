#!/usr/bin/env python3
"""Create a new choice-question training session."""

import argparse
import json
from pathlib import Path

from coachlib import create_session, write_json_atomic


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=10)
    parser.add_argument("--scope", default="java-backend-full-stack")
    parser.add_argument("--started-at")
    parser.add_argument("--output-dir", default="learning-data/sessions")
    args = parser.parse_args()
    session = create_session(args.count, args.scope, args.started_at)
    path = Path(args.output_dir) / f"{session['id']}.json"
    write_json_atomic(path, session)
    print(json.dumps({"path": str(path), "session": session}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
