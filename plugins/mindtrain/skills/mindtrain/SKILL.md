---
name: mindtrain
description: Configure a private MindTrain instance and run persistent conversational knowledge training through its Trainer MCP, including sessions, safe choice questions, clarification questions, exact grading, session-scoped candidates, summaries, reports, and scheduler backlog. Use when the user installs MindTrain, configures its private server, starts or continues training, answers a question, asks for a deeper follow-up, ends a session, or inspects learning progress.
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

## Run training

1. Call `create_training_session`; default to 10 main questions and the `weighted` scheduler.
2. Call `get_next_assignment`.
3. When it returns `assignment`, show only the stem and A-D options. Use exactly: `请回复选项字母，可用逗号分隔。`
4. Treat a clear option selection as a formal answer and call `submit_choice_answer` once.
5. Treat concept questions, uncertainty, challenges, and hint requests as interactions. Call `record_interaction`, answer conversationally, and keep the assignment pending.
6. After successful grading, render: result, correct answer, conclusion, option analysis, mechanism, pitfalls, version notes, related topics, and sources.
7. Offer exactly: `下一题`, `深入追问`, `结束总结`.
8. Call `finish_training_session` when the target is complete or the user ends early.

Never infer the correct answer before `submit_choice_answer` returns it. Invalid answer input does not consume the question.

## Generate a missing question

When `get_next_assignment` returns `generation_required`, read [candidate-policy.md](references/candidate-policy.md), generate one compliant question using the returned topic context, then call `create_candidate_question`. Call `get_next_assignment` again only after the candidate is accepted.

The candidate is usable only by its owning session. Never claim that it is published or reusable.

## Follow up deeply

For an explanatory follow-up, call `record_interaction` and answer in the current conversation.

For a deeper training question, generate another four-option question on the same topic and call `create_candidate_question` with `attemptType: follow_up` and the graded parent attempt ID. Present the returned follow-up assignment. It must increment only the follow-up count, never the main target. Do not count conversational explanations as attempts.

## Recover safely

- On `configuration_required`, return to the first-use configuration flow.
- On `answer_unparseable`, ask for option letters again without revealing answer count.
- On `no_available_items`, explain the scheduler reason and offer to finish or inspect backlog.
- On Core or MCP unavailability, retain the current visible question in conversation and retry the tool; do not fabricate persistence success.
- Use a new idempotency key for a new user action and reuse it only when retrying that same action.

Read [tool-contract.md](references/tool-contract.md) when tool inputs, statuses, or retry behavior are unclear.
