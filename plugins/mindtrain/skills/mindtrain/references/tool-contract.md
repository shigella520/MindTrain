# Trainer MCP contract

## Configuration tools

- `get_mindtrain_configuration`: reports whether a private instance is configured without returning its Token.
- `configure_mindtrain_instance`: validates and saves a full MCP URL and single-user Bootstrap Token.

## Training tools

- `create_training_session`: accepts optional `questionCount`, `domainId`, and `schedulerProvider`.
- `get_next_assignment`: accepts `sessionId`; returns `assignment`, `generation_required`, `no_available_items`, or `session_complete`.
- `submit_choice_answer`: accepts `assignmentId` and the user's unmodified `answer` text.
- `record_interaction`: records a question or follow-up without consuming the assignment.
- `create_candidate_question`: saves a complete candidate for the owning session only.
- `finish_training_session`: finishes a session.
- `get_learning_report`: returns learning and content overview metrics.
- `get_scheduler_backlog`: returns due backlog and new-item allowance.

Assignment payloads intentionally exclude `correctOptionIds`, explanations, and sources. Keep the returned assignment ID until an answer succeeds. Use a stable idempotency key when retrying the same write.
