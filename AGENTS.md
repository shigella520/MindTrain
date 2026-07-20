# Repository Instructions

## MindTrain target architecture

- Treat MindTrain as a domain-independent knowledge-training platform, not as a Java-only Skill.
- Use `doc/目标需求.md` as the product requirements source and `doc/概要设计.md` as the architecture source.
- Keep Training Core as the future source of truth for questions, candidates, prompts, sessions, attempts, review events, scheduler bindings, and plugin sync state.
- Keep Codex Skills stateless: they orchestrate MCP/API tools and must not become the authoritative application database.
- Keep scheduler behavior behind a provider contract. The initial providers are Core 加权调度 (stable ID: `weighted`) and the optional Anki scheduler plugin.
- Treat Anki as a scheduling plugin and rebuildable local projection, not as the authoritative MindTrain question bank.
- Keep production runtime data out of the repository after migration; retain only migrations, contracts, deployment configuration, and minimal non-private test fixtures.
- Do not introduce microservices prematurely. Build Training Core as a modular monolith first.

## Current Java prototype

- When the user asks to start, continue, review, or summarise Java interview practice, read and follow `skill/java-interview-coach/SKILL.md` until the Trainer MCP migration is complete.
- Preserve current `assets/`, `learning-data/`, `prompts/`, and `schemas/` as prototype and migration-source data.
- Append to JSONL learning history; never silently rewrite historical attempts.
- Put newly discovered prototype questions in `assets/candidates/`; train from `assets/questions/` by default.
- Permit a validated candidate only in the session that generated it; never reuse or auto-publish it.
- Grade single- and multiple-choice answers by exact option-set equality; scores are only 100 or 0.
- Never show an example answer combination that could disclose the correct answer count or option set. Use neutral text such as `请回复选项字母，可用逗号分隔`.
- Use repository scripts to update Attempts, Mastery, and Sessions rather than editing derived state manually.
- Record source URL, access date, version, and review state for generated questions.
- Increment the version when published question content or scoring criteria change.
- Validate JSON and JSONL files before committing.

## Safety and integrity

- Do not commit secrets, API keys, Anki credentials, access tokens, or private learning material.
- Do not overwrite or delete existing user learning data while restructuring the platform.
- Make external integrations idempotent and retain enough state to recover partial Core/plugin failures.
- Keep third-party MCP and Anki Add-on APIs behind MindTrain-owned adapters.
