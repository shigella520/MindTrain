# Trainer MCP contract

## Tools

- `create_training_session`: accepts optional `questionCount`, `domainId`, and `schedulerProvider`.
- `get_next_assignment`: accepts `sessionId`; returns `assignment`, `generation_required`, `no_available_items`, or `session_complete`.
- `submit_choice_answer`: accepts `assignmentId` and the user's unmodified `answer` text.
- `record_interaction`: accepts `sessionId`, optional `assignmentId`, an interaction type, and content.
- `create_candidate_question`: accepts `sessionId`, required `topicId`, and a complete question object. For a deeper training question, also send `attemptType: follow_up` and `parentAttemptId`; the response includes its pending `assignmentId`.
- `finish_training_session`: accepts `sessionId`.
- `get_learning_report`: returns learning and content overview metrics.
- `get_scheduler_backlog`: returns due count, oldest due time, new-item allowance, and pause state.

## Assignment safety

An assignment payload intentionally excludes `correctOptionIds`, `explanation`, and sources. Do not try to obtain or reconstruct them before submission.

Keep the returned `assignmentId` until an answer succeeds. Clarification and hint tools do not create a replacement assignment.

## Interaction types

Prefer:

- `clarification_question`
- `hint_requested`
- `answer_challenge`
- `explanation_followup`

An interaction response must report `consumedQuestion: false`.

## Idempotency

Tool calls may accept `idempotencyKey`. Generate a stable key for one logical write. Reuse it on transport retries; generate a different key for a new answer, interaction, candidate, or session.
