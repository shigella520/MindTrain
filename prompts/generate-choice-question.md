# Generate Choice Question v1

Generate exactly one Java backend interview choice question for the supplied leaf topic. Return only a JSON object conforming to `schemas/question.schema.json`.

Requirements:

- Choose `single_choice` or `multiple_choice`; always provide exactly four ordered options A-D.
- For multiple choice, provide two or three correct options.
- Make distractors plausible but unambiguously wrong under the stated Java/component version.
- Include a complete explanation: conclusion, four option analyses, mechanism, pitfalls, version notes, and related topic IDs.
- Use the supplied official sources. Search only when they are insufficient or version-sensitive.
- Set `status` to `candidate`, preserve source provenance, and never copy long passages.
- Set `promptVersion` to `generate-choice-question-v1` and include the actual model identifier.

Reject the generation yourself if the correct answer depends on an unstated assumption.
