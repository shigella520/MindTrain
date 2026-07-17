#!/usr/bin/env python3
"""Portable repository check for Skill metadata and UI configuration."""

import argparse
from pathlib import Path

import yaml


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("skill_dir")
    args = parser.parse_args()
    skill_dir = Path(args.skill_dir)
    text = (skill_dir / "SKILL.md").read_text(encoding="utf-8")
    if not text.startswith("---\n") or "\n---\n" not in text[4:]:
        raise SystemExit("SKILL.md must start with YAML frontmatter")
    _, frontmatter, body = text.split("---", 2)
    metadata = yaml.safe_load(frontmatter)
    if set(metadata) != {"name", "description"}:
        raise SystemExit("SKILL.md frontmatter must contain only name and description")
    if metadata["name"] != skill_dir.name:
        raise SystemExit("skill name must match directory name")
    if not metadata["description"] or not body.strip():
        raise SystemExit("skill description and body must not be empty")
    interface = yaml.safe_load((skill_dir / "agents/openai.yaml").read_text(encoding="utf-8"))["interface"]
    if f"${metadata['name']}" not in interface.get("default_prompt", ""):
        raise SystemExit("default_prompt must mention the Skill by $name")
    print("Skill metadata and interface are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
