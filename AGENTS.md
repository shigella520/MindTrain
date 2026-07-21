# Repository Instructions

## MindTrain target architecture

- Treat MindTrain as a domain-independent knowledge-training platform, not as a Java-only Skill.
- Use `doc/目标需求.md` as the product requirements source and `doc/概要设计.md` as the architecture source.
- Keep Training Core as the source of truth for questions, candidates, prompts, sessions, attempts, review events, scheduler bindings, and plugin sync state.
- Keep Codex Skills stateless: they orchestrate MCP/API tools and must not become the authoritative application database.
- Keep scheduler behavior behind a provider contract. The initial providers are Core 加权调度 (stable ID: `weighted`) and the optional Anki scheduler plugin.
- Treat Anki as a scheduling plugin and rebuildable local projection, not as the authoritative MindTrain question bank.
- Keep production runtime data out of the repository; retain only migrations, contracts, deployment configuration, and minimal non-private test fixtures.
- Do not introduce microservices prematurely. Build Training Core as a modular monolith first.

## Training and content integrity

- Use `plugins/mindtrain/skills/mindtrain/SKILL.md` as the only MindTrain Skill source.
- Access questions, candidates, sessions, attempts, mastery and reports only through Trainer MCP or Training Core APIs.
- Permit an unanswered generated candidate only in its owning session; answering activates it, while explicit rejection physically deletes it.
- Grade single- and multiple-choice answers by exact option-set equality; scores are only 100 or 0.
- Never show an example answer combination that could disclose the correct answer count or option set. Use neutral text such as `请回复选项字母，可用逗号分隔`.
- Record source URL, access date, version, and review state for generated questions.
- Increment the version when published question content or scoring criteria change.
- Use test-local fixtures rather than repository question banks or personal learning files.

## Safety and integrity

- Do not commit secrets, API keys, Anki credentials, access tokens, or private learning material.
- Do not overwrite or delete existing user learning data while restructuring the platform.
- Make external integrations idempotent and retain enough state to recover partial Core/plugin failures.
- Keep third-party MCP and Anki Add-on APIs behind MindTrain-owned adapters.
