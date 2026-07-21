---
name: mindtrain
description: Configure a private MindTrain instance and local reference libraries, organize user-approved training domains and knowledge points from local documents, and run persistent conversational training through its Trainer MCP. Use when the user configures MindTrain, selects a local document directory, asks to create or preview a training domain, confirms saving it, starts or continues training, generates reference-grounded questions, answers or rejects a question, asks follow-ups, revises a saved question, ends a session, or inspects learning progress.
---

# MindTrain

Use the bundled MindTrain bridge as the only application data interface. Do not read or write repository question, candidate, session, attempt, or mastery files.

## Configure first use

1. Call `get_mindtrain_configuration` before the first training action in a task.
2. When `configured` is false, explain that MindTrain needs the private Trainer MCP URL and the deployment's single-user Bootstrap Token.
3. Ask for the full HTTPS MCP URL. Ask for the Token only when the user is willing to provide it in the current private conversation; otherwise direct them to run `python3 scripts/mindtrain_mcp_bridge.py --configure` from the installed plugin directory.
4. Call `configure_mindtrain_instance` with the URL and Token. Never repeat, display, summarize, or persist the Token anywhere except through that configuration tool.
5. Continue only after configuration validation succeeds. If validation fails, report the sanitized error and let the user retry.

The bridge saves configuration outside the repository with user-only file permissions. Never put the private URL or Token into Skill files, Git configuration, examples, or commits.

## Build a training domain from local references

When the user names a local directory as a reference library, read [reference-library.md](references/reference-library.md) and follow its configure, sync, organize, preview, and confirmation workflow. The user chooses what to learn; Codex organizes the selected material into a proposed training domain, topic hierarchy, and relations. Local files, extracted text, absolute paths, and indexes must remain on the Codex host. Core receives only source metadata, hashes, the approved training structure, and generated questions.

Never call `apply_knowledge_catalog_import` until the user has seen the complete training-domain preview and explicitly confirmed saving it once. Reuse the exact `proposalHash` returned by preview. A conflict requires a revised proposal and a new preview; never overwrite an existing topic implicitly. When speaking to the user, say “save and enable the training domain,” not “import/apply a knowledge catalog.”

## Run training

1. Call `create_training_session`; Core always uses the application-configured `questionCount`. Use provider ID `weighted`, displayed to users as `加权调度`.
2. Call `get_next_assignment`.
3. When it returns `assignment`, show only the stem and A-D options. Use exactly: `请回复选项字母，可用逗号分隔。`
4. Treat a clear option selection as a formal answer and call `submit_choice_answer` once.
5. Treat concept questions, uncertainty, challenges, and hint requests as interactions. Call `record_interaction`, answer conversationally, and keep the assignment pending.
6. After successful grading, render: result, correct answer, conclusion, option analysis, mechanism, pitfalls, version notes, related topics, and sources.
7. Offer exactly: `下一题`, `深入追问`, `结束总结`.
8. Call `finish_training_session` when the target is complete or the user ends early.

Never infer the correct answer before `submit_choice_answer` returns it. Invalid answer input does not consume the question.

## Revise a flawed saved question

When the user reports that a displayed question is unclear, incorrect, outdated, or poorly sourced, record the feedback with `record_interaction` and discuss the issue first. Never change the question merely because the user challenged it.

Call `revise_saved_question` only after the user explicitly asks to update the question bank and the intended correction is clear. Use the question ID and version from the assignment, include its assignment ID as `sourceAssignmentId`, and submit only changed fields. Preserve option IDs and the correct option set unless authoritative sources support a scoring correction. Explain the revision and report the new version returned by Core.

On `question_version_conflict`, do not retry with a guessed version. Explain that the question changed concurrently and obtain the latest content through a future management flow.

## Generate a missing question

When `get_next_assignment` returns `generation_required`, read [candidate-policy.md](references/candidate-policy.md), follow the returned `generationProfile` exactly, and generate one compliant question. Do not choose a different type, difficulty, or primary topic. If the profile references a local library, search it and read only the smallest relevant passages. When local evidence is insufficient, ask the user each time before using external authoritative sources. Call `create_candidate_question`, then call `get_next_assignment` again only after the candidate is accepted.

Before it is answered, the generated candidate is temporary and usable only by its owning session. A successful answer activates it for ordinary cross-session scheduling.

## Reject a generated question

When the user clearly rejects the currently displayed AI-generated question before answering, call `reject_generated_question` with its `assignmentId`. Do not submit an answer and do not record the rejected question as an interaction. After Core confirms physical deletion, call `get_next_assignment` and present a materially different replacement. Never call this tool for an imported, previously answered, review, or ordinary new question.

## Follow up deeply

For an explanatory follow-up, call `record_interaction` and answer in the current conversation.

For a deeper training question, generate another four-option question on the same topic and call `create_candidate_question` with `attemptType: follow_up` and the graded parent attempt ID. Present the returned follow-up assignment. It must increment only the follow-up count, never the main target. Do not count conversational explanations as attempts.

## Recover safely

- On `configuration_required`, return to the first-use configuration flow.
- On `answer_unparseable`, ask for option letters again without revealing answer count.
- On `no_available_items`, explain the scheduler reason and offer to finish or inspect backlog.
- On Core or MCP unavailability, retain the current visible question in conversation and retry the tool; do not fabricate persistence success.
- On a local parser warning, report the affected file and continue with usable documents; never claim an unreadable document was indexed.
- Use a new idempotency key for a new user action and reuse it only when retrying that same action.

Read [tool-contract.md](references/tool-contract.md) when tool inputs, statuses, or retry behavior are unclear.
