# Knowledge catalog workflow

## Query

- Call `list_knowledge_domains` before creating a new domain and when the user asks what can be learned.
- Call `get_knowledge_catalog_tree` for one complete domain forest. A domain can have multiple root topics.
- Call `search_knowledge_topics` for names, descriptions, or keywords. Report the domain and ancestor path for every ambiguous match.
- Call `get_knowledge_topic` only when details, relations, sources, coverage, or mastery are needed.

Catalog data is private. Never claim that a failed or unauthenticated query means the catalog is empty.

## Create from AI dialogue

1. Establish the learning goal, audience, scope, emphasis, and difficulty. Do not require a local library.
2. Query existing domains and relevant topics. Choose either one new domain ID or one existing domain ID; one draft must not span domains.
3. Create a coherent topic forest. Use `parentId: null` for every root topic and `sortOrder` for deterministic sibling order. Add concise descriptions, keywords, importance from 1 to 5, and semantic relations only within the domain.
4. Call `preview_training_domain` with `originType: ai_dialogue`, a concise context object, no `libraryId`, and real sources only when authoritative references were actually used.
5. Show the full tree, relations, source coverage, validation warnings, conflicts, and create/unchanged counts. Explicitly state when the draft has no bound references.
6. After one explicit confirmation, call `confirm_training_domain` with the exact draft ID and proposal hash. Otherwise call `discard_training_domain_draft`.

## Revise a draft

Treat previews as immutable. When the user requests changes, discard the old draft, revise the proposal, and call `preview_training_domain` again. Never partially confirm a tree or silently replace an existing stable ID.
