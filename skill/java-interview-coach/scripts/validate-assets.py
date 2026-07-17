#!/usr/bin/env python3
"""Validate all structured assets with JSON Schema and repository invariants."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_value(value: Any, schema: dict[str, Any], label: str, errors: list[str]) -> None:
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    for error in sorted(validator.iter_errors(value), key=lambda item: list(item.path)):
        location = ".".join(str(part) for part in error.path)
        errors.append(f"{label}{':' + location if location else ''}: {error.message}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default=".")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    schemas = {path.stem.replace(".schema", ""): load(path) for path in (root / "schemas").glob("*.schema.json")}
    errors: list[str] = []

    taxonomy_path = root / "assets/topics/java-backend-taxonomy.json"
    taxonomy = load(taxonomy_path)
    validate_value(taxonomy, schemas["topic"], str(taxonomy_path.relative_to(root)), errors)
    topic_ids = [topic["id"] for topic in taxonomy.get("topics", [])]
    if len(topic_ids) != len(set(topic_ids)):
        errors.append("taxonomy contains duplicate topic IDs")
    leaf_ids = {topic["id"] for topic in taxonomy.get("topics", []) if topic.get("kind") == "leaf"}
    if len(leaf_ids) < 80:
        errors.append(f"taxonomy must contain at least 80 leaf topics; found {len(leaf_ids)}")

    sources_path = root / "assets/sources/official-sources.json"
    sources = load(sources_path)
    validate_value(sources, schemas["source"], str(sources_path.relative_to(root)), errors)
    source_ids = {source["id"] for source in sources}
    all_topic_ids = set(topic_ids)
    for topic in taxonomy.get("topics", []):
        if topic.get("parentId") is not None and topic["parentId"] not in all_topic_ids:
            errors.append(f"taxonomy topic {topic['id']}: unknown parentId {topic['parentId']}")
        unknown_refs = set(topic.get("sourceRefs", [])) - source_ids
        if unknown_refs:
            errors.append(f"taxonomy topic {topic['id']}: unknown sourceRefs {sorted(unknown_refs)}")

    for directory_name in ("questions", "candidates"):
        directory = root / "assets" / directory_name
        for path in sorted(directory.glob("*.json")):
            data = load(path)
            questions = data if isinstance(data, list) else [data]
            for index, question in enumerate(questions):
                label = f"{path.relative_to(root)}[{index}]"
                validate_value(question, schemas["question"], label, errors)
                option_ids = [option.get("id") for option in question.get("options", [])]
                if option_ids != ["A", "B", "C", "D"]:
                    errors.append(f"{label}: options must be ordered A, B, C, D")
                analyses = {item.get("optionId"): item.get("correct") for item in question.get("explanation", {}).get("optionAnalysis", [])}
                expected = set(question.get("correctOptionIds", []))
                if {option_id for option_id, correct in analyses.items() if correct} != expected:
                    errors.append(f"{label}: optionAnalysis correctness does not match correctOptionIds")
                unknown_topics = set(question.get("topicIds", [])) - leaf_ids
                if unknown_topics:
                    errors.append(f"{label}: unknown topic IDs {sorted(unknown_topics)}")
                unknown_sources = {source.get("sourceId") for source in question.get("sources", [])} - source_ids
                if unknown_sources:
                    errors.append(f"{label}: unknown source IDs {sorted(unknown_sources)}")
                if directory_name == "questions" and question.get("status") != "published":
                    errors.append(f"{label}: formal question assets must be published")
                if directory_name == "candidates" and question.get("status") == "published":
                    errors.append(f"{label}: candidate assets cannot be published")

    mastery_path = root / "learning-data/mastery.json"
    validate_value(load(mastery_path), schemas["mastery"], str(mastery_path.relative_to(root)), errors)

    attempts_path = root / "learning-data/attempts.jsonl"
    attempt_ids: set[str] = set()
    for number, line in enumerate(attempts_path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        attempt = json.loads(line)
        validate_value(attempt, schemas["attempt"], f"{attempts_path.relative_to(root)}:{number}", errors)
        if attempt.get("id") in attempt_ids:
            errors.append(f"{attempts_path.relative_to(root)}:{number}: duplicate attempt ID")
        attempt_ids.add(attempt.get("id"))
        if attempt.get("score") != (100 if attempt.get("correct") else 0):
            errors.append(f"{attempts_path.relative_to(root)}:{number}: score and correct flag disagree")
        exact_match = set(attempt.get("selectedOptionIds", [])) == set(attempt.get("correctOptionIds", []))
        if exact_match != bool(attempt.get("correct")):
            errors.append(f"{attempts_path.relative_to(root)}:{number}: exact-match grading invariant failed")

    mistakes_path = root / "learning-data/mistakes.jsonl"
    mistake_attempt_ids: set[str] = set()
    for number, line in enumerate(mistakes_path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        mistake = json.loads(line)
        validate_value(mistake, schemas["mistake"], f"{mistakes_path.relative_to(root)}:{number}", errors)
        if mistake.get("attemptId") in mistake_attempt_ids:
            errors.append(f"{mistakes_path.relative_to(root)}:{number}: duplicate mistake attemptId")
        mistake_attempt_ids.add(mistake.get("attemptId"))

    for path in sorted((root / "learning-data/sessions").glob("*.json")):
        validate_value(load(path), schemas["session"], str(path.relative_to(root)), errors)

    for path in sorted(root.rglob("*.jsonl")):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                json.loads(line)
            except Exception as exc:
                errors.append(f"{path.relative_to(root)}:{number}: {exc}")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("All schemas and repository asset invariants are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
