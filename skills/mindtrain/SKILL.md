---
name: mindtrain
description: Run persistent conversational knowledge training through the MindTrain Trainer MCP, including sessions, safe choice questions, clarification questions, exact grading, session-scoped candidates, user-approved published-question revisions, summaries, reports, and scheduler backlog. Use when the user asks to start or continue MindTrain practice, answer a question, challenge or correct question content, revise a flawed published question, request a deeper follow-up, end a session, or inspect progress.
---

# MindTrain

Use the MindTrain MCP as the only application data interface. Do not read or write repository question, candidate, session, attempt, or mastery files.

## Run training

1. Call `create_training_session`; default to 10 main questions and provider ID `weighted`, displayed to users as `加权调度`.
2. Call `get_next_assignment`.
3. When it returns `assignment`, show only the stem and A-D options. Use exactly: `请回复选项字母，可用逗号分隔。`
4. Treat a clear option selection as a formal answer and call `submit_choice_answer` once.
5. Treat concept questions, uncertainty, challenges, and hint requests as interactions. Call `record_interaction`, answer conversationally, and keep the assignment pending.
6. After successful grading, render: result, correct answer, conclusion, option analysis, mechanism, pitfalls, version notes, related topics, and sources.
7. Offer exactly: `下一题`, `深入追问`, `结束总结`.
8. Call `finish_training_session` when the target is complete or the user ends early.

Never infer the correct answer before `submit_choice_answer` returns it. Invalid answer input does not consume the question.

## Revise a flawed published question

When the user reports that a displayed question is unclear, incorrect, outdated, or poorly sourced, record the feedback with `record_interaction` and discuss the issue first. Never change the question merely because the user challenged it.

Call `revise_published_question` only after the user explicitly asks to update the question bank and the intended correction is clear. Use the question ID and version from the assignment, include its assignment ID as `sourceAssignmentId`, and submit only changed fields. Preserve option IDs and the correct option set unless authoritative sources support a scoring correction. Explain the revision and report the new version returned by Core.

On `question_version_conflict`, do not retry with a guessed version. Explain that the question changed concurrently and obtain the latest content through a future management flow.

## Generate a missing question

When `get_next_assignment` returns `generation_required`, read [candidate-policy.md](references/candidate-policy.md), generate one compliant question using the returned topic context, then call `create_candidate_question`. Call `get_next_assignment` again only after the candidate is accepted.

The candidate is usable only by its owning session. Never claim that it is published or reusable.

## Follow up deeply

For an explanatory follow-up, call `record_interaction` and answer in the current conversation.

For a deeper training question, generate another four-option question on the same topic and call `create_candidate_question` with `attemptType: follow_up` and the graded parent attempt ID. Present the returned follow-up assignment. It must increment only the follow-up count, never the main target. Do not count conversational explanations as attempts.

## Recover safely

- On `answer_unparseable`, ask for option letters again without revealing answer count.
- On `no_available_items`, explain the scheduler reason and offer to finish or inspect backlog.
- On Core or MCP unavailability, retain the current visible question in conversation and retry the tool; do not fabricate persistence success.
- Use a new idempotency key for a new user action and reuse it only when retrying that same action.

Read [tool-contract.md](references/tool-contract.md) when tool inputs, statuses, or retry behavior are unclear.
