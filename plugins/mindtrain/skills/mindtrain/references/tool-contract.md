# Trainer MCP contract

## Configuration tools

- `get_mindtrain_configuration`: reports whether a private instance is configured without returning its Token.
- `configure_mindtrain_instance`: validates and saves a full MCP URL and single-user Bootstrap Token.

## Training tools

- `create_training_session`: accepts optional `domainId` and `schedulerProvider`. Core uses the application-configured `questionCount`.
- `get_next_assignment`: accepts `sessionId`; returns `assignment`, `generation_required`, `no_available_items`, or `session_complete`. A generation request includes a mandatory `generationProfile` with the target question type, difficulty, and knowledge point.
- `submit_choice_answer`: accepts `assignmentId` and the user's unmodified `answer` text.
- `reject_generated_question`: accepts the pending generated `assignmentId`; physically deletes the unanswered Assignment, QuestionVersion, and Question, and restores main-question new-item allowance.
- `record_interaction`: records a question or follow-up without consuming the assignment.
- `create_candidate_question`: saves a complete temporary candidate for the owning session. A main candidate must match the issued `generationProfile`; Core rejects type or difficulty mismatches. Answered candidates become ordinary reusable questions.
- `revise_saved_question`: creates an immutable next active version after explicit user approval. Send `questionId`, `expectedVersion`, changed fields, `reason`, and the source Assignment when available.
- `finish_training_session`: finishes a session.
- `get_learning_report`: returns learning and content overview metrics.
- `get_scheduler_backlog`: returns due backlog and new-item allowance.

Assignment payloads intentionally exclude `correctOptionIds`, explanations, and sources. Keep the returned assignment ID until an answer succeeds. Use a stable idempotency key when retrying the same write.

## Local reference tools

- `configure_reference_library`: saves a named absolute directory only in local Plugin configuration.
- `sync_reference_library`: incrementally extracts supported documents and refreshes the local FTS index.
- `get_reference_library_status`, `list_reference_libraries`, and `list_reference_documents`: inspect local configuration and sync warnings.
- `search_reference_library`: returns ranked snippets and source metadata; use it before reading content.
- `read_reference_document`: reads a bounded local passage by document ID and locator.
- `remove_reference_library`: removes only local configuration and indexes.

## Catalog import tools

These are internal persistence tool names. In user-facing conversation, describe the workflow as previewing, saving, and enabling a training domain organized from the user's selected references.

- `preview_knowledge_catalog_import`: validates and saves a proposal without changing active domains or topics.
- `get_knowledge_catalog_import`: returns the proposal, hash, validation, conflicts, and diff.
- `apply_knowledge_catalog_import`: requires the exact preview `proposalHash` and explicit user confirmation.
- `reject_knowledge_catalog_import`: rejects the proposal without changing the active catalog.

Catalog payloads may include domain, topic hierarchy, topic relations, and source metadata. Never send an absolute local path or extracted document content to these tools.
