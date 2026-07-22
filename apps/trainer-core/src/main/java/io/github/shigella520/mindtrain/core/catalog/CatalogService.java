package io.github.shigella520.mindtrain.core.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shigella520.mindtrain.core.api.ApiException;
import io.github.shigella520.mindtrain.core.identity.UserContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> ORIGIN_TYPES = Set.of("local_reference", "ai_dialogue");
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public CatalogService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ImportResponse preview(PreviewRequest request) {
        if (request == null || request.proposal() == null || !request.proposal().isObject()) {
            throw bad("catalog_proposal_invalid", "proposal is required");
        }
        String originType = normalizedOrigin(request.originType(), request.libraryId());
        if (!ORIGIN_TYPES.contains(originType)) {
            throw bad("catalog_origin_type_invalid", "originType must be local_reference or ai_dialogue");
        }
        if ("local_reference".equals(originType) && blank(request.libraryId())) {
            throw bad("catalog_proposal_invalid", "libraryId is required for local_reference proposals");
        }
        JsonNode context = request.context() != null && request.context().isObject()
            ? request.context() : objectMapper.createObjectNode();
        JsonNode proposal = request.proposal();
        Validation validation = validate(proposal, originType, request.libraryId());
        if (validation.errors().contains("source contains local-only data")) {
            throw bad("catalog_source_data_forbidden",
                "Absolute paths, original text and extracted content must remain on the Codex host");
        }
        Diff diff = diff(proposal);
        String status = !validation.errors().isEmpty() ? "invalid" : !diff.conflicts().isEmpty() ? "conflict" : "previewed";
        String id = "catalog-draft-" + UUID.randomUUID();
        String hash = sha256(canonical(proposal));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String userId = UserContext.requireUserId();
        jdbc.sql("""
                INSERT INTO catalog_import(id,user_id,library_id,origin_type,context_json,proposal_hash,status,
                                           proposal_json,validation_json,diff_json,created_at)
                VALUES (:id,:userId,:libraryId,:originType,:context,:hash,:status,:proposal,:validation,:diff,:createdAt)
                """)
            .param("id", id).param("userId", userId).param("libraryId", request.libraryId())
            .param("originType", originType).param("context", json(context)).param("hash", hash)
            .param("status", status).param("proposal", json(proposal))
            .param("validation", json(objectMapper.valueToTree(validation)))
            .param("diff", json(objectMapper.valueToTree(diff))).param("createdAt", now).update();
        return new ImportResponse(id, id, originType, request.libraryId(), context, hash, status, proposal,
            validation, diff, now, null, null);
    }

    public ImportResponse get(String id) {
        String userId = UserContext.requireUserId();
        return jdbc.sql("SELECT * FROM catalog_import WHERE id=:id AND user_id=:userId")
            .param("id", id).param("userId", userId)
            .query((rs, rowNum) -> response(rs.getString("id"), rs.getString("origin_type"),
                rs.getString("library_id"), rs.getString("context_json"), rs.getString("proposal_hash"),
                rs.getString("status"), rs.getString("proposal_json"), rs.getString("validation_json"),
                rs.getString("diff_json"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("applied_at", OffsetDateTime.class), rs.getObject("rejected_at", OffsetDateTime.class)))
            .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "catalog_draft_not_found", "Training-domain draft was not found"));
    }

    @Transactional
    public ImportResponse apply(String id, String proposalHash) {
        ImportResponse current = get(id);
        if ("applied".equals(current.status())) {
            if (!current.proposalHash().equals(proposalHash)) throw hashMismatch();
            return current;
        }
        if (!"previewed".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "catalog_draft_not_applicable",
                "Only a valid conflict-free draft can be confirmed");
        }
        if (!current.proposalHash().equals(proposalHash)) throw hashMismatch();
        String userId = UserContext.requireUserId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (JsonNode domain : array(current.proposal(), "domains")) {
            if (existing("domain", text(domain, "id")) != null) continue;
            jdbc.sql("""
                    INSERT INTO knowledge_domain(id,user_id,name,content_json,created_at,origin_type,sort_order,updated_at)
                    VALUES(:id,:userId,:name,:content,:createdAt,:originType,:sortOrder,:updatedAt)
                    """)
                .param("id", text(domain, "id")).param("userId", userId).param("name", text(domain, "name"))
                .param("content", json(domain)).param("createdAt", now).param("originType", current.originType())
                .param("sortOrder", domain.path("sortOrder").asInt(0)).param("updatedAt", now).update();
        }
        for (JsonNode source : array(current.proposal(), "sources")) {
            if (existing("source", text(source, "id", text(source, "sourceId"))) != null) continue;
            jdbc.sql("""
                    INSERT INTO source_asset(id,user_id,source_type,library_id,relative_path,content_hash,metadata_json,created_at)
                    VALUES(:id,:userId,:sourceType,:libraryId,:relativePath,:contentHash,:metadata,:createdAt)
                    """)
                .param("id", text(source, "id", text(source, "sourceId"))).param("userId", userId)
                .param("sourceType", text(source, "sourceType")).param("libraryId", nullable(source, "libraryId"))
                .param("relativePath", nullable(source, "relativePath")).param("contentHash", nullable(source, "contentHash"))
                .param("metadata", json(source)).param("createdAt", now).update();
        }
        for (JsonNode topic : orderedTopics(current.proposal())) {
            if (existing("topic", text(topic, "id")) != null) continue;
            ObjectNode content = topicContent(topic);
            jdbc.sql("""
                    INSERT INTO topic(id,domain_id,parent_id,name,kind,importance,content_json,created_at,sort_order,updated_at)
                    VALUES(:id,:domainId,:parentId,:name,:kind,:importance,:content,:createdAt,:sortOrder,:updatedAt)
                    """)
                .param("id", text(topic, "id")).param("domainId", text(topic, "domainId"))
                .param("parentId", nullable(topic, "parentId")).param("name", text(topic, "name"))
                .param("kind", text(topic, "kind", "leaf")).param("importance", topic.path("importance").asInt(3))
                .param("content", json(content)).param("createdAt", now)
                .param("sortOrder", topic.path("sortOrder").asInt(0)).param("updatedAt", now).update();
        }
        for (JsonNode relation : array(current.proposal(), "relations")) {
            if (existing("relation", text(relation, "id")) != null) continue;
            jdbc.sql("""
                    INSERT INTO topic_relation(id,user_id,from_topic_id,to_topic_id,relation_type,content_json,created_at)
                    VALUES(:id,:userId,:fromId,:toId,:type,:content,:createdAt)
                    """)
                .param("id", text(relation, "id")).param("userId", userId)
                .param("fromId", text(relation, "fromTopicId")).param("toId", text(relation, "toTopicId"))
                .param("type", text(relation, "type")).param("content", json(relation)).param("createdAt", now).update();
        }
        jdbc.sql("UPDATE catalog_import SET status='applied', applied_at=:now WHERE id=:id AND user_id=:userId")
            .param("now", now).param("id", id).param("userId", userId).update();
        return get(id);
    }

    @Transactional
    public ImportResponse reject(String id) {
        ImportResponse current = get(id);
        if ("rejected".equals(current.status())) return current;
        if ("applied".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "catalog_draft_already_applied",
                "An applied training-domain draft cannot be discarded");
        }
        jdbc.sql("UPDATE catalog_import SET status='rejected', rejected_at=:now WHERE id=:id AND user_id=:userId")
            .param("now", OffsetDateTime.now(ZoneOffset.UTC)).param("id", id)
            .param("userId", UserContext.requireUserId()).update();
        return get(id);
    }

    public List<DomainSummary> domains(String query) {
        String normalized = lower(query);
        Map<String, Set<String>> questionIds = activeQuestionIds();
        Map<String, Integer> questionCounts = questionCounts(questionIds);
        List<DomainSummary> result = new ArrayList<>();
        for (DomainRow domain : domainRows()) {
            List<TopicRow> topics = topicRows(domain.id());
            if (!normalized.isEmpty() && !searchable(domain.id(), domain.name(), description(domain.content())).contains(normalized)) continue;
            int activeQuestions = domainQuestionCount(topics, questionIds);
            result.add(new DomainSummary(domain.id(), domain.name(), description(domain.content()), domain.originType(),
                (int) topics.stream().filter(topic -> topic.parentId() == null).count(), topics.size(), activeQuestions,
                domain.createdAt(), domain.updatedAt()));
        }
        return result;
    }

    public TopicTreeResponse tree(String domainId) {
        DomainRow domain = requireDomain(domainId);
        List<TopicRow> topics = topicRows(domainId);
        Map<String, Set<String>> questionIds = activeQuestionIds();
        Map<String, Integer> questionCounts = questionCounts(questionIds);
        Map<String, Integer> mastery = masteryScores();
        Map<String, List<TopicNode>> children = new HashMap<>();
        for (TopicRow topic : topics) {
            TopicNode node = node(topic, questionCounts, mastery, List.of());
            children.computeIfAbsent(topic.parentId(), ignored -> new ArrayList<>()).add(node);
        }
        Comparator<TopicNode> order = Comparator.comparingInt(TopicNode::sortOrder)
            .thenComparing(TopicNode::name).thenComparing(TopicNode::id);
        List<TopicNode> roots = buildChildren(null, children, order);
        int activeQuestions = domainQuestionCount(topics, questionIds);
        DomainSummary summary = new DomainSummary(domain.id(), domain.name(), description(domain.content()),
            domain.originType(), roots.size(), topics.size(), activeQuestions, domain.createdAt(), domain.updatedAt());
        return new TopicTreeResponse(summary, roots);
    }

    public TopicSearchResponse search(String query, String domainId, int requestedLimit, String cursor) {
        String normalized = lower(query);
        if (normalized.isEmpty()) throw bad("catalog_search_query_required", "q is required");
        int limit = Math.max(1, Math.min(requestedLimit <= 0 ? 20 : requestedLimit, 100));
        int offset = parseCursor(cursor);
        Map<String, DomainRow> domains = new LinkedHashMap<>();
        domainRows().forEach(domain -> domains.put(domain.id(), domain));
        if (!blank(domainId) && !domains.containsKey(domainId)) requireDomain(domainId);
        Map<String, Integer> questionCounts = questionCounts(activeQuestionIds());
        Map<String, Integer> mastery = masteryScores();
        List<TopicSearchResult> matches = new ArrayList<>();
        for (DomainRow domain : domains.values()) {
            if (!blank(domainId) && !domain.id().equals(domainId)) continue;
            List<TopicRow> topics = topicRows(domain.id());
            Map<String, TopicRow> byId = new HashMap<>();
            topics.forEach(topic -> byId.put(topic.id(), topic));
            for (TopicRow topic : topics) {
                if (!topicSearchable(topic).contains(normalized)) continue;
                matches.add(new TopicSearchResult(topic.id(), topic.domainId(), domain.name(), topic.parentId(),
                    topic.name(), description(topic.content()), ancestorPath(topic, byId), keywords(topic.content()),
                    questionCounts.getOrDefault(topic.id(), 0), mastery.get(topic.id())));
            }
        }
        matches.sort(Comparator.comparing(TopicSearchResult::domainName)
            .thenComparing(result -> String.join(" / ", result.ancestorPath())).thenComparing(TopicSearchResult::name));
        int from = Math.min(offset, matches.size());
        int to = Math.min(from + limit, matches.size());
        String nextCursor = to < matches.size() ? Integer.toString(to) : null;
        return new TopicSearchResponse(matches.subList(from, to), matches.size(), nextCursor);
    }

    public TopicDetail topic(String topicId) {
        TopicRow topic = requireTopic(topicId);
        DomainRow domain = requireDomain(topic.domainId());
        List<TopicRow> all = topicRows(domain.id());
        Map<String, TopicRow> byId = new HashMap<>();
        all.forEach(item -> byId.put(item.id(), item));
        Map<String, Integer> questionCounts = questionCounts(activeQuestionIds());
        Map<String, Integer> mastery = masteryScores();
        List<TopicNode> children = all.stream().filter(item -> topic.id().equals(item.parentId()))
            .sorted(topicOrder()).map(item -> node(item, questionCounts, mastery, List.of())).toList();
        List<JsonNode> relations = jdbc.sql("""
                SELECT content_json FROM topic_relation
                WHERE user_id=:userId AND (from_topic_id=:topicId OR to_topic_id=:topicId)
                ORDER BY created_at, id
                """).param("userId", UserContext.requireUserId()).param("topicId", topicId)
            .query(String.class).list().stream().map(this::read).toList();
        List<JsonNode> sources = new ArrayList<>();
        for (String sourceId : strings(topic.content().path("sourceRefs"))) {
            jdbc.sql("SELECT metadata_json FROM source_asset WHERE user_id=:userId AND id=:id")
                .param("userId", UserContext.requireUserId()).param("id", sourceId).query(String.class).optional()
                .map(this::read).ifPresent(sources::add);
        }
        return new TopicDetail(topic.id(), topic.domainId(), domain.name(), topic.parentId(), topic.name(),
            description(topic.content()), topic.kind(), topic.importance(), topic.sortOrder(),
            ancestorPath(topic, byId), keywords(topic.content()), strings(topic.content().path("sourceRefs")),
            questionCounts.getOrDefault(topic.id(), 0), mastery.get(topic.id()), children, relations, sources,
            topic.createdAt(), topic.updatedAt());
    }

    private Validation validate(JsonNode proposal, String originType, String libraryId) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String field : List.of("domains", "topics", "relations", "sources")) {
            if (!proposal.path(field).isArray()) errors.add(field + " must be an array");
        }
        if (!errors.isEmpty()) return new Validation(errors, warnings);
        Set<String> domains = ids(array(proposal, "domains"), "domain", errors);
        Set<String> topics = ids(array(proposal, "topics"), "topic", errors);
        ids(array(proposal, "relations"), "relation", errors);
        Set<String> sources = ids(array(proposal, "sources"), "source", errors);
        for (JsonNode domain : array(proposal, "domains")) if (blank(text(domain, "name"))) errors.add("domain name is required");

        Map<String, String> topicDomains = new HashMap<>();
        for (JsonNode topic : array(proposal, "topics")) {
            topicDomains.put(text(topic, "id"), text(topic, "domainId"));
        }
        Map<String, String> parents = new HashMap<>();
        Set<String> targetDomains = new HashSet<>(domains);
        for (JsonNode topic : array(proposal, "topics")) {
            String id = text(topic, "id");
            String domainId = text(topic, "domainId");
            String parent = nullable(topic, "parentId");
            targetDomains.add(domainId);
            if (!domains.contains(domainId) && !domainExists(domainId)) errors.add("unknown domain: " + domainId);
            if (parent != null && !topics.contains(parent) && !topicExists(parent)) errors.add("unknown parent topic: " + parent);
            String parentDomain = parent == null ? null : topicDomains.get(parent);
            if (parentDomain == null && parent != null) parentDomain = topicDomain(parent);
            if (parentDomain != null && !domainId.equals(parentDomain)) errors.add("parent topic belongs to another domain: " + id);
            if (topic.path("importance").asInt(0) < 1 || topic.path("importance").asInt() > 5) {
                errors.add("topic importance must be 1-5: " + id);
            }
            if (parent != null) parents.put(id, parent);
            for (String sourceRef : strings(topic.path("sourceRefs"))) {
                if (!sources.contains(sourceRef) && existing("source", sourceRef) == null) {
                    errors.add("unknown source reference: " + sourceRef);
                }
            }
        }
        if (targetDomains.size() != 1) errors.add("a training-domain draft must target exactly one domain");
        detectParentCycles(parents, errors);
        for (JsonNode relation : array(proposal, "relations")) {
            String from = text(relation, "fromTopicId");
            String to = text(relation, "toTopicId");
            String fromDomain = topicDomains.get(from);
            String toDomain = topicDomains.get(to);
            if (fromDomain == null) fromDomain = topicDomain(from);
            if (toDomain == null) toDomain = topicDomain(to);
            if (fromDomain == null || toDomain == null) errors.add("relation contains unknown topic: " + text(relation, "id"));
            else if (!fromDomain.equals(toDomain)) errors.add("relation crosses domains: " + text(relation, "id"));
            if (from.equals(to)) errors.add("self relation is not allowed: " + text(relation, "id"));
        }
        for (JsonNode source : array(proposal, "sources")) {
            if ("local_reference".equals(text(source, "sourceType"))) {
                if (blank(libraryId) || !libraryId.equals(text(source, "libraryId"))) errors.add("local source libraryId mismatch");
                if (blank(libraryId) || !text(source, "url").startsWith("mindtrain-local://" + libraryId + "/")) errors.add("invalid local source URL");
                if (blank(text(source, "relativePath")) || text(source, "relativePath").startsWith("/") || text(source, "relativePath").contains("..")) errors.add("invalid local source relativePath");
                if (!HASH.matcher(text(source, "contentHash")).matches()) errors.add("local source contentHash must be lowercase SHA-256");
            }
            if (source.has("path") || source.has("absolutePath") || source.has("text") || source.has("content")) {
                errors.add("source contains local-only data");
            }
        }
        if (array(proposal, "topics").isEmpty()) warnings.add("proposal contains no topics");
        if ("ai_dialogue".equals(originType) && array(proposal, "sources").isEmpty()) {
            warnings.add("AI dialogue draft has no bound reference sources");
        }
        return new Validation(errors, warnings);
    }

    private Diff diff(JsonNode proposal) {
        List<String> create = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        compare(array(proposal, "domains"), "domain", create, unchanged, conflicts);
        compare(array(proposal, "sources"), "source", create, unchanged, conflicts);
        compare(array(proposal, "topics"), "topic", create, unchanged, conflicts);
        compare(array(proposal, "relations"), "relation", create, unchanged, conflicts);
        return new Diff(create, unchanged, conflicts);
    }

    private void compare(ArrayNode nodes, String kind, List<String> create, List<String> unchanged, List<String> conflicts) {
        for (JsonNode node : nodes) {
            String id = text(node, "id", text(node, "sourceId"));
            JsonNode existing = existing(kind, id);
            String key = kind + ":" + id;
            if (existing == null) create.add(key);
            else if (canonical(existing).equals(canonical(kind.equals("topic") ? topicContent(node) : node))) unchanged.add(key);
            else conflicts.add(key);
        }
    }

    private JsonNode existing(String kind, String id) {
        if (blank(id)) return null;
        String userId = UserContext.requireUserId();
        return switch (kind) {
            case "domain" -> jsonColumn("SELECT content_json FROM knowledge_domain WHERE user_id=:userId AND id=:id", userId, id);
            case "source" -> jsonColumn("SELECT metadata_json FROM source_asset WHERE user_id=:userId AND id=:id", userId, id);
            case "topic" -> jsonColumn("""
                SELECT t.content_json FROM topic t JOIN knowledge_domain d ON d.id=t.domain_id
                WHERE d.user_id=:userId AND t.id=:id
                """, userId, id);
            case "relation" -> jsonColumn("SELECT content_json FROM topic_relation WHERE user_id=:userId AND id=:id", userId, id);
            default -> null;
        };
    }

    private JsonNode jsonColumn(String sql, String userId, String id) {
        return jdbc.sql(sql).param("userId", userId).param("id", id).query(String.class).optional().map(this::read).orElse(null);
    }

    private Set<String> ids(ArrayNode nodes, String kind, List<String> errors) {
        Set<String> result = new HashSet<>();
        for (JsonNode node : nodes) {
            String id = text(node, "id", text(node, "sourceId"));
            if (blank(id)) errors.add(kind + " id is required");
            else if (!result.add(id)) errors.add("duplicate " + kind + " id: " + id);
        }
        return result;
    }

    private void detectParentCycles(Map<String, String> parents, List<String> errors) {
        for (String start : parents.keySet()) {
            Set<String> visited = new HashSet<>();
            String current = start;
            while (current != null && parents.containsKey(current)) {
                if (!visited.add(current)) { errors.add("topic parent cycle includes: " + current); return; }
                current = parents.get(current);
            }
        }
    }

    private List<JsonNode> orderedTopics(JsonNode proposal) {
        List<JsonNode> remaining = new ArrayList<>();
        array(proposal, "topics").forEach(remaining::add);
        List<JsonNode> ordered = new ArrayList<>();
        Set<String> ready = new HashSet<>();
        while (!remaining.isEmpty()) {
            int before = remaining.size();
            Iterator<JsonNode> iterator = remaining.iterator();
            while (iterator.hasNext()) {
                JsonNode topic = iterator.next();
                String parent = nullable(topic, "parentId");
                if (parent == null || ready.contains(parent) || topicExists(parent)) {
                    ordered.add(topic); ready.add(text(topic, "id")); iterator.remove();
                }
            }
            if (remaining.size() == before) throw bad("catalog_proposal_invalid", "Topic hierarchy cannot be ordered");
        }
        return ordered;
    }

    private ObjectNode topicContent(JsonNode topic) {
        ObjectNode content = topic.path("content").isObject() ? topic.path("content").deepCopy() : objectMapper.createObjectNode();
        content.put("id", text(topic, "id"));
        content.put("name", text(topic, "name"));
        content.put("kind", text(topic, "kind", "leaf"));
        content.put("importance", topic.path("importance").asInt(3));
        content.put("sortOrder", topic.path("sortOrder").asInt(0));
        if (topic.has("description")) content.set("description", topic.path("description"));
        if (topic.has("applicableVersions")) content.set("applicableVersions", topic.path("applicableVersions"));
        if (topic.has("keywords")) content.set("keywords", topic.path("keywords"));
        if (topic.has("sourceRefs")) content.set("sourceRefs", topic.path("sourceRefs"));
        return content;
    }

    private List<DomainRow> domainRows() {
        return jdbc.sql("""
                SELECT id,name,content_json,origin_type,sort_order,created_at,updated_at
                FROM knowledge_domain WHERE user_id=:userId ORDER BY sort_order,name,id
                """).param("userId", UserContext.requireUserId()).query((rs, rowNum) -> new DomainRow(
                    rs.getString("id"), rs.getString("name"), read(rs.getString("content_json")),
                    rs.getString("origin_type"), rs.getInt("sort_order"),
                    rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    private DomainRow requireDomain(String domainId) {
        return domainRows().stream().filter(domain -> domain.id().equals(domainId)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "knowledge_domain_not_found", "Knowledge domain was not found"));
    }

    private List<TopicRow> topicRows(String domainId) {
        return jdbc.sql("""
                SELECT t.id,t.domain_id,t.parent_id,t.name,t.kind,t.importance,t.content_json,t.sort_order,t.created_at,t.updated_at
                FROM topic t JOIN knowledge_domain d ON d.id=t.domain_id
                WHERE d.user_id=:userId AND t.domain_id=:domainId ORDER BY t.sort_order,t.name,t.id
                """).param("userId", UserContext.requireUserId()).param("domainId", domainId)
            .query((rs, rowNum) -> new TopicRow(rs.getString("id"), rs.getString("domain_id"),
                rs.getString("parent_id"), rs.getString("name"), rs.getString("kind"), rs.getInt("importance"),
                read(rs.getString("content_json")), rs.getInt("sort_order"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    private TopicRow requireTopic(String topicId) {
        return jdbc.sql("""
                SELECT t.id,t.domain_id,t.parent_id,t.name,t.kind,t.importance,t.content_json,t.sort_order,t.created_at,t.updated_at
                FROM topic t JOIN knowledge_domain d ON d.id=t.domain_id
                WHERE d.user_id=:userId AND t.id=:topicId
                """).param("userId", UserContext.requireUserId()).param("topicId", topicId)
            .query((rs, rowNum) -> new TopicRow(rs.getString("id"), rs.getString("domain_id"),
                rs.getString("parent_id"), rs.getString("name"), rs.getString("kind"), rs.getInt("importance"),
                read(rs.getString("content_json")), rs.getInt("sort_order"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)))
            .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "knowledge_topic_not_found", "Knowledge topic was not found"));
    }

    private Map<String, Set<String>> activeQuestionIds() {
        Map<String, Set<String>> result = new HashMap<>();
        jdbc.sql("""
                SELECT q.id,qv.topic_ids_json FROM question q JOIN question_version qv
                  ON qv.question_id=q.id AND qv.version=q.current_version
                WHERE q.status='active' AND q.user_id=:userId
                """).param("userId", UserContext.requireUserId())
            .query((rs, rowNum) -> Map.entry(rs.getString("id"), rs.getString("topic_ids_json")))
            .list().forEach(question -> strings(read(question.getValue())).forEach(topicId ->
                result.computeIfAbsent(topicId, ignored -> new HashSet<>()).add(question.getKey())));
        return result;
    }

    private Map<String, Integer> questionCounts(Map<String, Set<String>> questionIds) {
        Map<String, Integer> counts = new HashMap<>();
        questionIds.forEach((topicId, ids) -> counts.put(topicId, ids.size()));
        return counts;
    }

    private int domainQuestionCount(List<TopicRow> topics, Map<String, Set<String>> questionIds) {
        Set<String> ids = new HashSet<>();
        topics.forEach(topic -> ids.addAll(questionIds.getOrDefault(topic.id(), Set.of())));
        return ids.size();
    }

    private Map<String, Integer> masteryScores() {
        Map<String, Integer> result = new HashMap<>();
        jdbc.sql("SELECT topic_id,mastery_score FROM topic_mastery WHERE user_id=:userId")
            .param("userId", UserContext.requireUserId())
            .query((rs, rowNum) -> Map.entry(rs.getString("topic_id"), rs.getInt("mastery_score")))
            .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private List<TopicNode> buildChildren(String parentId, Map<String, List<TopicNode>> grouped, Comparator<TopicNode> order) {
        return grouped.getOrDefault(parentId, List.of()).stream().sorted(order)
            .map(node -> new TopicNode(node.id(), node.domainId(), node.parentId(), node.name(), node.description(),
                node.kind(), node.importance(), node.sortOrder(), node.keywords(), node.sourceRefs(),
                grouped.getOrDefault(node.id(), List.of()).size(), node.activeQuestionCount(), node.masteryScore(),
                buildChildren(node.id(), grouped, order))).toList();
    }

    private TopicNode node(TopicRow topic, Map<String, Integer> questionCounts, Map<String, Integer> mastery,
                           List<TopicNode> children) {
        return new TopicNode(topic.id(), topic.domainId(), topic.parentId(), topic.name(), description(topic.content()),
            topic.kind(), topic.importance(), topic.sortOrder(), keywords(topic.content()),
            strings(topic.content().path("sourceRefs")), children.size(), questionCounts.getOrDefault(topic.id(), 0),
            mastery.get(topic.id()), children);
    }

    private List<String> ancestorPath(TopicRow topic, Map<String, TopicRow> byId) {
        List<String> reversed = new ArrayList<>();
        String parentId = topic.parentId();
        Set<String> seen = new HashSet<>();
        while (parentId != null && seen.add(parentId)) {
            TopicRow parent = byId.get(parentId);
            if (parent == null) break;
            reversed.add(parent.name());
            parentId = parent.parentId();
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private Comparator<TopicRow> topicOrder() {
        return Comparator.comparingInt(TopicRow::sortOrder).thenComparing(TopicRow::name).thenComparing(TopicRow::id);
    }

    private String topicSearchable(TopicRow topic) {
        return searchable(topic.id(), topic.name(), description(topic.content()), String.join(" ", keywords(topic.content())));
    }

    private String searchable(String... values) { return lower(String.join(" ", values)); }
    private String description(JsonNode content) { return text(content, "description", ""); }
    private List<String> keywords(JsonNode content) { return strings(content.path("keywords")); }
    private List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(value -> { if (value.isTextual()) result.add(value.asText()); });
        return result;
    }
    private int parseCursor(String cursor) {
        if (blank(cursor)) return 0;
        try { return Math.max(0, Integer.parseInt(cursor)); }
        catch (NumberFormatException exception) { throw bad("catalog_search_cursor_invalid", "cursor must be a non-negative integer"); }
    }
    private String normalizedOrigin(String originType, String libraryId) {
        return blank(originType) ? (blank(libraryId) ? "ai_dialogue" : "local_reference") : originType;
    }
    private String lower(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private boolean domainExists(String id) { return count("SELECT COUNT(*) FROM knowledge_domain WHERE user_id=:userId AND id=:id", id) > 0; }
    private boolean topicExists(String id) { return topicDomain(id) != null; }
    private String topicDomain(String id) {
        if (blank(id)) return null;
        return jdbc.sql("""
                SELECT t.domain_id FROM topic t JOIN knowledge_domain d ON d.id=t.domain_id
                WHERE d.user_id=:userId AND t.id=:id
                """).param("userId", UserContext.requireUserId()).param("id", id).query(String.class).optional().orElse(null);
    }
    private int count(String sql, String id) {
        var query = jdbc.sql(sql).param("id", id);
        if (sql.contains(":userId")) query.param("userId", UserContext.requireUserId());
        return query.query(Integer.class).single();
    }

    private ImportResponse response(String id, String originType, String libraryId, String context, String hash,
                                    String status, String proposal, String validation, String diff,
                                    OffsetDateTime createdAt, OffsetDateTime appliedAt, OffsetDateTime rejectedAt) {
        try {
            return new ImportResponse(id, id, originType, libraryId, read(context), hash, status, read(proposal),
                objectMapper.readValue(validation, Validation.class), objectMapper.readValue(diff, Diff.class),
                createdAt, appliedAt, rejectedAt);
        } catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private String canonical(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            ObjectNode object = objectMapper.createObjectNode();
            sorted.forEach((key, value) -> object.set(key, read(canonical(value))));
            return json(object);
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(read(canonical(value))));
            return json(array);
        }
        return json(node);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private ArrayNode array(JsonNode node, String field) { return node.path(field) instanceof ArrayNode a ? a : objectMapper.createArrayNode(); }
    private String text(JsonNode node, String field) { return text(node, field, ""); }
    private String text(JsonNode node, String field, String fallback) { String value = node.path(field).asText(""); return blank(value) ? fallback : value; }
    private String nullable(JsonNode node, String field) { String value = text(node, field); return blank(value) ? null : value; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String json(JsonNode value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); } }
    private JsonNode read(String value) { try { return objectMapper.readTree(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private ApiException hashMismatch() { return new ApiException(HttpStatus.CONFLICT, "catalog_proposal_hash_mismatch", "proposalHash does not match the preview"); }

    public record PreviewRequest(String originType, String libraryId, JsonNode context, JsonNode proposal) {}
    public record ApplyRequest(String proposalHash) {}
    public record Validation(List<String> errors, List<String> warnings) {}
    public record Diff(List<String> create, List<String> unchanged, List<String> conflicts) {}
    public record ImportResponse(String draftId, String importId, String originType, String libraryId,
                                 JsonNode context, String proposalHash, String status, JsonNode proposal,
                                 Validation validation, Diff diff, OffsetDateTime createdAt,
                                 OffsetDateTime appliedAt, OffsetDateTime rejectedAt) {}
    public record DomainSummary(String id, String name, String description, String originType, int rootTopicCount,
                                int topicCount, int activeQuestionCount, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt) {}
    public record TopicNode(String id, String domainId, String parentId, String name, String description,
                            String kind, int importance, int sortOrder, List<String> keywords,
                            List<String> sourceRefs, int childCount, int activeQuestionCount,
                            Integer masteryScore, List<TopicNode> children) {}
    public record TopicTreeResponse(DomainSummary domain, List<TopicNode> roots) {}
    public record TopicSearchResult(String id, String domainId, String domainName, String parentId, String name,
                                    String description, List<String> ancestorPath, List<String> keywords,
                                    int activeQuestionCount, Integer masteryScore) {}
    public record TopicSearchResponse(List<TopicSearchResult> items, int total, String nextCursor) {}
    public record TopicDetail(String id, String domainId, String domainName, String parentId, String name,
                              String description, String kind, int importance, int sortOrder,
                              List<String> ancestorPath, List<String> keywords, List<String> sourceRefs,
                              int activeQuestionCount, Integer masteryScore, List<TopicNode> children,
                              List<JsonNode> relations, List<JsonNode> sources, OffsetDateTime createdAt,
                              OffsetDateTime updatedAt) {}
    private record DomainRow(String id, String name, JsonNode content, String originType, int sortOrder,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    private record TopicRow(String id, String domainId, String parentId, String name, String kind, int importance,
                            JsonNode content, int sortOrder, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}
