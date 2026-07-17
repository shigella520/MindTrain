# Generate Choice Follow-up v1

Generate one harder or narrower choice question for the same primary topic as the parent question. Return only JSON conforming to `schemas/question.schema.json`.

- Keep four ordered options and exact, unambiguous answers.
- Avoid repeating the parent stem or merely rephrasing its options.
- Set `parentQuestionId` to the parent ID and `status` to `candidate`.
- Preserve reliable sources and set `promptVersion` to `generate-choice-follow-up-v1`.
- The resulting Attempt must use `attemptType: follow_up` and link `parentAttemptId`.
