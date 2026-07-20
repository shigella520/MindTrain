# Candidate question policy

Generate one `single_choice` or `multiple_choice` question with exactly four ordered options: A, B, C, D.

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

The explanation must contain:

- conclusion
- four option analyses with matching correctness flags
- mechanism steps
- pitfalls
- version notes
- related topic IDs

Use the generation context's source references. If they are insufficient or version-sensitive, use authoritative documentation and record its URL and access date. Never invent a source.
