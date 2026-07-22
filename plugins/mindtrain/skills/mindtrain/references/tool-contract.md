# Trainer MCP contract

## Configuration tools

- `get_mindtrain_configuration`: reports whether a private instance is configured without returning its Token.
- `configure_mindtrain_instance`: validates and saves a full MCP URL and single-user Bootstrap Token.

## Training tools

- `create_training_session`: accepts optional `domainId` and `schedulerProvider`. Core uses the sole domain when exactly one exists, rejects an empty catalog with `no_training_domains`, and requires explicit selection with `training_domain_selection_required` when multiple domains exist. One Session is permanently scoped to one domain.
- `get_next_assignment`: accepts `sessionId`; returns `assignment`, `generation_required`, `no_available_items`, or `session_complete`. A generation request includes a mandatory `generationProfile` with the target question type, difficulty, and knowledge point.
- `submit_choice_answer`: accepts `assignmentId` and the user's unmodified `answer` text.
- `reject_generated_question`: accepts the pending generated `assignmentId`; physically deletes the unanswered Assignment, QuestionVersion, and Question, and restores main-question new-item allowance.
- `record_interaction`: records a question or follow-up without consuming the assignment.
- `create_candidate_question`: saves a complete temporary candidate for the owning session. A main candidate must match the issued `generationProfile`; Core rejects type or difficulty mismatches. Answered candidates become ordinary reusable questions.
- `revise_saved_question`: creates an immutable next active version after explicit user approval. Send `questionId`, `expectedVersion`, changed fields, `reason`, and the source Assignment when available.
- `finish_training_session`: finishes a session.
- `get_learning_report`: returns learning and content overview metrics.
- `get_scheduler_backlog`: returns due backlog and new-item allowance, optionally filtered by `domainId`.

Assignment payloads intentionally exclude `correctOptionIds`, explanations, and sources. Keep the returned assignment ID until an answer succeeds. Use a stable idempotency key when retrying the same write.

## Local reference tools

- `configure_reference_library`: saves a named absolute directory only in local Plugin configuration.
- `sync_reference_library`: incrementally extracts supported documents and refreshes the local FTS index.
- `get_reference_library_status`, `list_reference_libraries`, and `list_reference_documents`: inspect local configuration and sync warnings.
- `search_reference_library`: returns ranked snippets and source metadata; use it before reading content.
- `read_reference_document`: reads a bounded local passage by document ID and locator.
- `remove_reference_library`: removes only local configuration and indexes.

## Knowledge catalog tools

All catalog tools require the configured private instance Token.

- `list_knowledge_domains`: lists domains with root Topic, Topic, and active-question counts.
- `get_knowledge_catalog_tree`: returns the complete ordered Topic forest for one domain.
- `search_knowledge_topics`: searches topic names, descriptions, and keywords with optional domain filtering and cursor pagination.
- `get_knowledge_topic`: returns one Topic's path, children, relations, sources, coverage, and mastery.
- `preview_training_domain`: validates and saves an immutable proposal without changing active domains or topics. Use `originType: local_reference` with `libraryId`, or `originType: ai_dialogue` without it.
- `get_training_domain_draft`: returns the proposal, hash, validation warnings, conflicts, and diff.
- `confirm_training_domain`: requires the exact `proposalHash` and explicit user confirmation.
- `discard_training_domain_draft`: discards a proposal without changing the active catalog.

One draft targets exactly one new or existing domain and may contain multiple root Topics. Catalog payloads may include domain, topic hierarchy, topic relations, and source metadata. Never send an absolute local path or extracted document content. Legacy `*_knowledge_catalog_import` tools are compatibility aliases and should not be selected for new workflows.
