# Repository Instructions

- When the user asks to start, continue, review, or summarise Java interview practice, read and follow `skill/java-interview-coach/SKILL.md`.
- Keep Codex workflows under `skill/`; keep reusable domain data outside the Skill.
- Treat `schemas/` as the contract shared by Codex and the future Web App.
- Append to JSONL learning history; never silently rewrite historical attempts.
- Put newly discovered questions in `assets/candidates/`; train from `assets/questions/` by default.
- Permit a validated candidate only in the session that generated it; never reuse or auto-publish it.
- Grade single- and multiple-choice answers by exact option-set equality; scores are only 100 or 0.
- Use repository scripts to update Attempts, Mastery, and Sessions rather than editing derived state manually.
- Record source URL, access date, version, and review state for generated questions.
- Increment the version when published question content or scoring criteria change.
- Validate JSON and JSONL files before committing.
- Never commit secrets, API keys, credentials, or private interview material.
