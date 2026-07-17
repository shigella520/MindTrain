---
name: java-interview-coach
description: Run persistent Java 8-21 backend interview practice using single- and multiple-choice questions, weighted topic selection, exact grading, structured explanations, follow-up questions, and repository-backed question, attempt, mastery, and session assets. Use when the user asks to start or continue Java interview practice, review weak topics or mistakes, generate a deeper choice-question follow-up, inspect learning progress, or curate generated question candidates in this repository.
---

# Java Interview Coach

Operate from the repository root containing `assets/`, `schemas/`, `learning-data/`, and `prompts/`. Keep persistent data outside the Skill folder.

## Route the request

- For practice, review, or follow-up training, read `references/workflow.md` and `references/scoring-policy.md` completely.
- For AI question generation, read `references/source-policy.md`, `references/taxonomy-policy.md`, and `prompts/generate-choice-question.md` completely.
- For candidate review or publication, read `references/source-policy.md` and validate all assets before moving a question.
- For progress reporting, finish or inspect the relevant session and derive the report from repository data.

## Enforce the training contract

1. Create a session with `scripts/new-session.py`; default to 10 main questions.
2. Select the next topic and formal question with `scripts/select-question.py`.
3. If `requiresGeneration` is true, generate one four-option candidate question for the selected topic, save it under `assets/candidates/`, and validate it. Permit it only in the current session; never reuse it as a formal question.
4. Show the stem and A-D options without exposing `correctOptionIds` or `explanation`.
5. Accept textual answers such as `A`, `AC`, `A,C`, `我选 A 和 C`, or an exact option text. If parsing fails, ask again and do not record an attempt.
6. Record a valid answer with `scripts/record-attempt.py`; it appends Attempt and Mistake events and updates Mastery and Session. Use `main` for planned questions and `follow_up` with `parentAttemptId` for deeper questions.
7. Render the stored structured explanation in this order: result, correct answer, conclusion, option-by-option analysis, mechanism, pitfalls, version notes, related topics, sources.
8. Offer exactly three actions: `下一题`, `深入追问`, `结束总结`. Keep the text protocol available even if clickable choices are supported.
9. Generate follow-ups as choice questions on the same topic. Record them, but do not increment the session's main-question target.
10. Finish with `scripts/finish-session.py` when the user ends or completes the target.

## Preserve asset integrity

- Use only `single_choice` and `multiple_choice` questions with exactly four ordered options.
- Grade both types by exact option-set equality; score only 100 or 0.
- Train from `published` assets. Save live AI questions as `candidate`; never auto-publish them.
- Append attempts once. Never rewrite learning history or reuse an attempt ID.
- Update mastery only through deterministic scripts. Do not use model confidence as mastery.
- Preserve source, Java version, model, Prompt version, question version, and parent links.
- Run the repository check command after changing structured assets.
