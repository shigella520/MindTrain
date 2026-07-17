#!/usr/bin/env python3
"""Detect duplicate question IDs and normalized titles."""

import json
import re
import sys
from collections import defaultdict
from pathlib import Path


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "assets")
    values = {"id": defaultdict(list), "title": defaultdict(list)}
    for path in sorted(root.rglob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        for item in data if isinstance(data, list) else [data]:
            if not isinstance(item, dict):
                continue
            if item.get("id"):
                values["id"][str(item["id"])].append(str(path))
            if item.get("title"):
                key = re.sub(r"\W+", "", str(item["title"]), flags=re.UNICODE).casefold()
                values["title"][key].append(str(path))
    duplicates = [(kind, key, paths) for kind, index in values.items() for key, paths in index.items() if len(paths) > 1]
    for kind, key, paths in duplicates:
        print(f"duplicate {kind} {key}: {', '.join(paths)}")
    return 1 if duplicates else 0


if __name__ == "__main__":
    raise SystemExit(main())
