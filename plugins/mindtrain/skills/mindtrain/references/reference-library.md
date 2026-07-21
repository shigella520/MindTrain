# Local reference library workflow

Use this workflow when a user wants MindTrain to learn from a local directory.

## Configure and index

1. Call `configure_reference_library` with a stable lowercase library ID, display name, and absolute directory.
2. Call `sync_reference_library`. Supported formats are Markdown, plain text, PDF, DOCX, and PPTX. PDF text extraction does not include OCR.
3. Call `get_reference_library_status` and report skipped or unreadable documents. Do not continue when no usable content exists.

The directory path, extracted text, and SQLite index are local-only. Never include them in a Core request, generated source record, chat summary, or committed file.

## Organize the training domain and knowledge points

1. Inspect `list_reference_documents`, document headings, and representative search results. Read bounded passages only when needed.
2. Organize the user-selected material into a proposed training domain, hierarchical knowledge points, semantic relations, and immutable source metadata. The user decides the learning goal; do not substitute a different domain. Bind every topic to at least one source when possible.
3. Use `applicableVersions` for domain-independent version constraints. Do not create new `javaVersions` fields.
4. Call `preview_training_domain` with `originType: local_reference`, the configured `libraryId`, a concise learning context, and the proposal once it is internally consistent.
5. Show the user the full training-domain and knowledge-point tree, relations summary, source coverage, warnings, conflicts, and create/unchanged counts.
6. Ask whether to save and enable this training domain. After one explicit confirmation, call `confirm_training_domain` with the returned draft ID and exact proposal hash. Otherwise call `discard_training_domain_draft`.

Describe this as a training-domain draft, not as importing a predefined catalog. If preview reports a conflict, revise the stable ID or content deliberately and preview again. Never silently overwrite or delete an active topic.

## Source metadata

Local sources use `mindtrain-local://<libraryId>/<relativePath>` and include:

- `sourceType`, `sourceId`, `url`
- `libraryId`, `relativePath`, `contentHash`
- `title`, `accessedAt`, and a section/page/slide `locator`

A changed file creates a new content-hash source version. Existing questions retain their original source reference.

## Generate grounded questions

For `generation_required`, search by the requested topic name and keywords, then read one to three relevant passages. Generate from those passages while following `candidate-policy.md`. If the passages cannot support an unambiguous answer, ask whether external authoritative sources may be used for this question. Do not retain that permission for later questions.
