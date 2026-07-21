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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public CatalogService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ImportResponse preview(PreviewRequest request) {
        if (request == null || blank(request.libraryId()) || request.proposal() == null || !request.proposal().isObject()) {
            throw bad("catalog_proposal_invalid", "libraryId and proposal are required");
        }
        JsonNode proposal = request.proposal();
        Validation validation = validate(proposal, request.libraryId());
        if (validation.errors().contains("source contains local-only data")) {
            throw bad("catalog_source_data_forbidden",
                "Absolute paths, original text and extracted content must remain on the Codex host");
        }
        Diff diff = diff(proposal);
        String status = !validation.errors().isEmpty() ? "invalid" : !diff.conflicts().isEmpty() ? "conflict" : "previewed";
        String id = "catalog-import-" + UUID.randomUUID();
        String hash = sha256(canonical(proposal));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String userId = UserContext.requireUserId();
        jdbc.sql("""
                INSERT INTO catalog_import(id,user_id,library_id,proposal_hash,status,proposal_json,validation_json,diff_json,created_at)
                VALUES (:id,:userId,:libraryId,:hash,:status,:proposal,:validation,:diff,:createdAt)
                """)
            .param("id", id).param("userId", userId).param("libraryId", request.libraryId())
            .param("hash", hash).param("status", status).param("proposal", json(proposal))
            .param("validation", json(objectMapper.valueToTree(validation)))
            .param("diff", json(objectMapper.valueToTree(diff))).param("createdAt", now).update();
        return new ImportResponse(id, request.libraryId(), hash, status, proposal, validation, diff, now, null, null);
    }

    public ImportResponse get(String id) {
        String userId = UserContext.requireUserId();
        return jdbc.sql("SELECT * FROM catalog_import WHERE id=:id AND user_id=:userId")
            .param("id", id).param("userId", userId)
            .query((rs, rowNum) -> response(rs.getString("id"), rs.getString("library_id"),
                rs.getString("proposal_hash"), rs.getString("status"), rs.getString("proposal_json"),
                rs.getString("validation_json"), rs.getString("diff_json"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("applied_at", OffsetDateTime.class),
                rs.getObject("rejected_at", OffsetDateTime.class)))
            .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "catalog_import_not_found", "Catalog import was not found"));
    }

    @Transactional
    public ImportResponse apply(String id, String proposalHash) {
        ImportResponse current = get(id);
        if ("applied".equals(current.status())) {
            if (!current.proposalHash().equals(proposalHash)) throw hashMismatch();
            return current;
        }
        if (!"previewed".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "catalog_import_not_applicable", "Only a valid conflict-free preview can be applied");
        }
        if (!current.proposalHash().equals(proposalHash)) throw hashMismatch();
        String userId = UserContext.requireUserId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (JsonNode domain : array(current.proposal(), "domains")) {
            if (existing("domain", text(domain, "id")) != null) continue;
            jdbc.sql("INSERT INTO knowledge_domain(id,user_id,name,content_json,created_at) VALUES(:id,:userId,:name,:content,:createdAt)")
                .param("id", text(domain, "id")).param("userId", userId).param("name", text(domain, "name"))
                .param("content", json(domain)).param("createdAt", now).update();
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
                    INSERT INTO topic(id,domain_id,parent_id,name,kind,importance,content_json,created_at)
                    VALUES(:id,:domainId,:parentId,:name,:kind,:importance,:content,:createdAt)
                    """)
                .param("id", text(topic, "id")).param("domainId", text(topic, "domainId"))
                .param("parentId", nullable(topic, "parentId")).param("name", text(topic, "name"))
                .param("kind", text(topic, "kind", "leaf")).param("importance", topic.path("importance").asInt(3))
                .param("content", json(content)).param("createdAt", now).update();
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
            throw new ApiException(HttpStatus.CONFLICT, "catalog_import_already_applied", "An applied catalog import cannot be rejected");
        }
        jdbc.sql("UPDATE catalog_import SET status='rejected', rejected_at=:now WHERE id=:id AND user_id=:userId")
            .param("now", OffsetDateTime.now(ZoneOffset.UTC)).param("id", id).param("userId", UserContext.requireUserId()).update();
        return get(id);
    }

    private Validation validate(JsonNode proposal, String libraryId) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String field : List.of("domains", "topics", "relations", "sources")) {
            if (!proposal.path(field).isArray()) errors.add(field + " must be an array");
        }
        if (!errors.isEmpty()) return new Validation(errors, warnings);
        Set<String> domains = ids(array(proposal, "domains"), "domain", errors);
        Set<String> topics = ids(array(proposal, "topics"), "topic", errors);
        ids(array(proposal, "relations"), "relation", errors);
        ids(array(proposal, "sources"), "source", errors);
        for (JsonNode domain : array(proposal, "domains")) if (blank(text(domain, "name"))) errors.add("domain name is required");
        Map<String, String> parents = new HashMap<>();
        for (JsonNode topic : array(proposal, "topics")) {
            String id = text(topic, "id");
            String domainId = text(topic, "domainId");
            String parent = nullable(topic, "parentId");
            if (!domains.contains(domainId) && !domainExists(domainId)) errors.add("unknown domain: " + domainId);
            if (parent != null && !topics.contains(parent) && !topicExists(parent)) errors.add("unknown parent topic: " + parent);
            if (topic.path("importance").asInt(0) < 1 || topic.path("importance").asInt() > 5) errors.add("topic importance must be 1-5: " + id);
            if (parent != null) parents.put(id, parent);
        }
        detectParentCycles(parents, errors);
        for (JsonNode relation : array(proposal, "relations")) {
            String from = text(relation, "fromTopicId");
            String to = text(relation, "toTopicId");
            if ((!topics.contains(from) && !topicExists(from)) || (!topics.contains(to) && !topicExists(to))) {
                errors.add("relation contains unknown topic: " + text(relation, "id"));
            }
            if (from.equals(to)) errors.add("self relation is not allowed: " + text(relation, "id"));
        }
        for (JsonNode source : array(proposal, "sources")) {
            if ("local_reference".equals(text(source, "sourceType"))) {
                if (!libraryId.equals(text(source, "libraryId"))) errors.add("local source libraryId mismatch");
                if (!text(source, "url").startsWith("mindtrain-local://" + libraryId + "/")) errors.add("invalid local source URL");
                if (blank(text(source, "relativePath")) || text(source, "relativePath").startsWith("/") || text(source, "relativePath").contains("..")) errors.add("invalid local source relativePath");
                if (!HASH.matcher(text(source, "contentHash")).matches()) errors.add("local source contentHash must be lowercase SHA-256");
            }
            if (source.has("path") || source.has("absolutePath") || source.has("text") || source.has("content")) errors.add("source contains local-only data");
        }
        if (array(proposal, "topics").isEmpty()) warnings.add("proposal contains no topics");
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
        String userId = UserContext.requireUserId();
        return switch (kind) {
            case "domain" -> jsonColumn("SELECT content_json FROM knowledge_domain WHERE user_id=:userId AND id=:id", userId, id);
            case "source" -> jsonColumn("SELECT metadata_json FROM source_asset WHERE user_id=:userId AND id=:id", userId, id);
            case "topic" -> jsonColumn("SELECT content_json FROM topic WHERE id=:id", userId, id);
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
        if (topic.has("applicableVersions")) content.set("applicableVersions", topic.path("applicableVersions"));
        if (topic.has("keywords")) content.set("keywords", topic.path("keywords"));
        if (topic.has("sourceRefs")) content.set("sourceRefs", topic.path("sourceRefs"));
        return content;
    }

    private boolean domainExists(String id) { return count("SELECT COUNT(*) FROM knowledge_domain WHERE user_id=:userId AND id=:id", id) > 0; }
    private boolean topicExists(String id) { return count("SELECT COUNT(*) FROM topic WHERE id=:id", id) > 0; }
    private int count(String sql, String id) {
        var query = jdbc.sql(sql).param("id", id);
        if (sql.contains(":userId")) query.param("userId", UserContext.requireUserId());
        return query.query(Integer.class).single();
    }

    private ImportResponse response(String id, String libraryId, String hash, String status, String proposal,
                                    String validation, String diff, OffsetDateTime createdAt,
                                    OffsetDateTime appliedAt, OffsetDateTime rejectedAt) {
        try {
            return new ImportResponse(id, libraryId, hash, status, read(proposal),
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

    public record PreviewRequest(String libraryId, JsonNode proposal) {}
    public record ApplyRequest(String proposalHash) {}
    public record Validation(List<String> errors, List<String> warnings) {}
    public record Diff(List<String> create, List<String> unchanged, List<String> conflicts) {}
    public record ImportResponse(String importId, String libraryId, String proposalHash, String status,
                                 JsonNode proposal, Validation validation, Diff diff, OffsetDateTime createdAt,
                                 OffsetDateTime appliedAt, OffsetDateTime rejectedAt) {}
}
