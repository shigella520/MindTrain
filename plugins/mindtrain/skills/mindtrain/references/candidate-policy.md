# Candidate question policy

Generate exactly one choice question that follows the Core-provided `generationProfile`.

Follow the profile exactly:

- use `generationProfile.questionType` as `type`
- use `generationProfile.difficulty` as `difficulty`
- include `generationProfile.knowledgePoint.topicId` as the primary topic
- use `generationProfile.knowledgePoint.importance` as `importance`
- scope the question to the knowledge-point name, keywords, applicable versions, and source references

Always provide exactly four ordered options: A, B, C, D.

Required fields:

- `schemaVersion: 1`
- stable lowercase `id`
- `version: 1`
- `status: candidate`
- `type`, `title`, `stem`, `options`, `correctOptionIds`
- returned `topicId` in `topicIds`
- `difficulty` and `importance` from 1 to 5
- applicable versions
- structured `explanation`
- at least one source with ID, URL, title, and access date
- `createdBy`, model, Prompt version, and creation time

For single choice, provide one correct option. For multiple choice, provide two or three. Do not mention the correct-option count in the stem or answer prompt.

Quality rules:

- State enough version, component, and scenario context for exactly one defensible answer set. Reject and regenerate the question if correctness depends on an unstated assumption.
- Test the requested knowledge point directly. Do not merely repeat a definition or copy wording from a source.
- Make every distractor plausible to a learner but unambiguously wrong under the stated context and version.
- Keep options parallel in scope and grammatical form. Avoid overlapping choices, hidden combinations, wording clues, and `all/none of the above`.
- Avoid negative stems such as “which is incorrect” unless the learning objective specifically requires one.
- Do not repeat an existing or parent stem, or merely rephrase its options.
- Never copy long source passages.

The explanation must contain:

- conclusion
- four option analyses with matching correctness flags
- mechanism steps
- pitfalls
- version notes
- related topic IDs

Use the knowledge point's source references first. If they are insufficient or version-sensitive, use authoritative documentation and record its URL and access date. Never invent a source. Set `promptVersion` to `mindtrain-candidate-v2` and include the actual model identifier.

`candidate` is a temporary pre-answer persistence state. Core activates an answered candidate for ordinary scheduling, or physically deletes it when the user rejects it before answering.
