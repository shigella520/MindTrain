# Taxonomy Policy

Use `assets/topics/java-backend-taxonomy.json` as the source of selectable knowledge points. Select only `kind: leaf` entries and preserve their stable IDs.

The taxonomy covers Java 8-21 language and collections, JVM, concurrency, I/O, Spring, MySQL, Redis, messaging, and system design. A generated question must reference at least one selected leaf topic and use the topic's `javaVersions`, keywords, importance, and `sourceRefs` as generation context.

Do not silently rename or remove published topic IDs. Add new topics with new stable IDs and increment the taxonomy version.
